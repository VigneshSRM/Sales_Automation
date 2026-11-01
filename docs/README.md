# Documentation index

The specification's eleven mandatory deliverables, and where each one lives.

| # | Deliverable | File | State |
|---|---|---|---|
| 1 | Requirements & business rules (from the brief + updated addendum) | `requirements/SRS.md`, `requirements/business-rules.md` | Delivered |
| 2 | Architecture & architecture decisions | `architecture/system-architecture.md`, `architecture/architecture-decisions.md`, `architecture/compatibility.md` | Delivered |
| 3 | Data model: ERD + data dictionary | `database/ERD.md`, `database/data-dictionary.md` (generated) | Delivered |
| 4 | API documentation | `api/API-SPECIFICATION.md`, `api/endpoint-inventory.md` (generated) | Delivered (114 endpoints, incl. the two-way email module) |
| 5 | Security checklist | `security/security-checklist.md` | Delivered; open items listed |
| 6 | Test plan & traceability | `testing/test-plan.md`, `requirements/traceability-matrix.md` | Delivered |
| 7 | Deployment guide (WAR on Tomcat 11) + env template | `deployment/DEPLOYMENT-GUIDE.md`, `operations/deployment-tomcat11.md`, `../deploy/env/.env.example` | Delivered |
| 8 | Backup & recovery guide | `operations/backup-recovery.md` | Delivered |
| 9 | UAT scenarios | `testing/uat-scenarios.md` | Delivered (20 scenarios, status per scenario) |
| 10 | Release completion reports | `releases/release-0.md` … `releases/release-3.md`, `releases/release-3b-two-way-email.md` | Delivered for 0–3 and the addendum; 4–8 not started |
| 11 | Final implementation report + known issues | `implementation/FINAL-IMPLEMENTATION-REPORT.md`, `implementation/KNOWN-ISSUES.md` | Delivered (honest current state) |

## Developer tooling

| Tool | Purpose |
|---|---|
| `tools/generate_schema.py` | Regenerates `V1__core_schema.sql` from the JPA entities (never hand-edit V1) |
| `tools/schema_report.py` | Entity ↔ migration parity report |
| `tools/data_dictionary.py` | Builds the data dictionary from the migrations |
| `tools/api_inventory.py` | Builds the endpoint inventory from the controllers |
| `tools/static_audit.py` | JDK-free consistency checks: imports resolve, one type per file, balanced blocks, capability/error codes declared, repository methods declared |
| `tools/capability_audit.py` | Cross-checks every enforced capability against the RBAC seed in both directions |
| `tools/java_symbol_audit.py` | Compile surrogate: resolves every `<field>.<method>()` call against the project type's declared/Lombok-generated methods |
| `scripts/verify-war.py` | 24 packaging assertions on a built WAR (single-WAR delivery contract) |
| `scripts/verify-war-selftest.py` | Proves the WAR checker accepts a conforming archive and rejects a broken one |

## Read order for a new engineer

1. `../DEVELOPMENT_STATUS.md` — what actually exists, and what is *not* verified.
2. `requirements/SRS.md` → `requirements/business-rules.md`.
3. `architecture/system-architecture.md` → `architecture/architecture-decisions.md`.
4. `architecture/compatibility.md` — the pinned version matrix and why.
5. `database/ERD.md` → `database/data-dictionary.md`.
6. `api/API-SPECIFICATION.md` → `api/endpoint-inventory.md`.
7. `deployment/DEPLOYMENT-GUIDE.md` → `operations/deployment-tomcat11.md` to run it on Tomcat 11.
8. `operations/sandbox-environment.md` — exactly which verification steps were executed and which are
   only scripted (read this before believing any "it works" claim).
