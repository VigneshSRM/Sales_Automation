# Final implementation report (interim — releases 0–3)

> This is the honest state of the repository on 2026-11-01. It is deliberately not titled
> "complete": four of the nine release trains are authored, none of the authored backend has been
> compiled or executed.

## 1. Summary

| Dimension | Value |
|---|---|
| Backend sources | 178 Java files, ~14.3k lines |
| Entities / repositories / controllers / services | 42 / 42 / 18 / 28 |
| Flyway migrations | 7 files, 75 tables (`V1` core is generated from entities) |
| REST endpoints | 91 across 17 controllers (generated inventory in `docs/api/endpoint-inventory.md`) |
| Frontend | 22 static routes, ~6.0k lines, TypeScript clean, production build green |
| Documentation | 15 documents including the 11 mandated deliverables |
| Automated tooling | schema generator, schema parity report, data dictionary, endpoint inventory, static audit |

## 2. What is functional by construction

These modules have full stacks — persistence, validated DTOs, service-layer business rules,
capability checks, audit + outbox publication, REST endpoints and a UI screen wired to those
endpoints:

authentication & MFA · workspace bootstrap · RBAC · users/roles · lead capture, lifecycle,
duplicates, scoring · accounts · contacts · pipeline & opportunities with server-side gates · board &
forecast · activities & tasks · SMTP accounts · templates · composer with four-eyes approval ·
suppression & consent · follow-up sequences · dashboard · global search · notifications ·
audit log · job/outbox administration.

## 3. What is not functional

| Area | State |
|---|---|
| Calendly & meetings | V3 schema only — no connection, no webhook, no sync, no UI |
| AI providers, gateway, agents, approvals | V4 schema only — **no provider adapters, no simulated responses** |
| Workflow builder | V7 schema only — outbox/job engine exists but nothing user-configurable |
| Proposals, onboarding, renewals | V5 schema only |
| Campaigns, reply classification, inbound IMAP, unsubscribe endpoint, files, imports, saved reports | Not implemented |

The UI deliberately has no navigation entries for anything in this table, so no screen is a dead end.

## 4. Engineering decisions worth knowing

1. **Identifiers** are UUIDs stored as `CHAR(36)` strings in Hibernate, keeping `ddl-auto=validate`
   honest and index-friendly on MariaDB.
2. **No `ddl-auto=update` anywhere**; Flyway owns the schema and `V1` is generated from the entity
   model to remove drift by construction.
3. **Stage gates are policy-versioned** (`pipeline-policy-v1`) and each decision is written to
   `stage_history`, so a closed-won deal can be re-explained months later.
4. **Eligibility is evaluated at send time, inside the transaction**; a recipient who unsubscribes
   between approval and delivery is still protected.
5. **Four-eyes approvals** are enforced server-side (author ≠ approver) for both templates and
   messages.
6. **Async work is leased in MariaDB**, so multiple Tomcat instances cannot double-send.
7. **The UI cannot invent numbers**: every KPI, board total and CSV cell comes from a server
   response; the client only formats.
8. **Static export inside the WAR** means one origin, no CORS, cookies and CSRF unchanged — at the
   cost of `?id=` detail routes (documented in ADR-004).

## 5. Verification actually performed

```
$ python3 tools/static_audit.py
files scanned: 178  types declared: 312
problems: 1                      # two controllers sharing the /api/v1 prefix (benign)
$ python3 tools/schema_report.py
entities: 42  tables in migrations: 75
$ python3 tools/api_inventory.py
wrote docs/api/endpoint-inventory.md: 91 endpoints
$ cd frontend && npx tsc --noEmit && npm run build
✓ 22 static routes exported
```

Not performed, and therefore not claimed: `mvn verify`, Flyway execution, Testcontainers, Playwright,
a real SMTP send, a Calendly webhook, any AI call.

## 6. Path to a genuinely complete system

1. Compile and boot (release 8 gate) — fix what the compiler and Flyway find.
2. Release 4 — Calendly/meetings (schema is waiting; webhook security design is documented).
3. Release 5 — AI gateway + ten agents + approval queue.
4. Release 6 — workflow interpreter with loop guards, on the existing outbox/job engine.
5. Release 7 — proposals, onboarding, renewals, campaigns, inbound, saved reports.
6. Release 8 — performance pass, backup/DR drill, penetration test fixes, CI with dependency
   scanning and the full test suite.

Each step ends with a completion report in `docs/releases/`.
