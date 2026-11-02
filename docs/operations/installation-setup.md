# Installation and setup guide — Infeara Sales OS

This is the long form of the README's install section: per-component installation, Tomcat
hardening, TLS, first-run provisioning, integration wiring, upgrade and rollback.

The scripted path (`scripts/install-toolchain.sh` → `verify-toolchain.sh` → `build-war.sh` →
`host-app.sh`) is the canonical one and is what CI executes. Everything here is written so you can
also do it by hand on a host where running repository scripts as root is not allowed.

**One artefact.** The UI and the API ship in a single `infeara-sales-os.war`, deployed as
`ROOT.war`. Tomcat supplies the servlet container (`spring-boot-starter-tomcat` is `provided`), so
there is one process, one origin, and no CORS in production.

---

## 0. Supported topologies

| Topology | Status | Notes |
| --- | --- | --- |
| Single node: nginx/varnish → Tomcat 11 → MariaDB (same host) | **Supported, verified** | This is what CI proves and what this guide deploys |
| Single node, plain HTTP, no proxy | Supported for LAN/dev only | Set `APP_SECURITY_COOKIE_SECURE=auto` (default) or `never`; never `always` behind a plain connector |
| Tomcat terminated TLS | Supported | Then `APP_SECURITY_COOKIE_SECURE=auto` also works, since the request itself is secure |
| Multiple Tomcat nodes behind a load balancer | Supported with caveats | Sessions live in MariaDB so no sticky sessions are needed, but only **one** node may run `WORKER_ENABLED=true` per environment unless you set distinct `WORKER_INSTANCE_ID`s and rely on job leases |
| Tomcat 10.1 / Tomcat 9 / Jetty / embedded bootJar | **Not supported** | The WAR targets Servlet 6.1 (Jakarta EE 11) |
| Container image | **Not provided** | The approved architecture is a WAR on Tomcat; dev-only infrastructure uses Docker (`deploy/docker-compose.dev.yml`) |

---

## 1. Before you start

Checklist:

- [ ] Linux host, Debian/Ubuntu 22.04+ or RHEL 9 / Rocky / Alma 9 (the installer detects `apt`/`dnf`/`yum`)
- [ ] root or `sudo`
- [ ] Outbound HTTPS to `adoptium.net`, `archive.apache.org`, `r.mariadb.com`, `repo.maven.apache.org`, `registry.npmjs.org`
- [ ] 2 vCPU / 4 GB RAM / 20 GB disk recommended (75.8 MB WAR + Tomcat + Maven repo + npm cache; DB grows with data)
- [ ] A DNS name and, for anything non-local, TLS in front of Tomcat
- [ ] A secret store (Vault, AWS/GCP Secret Manager, systemd `EnvironmentFile` with `0600`, …) for the values generated in §7

Versions are pinned and verified in `docs/architecture/compatibility.md`:

| Component | Pinned | Why |
| --- | --- | --- |
| Java | 17 | Source/target level of the project |
| Spring Boot | 4.0.9 | Spring Framework 7 / Security 7 / Servlet 6.1 |
| Tomcat | 11.0.27 | Servlet 6.1; the exact patch CI runs |
| MariaDB | 11.4 LTS | Only supported database |
| Maven | 3.9.11 | Build |
| Node.js | 22 | UI static export |

---

## 2. Host preparation

```bash
sudo apt-get update && sudo apt-get install -y ca-certificates curl unzip tar gnupg lsb-release procps
# RHEL family: sudo dnf install -y ca-certificates curl unzip tar gnupg2 procps-ng

sudo timedatectl set-timezone UTC          # the app stores UTC; display timezone is a config value
sudo useradd --system --home /opt/infeara --shell /usr/sbin/nologin infeara 2>/dev/null || true
sudo mkdir -p /opt/infeara /var/log/tomcat
sudo chown -R infeara:infeara /opt/infeara /var/log/tomcat
```

Firewall: open `443` (proxy) to the world, `8080` only to the proxy, `3306` only to localhost.

```bash
# ufw example
sudo ufw allow 443/tcp
sudo ufw allow from 127.0.0.1 to any port 8080
sudo ufw enable
```

---

## 3. JDK 17

```bash
# Debian/Ubuntu
sudo apt-get install -y openjdk-17-jdk-headless
# RHEL/Rocky/Alma
sudo dnf install -y java-17-openjdk-devel

java -version      # must print 17.x
javac -version     # MUST exist — a JRE cannot build the WAR
```

