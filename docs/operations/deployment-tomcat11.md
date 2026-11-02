# Deploying the WAR on Apache Tomcat 11

Target: a single Tomcat 11 instance (or cluster behind a load balancer) with MariaDB 11.4.
The application is a **WAR**, not an executable jar: the embedded Tomcat starter is `provided`.

## 1. Prerequisites

| Component | Version | Notes |
|---|---|---|
| JDK | 17 | `JAVA_HOME` used by Tomcat |
| Tomcat | 11.0.x | Servlet 6.1; Java 17+ |
| MariaDB | 11.4 LTS | InnoDB, `utf8mb4`, UTC timezone |
| Reverse proxy | optional | TLS termination + HSTS (nginx/Apache) |

## 2. Build the artifact — one WAR, UI + API

```bash
./scripts/build-war.sh              # or: ./scripts/build-war.sh --with-tests
```

The script performs exactly these steps and fails on the first broken one:

1. `scripts/verify-toolchain.sh` — refuses to build unless JDK 17, Maven 3.9, Tomcat 11 exist.
2. `cd frontend && npm ci && npm run build` — Next.js **static export**; the postbuild hook copies
   `frontend/out` into `backend/src/main/resources/static`.
3. `cd backend && mvn -B clean package -DskipTests` — Spring Boot WAR, `spring-boot-starter-tomcat`
   is `provided` so Tomcat 11 supplies the container.
4. `python3 scripts/verify-war.py backend/target/infeara-sales-os.war` — 24 packaging assertions
   (launcher class, contiguous Flyway migrations, every required UI page, container jars confined to
   `WEB-INF/lib-provided`, no test libraries, no hardcoded secrets in the bundled YAML).
5. Copies the result to `dist/infeara-sales-os.war` **and** `dist/ROOT.war`.

### Context path: the one thing that must match the UI build

The export writes absolute asset URLs (`/_next/static/...`), so the WAR must be deployed at the
**ROOT context**: `dist/ROOT.war` → `${CATALINA_BASE}/webapps/ROOT.war`, UI at `/`, API at
`/api/v1`. This is a deliberate deviation from the earlier `/infeara-sales-os` + `/ui` layout:

| Approach | UI URL | Verdict |
|---|---|---|
| `infeara-sales-os.war` (named context) | `/infeara-sales-os/ui/…` | asset URLs break unless the UI is rebuilt with a matching `basePath` |
| `ROOT.war` (this release) | `/` | works unchanged; `scripts/verify-war.py` asserts the absolute-asset invariant |
| named context + `NEXT_BASE_PATH=/infeara-sales-os` | `/infeara-sales-os/…` | supported by the build, but `next/link` prefixes must be re-tested |

Deep links work because `config/WebConfig` resolves a directory request to its `index.html`
(`engagement/mailbox/` → `static/engagement/mailbox/index.html`); Tomcat's welcome-file mechanism
does not apply to resources served through the DispatcherServlet.

## 3. Database preparation

```sql
CREATE DATABASE infeara_sales CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'infeara'@'%' IDENTIFIED BY '<generated-secret>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON infeara_sales.* TO 'infeara'@'%';
FLUSH PRIVILEGES;

-- UTC session and strict SQL mode are mandatory
SET GLOBAL time_zone = '+00:00';
```

Flyway creates and migrates the schema on first start; `ddl-auto=validate` then fails fast if the
entity model and schema ever diverge.

## 4. Environment variables

