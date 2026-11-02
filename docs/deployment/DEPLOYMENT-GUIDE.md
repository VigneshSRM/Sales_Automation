# Deployment guide

Production target: **WAR on Apache Tomcat 11** with **MariaDB 11.4**. This page is the operational
entry point; the deep dives are linked.

## 1. Prerequisites

**Only Java and Tomcat are installed on a server.** Everything else the application needs is a jar
inside the WAR (`WEB-INF/lib` — 101 runtime jars, verified on every build); Tomcat contributes only
the servlet container (`WEB-INF/lib-provided` — 4 jars, `provided` scope).

| Component | Version | Needed | Notes |
|---|---|---|---|
| JDK | 17 (LTS) | **Host, runtime** | Required by Spring Framework 7 and Tomcat 11 |
| Apache Tomcat | 11.0.x | **Host, runtime** | Servlet 6.1; the WAR is descriptor-free |
| MariaDB | 11.4 LTS | **Host, external service** | InnoDB, `utf8mb4_unicode_ci`, `time_zone='+00:00'` |
| Node.js | 22 LTS | Build machine only | Frontend static export |
| Maven | 3.9+ | Build machine only | Produces the WAR |
| Application libraries | managed by `pom.xml` | **Inside the WAR** | Spring, Hibernate, HikariCP, MariaDB JDBC, Flyway, Jackson, springdoc, mail, BouncyCastle, Logback |

### Dependency placement

| Location | Rule |
|---|---|
| `WEB-INF/lib` (inside the WAR) | The only supported home for an application library — versioned, portable, verified |
| `WEB-INF/lib-provided` | Only what Tomcat supplies; must match the container |
| `$CATALINA_HOME/lib` | **Not** for application jars — invisible to the WAR, absent from git, untested by the packaging gate |

Need another library? Add it to `backend/pom.xml`, run `./scripts/build-war.sh` (24 packaging
assertions) and redeploy the single WAR. Never install an application dependency into Tomcat.
Full procedure: `docs/operations/installation-setup.md` §0.

## 2. Build

```bash
cd frontend && npm ci && npm run build      # static export copied into the WAR resources
cd ../backend && mvn -DskipTests clean package
ls -lh target/infeara-sales-os.war
```

`mvn verify` (instead of `-DskipTests package`) also runs the unit, slice and Testcontainers
integration suites — required before promoting a release.

## 3. Provision the database

```sql
CREATE DATABASE infeara_sales CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'infeara_app'@'%' IDENTIFIED BY '<generated-secret>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON infeara_sales.* TO 'infeara_app'@'%';
SET GLOBAL time_zone = '+00:00';
```

Flyway applies V1–V7 on first start; `ddl-auto=validate` then fails fast if the entity model and the
schema diverge.

## 4. Environment

Copy `deploy/env/.env.example` into your secret store, fill it, and export the values from
`$CATALINA_BASE/bin/setenv.sh`. Never put secrets in `server.xml` or in the WAR.

Minimum viable set: `SPRING_PROFILES_ACTIVE`, `DB_*`, `APP_BASE_URL`, `APP_TIMEZONE`,
`SMTP_SECRET_ENCRYPTION_KEY`, `COOKIE_SECURE`, and — for the very first start only —
`BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD`.

## 5. Deploy and verify

```bash
cp target/infeara-sales-os.war $CATALINA_BASE/webapps/
$CATALINA_HOME/bin/catalina.sh run
curl -fsS http://localhost:8080/infeara-sales-os/actuator/health
```

Health must report `UP` with a reachable database. Then:

1. Sign in as the bootstrap administrator and **rotate the password immediately**.
2. Enrol MFA for every administrator (privileged roles require it).
3. Configure an SMTP account, run the live test, activate it.
4. Create the first templates, submit and approve them.
5. Confirm `/api/v1/admin/automation-health` shows zero dead outbox events / jobs.

## 6. Runtime topology

Everything asynchronous runs inside Tomcat on a schedule: outbox relay, durable jobs, sequence
engine, email dispatcher. Work is claimed with database leases (`locked_by`, `locked_until`), so a
multi-instance deployment is safe without Redis or an external scheduler. Set
`WORKER_ENABLED=false` on HTTP-only instances.

## 7. Upgrades and rollback

Forward-only migrations; never edit an applied file. Back up the database first, deploy the new WAR,
verify health and automation health. Rollback = redeploy the previous WAR **if** the release notes do
not mark the migration as non-reversible; otherwise restore the backup.

## 8. Related documents

* `../operations/deployment-tomcat11.md` — connector/`JAVA_OPTS`/upgrade detail.
* `../operations/backup-recovery.md` — backup, restore, PITR, audit-chain verification.
* `../security/security-checklist.md` — control-by-control status and open items.
* `../../deploy/docker-compose.dev.yml` — development only (MariaDB + Mailpit); **not** a production
  deployment path.