Temurin tarball alternative (what `install-toolchain.sh` prefers when the distro ships another
default JDK): unpack `OpenJDK17U-jdk_x64_linux_hotspot_*.tar.gz` to `/opt/infeara/jdk-17`, then
`export JAVA_HOME=/opt/infeara/jdk-17`.

`scripts/verify-toolchain.sh` fails if `javac` is missing or the major version is not 17.

---

## 4. Maven 3.9

```bash
cd /tmp
curl -fsSLo maven.tgz https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz
curl -fsSLo maven.tgz.sha512 https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz.sha512
echo "$(cat maven.tgz.sha512)  maven.tgz" | sha512sum -c -      # abort on mismatch
sudo tar -xzf maven.tgz -C /opt/infeara
sudo ln -sfn /opt/infeara/apache-maven-3.9.11 /opt/infeara/maven
sudo ln -sf /opt/infeara/maven/bin/mvn /usr/local/bin/mvn
mvn -v        # Apache Maven 3.9.11, Java 17
```

---

## 5. Tomcat 11

```bash
cd /tmp
curl -fsSLo tomcat.tgz https://archive.apache.org/dist/tomcat/tomcat-11/v11.0.27/bin/apache-tomcat-11.0.27.tar.gz
sudo tar -xzf tomcat.tgz -C /opt/infeara
sudo ln -sfn /opt/infeara/apache-tomcat-11.0.27 /opt/infeara/tomcat
sudo chown -R infeara:infeara /opt/infeara/apache-tomcat-11.0.27
```

Hardening — the first line is what `install-toolchain.sh` does for you; the rest is recommended for
an internet-facing host:

```bash
sudo rm -rf /opt/infeara/tomcat/webapps/{manager,host-manager,docs,examples}
sudo chmod 0750 /opt/infeara/tomcat/conf
sudo chmod 0600 /opt/infeara/tomcat/conf/server.xml
sudo chown -R infeara:infeara /opt/infeara/tomcat
# remove the shutdown port entirely, or set it to -1 in conf/server.xml:
#   <Server port="-1" shutdown="SHUTDOWN">
# If (and only if) you deploy through conf/Catalina/localhost/ROOT.xml, you can also turn off
# automatic deployment of anything dropped into webapps/:
#   <Host name="localhost" appBase="webapps" deployOnStartup="false" autoDeploy="false" unpackWARs="true">
```

Confirmed by `$CATALINA_HOME/bin/version.sh`:

```
Server version: Apache Tomcat/11.0.27
JVM Version:    17.x
```

### systemd unit

Tomcat's `startup.sh` is fine for a first run; a supervised service is better in production
(`/etc/systemd/system/infeara.service`):

```ini
[Unit]
Description=Infeara Sales OS (Tomcat 11)
After=network-online.target mariadb.service
Wants=network-online.target

[Service]
Type=forking
User=infeara
Group=infeara
Environment=JAVA_HOME=/opt/infeara/jdk-17
Environment=CATALINA_HOME=/opt/infeara/tomcat
Environment=CATALINA_BASE=/opt/infeara/tomcat
Environment=CATALINA_PID=/opt/infeara/tomcat/temp/tomcat.pid
PIDFile=/opt/infeara/tomcat/temp/tomcat.pid
ExecStart=/opt/infeara/tomcat/bin/startup.sh
ExecStop=/opt/infeara/tomcat/bin/shutdown.sh 30 -force
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

`setenv.sh` (written by `host-app.sh`, template at `deploy/tomcat/setenv.sh`) is still the place
the application's environment variables live; systemd only supplies `JAVA_HOME`/`CATALINA_*`.

---

## 6. MariaDB 11.4

```bash
# Debian/Ubuntu — vendor repository, because distro packages are older than 11.4
curl -fsSL https://r.mariadb.com/downloads/mariadb_repo_setup | sudo bash -s -- --mariadb-server-version="mariadb-11.4"
sudo apt-get install -y mariadb-server mariadb-client
sudo systemctl enable --now mariadb