Provide them via `$CATALINA_BASE/bin/setenv.sh` (never in `server.xml`, never in the WAR). The full
list is in `deploy/env/.env.example`. Minimum set:

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL='jdbc:mariadb://db.internal:3306/infeara_sales?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC'
export DB_USERNAME=infeara
export DB_PASSWORD='…'
export APP_BASE_URL='https://sales.infeara.example'
export APP_TIMEZONE=Asia/Kolkata
export SMTP_SECRET_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export SMTP_SECRET_ENCRYPTION_KEY_ID=k1
export BOOTSTRAP_ADMIN_EMAIL='admin@infeara.example'
export BOOTSTRAP_ADMIN_PASSWORD='<one-time>'
export CORS_ALLOWED_ORIGINS=''
```

## 5. Deploy (automated)

```bash
sudo ./scripts/host-app.sh
```

`scripts/host-app.sh` is idempotent and does, in order:

1. creates the database and a least-privilege application user (DML + DDL, no `GRANT`/`CREATE USER`);
2. renders `$CATALINA_BASE/bin/setenv.sh` (mode 0750) from `deploy/tomcat/setenv.sh` with generated
   secrets: `SMTP_SECRET_ENCRYPTION_KEY`, the DB password and the bootstrap admin password;
3. installs `dist/ROOT.war`;
4. stops a running instance, starts Tomcat, and waits up to 60 s for `/actuator/health` to report
   `UP` (prints `catalina.out` tail on failure);
5. smoke-checks the UI (`/` → 200) and the API (`/api/v1/auth/me` → 401).

### Manual equivalent

```bash
cp dist/ROOT.war $CATALINA_BASE/webapps/ROOT.war
$CATALINA_HOME/bin/shutdown.sh 10 -force; $CATALINA_HOME/bin/startup.sh
tail -f $CATALINA_BASE/logs/catalina.out
curl -fsS http://localhost:8080/actuator/health       # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/     # 200 (UI)
```

First start performs, in this order: Flyway migration → RBAC seed (already in migrations) →
workspace bootstrap (settings, catalogues, pipeline stages, scoring model v1, admin user).
Bootstrap is idempotent: it detects an existing workspace and does nothing.

## 6. Recommended `server.xml` connector (behind a TLS proxy)

```xml
<Connector port="8080" protocol="org.apache.coyote.http11.Http11NioProtocol"
           maxThreads="200" minSpareThreads="20" connectionTimeout="20000"
           maxHttpHeaderSize="16384" URIEncoding="UTF-8"
           proxyName="sales.infeara.example" proxyPort="443" scheme="https" />
```

The application reads `X-Forwarded-*` for cookie `Secure` decisions, so the proxy must set them and
must be the only path to port 8080.

## 7. `JAVA_OPTS` baseline

```bash
CATALINA_OPTS="-Xms1g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -Duser.timezone=UTC -Dfile.encoding=UTF-8 \
  -Djava.security.egd=file:/dev/urandom"
