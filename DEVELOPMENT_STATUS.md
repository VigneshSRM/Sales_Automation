# Development status — Infeara Sales OS

**Snapshot:** 2026-11-02 · **Branch state:** working tree (release trains 0–3 plus the addendum
two-way-email module; meetings/Calendly, the ten AI agents, workflows and commercial modules remain)
**Repository size:** 215 Java sources, 49 entities, 82 tables across 10 Flyway migrations,
25 frontend routes.
**Deployment status:** the single WAR has been **built and deployed to a real Apache Tomcat 11.0.27**,
and the running application answers `/actuator/health`, serves the UI from `/` and returns the
documented JSON envelopes from `/api/v1/**`. See §1 and
`docs/operations/deployment-tomcat11.md` §11 for the recorded evidence.

Read this file before trusting any other claim about the system. It is the single source of truth for
what exists, what is schema-only, and what has been *verified*.

---

## 1. Verification status (read this first)

| Check | State | Evidence |
|---|---|---|
| Frontend type-check | ✅ **Passing** | `cd frontend && npx tsc --noEmit` → no diagnostics |
| Frontend production build | ✅ **Passing** | `npm run build` → 22 static routes exported, copied into `backend/src/main/resources/static` (WAR root) |
| Backend compilation (`mvn clean package`) | ✅ **Passing** | CI run `41365290275`: 861-entry / 75.8 MB WAR, `BUILD SUCCESS` in 26 s. The authoring sandbox still cannot compile (no JDK/Maven, Maven Central blocked) — the build runs on a stock `ubuntu-24.04` runner |
| Flyway migration execution | ✅ **Passing** | All 10 migrations (`V1`–`V10`) applied with `success = 1` against MariaDB 11.4 on the runner |
| Tomcat 11 deployment | ✅ **Verified** | Tomcat 11.0.27, WAR deployed as `ROOT.war`, `Server startup` logged, context started |
| `/actuator/health` | ✅ **UP** | `{"groups":["liveness","readiness"],"status":"UP"}` |
| Deployed UI | ✅ **Serving** | `GET /` → 200 (8 843 B), `GET /leads/` → 200, `GET /engagement/mailbox/` → 200, hashed `/_next/static/**` asset → 200 |
| Deployed API | ✅ **Serving** | `GET /api/v1/auth/me` → 401 `UNAUTHENTICATED` envelope; `POST /api/v1/auth/login` → 200 `MFA_ENROLMENT_REQUIRED` with an enrolment token; `GET /v3/api-docs` → 200, 97 `/api/**` paths |
| First-run bootstrap | ✅ **Verified** | 1 workspace, 1 Super Admin, 66 capabilities, 10 roles, 1 `WORKSPACE_BOOTSTRAPPED` audit row |
| Frontend unit tests | ✅ **28 passing** | `npx vitest run` → 4 files / 28 tests (formatting, API error mapping, component behaviour, mailbox API route contract) |
| Backend unit + architecture tests | ❌ **Executed and failing** | 6 JUnit classes (`PasswordPolicyTest`, `PageQueryTest`, `SecretCipherTest`, `TotpServiceTest`, `LeadStatusTest`, `ArchitectureTest`) run by CI as an informational step (`continue-on-error`); Maven reports `BUILD FAILURE` and the diagnostics are published to issue #1. Fixing them is next action #1 |
| Integration tests | ⚠️ **Authored, not executed** | `SchemaMigrationIT` (Testcontainers MariaDB 11.4) verifies V1–V7 apply and the schema contains the core tables; needs Docker |
| Static cross-checking | ✅ **Run** | `tools/static_audit.py` → 215 files, one benign finding (two controllers share the `/api/v1` prefix) |
| Capability audit | ✅ **Run** | `tools/capability_audit.py` → 66 seeded, 44 enforced in Java, 10 gated in the UI, **0 used-but-unseeded** (it found three missing seeds, fixed in V10) |
| Java symbol audit (compile surrogate) | ✅ **Run** | `tools/java_symbol_audit.py` → 215 types, 1011 field-qualified call sites verified, **0 missing methods** (limitation: it cannot type local variables/parameters, so it is a filter, not a compiler) |
| Entity ↔ migration parity | ✅ **Run** | `tools/schema_report.py` → 49 entities / 82 tables, no column mismatches (now follows `ALTER TABLE … ADD COLUMN`) |
| Data dictionary | ✅ Generated | `tools/data_dictionary.py` → `docs/database/data-dictionary.md` (82 tables) |
| WAR packaging contract | ✅ **Proven on the real WAR** | `scripts/verify-war.py` → 24/24 checks on the 75.8 MB artifact; `scripts/verify-war-selftest.py` → 24 assertions (conforming archive accepted, broken archive rejected for 14 reasons) |
| Migration static checks | ✅ **Run** | `scripts/validate-migrations.py` → 10 migrations / 82 tables, no unknown keys, foreign keys or data targets (its selftest replays the two production defects) |
| Entity ↔ schema cross-check | ✅ **Run** | `scripts/validate-entities.py` → 49 entities, every mapped column present in the migrations, 33 tables still unmapped (schema-only modules) |