# RHEL family
sudo dnf install -y MariaDB-server MariaDB-client
```

Server settings that matter (`/etc/mysql/mariadb.conf.d/99-infeara.cnf`):

```ini
[mysqld]
character-set-server = utf8mb4
collation-server     = utf8mb4_unicode_ci
default-time-zone    = +00:00
innodb_buffer_pool_size = 512M
max_connections      = 200
```

The application connects with `sessionVariables.time_zone=+00:00` and stores `DATETIME(6)` in UTC, so
the server timezone must not be local time.

### Database and least-privilege user

```sql
CREATE DATABASE IF NOT EXISTS infeara_sales
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'infeara'@'localhost' IDENTIFIED BY '<strong-password>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON infeara_sales.* TO 'infeara'@'localhost';
FLUSH PRIVILEGES;
```

Notes:

* `CREATE`/`ALTER`/`INDEX`/`REFERENCES` are required because **Flyway owns the schema** and applies
  `V1`–`V10` on first boot. `GRANT`/`CREATE USER`/`DROP` are deliberately not granted.
* If your DBA applies migrations out of band, run Flyway separately and remove `CREATE`/`ALTER`.
* Verify: `mariadb -h 127.0.0.1 -u infeara -p -e "SELECT @@character_set_database, @@collation_database, @@time_zone"` → `utf8mb4 | utf8mb4_unicode_ci | +00:00`.

Verify the schema after the first start:

```sql
SELECT version, description, success FROM infeara_sales.flyway_schema_history ORDER BY installed_rank;
-- expect V1..V10, all success = 1
SHOW TABLES;  -- expect 83 tables (82 application tables + flyway_schema_history)
```

---

## 7. Build the WAR

```bash
git clone https://github.com/VigneshSRM/Sales_Automation.git
cd Sales_Automation

# Option A — the scripts (recommended, identical to CI)
sudo ./scripts/install-toolchain.sh       # if you skipped §3–§6
source /etc/profile.d/infeara-sales.sh
./scripts/verify-toolchain.sh
./scripts/build-war.sh                    # --with-tests to run JUnit + Testcontainers
```

```bash
# Option B — by hand
cd frontend && npm ci && npm run build && cd ..      # exports into backend/src/main/resources/static
cd backend && mvn -DskipTests package && cd ..
cp backend/target/infeara-sales-os.war dist/infeara-sales-os.war
python3 scripts/verify-war.py dist/infeara-sales-os.war   # 24 assertions
```

Expected artefacts:

```
backend/target/infeara-sales-os.war   ~75.8 MB, 866 entries
dist/infeara-sales-os.war             copy used by host-app.sh (plus dist/ROOT.war)
```

`verify-war.py` is the packaging contract, and it is worth reading its output once: it asserts the
Spring Boot servlet initializer is present (so external Tomcat can boot it), that `V1`–`V10` are
inside `WEB-INF/classes/db/migration` and contiguous, that the UI is a static export (no
`node_modules`), that container jars are `provided`, that no test libraries, secrets or build
detritus are packaged, and that the API is called same-origin.

---

## 8. Deploy and configure

```bash
sudo ./scripts/host-app.sh
```

That script performs §5–§8 automatically. Doing it by hand:

```bash
# 1. environment for the JVM (template: deploy/tomcat/setenv.sh)
sudo cp deploy/tomcat/setenv.sh /opt/infeara/tomcat/bin/setenv.sh
sudo chmod 0750 /opt/infeara/tomcat/bin/setenv.sh
sudo editor /opt/infeara/tomcat/bin/setenv.sh

# 2. deploy as ROOT so the static export's absolute /_next URLs resolve
sudo rm -rf /opt/infeara/tomcat/webapps/ROOT /opt/infeara/tomcat/webapps/ROOT.war
sudo cp dist/infeara-sales-os.war /opt/infeara/tomcat/webapps/ROOT.war
sudo chown infeara:infeara /opt/infeara/tomcat/webapps/ROOT.war

# 3. optional: the reviewed context (cookie policy, access log without query strings)
sudo cp deploy/tomcat/context.xml /opt/infeara/tomcat/conf/Catalina/localhost/ROOT.xml