```

## 8. Worker topology

The outbox relay, job engine, sequence engine and email dispatcher run inside Tomcat on a schedule.
Every row is claimed with a lease (`locked_by`, `locked_until`), so running several Tomcat instances
is safe by construction — no Redis, no external scheduler. Set `WORKER_ENABLED=false` on instances
that should serve HTTP only.

## 9. Upgrade procedure

1. Back up the database (`docs/operations/backup-recovery.md`).
2. Deploy the new WAR into a **new** context or stop the old one first (Tomcat undeploys cleanly).
3. Flyway applies forward-only migrations; never edit an applied migration.
4. Verify `/actuator/health`, sign in, and check `/api/v1/admin/automation-health`.
5. Rollback = redeploy the previous WAR. If a migration is not backward compatible, restore the
   backup instead — the release notes state which releases are non-reversible.

## 10. Post-deployment checklist

- [ ] WAR deployed as **ROOT.war** (or the UI rebuilt with a matching `NEXT_BASE_PATH`)
- [ ] `/` serves the login page and `/_next/**` returns 200 (not 404)
- [ ] `python3 scripts/verify-war.py dist/infeara-sales-os.war` passed on the artefact you deployed
- [ ] `SPRING_PROFILES_ACTIVE=prod` (TLS mode `NONE` for SMTP is rejected in this profile)
- [ ] `SMTP_SECRET_ENCRYPTION_KEY` set, backed up in the secret manager
- [ ] Bootstrap admin password rotated after first sign-in
- [ ] MFA enrolled for every administrator
- [ ] SMTP profile tested and set to ACTIVE
- [ ] Two-way email: a mailbox added, **Test** passed, then enabled (it cannot be enabled before a
      passing test), and one real inbound message visible in the unified inbox
- [ ] Response policy reviewed: `AUTOMATIC` rules exist only where the business intends unattended
      replies; otherwise leave the default (everything goes to a human)
- [ ] Backups scheduled and a restore has been rehearsed
- [ ] Audit chain verified (`SELECT count(*) FROM audit_logs` growing, no gaps)

## 11. Verified deployment (recorded evidence)

The deployment in §2–§6 is not a claim: it runs on every push to this branch through
`.github/workflows/build-and-host-war.yml` (JDK 17 Temurin, Node 22, MariaDB 11.4 service,
Apache Tomcat 11.0.27 downloaded from the Apache archive, WAR copied to
`$CATALINA_BASE/webapps/ROOT.war`). The workflow posts the evidence block to the repository's
tracking issue (#1) on **every** run, success or failure, so the result is readable without
access to the CI logs.

The first green run (workflow run `41365290275`, commit `7100d889`) recorded:

| Evidence | Result |
|---|---|
| WAR artefact | `infeara-sales-os.war`, 862 entries, 75.8 MB, uploaded as `infeara-sales-os-war` (70.9 MB zipped) |
| Packaging contract | 24/24 checks in `scripts/verify-war.py` |
| Tomcat | 11.0.27, `ROOT.war` deployed, `Server startup` logged |
| `GET /actuator/health` | `{"groups":["liveness","readiness"],"status":"UP"}` |
| `GET /` (Next.js export served by Tomcat) | 200, 8 843 bytes |
| `GET /leads/` | 200 |
| `GET /engagement/mailbox/` | 200 — the addendum inbox is inside the same WAR |
| `GET /api/v1/auth/me` (no session) | 401 with the documented error envelope (`UNAUTHENTICATED`, requestId echo) |
| `GET /v3/api-docs` | 200, 97 unique `/api/**` paths |
| Flyway | 10/10 migrations `success = 1` (`V1`…`V10`) |
| Schema | 83 tables = 82 application tables + `flyway_schema_history` |
| RBAC seed + bootstrap | 66 capabilities, 10 roles, 1 user (bootstrap Super Admin), 1 audit row (`WORKSPACE_BOOTSTRAPPED`) |

### Defects that only a real deployment could find

Each of these was invisible to `mvn package` (which had already succeeded) and was fixed,
committed and re-verified:

1. `V1` declared a `UNIQUE KEY` over `outbox_events.dedupe_key`, a column that only exists on
   `leads`/`notifications` → MariaDB error 1072, Flyway aborted. `tools/generate_schema.py` now
   refuses to emit a key over a column the entity does not declare.
2. `V5` declared a foreign key to `service_offerings`; the table is `services` → error 150.
   `scripts/validate-migrations.py` (new) now checks the whole script set statically, and its
   selftest replays both production defects.
3. The schema generator read `@Column(name = "capability")` on `Role.capabilities` (an
   `@ElementCollection`) as a real column → `roles.capability NOT NULL` and error 1364 on the RBAC
   seed. Collection-typed fields are now skipped.
4. `JobEngine` took `List<JobHandler>` in its constructor → `BeanCurrentlyInCreationException`
   (`jobEngine → mailboxSyncJobHandler → mailboxSyncService → inboundEmailService → jobEngine`).
   Handlers are now resolved lazily through `ObjectProvider<JobHandler>`.
5. The first-run bootstrap called `AuditRecorder.recordSystem` (`REQUIRES_NEW`) while its own
   transaction still held the new workspace row → the audit insert waited on a foreign-key lock
   until the socket timed out. Seeding now runs in an explicit `TransactionTemplate` and the audit
   event is written after the commit.
6. `scripts/validate-entities.py` (new) cross-checks every `@Entity` against the migrated columns,
   including the 18 columns `V8` adds to `email_messages` via `ALTER TABLE`, so a
   `ddl-auto=validate` failure cannot reach a deployment again.

### Reproducing it yourself

On any host with network access to Maven Central and the Apache archive:

```bash
sudo ./scripts/install-toolchain.sh     # JDK 17, Maven 3.9, Tomcat 11.0.27, MariaDB 11.4
./scripts/verify-toolchain.sh           # prints the pinned versions it found
./scripts/build-war.sh                  # frontend → WAR → scripts/verify-war.py
sudo ./scripts/host-app.sh              # creates the schema, secrets, setenv.sh, deploys ROOT.war
```

The sandbox used for this implementation cannot reach those hosts, which is documented in
`docs/operations/sandbox-environment.md`; that is why the identical sequence runs in CI and the
evidence is published to issue #1.