> **What the green deployment does *not* prove.** Compilation, schema creation, boot, HTTP serving and
> the RBAC seed are verified. Nothing that needs a third party has been exercised: no SMTP send, no IMAP
> session, no AI provider call, no Calendly webhook. The JUnit/Testcontainers suites run in CI as an
> informational step and currently fail (see §5), so **"tests pass" is not a claim this repository can
> make yet**. Treat the business workflows as unverified end-to-end.

---

## 2. Module status

Legend — **Implemented**: endpoints + validation + persistence + domain rules ·
**Partial**: some paths missing, listed · **Schema**: migration only, no code · **Not started**.

| # | Module | Status | Detail |
|---|---|---|---|
| 1 | Authentication (password, lockout, MFA/TOTP, sessions) | **Implemented** | `identity/*`, `security/*`: cookie session, hashed session tokens, lockout, TOTP enrolment + verification, signed token flow for MFA challenges |
| 2 | Workspace & user management | **Implemented** | `workspace/*`, `identity/UserAdminService`, `web/UserController` |
| 3 | RBAC | **Implemented** | `role_capabilities` seeded in V6 (10 system roles, 55 capabilities), `CapabilityEvaluator` on every service method, `web/UserController` role listing |
| 4 | Executive dashboard | **Implemented** | `analytics/DashboardService` (forecast-shaped payload with metric definitions + limitations), `analytics/ActivityFeedService` |
| 5 | Leads | **Implemented** | `crm/lead/*`: capture with idempotency, patch with rowVersion, lifecycle graph, assignment, qualification, merge with `merge_audits`, bulk assign, archive, duplicate review |
| 6 | Accounts | **Implemented** | `crm/account/*` create/list/detail/update |
| 7 | Contacts | **Implemented** | `crm/contact/*` create/list/detail/update, consent + suppression state |
| 8 | Lead research & intelligence | **Partial** | Manual research/evidence fields exist; the AI Lead Researcher agent is **not implemented** (release 5) |
| 9 | Qualification & scoring | **Implemented** | `crm/scoring/*`: 7 weighted factors, versioned model rows, overrides with reason + audit, `lead_score_history` |
| 10 | Duplicate detection | **Implemented** | `crm/dedupe/DuplicateService`: exact email/phone, domain + company candidates, never auto-merges |
| 11 | Pipeline & opportunities | **Implemented** | `sales/pipeline/*`: 9 seeded stages, gate enforcement (WON/LOST/DISCOVERY/PROPOSAL_SENT), backwards/skip reasons, board, forecast, stage history with `ruleVersion` |
| 12 | Activities & tasks | **Implemented** | `crm/activity/*`, `crm/task/*`, `web/WorkController` |
| 13 | SMTP accounts | **Implemented** | `engagement/smtp/*`: AES-256-GCM secrets, live test before activation, TLS rules, daily/hourly caps |
| 14 | Email templates | **Implemented** | `engagement/template/*`: allow-listed merge fields, preview, submit/approve, versioning |
| 15 | Composer & delivery | **Implemented** | `engagement/email/*`: compose, approve (four-eyes), send-now, cancel, dispatcher, delivery events, message-key idempotency |
| 16 | Unified communication history / two-way inbox | **Code complete, unverified** | `engagement/mailbox/*` (29 files): real IMAP sync, threading, dedupe, unified inbox, notes, assignment, approvals. Not exercised against a live mailbox |
| 17 | Campaigns | **Schema** | `campaigns` table only |
| 18 | Follow-up sequences | **Implemented** | `engagement/sequence/*`: versioned cadence, one active enrolment per lead, stop-on-reply, scheduled engine |
| 19 | Reply classification & AI auto-response | **Code complete, unverified** | `ai/*` + `EmailIntelligenceService`: schema-validated classification, response policy, auto-send only when a rule allows it. **No provider has ever been called** |
| 20 | Suppression & consent | **Implemented** | `engagement/consent/*`: suppression list, consent records, eligibility re-checked before every send |
| 21 | AI provider management & agent workforce | **Partial** | Real OpenRouter + OpenAI adapters, `AiGateway` with schema validation and prompt-injection fencing, `JsonSchemaValidator`. The **ten named agents** (V4 `ai_agents`) are still schema-only; only the email analyst exists |
| 22 | AI approvals | **Schema** | `approvals` table only |
| 23 | Workflow automation | **Partial** | Outbox + durable jobs + handlers + replay + dead-letter implemented (`automation/*`); the user-configurable workflow builder/interpreter (`workflows` tables) is **not implemented** |
| 24 | Proposals | **Schema** | `sales/proposal` empty; the Proposal Sent gate correctly blocks until proposals exist |
| 25 | Customer onboarding | **Schema** | V5 tables only |
| 26 | Renewals & upsell | **Schema** | V5 tables only |
| 27 | Calendly & meetings | **Schema** | V3 tables only; **no webhook endpoint yet** |
| 28 | Revenue analytics & reports | **Partial** | Forecast + dashboard + CSV export of server figures done; saved reports (`report_definitions`, `saved_views`) not implemented |
| 29 | Notifications | **Implemented** | Deduped in-app notifications, preferences, unread count, mark-read |
| 30 | Audit logging | **Implemented** | Per-workspace SHA-256 hash chain, tamper-evident, filterable |
| 31 | System administration | **Implemented** | Users/roles, catalogues, audit, job replay, automation + integration health |
| 32 | Files & attachments | **Schema** | V5 tables only |
| 33 | Data import | **Not started** | No CSV endpoint |