# 4. start
sudo systemctl start infeara          # or: sudo /opt/infeara/tomcat/bin/startup.sh
curl -s http://localhost:8080/actuator/health
```

`ROOT.xml` is only needed if you want the explicit context; Tomcat derives the context path from the
WAR name for auto-deployment. **Never** deploy as `infeara-sales-os.war` — the UI would 404 on every
asset.

### Environment reference

Minimum for `prod` (all names are read directly by `application.yml` — do not rename):

| Variable | Example | Notes |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` | `dev` locally |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | `127.0.0.1` `3306` `infeara_sales` `infeara` `***` | Hikari pool: `DB_POOL_MAX` (20), `DB_POOL_MIN` (4) |
| `SMTP_SECRET_ENCRYPTION_KEY` | `openssl rand -base64 32` | AES-256-GCM key for stored SMTP/IMAP/Calendly secrets. **Losing it means re-entering every integration secret** |
| `SMTP_SECRET_ENCRYPTION_KEY_ID` | `k1` | Rotation: move the old key to `SMTP_SECRET_PREVIOUS_KEYS=k1:<base64>` |
| `APP_BASE_URL` | `https://sales.example.com` | Used in links and responses |
| `APP_ENV` | `production` | |
| `APP_TIMEZONE` | `Asia/Kolkata` | Display/business-hours timezone |
| `BOOTSTRAP_WORKSPACE_NAME` `_SLUG` `_TIMEZONE` | `Infeara Technologies` `infeara` `Asia/Kolkata` | First run |
| `BOOTSTRAP_ADMIN_EMAIL` `_PASSWORD` `_NAME` | | Only used while the workspace has no users; empty password = no bootstrap |
| `WORKER_ENABLED` | `true` | Outbox relay, job engine, email dispatcher, inbound discovery |
| `WORKER_INSTANCE_ID` | | Required if more than one node runs the worker |

Optional:

| Group | Variables |
| --- | --- |
| Session / cookies | `SESSION_TTL_MINUTES` (480), `SESSION_IDLE_MINUTES` (60), `COOKIE_SECURE` (container session cookie, default `true`), `APP_SECURITY_COOKIE_SECURE` (`auto`\|`always`\|`never`) |
| Rate limiting | `RATE_LIMIT_AUTH_PER_MINUTE` (20), `RATE_LIMIT_API_PER_MINUTE` (600), `RATE_LIMIT_WEBHOOK_PER_MINUTE` (300) |
| Server | `SERVER_PORT` (8080), `SERVER_CONTEXT_PATH` (must stay `/`) |
| Inbound mailbox | `EMAIL_INBOUND_DISCOVERY_INTERVAL_MS` (60000), `EMAIL_INBOUND_MAX_BODY_CHARS` (200000), `EMAIL_INBOUND_MAX_ATTACHMENT_BYTES` (10485760), `EMAIL_INBOUND_MAX_ATTACHMENTS` (20), `EMAIL_INBOUND_MAX_MESSAGES_PER_SYNC` (50), `EMAIL_INBOUND_CONNECT_TIMEOUT_SECONDS` (20), `EMAIL_INBOUND_READ_TIMEOUT_SECONDS` (60), `EMAIL_BUSINESS_HOURS_START`/`_END` (09:00/18:00) |
| AI | `AI_PREFERRED_PROVIDER` (`OPENROUTER`\|`OPENAI`), `AI_OPENROUTER_API_KEY`, `AI_OPENROUTER_MODEL`, `AI_OPENAI_API_KEY`, `AI_OPENAI_MODEL`, `AI_TIMEOUT_SECONDS` (30) |
| Calendly | `CALENDLY_WEBHOOK_TOLERANCE_SECONDS` (180) |
| CORS | `CORS_ALLOWED_ORIGINS` (empty = same-origin, the supported deployment) |
| Logging | `LOG_LEVEL_APP` (INFO) |

Full commented template: `deploy/env/.env.example` and `deploy/tomcat/setenv.sh`.

Secrets discipline: `setenv.sh` is `0750` and owned by the Tomcat user; if you keep values in a
secret manager, render `setenv.sh` at deploy time (that is exactly what `host-app.sh` does) rather
than committing anything. The app never logs request bodies, headers or credentials.

---

## 9. First-run provisioning and sign-in

On first start, while the workspace has no users, the application:

1. creates the workspace (`BOOTSTRAP_WORKSPACE_*`) and a `WORKSPACE_BOOTSTRAPPED` audit entry;
2. seeds 10 roles, 66 capabilities, pipeline stages, services, sources, loss reasons and the scoring
   model v1 (Flyway `V6`/`V9`/`V10`);
3. creates the bootstrap administrator from `BOOTSTRAP_ADMIN_EMAIL`/`_PASSWORD`.

Then sign in at `http://localhost:8080/`:

1. `POST /api/v1/auth/login` returns `MFA_ENROLMENT_REQUIRED` — **no session exists yet**.
2. The UI shows the TOTP provisioning URI/secret; scan it and confirm with a code
   (`POST /api/v1/auth/mfa/enrol/confirm`). Only then is the `INF_SESSION` cookie issued.
3. Subsequent logins return `MFA_CHALLENGE_REQUIRED` → `POST /api/v1/auth/mfa/verify`.
4. Five failed logins lock the account for 15 minutes (`max-failed-logins`, `lockout-minutes`).

This is the exact flow the CI authenticated smoke test reproduces
(`scripts/smoke-authenticated.py`, 16/16 checks); if a browser behaves differently, run that script
first — it isolates API from browser problems.

---

## 10. Post-install integration setup

Everything below is done in the UI and hits real endpoints. Nothing is pre-seeded with fake data.

**Sending (outbound SMTP)** — *Engagement → Sending accounts*

1. Add host, port, TLS mode, username and password (stored AES-256-GCM encrypted; the API never
   returns it).
2. **Test** → `POST /api/v1/smtp-profiles/{id}/test`. Only a passing test permits `ACTIVE`.
3. Set daily/hourly caps; the eligibility check is re-run before every send.

**Mailbox (two-way inbound)** — *Engagement → Mailbox settings*

1. Add an **IMAP** mailbox (host, port, TLS, folder). `GMAIL_API` and `MICROSOFT_GRAPH` are not
   implemented and return `501 UNSUPPORTED_OPERATION` — this is deliberate, not a bug.
2. **Test connection**, then enable sync. The account stays `PENDING_TEST` until a test passes.
3. Discovery enqueues `MAILBOX_SYNC` per active mailbox every 60 s (`EMAIL_INBOUND_DISCOVERY_INTERVAL_MS`);
   the IMAP session runs outside any DB transaction. Messages are deduplicated on `inbound_key`,
   threaded on `thread_key`, and a `UIDVALIDITY` reset refetches from UID 0.

**AI triage (optional)** — set `AI_OPENROUTER_API_KEY` or `AI_OPENAI_API_KEY` and restart. Classification
is schema-validated (`PROMPT_VERSION=email-analysis-v1`); with no key, the API returns
`AI_PROVIDER_UNAVAILABLE` and the UI says analysis is unavailable. There is no mock provider.

**Response policy** — *Engagement → Mailbox rules*. Without a rule matching an intent, **nothing** is
sent automatically. Auto-send additionally requires: intent allowed by a rule, outside-business-hours
block cleared, approval hash match (SHA-256 of subject+body) and four-eyes approval within 72 h.

**Calendly (schema only today)** — tables exist (`V3`), no webhook endpoint is implemented; see
`DEVELOPMENT_STATUS.md` before promising this to a user.

---

## 11. TLS reverse proxy (recommended)

Terminate TLS at nginx and forward to Tomcat on `127.0.0.1:8080`. `/etc/nginx/conf.d/infeara.conf`:

```nginx
server {
    listen 443 ssl http2;
    server_name sales.example.com;

    ssl_certificate     /etc/letsencrypt/live/sales.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/sales.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    client_max_body_size 12m;          # matches spring.servlet.multipart max-request-size

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;   # <- required by cookie-secure=auto
        proxy_set_header   X-Forwarded-Port  $server_port;
        proxy_read_timeout 120s;                        # mail/AI calls can be slow
        proxy_buffering    off;                         # streaming responses
    }
}
```

Cookie behaviour matrix (`APP_SECURITY_COOKIE_SECURE`):

| Deployment | Value | Result |
| --- | --- | --- |
| TLS at proxy, `X-Forwarded-Proto` forwarded (prod profile reads it via `forward-headers-strategy: native`) | `auto` *(default)* | Cookie `Secure` — correct |
| Tomcat itself terminates TLS | `auto` | Cookie `Secure` — correct |
| Plain HTTP, local/LAN only | `auto` | Cookie not `Secure` — works |
| Plain HTTP with `always` | ✗ | Every client drops the cookie → **all authenticated calls 401** (this defect was found and fixed during verification) |

---

## 12. Verification checklist

Run after any install or upgrade:

```bash
BASE=http://localhost:8080

curl -s $BASE/actuator/health                       # {"status":"UP", ...}
curl -s -o /dev/null -w '%{http_code}\n' $BASE/     # 200  (login page)
curl -s -o /dev/null -w '%{http_code}\n' $BASE/leads/                       # 200
curl -s -o /dev/null -w '%{http_code}\n' $BASE/engagement/mailbox/          # 200
curl -s -o /dev/null -w '%{http_code}\n' $BASE/v3/api-docs                  # 200 (97 paths)
curl -s $BASE/api/v1/auth/me                        # 401 UNAUTHENTICATED envelope (correct when signed out)

# authenticated end-to-end (login, MFA enrolment, module reads, CSRF write)
python3 scripts/smoke-authenticated.py --base-url $BASE \
  --email "$BOOTSTRAP_ADMIN_EMAIL" --password "$BOOTSTRAP_ADMIN_PASSWORD"
```

Expected: every line green, 16/16 checks. The same script runs in CI, so a pass locally means the
deployment is functionally equivalent to the verified one.

Database sanity:

```sql
SELECT COUNT(*) FROM infeara_sales.flyway_schema_history WHERE success = 1;   -- 10
SELECT COUNT(*) FROM infeara_sales.flyway_schema_history WHERE success = 0;   -- 0
SHOW TABLES;                                                                  -- 83
```

---

## 13. Upgrade

```bash
cd /opt/src/Sales_Automation
git fetch --tags && git checkout <release-tag-or-commit>
./scripts/build-war.sh                       # re-runs verify-war.py
sudo ./scripts/host-app.sh                    # stops Tomcat, deploys, restarts, waits for health
python3 scripts/smoke-authenticated.py --base-url http://localhost:8080 --email ... --password ...
```

Notes:

* Migrations run on startup (`spring.flyway.enabled: true`, `validate-on-migrate: true`). **Never
  edit an applied migration** — add a new `V{n}__description.sql`.
* Take a database dump before upgrading (`docs/operations/backup-recovery.md`).
* `setenv.sh` is regenerated by `host-app.sh`; if you maintain secrets elsewhere, re-render it from
  your secret store first.

### Rollback

1. Stop Tomcat.
2. Restore the previous WAR (`webapps/ROOT.war`) — keep every released WAR under
   `/opt/infeara/releases/` tagged by version.
3. Restore the matching database dump. Flyway does not roll back; a WAR that predates the last
   migration set must be paired with the dump taken before that upgrade.
4. Start Tomcat and verify with §12.

---

## 14. Uninstall

```bash
sudo systemctl stop infeara && sudo systemctl disable infeara
sudo rm -rf /opt/infeara/{tomcat,maven,jdk-17,webapps}
sudo mariadb -e "DROP DATABASE infeara_sales; DROP USER 'infeara'@'localhost';"
sudo rm -f /etc/profile.d/infeara-sales.sh /etc/systemd/system/infeara.service
```

The database is the only place your business data lives — dump it before dropping.

---

## 15. Troubleshooting

| Symptom | Likely cause | Action |
| --- | --- | --- |
| `verify-toolchain.sh` fails on `javac` | JRE only, or JDK 21 | Install JDK 17 with `javac`; do not patch the gate |
| `verify-toolchain.sh` fails on Tomcat | Tomcat 10.x/9.x on `CATALINA_HOME` | Install 11.0.x; Servlet 6.1 is mandatory |
| Build fails downloading Maven deps | Egress blocked | Whitelist `repo.maven.apache.org`/`repo1.maven.org` (a sandbox without this is exactly why CI exists) |
| `ROOT.war` not exploding | `unpackWARs` off, or permissions | Ensure the Tomcat user owns `webapps/` and `temp/` |
| `/` returns 404 | WAR named `infeara-sales-os.war` | Redeploy as `ROOT.war` |
| `/` returns 200 but page unstyled | Served from a non-root context | Root context only, or host the assets at the same path |
| `/actuator/health` `DOWN` (db) | Wrong credentials or DB down | `mysql -u infeara -p -h 127.0.0.1 infeara_sales -e 'select 1'` |
| Startup fails `FlywayValidateException` | An applied migration was edited, or DB has partial history | Restore the original file; `flyway repair` only with a DBA decision |
| `Schema-validation: missing table X` | Migrations never ran (DDL rights missing) | Grant `CREATE, ALTER, INDEX, REFERENCES`; restart |
| Login OK, then every request 401 | Session cookie marked `Secure` over plain HTTP | `APP_SECURITY_COOKIE_SECURE=auto` and/or forward `X-Forwarded-Proto` |
| Login OK, then 403 `FORBIDDEN` | Role lacks the capability | Check the role matrix; capability strings must exist in seeds (`tools/capability_audit.py`) |
| `429` on login | Auth rate limit (20/min) | Expected under load tests; tune `RATE_LIMIT_AUTH_PER_MINUTE` |
| `501 UNSUPPORTED_OPERATION` on a mailbox | Gmail API / Microsoft Graph provider | Only IMAP is implemented |
| `AI_PROVIDER_UNAVAILABLE` | No AI provider key | Expected; configure a key or accept AI-less triage |
| Inbound mail not arriving | Mailbox not `ACTIVE`, sync disabled, or discovery off | Verify a passing connection test, enable sync, check `catalina.out` for `MAILBOX_SYNC` jobs |
| Jobs piling up | `WORKER_ENABLED=false` | Enable it on exactly one node |
| OutOfMemoryError under load | Heap too small for attachments | `CATALINA_OPTS -Xmx`, and lower `EMAIL_INBOUND_MAX_ATTACHMENT_BYTES` |

