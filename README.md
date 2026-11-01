# Infeara Sales OS

Autonomous sales CRM and revenue operations platform for **Infeara Technologies**, built to the
functional specification in `docs/requirements/SRS.md` (derived from the SDLC brief) plus the
**updated** brief's addendum: a real two-way mailbox, AI triage with schema-constrained output, and a
response policy that decides between auto-send, human approval and silence.

| | |
|---|---|
| Backend | Java 17 · Spring Boot 4.0.9 · Spring Framework 7 · Spring Security 7 · Spring Data JPA/Hibernate 7 · Flyway · MariaDB 11.4 |
| Frontend | Next.js 16 (static export) + React 19 + TypeScript, bundled **inside the WAR** and served same-origin from Tomcat (ROOT context) |
| Deployment | One WAR on **Apache Tomcat 11** (`ROOT.war`, Servlet 6.1) containing both the API and the UI |
| Async work | MariaDB transactional outbox + durable job table (no Redis, no broker) |

## Repository layout

```
backend/      Spring Boot WAR (Maven) — modular monolith
frontend/     Next.js UI, exported into backend/src/main/resources/static (WAR root)
docs/         Requirements, architecture, data model, API, security, operations, testing, releases
deploy/       Tomcat / Docker development assets and environment templates
tools/        Developer tooling (schema generator, static analysis helpers)
scripts/      Toolchain install, WAR build + packaging verification, Tomcat hosting
```

## Host it on Tomcat 11 (the supported deployment)

```bash
sudo ./scripts/install-toolchain.sh   # JDK 17, Maven 3.9, Tomcat 11.0.x, MariaDB 11.4
./scripts/verify-toolchain.sh         # pinned-version gate
./scripts/build-war.sh                # UI export -> WAR -> 24 packaging assertions
sudo ./scripts/host-app.sh            # database, setenv.sh, ROOT.war, health wait
# then: http://localhost:8080/  (UI)  ·  /api/v1  ·  /swagger-ui.html  ·  /actuator/health
```

`scripts/build-war.sh` produces **one** artefact containing the API and the UI; Tomcat supplies the
servlet container (`spring-boot-starter-tomcat` is `provided`).

## Quick start (development)

```bash
# 1. Database + mail sink (development only)
docker compose -f deploy/docker-compose.dev.yml up -d

# 2. Backend (needs JDK 17 + Maven 3.9+)
cd backend
mvn -Dspring-boot.run.profiles=dev spring-boot:run

# 3. Frontend (proxies /api to localhost:8080)
cd frontend
npm install
npm run dev          # http://localhost:3000
```

`scripts/serve-ui-static.sh` serves only the built UI (no API) — useful for design review where a JDK
cannot be installed.

First run provisions the workspace, RBAC seed data, pipeline stages, services, sources, loss
reasons and the scoring model v1. The administrator account is created only when
`BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` are supplied — no credentials exist in
source control.

## Build the production WAR

```bash
cd frontend && npm ci && npm run build     # exports UI into backend static resources
cd ../backend && mvn -DskipTests package   # target/infeara-sales-os.war
```

Copy the WAR into `$CATALINA_BASE/webapps/` — see `docs/operations/deployment-tomcat11.md`.

## Documentation map

| Document | Purpose |
|---|---|
| `docs/requirements/SRS.md` | Authoritative requirements, NFRs, acceptance criteria |
| `docs/requirements/business-rules.md` | Enforceable business rules (BR-*) |
| `docs/requirements/traceability-matrix.md` | Requirement → implementation → test mapping |
| `docs/architecture/system-architecture.md` | Deployment, layering, runtime topology |
| `docs/architecture/architecture-decisions.md` | ADRs with trade-offs and consequences |
| `docs/architecture/compatibility.md` | Verified Java / Spring Boot / Tomcat 11 matrix |
| `docs/database/ERD.md`, `docs/database/data-dictionary.md` | Data model |
| `docs/api/API-SPECIFICATION.md` | REST contract, envelopes, error codes |
| `docs/security/security-checklist.md` | Control-by-control security status |
| `docs/operations/*` | Tomcat deployment, environment variables, backup/recovery |
| `docs/testing/*` | Test strategy, UAT scenarios |
| `docs/releases/*` | Per-release completion reports |
| `DEVELOPMENT_STATUS.md` | **Current, honest implementation status** — read this first |

## Honesty statement

This repository is a working implementation in progress, delivered release by release. The
`DEVELOPMENT_STATUS.md` file lists exactly which modules are implemented, which are schema-only and
which are not started. Nothing in this repository is described as complete unless its endpoints,
validation, persistence and tests exist.