---

## 3. Frontend status

| Route | State |
|---|---|
| `/login` | Implemented (password → MFA → enrolment flows against the real API) |
| `/` dashboard | Implemented against the forecast-shaped dashboard contract |
| `/leads`, `/leads/detail` | Implemented (list, filters, capture modal, detail workspace, lifecycle actions) |
| `/accounts`, `/accounts/detail` | Implemented |
| `/contacts`, `/contacts/detail` | Implemented (read + inline edit) |
| `/pipeline` | Implemented (stage columns, per-currency totals, gate errors surfaced) |
| `/opportunities/detail` | Implemented (fields, stage transitions, history) |
| `/tasks` | Implemented |
| `/reports` | Implemented (period selector, per-currency table, CSV export of server figures) |
| `/engagement/inbox` | Implemented (outbound approvals: approve / send now / cancel, live eligibility, delivery events) |
| `/engagement/mailbox` | Implemented (unified inbox: queue filters, thread timeline, AI panel, reply/approve/reject, assign, note, close) |
| `/engagement/mailbox/settings` | Implemented (create mailbox, live connection test, enable/disable gated on a passing test, sync) |
| `/engagement/mailbox/rules` | Implemented (response-policy rules, intent allow/block lists, enable/disable) |
| `/engagement/templates` | Implemented (create, edit, preview, submit, approve) |
| `/engagement/sequences` | Implemented (cadence editor, activate/pause, enrol, stop) |
| `/engagement/smtp` | Implemented (create, test, activate/disable; write-only secrets) |
| `/admin/users`, `/admin/catalog`, `/admin/audit`, `/admin/jobs` | Implemented |
| Meetings, AI control centre, approvals, proposals | **Not built** — no nav entries exist for them |

---

## 4. Release train

| Release | Scope | State |
|---|---|---|
| 0 | Architecture, compatibility matrix, schema strategy, tooling | **Delivered** — `docs/architecture/*`, `tools/*` |
| 1 | Platform foundation: workspace, auth, MFA, RBAC, audit, notifications, outbox/jobs, admin | **Code complete, unverified** |
| 2 | CRM core: leads, scoring, dedupe, accounts, contacts, pipeline, tasks | **Code complete, unverified** |
| 3 | Engagement: SMTP, templates, composer, approvals, sequences, consent/suppression | **Code complete, unverified** |
| 3b | **Addendum — two-way email + AI auto-response** (V8/V9, `engagement/mailbox`, `ai/*`, inbox UI, WAR hosting scripts) | **Code complete, unverified** — see `docs/releases/release-3b-two-way-email.md` |
| 4 | Meetings & Calendly | **Not started** (schema ready) |
| 5 | AI workforce & approvals | **Not started** (schema ready) |
| 6 | Workflow builder | **Not started** (engine foundation done) |
| 7 | Commercial: proposals, onboarding, renewals, saved reports | **Not started** (schema ready) |
| 8 | Hardening: performance, backup drill, penetration fixes | **Not started** |

---

## 5. Next actions, in order

1. Fix the failing JUnit suite (`mvn test` in CI prints the diagnostics in the issue comment); the
   build itself is green, so this is about proving behaviour, not about making the WAR build.
2. `sudo ./scripts/install-toolchain.sh && ./scripts/build-war.sh && sudo ./scripts/host-app.sh` on a
   host with normal egress reproduces the verified deployment (see `docs/operations/deployment-tomcat11.md` §11).
3. Against a real mailbox: add it, **Test**, enable, send yourself one message, confirm it appears in
   the unified inbox once (dedupe), is threaded, and produces an AI analysis or a clear
   "provider not configured" state.
3. Run the Testcontainers integration suite; wire the Playwright E2E suite for the green UAT scenarios.
4. Release 4: Calendly OAuth connection, webhook endpoint with signature verification
   (persist-before-process), event sync, meeting timeline UI.
5. Release 5: `AiProvider` / `AiGateway`, the ten agents, JSON-Schema validation, approval queue.
6. Release 6: workflow definitions/interpreter on the existing outbox/job engine, with loop guards.
7. Release 7: proposals + onboarding + renewals + saved reports.