Log locations: `$CATALINA_BASE/logs/catalina.out` (application + startup),
`$CATALINA_BASE/logs/access.*.log` (access log, query strings stripped),
`/var/log/tomcat/access` if `application-prod.yml`'s access log path is used.

---

## Appendix A — development environment

```bash
docker compose -f deploy/docker-compose.dev.yml up -d        # MariaDB 11.4 + Mailpit
cd backend && DB_USER=infeara DB_PASSWORD=dev-password mvn spring-boot:run
cd frontend && npm ci && npm run dev                        # :3000, proxies /api to :8080
```

Dev defaults: profile `dev`, datasource `infeara_sales`/`infeara_app`/`infeara_dev_password`,
`ddl-auto: validate` (Flyway still owns the schema), session cookie not `Secure`, SQL log at DEBUG.
Mailpit UI: http://localhost:8025 (SMTP on 1025 with any credentials).

Tests: `cd frontend && npx tsc --noEmit && npx vitest run` (28),
`cd backend && mvn test` (26), `./scripts/build-war.sh --with-tests` for the Testcontainers ITs.

## Appendix B — macOS / Windows developers

The WAR deployment is Linux-only in this documentation because that is what was verified. For local
development on macOS: install Temurin 17 (`brew install --cask temurin@17`), Maven
(`brew install maven`), Node 22 and Docker Desktop, then follow Appendix A. There is no verified
Tomcat-on-Windows run; if you need one, treat it as unverified and record the result in
`docs/operations/`.

## Appendix C — no toolchain available? (CI / preview)

* **CI is the runtime.** `.github/workflows/build-and-host-war.yml` installs the toolchain on a clean
  `ubuntu-24.04` runner, builds the WAR, deploys it to Tomcat 11.0.27 with MariaDB 11.4, runs the
  smoke suites and publishes the evidence to issue #1. Trigger it with
  `gh workflow run build-and-host-war`.
* **Design preview.** `python3 scripts/preview-api.py --port 3000 --ui backend/src/main/resources/static`
  serves the exported UI plus fixture `/api/v1/**` (banner + `X-Infeara-Preview: fixtures`; writes
  return `PREVIEW_ONLY`). Verified by `scripts/verify-preview.py` (69/69). Sample data only — see
  `docs/operations/design-preview.md`.

## Appendix D — file and ownership map

| Path | Owner | Mode | Contents |
| --- | --- | --- | --- |
| `/opt/infeara/tomcat` | `infeara` | 0750 conf | Tomcat 11.0.27 |
| `/opt/infeara/tomcat/bin/setenv.sh` | `infeara` | **0750** | All deployment secrets — the only file that holds them |
| `/opt/infeara/tomcat/webapps/ROOT.war` | `infeara` | 0640 | The single WAR (UI + API) |
| `/opt/infeara/tomcat/logs/` | `infeara` | 0750 | `catalina.out`, access logs |
| `/etc/mysql/mariadb.conf.d/99-infeara.cnf` | root | 0644 | utf8mb4, UTC, buffer pool |
| `/etc/profile.d/infeara-sales.sh` | root | 0644 | `JAVA_HOME`, `MAVEN_HOME`, `CATALINA_HOME`, `PATH` |
