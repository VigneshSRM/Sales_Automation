# Infeara Sales OS

Autonomous sales CRM and revenue operations platform for **Infeara Technologies** — one deployable
WAR containing a Spring Boot API and a Next.js UI, hosted on **Apache Tomcat 11** against
**MariaDB 11.4**.

Built to the functional specification in `docs/requirements/SRS.md` (derived from the SDLC brief)
plus the updated brief's addendum: a real two-way mailbox, AI triage with schema-constrained output,
and a response policy that decides between auto-send, human approval and silence.

| | |
|---|---|
| Backend | Java 17 · Spring Boot 4.0.9 · Spring Framework 7 · Spring Security 7 · Spring Data JPA / Hibernate 7 · Flyway · MariaDB 11.4 |
| Frontend | Next.js 16 static export + React 19 + TypeScript, packaged **inside the WAR** and served same-origin from Tomcat |
| Runtime | Apache Tomcat 11.0.x (Servlet 6.1), `ROOT.war`, one process, one origin, no CORS |
| Async | MariaDB transactional outbox + durable job table (no Redis, no broker) |
| AI | Real OpenRouter → OpenAI adapters; unconfigured = explicit `AI_PROVIDER_UNAVAILABLE`, never mock data |

## Current verification state

Everything below was produced by CI run
[`41403295866`](https://github.com/VigneshSRM/Sales_Automation/actions/runs/41403295866) — a clean
`ubuntu-24.04` runner that installed the toolchain, built the WAR, deployed it to Tomcat 11.0.27
against MariaDB 11.4 and exercised it over HTTP. Nothing in this table is a projection.

| Check | Result |
| --- | --- |
| WAR build | `BUILD SUCCESS` — 866 entries, 75.8 MB, single `ROOT.war` (UI + API) |
| Packaging contract | `scripts/verify-war.py` 24/24 |
| Tomcat 11.0.27 | deployed as `ROOT.war`, `/actuator/health` **UP** |
| UI | `/` 200 · `/leads/` 200 · `/engagement/mailbox/` 200 · hashed `/_next/static/**` 200 |
| API | `/v3/api-docs` 200, **97 paths**; anonymous `/api/v1/auth/me` → 401 envelope |
| Database | Flyway `V1`–`V10` applied, `success = 1`, 83 tables |
| Tests | backend 26/26, frontend 28/28 |
| Authenticated smoke | 16/16 — login → MFA enrolment → session cookie → module reads → CSRF-protected write |

`DEVELOPMENT_STATUS.md` is the honest, per-module status (implemented / code complete but
unverified / schema-only / not started). Read it before believing any "it's done" claim elsewhere.

---

# Installation and setup

Two paths are supported. Pick the one that matches what you are doing.

| Path | Use it for | Needs |
| --- | --- | --- |
| **A. Tomcat 11 (production)** | The real, supported deployment: one WAR, UI + API on one origin | Linux host, root/sudo, outbound HTTPS |
| **B. Local development** | Changing the code, running tests, live reload | JDK 17, Node 22, Docker |

A third path exists for review environments where no JDK can be installed (design preview with
fixture data) — see the last section.

## Prerequisites

| Component | Version | Notes |
| --- | --- | --- |
| JDK | **17** (LTS) | Must include `javac` — a JRE cannot build the WAR. Temurin/OpenJDK both fine. |
| Maven | **3.9+** | Only needed to build; not needed to run. |
| Apache Tomcat | **11.0.x** | Servlet 6.1 required by Spring Boot 4. Tomcat 10.1 or earlier will not deploy this WAR. |
| MariaDB | **11.4 LTS** | Only supported database. MySQL 8 is not tested; no other engine is supported. |
| Node.js | **22 LTS** | Builds the UI into `backend/src/main/resources/static`. |
| Docker | any recent | Path B only, for MariaDB + Mailpit. |
| OS | Debian/Ubuntu or RHEL/Rocky/Alma | The installer detects `apt`/`dnf`/`yum`. |
| Network | `adoptium.net`, `archive.apache.org`, `r.mariadb.com`, Maven Central, `registry.npmjs.org` | The sandbox this repository was authored in blocks all but the last two — see `docs/operations/sandbox-environment.md`. |

Disk ~2 GB (75.8 MB WAR, Tomcat, Maven repo, npm cache). RAM 2 GB minimum for Tomcat; the worker
runs inside the same JVM as the API.

## Path A — production install on Tomcat 11

Scripted, idempotent, and the same sequence CI runs. Five steps:

```bash
git clone https://github.com/VigneshSRM/Sales_Automation.git
cd Sales_Automation

# 1. Toolchain: JDK 17, Maven 3.9.11, Tomcat 11.0.27, MariaDB 11.4 (apt or dnf)
sudo ./scripts/install-toolchain.sh
source /etc/profile.d/infeara-sales.sh        # or open a new shell

# 2. Version gate — fails loudly if anything is off
./scripts/verify-toolchain.sh

# 3. Build the single WAR (UI export → Maven → 24 packaging assertions)
./scripts/build-war.sh                        # add --with-tests to run JUnit + Testcontainers

# 4. Provision MariaDB, render setenv.sh, deploy ROOT.war, wait for health
sudo ./scripts/host-app.sh

# 5. Import the generated secrets into your secret store, then verify
curl -s http://localhost:8080/actuator/health
```

`host-app.sh` prints the bootstrap administrator and a generated password once. Those values exist
only in `$CATALINA_BASE/bin/setenv.sh` (mode `0750`) — move them to your secret manager and rotate
the password at first sign-in.

Open **http://localhost:8080/** — the UI and the API are the same WAR on the same origin.

### What each step actually does

1. **`install-toolchain.sh`** installs JDK 17, Maven 3.9.11 (archive SHA-512 verified) and MariaDB 11.4 from the vendor repo;
   Tomcat 11.0.27 is unpacked with `manager`/`host-manager`/`docs`/`examples` removed to reduce the
   attack surface. It
   writes `/etc/profile.d/infeara-sales.sh` so `JAVA_HOME`, `CATALINA_HOME` and `PATH` are correct
   for every new shell. Already-correct versions are skipped, never reinstalled.
2. **`verify-toolchain.sh`** asserts `javac` 17, Maven 3.9+, Tomcat 11 (reads
   `bin/version.sh`), a MariaDB client and Node 20+. Exit code 1 = do not build.
3. **`build-war.sh`** runs `npm ci && npm run build` (static export → `backend/src/main/resources/static`),
   then `mvn package` with the container at `provided` scope, then `scripts/verify-war.py`. Output:
   `dist/infeara-sales-os.war` plus 24 printed assertions.
4. **`host-app.sh`** creates the database and a least-privilege application user (DML + DDL only, no
   `GRANT`/`CREATE USER`), generates the AES-256-GCM key and the bootstrap password, writes
   `setenv.sh`, copies the WAR as `ROOT.war`, starts Tomcat and blocks until
   `/actuator/health` reports `UP` (60 s budget, full `catalina.out` tail on failure).

### Manual install (no scripts)

If policy forbids running repository scripts as root, do the same thing by hand:

```bash
# Toolchain (Ubuntu; substitute the dnf package names on RHEL-family)
sudo apt-get install -y openjdk-17-jdk-headless curl unzip
# Maven 3.9.x and Tomcat 11.0.x from archive.apache.org, unpacked under /opt/infeara
sudo apt-get install -y mariadb-server && sudo systemctl enable --now mariadb

# Database
sudo mariadb -e "CREATE DATABASE infeara_sales CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  CREATE USER 'infeara'@'localhost' IDENTIFIED BY '<strong-password>';
  GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON infeara_sales.* TO 'infeara'@'localhost';"

# Build
cd frontend && npm ci && npm run build && cd ../backend && mvn -DskipTests package

# Deploy
sudo cp target/infeara-sales-os.war /opt/infeara/tomcat/webapps/ROOT.war
sudo $EDITOR /opt/infeara/tomcat/bin/setenv.sh   # start from deploy/tomcat/setenv.sh
sudo /opt/infeara/tomcat/bin/startup.sh
```

The full environment variable list with meaning and defaults is in
`deploy/env/.env.example`, `deploy/tomcat/setenv.sh` and §"Configuration" below.

## Path B — local development

```bash
# 1. Infrastructure: MariaDB 11.4 + Mailpit (catches outbound mail on :1025, UI on :8025)
docker compose -f deploy/docker-compose.dev.yml up -d

# 2. Backend on :8080 (Flyway migrates on boot; dev profile trusts no TLS and logs at DEBUG)
cd backend
DB_USER=infeara DB_PASSWORD=dev-password mvn spring-boot:run

# 3. UI on :3000 with /api proxied to :8080 (single origin, cookies behave as in production)
cd frontend
npm ci
npm run dev
```

Then in the UI: create an SMTP profile pointing at `localhost:1025` (TLS `NONE`, any user/password)
and run **Test** — mail appears at http://localhost:8025. Inbound needs a real IMAP mailbox; it
cannot be faked, by design.

Useful commands:

```bash
cd frontend && npx tsc --noEmit && npx vitest run     # type-check + 28 unit tests
cd backend  && mvn test                               # 26 unit + architecture tests
./scripts/build-war.sh --with-tests                   # full build incl. Testcontainers ITs (needs Docker)
python3 scripts/validate-migrations.py                # migration static checks
python3 tools/capability_audit.py                     # capability strings vs seeded roles
```

## Configuration

Everything is environment-driven; **no credential appears in source control**. Minimum needed to
boot in `prod`:

| Variable | Purpose |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` for Tomcat deployment, `dev` locally |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | MariaDB connection (names are read directly by `application.yml` — do not rename) |
| `SMTP_SECRET_ENCRYPTION_KEY` | Base64 32-byte AES-256-GCM key for stored integration secrets: `openssl rand -base64 32` |
| `SMTP_SECRET_ENCRYPTION_KEY_ID` | Key label, `k1` by default; rotation via `SMTP_SECRET_PREVIOUS_KEYS=<id>:<key>,…` |
| `APP_BASE_URL` | Public base URL used in links and API responses |
| `BOOTSTRAP_ADMIN_EMAIL` `BOOTSTRAP_ADMIN_PASSWORD` | First-run administrator. Empty password = skip bootstrap. Used only while the workspace has no users |
| `BOOTSTRAP_WORKSPACE_NAME` `BOOTSTRAP_WORKSPACE_SLUG` `BOOTSTRAP_WORKSPACE_TIMEZONE` | First-run workspace |
| `WORKER_ENABLED` | `true` runs the outbox relay, job engine, email dispatcher and inbound discovery in-process |

Optional groups (all documented in `deploy/env/.env.example`): session and rate limits
(`SESSION_TTL_MINUTES`, `SESSION_IDLE_MINUTES`, `COOKIE_SECURE`, `APP_SECURITY_COOKIE_SECURE`),
inbound mailbox caps (`EMAIL_INBOUND_*`), AI providers (`AI_OPENROUTER_API_KEY`,
`AI_OPENAI_API_KEY`, `AI_PREFERRED_PROVIDER`), Calendly (`CALENDLY_WEBHOOK_TOLERANCE_SECONDS`).

Notes that save real debugging time:

* **`APP_SECURITY_COOKIE_SECURE`** (default `auto`) decides whether the session cookie is marked
  `Secure`. `auto` marks it `Secure` only when the request arrived over TLS (honouring
  `X-Forwarded-Proto`, which `prod` reads via `server.forward-headers-strategy: native`). Setting
  `always` behind a plain-HTTP Tomcat connector makes every client silently drop the cookie and
  every authenticated request returns 401 — this exact defect was found and fixed during
  verification (`docs/implementation/KNOWN-ISSUES.md`, F-1).
* **Deploy as `ROOT.war`.** The exported UI uses absolute `/_next/...` asset URLs, so a non-root
  context path serves an unstyled page.
* **Reverse proxy:** terminate TLS in front and forward
  `X-Forwarded-Proto`/`X-Forwarded-For`; then `APP_SECURITY_COOKIE_SECURE=auto` is correct. A sample
  nginx server block is in `docs/operations/installation-setup.md`.
* **AI and inbound mail left empty is a supported state**: the UI says analysis is unavailable
  instead of showing fabricated classifications.

## After installation

1. Sign in at `http://localhost:8080/` with the bootstrap administrator.
2. Complete **MFA enrolment** — the first login returns `MFA_ENROLMENT_REQUIRED`; scan the TOTP
   provisioning URI and confirm. No session exists before enrolment completes.
3. **Engagement → Sending accounts**: add the SMTP profile, run **Test**, and activate only after it
   passes (activation is gated on a passing test).
4. **Engagement → Mailbox settings**: add the IMAP mailbox, run **Test**, enable sync. Sync is
   discovery-based: a `MAILBOX_SYNC` job is enqueued every 60 s and the IMAP session runs outside
   any database transaction.
5. Optional: AI keys (`AI_OPENROUTER_API_KEY`), then **Mailbox rules** to define which intents may
   be auto-answered. Without a rule, nothing is sent automatically.
6. Point monitoring at `/actuator/health` (liveness + readiness probes) and
   `/actuator/prometheus`.

## Operations quick reference

| Task | Command |
| --- | --- |
| Health | `curl -s http://localhost:8080/actuator/health` |
| API docs | `http://localhost:8080/swagger-ui.html` · `http://localhost:8080/v3/api-docs` |
| Logs | `tail -f $CATALINA_BASE/logs/catalina.out` |
| Redeploy a new WAR | `sudo ./scripts/host-app.sh` (stops, replaces, restarts, waits for health) |
| Stop / start | `$CATALINA_HOME/bin/shutdown.sh` · `$CATALINA_HOME/bin/startup.sh` |
| Backup | `docs/operations/backup-recovery.md` (mariadb-dump + `setenv.sh` + WAR version) |

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Tomcat 404 on `/` | WAR not named `ROOT.war` | Rename; the export needs the root context |
| `/actuator/health` never `UP` | DB unreachable or Flyway mismatch | `grep -i -m20 'error\|exception' $CATALINA_BASE/logs/catalina.out` |
| Login succeeds, then every call is 401 | Cookie `Secure` on a plain-HTTP connector | Set `APP_SECURITY_COOKIE_SECURE=auto` (or front it with TLS + `X-Forwarded-Proto`) |
| `Schema-validation: missing table` | Migrations did not run | Check `flyway_schema_history`; the app user needs DDL rights |
| `UNSUPPORTED_OPERATION` on a mailbox | Provider is Gmail API / Microsoft Graph | Only IMAP is implemented; non-IMAP providers return 501 by design |
| `AI_PROVIDER_UNAVAILABLE` | No AI key configured | Expected: set `AI_OPENROUTER_API_KEY` or `AI_OPENAI_API_KEY` |
| Build fails at `verify-toolchain.sh` | Wrong JDK/Tomcat/Node | Install the pinned versions; do not patch the gate |
| UI loads unstyled | Assets served from another origin | Serve from the same WAR/root context |

## Design preview without a JDK

For review hosts where nothing can be installed, `scripts/preview-api.py` serves the built static
export **plus** a fixture `/api/v1/**` on one port so every screen renders (banner + `X-Infeara-Preview`
header; writes answer `PREVIEW_ONLY`). Verify it with `scripts/verify-preview.py` (69/69 checks).

```bash
python3 scripts/preview-api.py --port 3000 --ui backend/src/main/resources/static
python3 scripts/verify-preview.py --base http://127.0.0.1:3000
```

This is a review tool, not a deployment. See `docs/operations/design-preview.md`.

## Repository layout

```
backend/      Spring Boot WAR (Maven) — modular monolith, V1–V10 Flyway migrations
frontend/     Next.js UI, exported into backend/src/main/resources/static (WAR root)
docs/         Requirements, architecture, data model, API, security, operations, testing, releases
deploy/       Tomcat config, environment template, dev docker-compose (MariaDB + Mailpit)
scripts/      Toolchain install, WAR build + packaging verification, Tomcat hosting, preview, smoke tests
tools/        Schema/doc generators and static audits
dist/         Build output (gitignored)
```

## Documentation map

| Document | Purpose |
| --- | --- |
| **`docs/operations/installation-setup.md`** | **Full install & setup guide — this README in depth: manual per-component install, TLS proxy, hardening, upgrades, uninstall** |
| `DEVELOPMENT_STATUS.md` | Honest per-module implementation status — read first |
| `docs/operations/deployment-tomcat11.md` | Tomcat deployment, evidence transcript, runbook |
| `docs/operations/deployment-attempt-2026-11-02.md` | Exactly what was executed, including the failures |
| `docs/operations/sandbox-environment.md` | Which verification steps ran here and which are scripted only |
| `docs/operations/backup-recovery.md` | Backup, restore, disaster recovery |
| `docs/operations/design-preview.md` | Reviewing the UI without a JDK |
| `docs/requirements/SRS.md`, `docs/requirements/business-rules.md`, `docs/requirements/traceability-matrix.md` | Requirements and BR-* rules |
| `docs/architecture/system-architecture.md`, `docs/architecture/architecture-decisions.md`, `docs/architecture/compatibility.md` | Layering, ADRs, pinned version matrix |
| `docs/database/ERD.md`, `docs/database/data-dictionary.md` | Data model (82 tables) |
| `docs/api/API-SPECIFICATION.md` | REST contract, envelopes, error codes |
| `docs/security/security-checklist.md` | Control-by-control security status |
| `docs/testing/test-plan.md`, `docs/testing/uat-scenarios.md` | Test strategy and UAT |
| `docs/releases/*` | Per-release completion reports |

## Honesty statement

This is a working implementation delivered release by release. Endpoints, validation, persistence
and tests exist for the modules marked *Implemented* in `DEVELOPMENT_STATUS.md`; modules marked
*schema-only* or *not started* are exactly that, and no screen or button claims otherwise.
Integrations that are not configured fail explicitly rather than pretending to work.
