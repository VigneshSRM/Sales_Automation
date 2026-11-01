# Software Requirements Specification — Infeara Sales OS

| Field | Value |
|---|---|
| Document ID | INFEARA-SRS-001 |
| Version | 1.0 |
| Date | 2026-11-01 |
| Source baseline | `Infeara_Sales_OS_Complete_SDLC_Specification.pdf` v1.0 (02 Nov 2026) |
| Owner | Solution Architect / Business Analyst |
| Status | Baseline for implementation (Release 0 exit artefact) |
| Traceability | See `docs/requirements/traceability-matrix.md` |

This SRS restates the approved specification as testable requirements. Where the specification
gives an example or a "configurable" value, the requirement below is stated so that the value is
configuration, not code. Requirement IDs are stable and are referenced by the API specification,
database documentation, automated tests and UAT scenarios.

---

## 1. Purpose, scope and system context

Infeara Sales OS is an internal, browser-based revenue-operations application that manages the full
B2B sales lifecycle for one or more workspaces: prospect intake → research → qualification →
outreach → replies → meetings → discovery → proposal → negotiation → closed won → onboarding →
renewal/expansion, with AI assistance, workflow automation and analytics.

**In scope (P0/P1 modules):** authentication, workspace and user administration, RBAC, executive
dashboard, leads, accounts, contacts, lead research/intelligence, qualification/scoring, duplicate
detection, pipeline, opportunities, activities/tasks, SMTP accounts, templates, composer, unified
communication history, campaigns, sequences, reply classification, suppression/consent, Calendly,
meetings, AI provider management, AI agents, AI approvals, workflow automation, proposals, customer
onboarding, renewals/upsell, revenue analytics, reports, notifications, audit, system administration.

**Explicitly out of scope (initial release):** full accounting/ERP, automated contract signature,
autonomous discount approval, bulk scraping of personal data, a native competing booking engine.

**System boundary:** modular monolith (Java 17 / Spring Boot 4.x) packaged as a WAR for on-premises
Apache Tomcat 11, MariaDB as system of record, Next.js/TypeScript frontend served by the same origin,
outbound email through customer-provided SMTP accounts, inbound email through IMAP/mailbox/webhook,
Calendly as the scheduling system of record, OpenRouter/OpenAI through a provider-neutral AI gateway.

## 2. Definitions

See `docs/database/data-dictionary.md` §Glossary for CRM terms (lead, account, contact,
opportunity, sequence, suppression, outbox, idempotency, RPO/RTO).

## 3. Actors and personas

| ID | Persona | Typical access |
|---|---|---|
| PERS-01 | Super Admin | All workspace settings, users, integrations, audit, data administration |
| PERS-02 | Sales Director | All sales teams, pipeline, forecast, campaign and performance reports |
| PERS-03 | Sales Manager | Assigned team, opportunities, tasks, approvals, team reports |
| PERS-04 | Sales Representative | Owned/assigned leads, contacts, accounts, opportunities, activities, permitted email |
| PERS-05 | Marketing Manager | Campaigns, templates, sequences, consent/suppression reporting, campaign analytics |
| PERS-06 | Marketing Executive | Assigned campaigns and approved templates; no unrestricted export |
| PERS-07 | Sales Operations | CRM configuration, routing rules, workflow definitions, data quality |
| PERS-08 | Technical Consultant | Assigned discovery meetings, technical notes, relevant opportunity data |
| PERS-09 | Finance / Commercial | Approved deal values, proposal terms, commercial reporting |
| PERS-10 | Read Only / Auditor | Read-only within scope; audit access only if separately granted |
| PERS-11 | Automation worker (system principal) | Executes approved jobs; never a human login; no interactive permissions |

## 4. Non-functional requirements

| ID | Requirement | Acceptance criteria |
|---|---|---|
| NFR-STACK-001 | Backend runs on Java 17 with a Spring Boot release officially supporting Servlet 6.1 / Tomcat 11; versions pinned in `pom.xml` and deployment manifests. | `docs/architecture/architecture-decisions.md` ADR-001 records the verified matrix; compatibility PoC WAR deploys on Tomcat 11. |
| NFR-STACK-002 | Schema is managed exclusively by Flyway versioned migrations; Hibernate `ddl-auto` is `validate` (test) / `none` (prod). | Integration test fails when entity and migration diverge. |
| NFR-DB-001 | MariaDB InnoDB, `utf8mb4`, UTC timestamps, `DECIMAL` money, UUID identifiers, IANA timezone identifiers. | Migration review checklist + schema tests. |
| NFR-NOREDIS-001 | No Redis, no external queue/broker. Asynchronous work uses MariaDB outbox/job tables only. | Dependency review: no Redis artifact in `mvn dependency:tree`. |
| NFR-PERF-001 | Normal API reads p95 < 300 ms under agreed load; list endpoints are server-side paginated with a hard maximum page size. | Gatling/JMeter evidence + `PageRequest` cap test. |
| NFR-PERF-002 | No unbounded queries, no full-table retrieval, no N+1 on list endpoints. | Code review checklist; pagination default 25 / max 100. |
| NFR-AVAIL-001 | 99.9% monthly availability target after stabilisation; health endpoints for liveness/readiness. | Actuator probes documented in deployment guide. |
| NFR-ASYNC-001 | Queue acceptance is independent of provider response time; webhook acknowledgement is fast and durable. | Webhook endpoint only validates + persists + returns 2xx; processing is asynchronous. |
| NFR-OBS-001 | Structured logs with timestamp, level, module, workspace ref, user ref, request/correlation ID; secrets redacted; metrics and alerting defined. | Log review test asserts no secret patterns; metrics listed in ops runbook. |
| NFR-COMPAT-001 | Responsive desktop/tablet/mobile, WCAG 2.2 AA target, keyboard navigable, visible focus states. | Accessibility checklist + Playwright keyboard journey. |
| NFR-I18N-001 | All timestamps stored UTC and displayed in the user's/workspace's IANA timezone with explicit labels. | Server serialises ISO-8601 `Z`; UI formats with `Intl.DateTimeFormat` + timezone label. |

## 5. Functional requirements by module

Each requirement lists: statement, priority (P0 mandatory for release, P1 next), and acceptance
criteria reference(s). Test IDs are of the form `TC-<AREA>-<nnn>` and are maintained in the test plan.

### 5.1 Authentication and session (FR-AUTH)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-AUTH-001 | Users authenticate with email + password over TLS. Passwords are hashed with Argon2id (fallback BCrypt) and never logged. | P0 | TC-AUTH-001/002 |
| FR-AUTH-002 | Sessions use secure, HttpOnly, SameSite cookies with server-side expiry; logout invalidates the session record. | P0 | TC-AUTH-003 |
| FR-AUTH-003 | Accounts lock after N (configurable, default 5) consecutive failed attempts within a window; lockout is time-based and audited. | P0 | TC-AUTH-004 |
| FR-AUTH-004 | Privileged capabilities (admin, export, SMTP manage, AI provider manage, workflow publish) require MFA enrolment; unenrolled privileged users are forced to enrol. | P0 | TC-AUTH-005 |
| FR-AUTH-005 | MFA uses TOTP (RFC 6238) with ±1 step window, replay protection on the previously used code, and one-time recovery codes. | P0 | TC-AUTH-006 |
| FR-AUTH-006 | CSRF protection applies to all cookie-authenticated state-changing requests; API clients may use a documented token model instead. | P0 | TC-AUTH-007 |
| FR-AUTH-007 | Password reset uses single-use, expiring, hashed tokens; tokens are never returned by API responses. | P1 | TC-AUTH-008 |
| FR-AUTH-008 | Every login, logout, MFA event, lockout and failed attempt produces an audit record with outcome and IP. | P0 | TC-AUD-001 |

### 5.2 Workspace and user management (FR-WS)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-WS-001 | All business data belongs to exactly one workspace; every query is workspace-scoped at the repository/service boundary. | P0 | TC-ISO-001/002 |
| FR-WS-002 | A user's email is unique per workspace; the same person may exist in several workspaces with distinct roles. | P0 | TC-WS-001 |
| FR-WS-003 | Super Admin can create/deactivate users, assign roles and teams; deactivation revokes sessions immediately. | P0 | TC-WS-002 |
| FR-WS-004 | Cross-workspace record access by ID substitution returns 404 (not 403) to avoid resource enumeration. | P0 | TC-ISO-003 |
| FR-WS-005 | Workspace settings include default timezone, sending windows, retention policy and feature toggles. | P1 | TC-WS-003 |

### 5.3 RBAC and record scope (FR-RBAC)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-RBAC-001 | Capability codes are DB records (`leads.create`, `emails.send`, `approvals.decide`, …). Roles map to capabilities; users map to roles. | P0 | TC-RBAC-001 |
| FR-RBAC-002 | Record scope is `OWN`, `TEAM`, `WORKSPACE` or explicit sharing; service methods enforce scope, not only the UI. | P0 | TC-RBAC-002/003 |
| FR-RBAC-003 | Method-level authorization (`@PreAuthorize`) guards every mutating service/controller operation. | P0 | TC-RBAC-004 |
| FR-RBAC-004 | Denying a capability returns 403 with a stable error code; hiding UI is never treated as authorization. | P0 | TC-RBAC-005 |
| FR-RBAC-005 | Permission changes are audited with before/after role mapping. | P0 | TC-AUD-002 |

### 5.4 Executive dashboard (FR-DASH)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-DASH-001 | Dashboard shows KPI cards (open pipeline, weighted pipeline, won value, win rate, new leads, meetings, replies) for a date range and team filter. | P0 | TC-DASH-001 |
| FR-DASH-002 | Dashboard shows pipeline funnel, revenue trend, upcoming meetings, my tasks, AI agent health and failed automations. | P0 | TC-DASH-002 |
| FR-DASH-003 | All dashboard numbers are computed server-side from authorised data and include metric definition + generated-at timestamp. | P0 | TC-DASH-003 |
| FR-DASH-004 | Dashboard never sums mixed currencies without an explicit conversion policy; unconverted amounts are shown per currency. | P0 | TC-DASH-004 |

### 5.5 Leads (FR-LEAD)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-LEAD-001 | Create leads manually, from website webhook, approved CSV import, referral, or parsed email enquiry; source is always recorded with evidence reference. | P0 | TC-LEAD-001/010 |
| FR-LEAD-002 | Lead fields: first/last name, business email, phone, WhatsApp, account/company, domain, industry, location, employee band, service interests, requirement, budget range + currency, timeline, source, campaign, owner, consent reference. | P0 | TC-LEAD-002 |
| FR-LEAD-003 | Views: all, my leads, unassigned, new, researching, qualified, engaged, follow-up due, nurture, disqualified, archived (server-side filters). | P0 | TC-LEAD-003 |
| FR-LEAD-004 | Lead detail exposes overview, intelligence, activities, engagement, opportunities, meetings, documents, audit tabs. | P0 | TC-LEAD-004 |
| FR-LEAD-005 | Bulk operations (assign, tag, enroll, export, archive) apply per-record authorization and return a partial-failure report. | P0 | TC-LEAD-005/006 |
| FR-LEAD-006 | Merge is a privileged, previewed operation preserving activities, messages, meetings and references; result is an immutable merge audit record. | P0 | TC-LEAD-007 |
| FR-LEAD-007 | Lead status follows the allowed-transition graph (§6.1); illegal transitions are rejected with 400 `INVALID_STATE_TRANSITION`. | P0 | TC-LEAD-008 |
| FR-LEAD-008 | Create/update are idempotent on `Idempotency-Key`; duplicate submissions return the original result (409 on conflicting reuse). | P0 | TC-LEAD-009 |
| FR-LEAD-009 | Website intake webhook validates a per-source shared secret, normalises payload, records consent evidence and never creates uncontrolled duplicates. | P0 | TC-INTAKE-001/002 |
| FR-LEAD-010 | CSV import validates headers, size, encoding and per-row data, runs asynchronously and reports per-row errors. | P1 | TC-LEAD-011 |

### 5.6 Accounts and contacts (FR-ACC / FR-CON)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-ACC-001 | Account = company; fields: legal/trading name, website/domain, industry, size band, HQ, locations, description, owner, relationship status, source. | P0 | TC-ACC-001 |
| FR-ACC-002 | An account has many contacts and many opportunities. | P0 | TC-ACC-002 |
| FR-ACC-003 | Account detail shows company profile, domain, contacts, opportunities, activity, services, owner. | P0 | TC-ACC-003 |
| FR-ACC-004 | Contact = individual; fields: name, title, business email, phone, preferred channel, timezone, account, decision role, communication preferences, consent state, suppression state. | P0 | TC-CON-001 |
| FR-CON-002 | A contact is never silently reassigned to another account when an email/domain match is ambiguous; ambiguity raises a review item. | P0 | TC-CON-002 |
| FR-CON-003 | Contact timeline unifies emails, calls, meetings, notes, AI actions and stage events. | P1 | TC-CON-003 |

### 5.7 Lead research and intelligence (FR-RES)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-RES-001 | Research runs as an authorised agent execution producing a structured company profile with evidence URLs and retrieval timestamp. | P1 | TC-AI-001/002 |
| FR-RES-002 | Research output is stored as structured JSON validated against a JSON Schema; unsupported claims are discarded. | P1 | TC-AI-003 |
| FR-RES-003 | Server-side fetches of customer-supplied URLs block private/loopback/link-local addresses and metadata endpoints (SSRF). | P0 | TC-SEC-SSRF-001 |

### 5.8 Qualification and scoring (FR-QUAL)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-QUAL-001 | Score 0–100 from configurable, versioned weights: industry/service fit 15, company size 10, requirement fit 20, budget 15, timeline 10, decision-maker 15, engagement 15. | P0 | TC-QUAL-001/002 |
| FR-QUAL-002 | Bands: 80–100 high priority, 60–79 qualified candidate, 40–59 nurture, 0–39 low priority (configurable). | P0 | TC-QUAL-003 |
| FR-QUAL-003 | Persist total, model version, factor breakdown, evidence/source and calculated-at. | P0 | TC-QUAL-004 |
| FR-QUAL-004 | Human override requires a reason and produces an audit event; AI cannot overwrite verified facts without provenance. | P0 | TC-QUAL-005 |
| FR-QUAL-005 | Score calculation is server-side only; client-supplied scores are rejected. | P0 | TC-QUAL-006 |

### 5.9 Duplicate detection (FR-DUP)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-DUP-001 | Normalise email (lowercase, trim, strip plus-tags per policy) and phone (E.164) before comparison. | P0 | TC-DUP-001 |
| FR-DUP-002 | Exact normalised email is a strong duplicate candidate; exact verified E.164 phone is strong but requires review for shared switchboards. | P0 | TC-DUP-002 |
| FR-DUP-003 | Company domain + normalised name is a candidate, never an automatic merge; fuzzy similarity produces a review warning only. | P0 | TC-DUP-003 |
| FR-DUP-004 | Merge requires an authorised user, preview, conflict resolution and immutable merge audit. | P0 | TC-DUP-004 |
| FR-DUP-005 | Duplicate candidates are returned on create but do not block creation unless policy marks the source as `REJECT_ON_DUPLICATE`. | P0 | TC-DUP-005 |

### 5.10 Pipeline and opportunities (FR-PIPE / FR-OPP)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-PIPE-001 | Stages are configurable per workspace with `order_no` and explicit terminal semantics (`WON`/`LOST`/none); terminal types cannot be removed. | P0 | TC-PIPE-001 |
| FR-PIPE-002 | Pipeline board groups opportunities by stage with per-stage value totals and server-confirmed drag/drop transitions. | P0 | TC-PIPE-002 |
| FR-OPP-001 | Opportunity records account, primary contact, originating lead, service, amount `DECIMAL(19,4)`, currency, probability, expected close date, owner. | P0 | TC-OPP-001 |
| FR-OPP-002 | `weighted_value = amount × probability / 100`; open/weighted pipeline exclude terminal stages; won value uses final amount and close date within period; win rate = won/(won+lost)×100. | P0 | TC-OPP-002/003 |
| FR-OPP-003 | Stage transition is a domain command `POST /opportunities/{id}/transition` validating permission, workspace, row version, allowed transition and destination-stage required fields. | P0 | TC-OPP-004 |
| FR-OPP-004 | `PROPOSAL_SENT` requires an approved proposal version and send evidence, unless a permissioned manual-send exception is recorded. | P0 | TC-OPP-005 |
| FR-OPP-005 | `CLOSED_WON` requires close date, final amount + currency, decision contact and onboarding handoff; `CLOSED_LOST` requires a controlled loss reason. | P0 | TC-OPP-006/007 |
| FR-OPP-006 | Transitions append stage history (from, to, actor, reason, rule version, timestamp) and are rejected when the row version is stale (409). | P0 | TC-OPP-008/009 |
| FR-OPP-007 | A company may hold multiple concurrent opportunities; one-opportunity-per-company is never enforced. | P0 | TC-OPP-010 |

### 5.11 Activities and tasks (FR-ACT)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-ACT-001 | Activity types: email, call, meeting, note, task, proposal, stage change, import, AI action, system event; every significant action appends a timeline event. | P0 | TC-ACT-001 |
| FR-ACT-002 | Tasks have owner, due date, priority, status, related record, reminder policy and completion note. | P0 | TC-ACT-002 |
| FR-ACT-003 | Overdue-task notifications are deduplicated per task/day and respect user notification preferences. | P1 | TC-ACT-003 |
| FR-ACT-004 | Timeline events are distinct from immutable audit records. | P0 | TC-AUD-003 |

### 5.12 SMTP accounts (FR-SMTP)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-SMTP-001 | Administrator configures multiple SMTP profiles: name (unique per workspace, 2–80), host, port 1–65535, TLS mode enum, username, secret, from name/address, reply-to, daily limit, status. | P0 | TC-SMTP-001 |
| FR-SMTP-002 | Secrets are encrypted at rest with AES-256-GCM using a key held outside the database; they are never returned by any API and are masked in UI and logs. | P0 | TC-SMTP-002/003 |
| FR-SMTP-003 | "Test connection" performs a real SMTP handshake/authentication and returns classified outcomes (auth failure, TLS failure, timeout, success); no simulated success. | P0 | TC-SMTP-004 |
| FR-SMTP-004 | `NONE` TLS mode is rejected in production unless an explicitly approved exception flag is recorded and audited. | P0 | TC-SMTP-005 |
| FR-SMTP-005 | Per-profile and per-domain daily caps, rate limits and sending windows are enforced server-side before send. | P0 | TC-SMTP-006 |
| FR-SMTP-006 | Profile status transitions PENDING_TEST → ACTIVE / ERROR / DISABLED; auth failure flags the profile and pauses dependent sequences and alerts admins. | P0 | TC-SMTP-007 |
| FR-SMTP-007 | Sender selection is policy-based by campaign/service with fallback only to explicitly approved profiles; accounts are never rotated to evade limits. | P0 | TC-SMTP-008 |

### 5.13 Templates and composer (FR-TPL / FR-COMP)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-TPL-001 | Templates store name, subject, HTML body, plain-text body, version, status; published templates are immutable versions. | P0 | TC-TPL-001 |
| FR-TPL-002 | Merge fields are allowlisted; unknown variables are rejected at save and at render time. | P0 | TC-TPL-002 |
| FR-TPL-003 | Untrusted text is HTML-escaped on render; approved rich text is sanitised against an allowlist. | P0 | TC-TPL-003 |
| FR-COMP-001 | Composer selects SMTP profile, template, contact/lead, subject/body, attachments; preview uses the real resolved merge values. | P0 | TC-COMP-001 |
| FR-COMP-002 | Test send goes only to an approved internal recipient list. | P0 | TC-COMP-002 |
| FR-COMP-003 | Exact rendered subject, body, sender profile and template version are persisted for audit. | P0 | TC-COMP-003 |

### 5.14 Unified communication history and inbox (FR-INBOX)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-INBOX-001 | Inbox lists inbound messages from the inbound synchroniser, matched to lead/contact/opportunity or routed to an unmatched queue. | P0 | TC-INBOX-001 |
| FR-INBOX-002 | Matching uses provider thread/message IDs, `In-Reply-To`/`References`, sender address and CRM context; display name alone is never sufficient. | P0 | TC-INBOX-002 |
| FR-INBOX-003 | Ambiguous matches are held for human review; humans can link/unlink a message to a record (audited). | P0 | TC-INBOX-003 |
| FR-INBOX-004 | Unified history merges outbound messages, inbound replies, meetings, notes, calls and AI actions in one authorised timeline. | P0 | TC-INBOX-004 |
| FR-INBOX-005 | Inbound polling is idempotent on provider message ID; duplicates never create duplicate history entries. | P0 | TC-INBOX-005 |
| FR-INBOX-006 | Inbound content is treated as untrusted input (no execution, escaped render, prompt-injection aware AI handling). | P0 | TC-SEC-INBOX-001 |

### 5.15 Campaigns and sequences (FR-CAMP / FR-SEQ)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-CAMP-001 | Campaigns define audience rules, templates, steps, sending account, schedule and approval; activation requires validated audience + consent checks. | P1 | TC-CAMP-001 |
| FR-SEQ-001 | Sequences have name, purpose, service, audience criteria, owner, sender profile, timezone, sending window, daily cap, approval status and immutable published version. | P0 | TC-SEQ-001 |
| FR-SEQ-002 | Step types: send email, wait, condition, create task, notify owner, enroll/exit, request approval. | P0 | TC-SEQ-002 |
| FR-SEQ-003 | Exit conditions: reply, unsubscribe, complaint, hard bounce, booked meeting, disqualified lead, manual pause, campaign end. | P0 | TC-SEQ-003 |
| FR-SEQ-004 | Enrollment is unique per (lead, sequence version, active-policy); completed enrollments are never silently restarted; re-enrolment requires policy + cooling period. | P0 | TC-SEQ-004/005 |
| FR-SEQ-005 | Each step run carries a unique idempotency key; retries never duplicate a send. | P0 | TC-SEQ-006 |
| FR-SEQ-006 | Immediately before every send the engine re-checks suppression, consent, reply state, meeting state, sender health and daily limits. | P0 | TC-SEQ-007 |
| FR-SEQ-007 | Baseline cadence day 0/2/5/8/12 is seed configuration, not code; sending occurs only within configured local windows using contact timezone with fallback. | P0 | TC-SEQ-008 |

### 5.16 Reply classification and suppression/consent (FR-REPLY / FR-SUPP)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-REPLY-001 | Reply classification produces intent category, urgency, sentiment indicator and action recommendation with schema validation. | P1 | TC-AI-010 |
| FR-REPLY-002 | Classification never auto-sends; it may recommend and, per policy, pause a sequence. | P1 | TC-AI-011 |
| FR-SUPP-001 | Suppression entries are unique per (workspace, normalised email, purpose scope) and are checked on every send. | P0 | TC-SUPP-001 |
| FR-SUPP-002 | Unsubscribe is functional, immediate, and blocks future marketing sends across all sequences, campaigns and SMTP profiles. | P0 | TC-SUPP-002 |
| FR-SUPP-003 | Hard bounces and complaints create suppression automatically; overriding requires explicit authorised action and never overrides a legal opt-out. | P0 | TC-SUPP-003 |
| FR-SUPP-004 | Consent records store contact, channel, purpose, status, source, captured-at, evidence reference and revocation. | P0 | TC-SUPP-004 |
| FR-SUPP-005 | Transactional messages are separated from marketing purposes and evaluated against purpose-specific permission. | P0 | TC-SUPP-005 |

### 5.17 Calendly integration and meetings (FR-CAL / FR-MEET)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-CAL-001 | Admin connects Calendly with credentials stored encrypted, can list organisation event types and select permitted ones, and can test the connection. | P0 | TC-CAL-001 |
| FR-CAL-002 | Booking links are associated with meeting purposes and are available as merge fields in templates/sequences. | P0 | TC-CAL-002 |
| FR-CAL-003 | `POST /api/v1/webhooks/calendly` verifies `Calendly-Webhook-Signature` (HMAC-SHA256 over `{t}.{rawBody}`, timing-safe, ±180 s replay window), persists the raw event before processing, dedupes on provider event ID and acknowledges 2xx quickly. | P0 | TC-CAL-003/004 |
| FR-CAL-004 | Invitee created/canceled/rescheduled events create or update exactly one CRM meeting, retain history and stop applicable sequence enrollments. | P0 | TC-CAL-005/006 |
| FR-CAL-005 | Meetings store external event URI, invitee URI, event type, start/end UTC, IANA timezone, status, organiser, payload hash. | P0 | TC-CAL-007 |
| FR-CAL-006 | Status mapping: SCHEDULED, RESCHEDULED (prior slot retained), CANCELLED, COMPLETED, NO_SHOW (never inferred from elapsed time). | P0 | TC-CAL-008 |
| FR-CAL-007 | Contact matching from invitee email is a candidate requiring resolvable match; ambiguous invitees are queued for review. | P0 | TC-CAL-009 |
| FR-CAL-008 | Calendly API outage persists a pending sync job, retries with backoff and surfaces a stale-sync state; no invented availability or confirmation is ever returned. | P0 | TC-CAL-010 |

### 5.18 AI providers, agents and approvals (FR-AIP / FR-AGT / FR-APPR)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-AIP-001 | Business modules call an internal AI gateway; they never call provider URLs directly. | P0 | TC-AI-020 |
| FR-AIP-002 | Providers are configured per workspace: provider code, model allowlist, endpoint config, encrypted secret, status; `validateConfiguration` and `healthCheck` are real provider calls. | P0 | TC-AI-021 |
| FR-AIP-003 | Gateway enforces budgets, rate limits, timeouts, bounded retries and schema validation, and persists usage (tokens, latency, cost estimate, correlation ID). | P0 | TC-AI-022/023 |
| FR-AIP-004 | Copilot integration occurs only via an officially documented, licensed API with supported auth; otherwise Copilot is excluded as a runtime provider. | P1 | Documented decision ADR-006 |
| FR-AGT-001 | Every agent defines versioned instructions, provider/model, allowed tools, data scope, max tokens, timeout, rate limit, cost budget, approval policy. | P0 | TC-AI-030 |
| FR-AGT-002 | Agents declared: Lead Research, Lead Qualification, Sales Strategy, Email Writing, Engagement Monitoring, Follow-up, Meeting Assistant, Proposal Assistant, Pipeline Intelligence, Revenue Analytics. | P1 | TC-AI-031 |
| FR-AGT-003 | Agents have no direct database access; tool calls run through backend services that independently enforce RBAC/workspace/business rules. | P0 | TC-AI-032 |
| FR-AGT-004 | Execution states: QUEUED → RUNNING → SUCCEEDED / FAILED / WAITING_FOR_APPROVAL → APPROVED → EXECUTING → SUCCEEDED / REJECTED / CANCELLED, persisted with history. | P0 | TC-AI-033 |
| FR-AGT-005 | AI output is untrusted: sanitised, never concatenated into SQL/shell, validated against JSON Schema before use. | P0 | TC-AI-034 |
| FR-APPR-001 | Approvals bind action payload hash + object version + policy version, expire, cannot be reused for another record, and are invalidated when content changes. | P0 | TC-APPR-001/002 |
| FR-APPR-002 | AI cannot approve discounts, contract terms or final proposal issue; only authorised humans can. | P0 | TC-APPR-003 |
| FR-APPR-003 | Approval records capture approver, timestamp, decision, comments, object/version and resulting action. | P0 | TC-APPR-004 |

### 5.19 Workflow automation (FR-WF)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-WF-001 | Workflows are versioned graphs: trigger, conditions, actions, branches, wait, retry, validation, test run, publish, version history. | P1 | TC-WF-001 |
| FR-WF-002 | Triggers: lead created, lead qualified, email received, email bounced, meeting booked, meeting cancelled, opportunity stage changed, proposal sent, deal won, follow-up overdue. | P1 | TC-WF-002 |
| FR-WF-003 | Actions: create task, assign lead, send approved email, start/stop sequence, execute AI agent, notify rep, create opportunity, update CRM record. | P1 | TC-WF-003 |
| FR-WF-004 | Loop protection: max steps per run, max depth of triggered runs, per-record concurrency guard, cycle detection at publish. | P0 | TC-WF-004 |
| FR-WF-005 | Workflow runs record status, step trace, errors and are replayable only after review. | P1 | TC-WF-005 |

### 5.20 Proposals, onboarding, renewals, analytics, reports (FR-PROP / FR-ONB / FR-REN / FR-REV / FR-REP)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-PROP-001 | Proposal drafts derive from an opportunity + service template; versions are immutable; rendered PDF artifact reference is stored. | P1 | TC-PROP-001 |
| FR-PROP-002 | Commercial approval is required for discount, non-standard terms and final issue. | P1 | TC-PROP-002 |
| FR-PROP-003 | Sending a proposal records recipient, sent-at, SMTP message reference and proposal version; acceptance is never inferred from opens. | P1 | TC-PROP-003 |
| FR-ONB-001 | Closed-won creates an onboarding checklist and delivery handoff. | P1 | TC-ONB-001 |
| FR-ONB-002 | Contract start/end, renewal date, service scope and account owner are captured. | P1 | TC-ONB-002 |
| FR-REN-001 | Renewal reminders and customer review tasks are created from configured policy. | P1 | TC-REN-001 |
| FR-REN-002 | Upsell recommendations are suggestions only; commercial proposals require human approval. | P1 | TC-REN-002 |
| FR-REV-001 | Revenue analytics compute pipeline, conversion, velocity and forecast on authorised aggregates with metric definitions. | P1 | TC-REV-001 |
| FR-REP-001 | Reports support filters, metric definitions, drill-down, export permission and generated-at timestamp. | P1 | TC-REP-001 |

### 5.21 Notifications, audit, administration (FR-NOTIF / FR-AUD / FR-ADMIN)

| ID | Requirement | Pri | Tests |
|---|---|---|---|
| FR-NOTIF-001 | Notification centre shows unread count, type, payload summary and deep link; read state per user. | P0 | TC-NOTIF-001 |
| FR-NOTIF-002 | Notification creation respects per-user channel preferences and is deduplicated. | P1 | TC-NOTIF-002 |
| FR-AUD-001 | Audit log is append-only, immutable through application APIs, and records actor, action, object, before/after summary, timestamp, correlation ID. | P0 | TC-AUD-004 |
| FR-AUD-002 | Audit coverage includes login/security, lead changes, assignment/merge, email send, suppression/consent, meeting sync, AI execution, commercial approval, admin changes. | P0 | TC-AUD-005 |
| FR-ADMIN-001 | Service catalogue, lead sources, pipeline stages, roles/capabilities, integrations and system settings are administrable records — never hard-coded strings. | P0 | TC-ADMIN-001 |
| FR-ADMIN-002 | Administration shows integration health: last successful test, last event, error summary, reconnect action. | P1 | TC-ADMIN-002 |
| FR-ADMIN-003 | Failed jobs are listed with error, attempts and replay action (authorised, audited). | P0 | TC-JOB-010 |

## 6. Business rules

Business rules are catalogued in `docs/requirements/business-rules.md` with IDs `BR-<AREA>-<nnn>`.
Summary of the rule families: lead lifecycle, opportunity stage gates and forecast maths, scoring,
duplicate handling, send eligibility, sequence stop conditions, approval binding, AI guardrails,
workspace isolation and audit immutability.

## 7. API requirements

`docs/api/API-SPECIFICATION.md` and the generated OpenAPI document
(`/v3/api-docs`, swagger UI `/swagger-ui.html`) are normative. Cross-cutting requirements:
base path `/api/v1`; UUID ids; ISO-8601 UTC timestamps; `page/size/sort` with allowlisted sort
fields and `size ≤ 100`; `Idempotency-Key` for send/booking/import/commands; `If-Match`/`rowVersion`
for concurrent edits; consistent `{success, data|error, meta.requestId}` envelope; documented error
codes; UTC storage with IANA timezone fields.

## 8. Ambiguities and open decisions (material)

Recorded as `AMB-xx`. Material items require product-owner clarification before the affected release
exits; until then the documented interim behaviour applies and is flagged in `DEVELOPMENT_STATUS.md`.

| ID | Ambiguity | Interim behaviour (documented, reversible) | Affects |
|---|---|---|---|
| AMB-01 | Frontend hosting: bundled in WAR vs separately hosted. | **Approved interim:** Next.js static export bundled into the WAR under `/ui`, served same-origin (no CORS, no second deployment). Revisit if SSR is required. | Architecture, deployment |
| AMB-02 | Spring Boot line for Java 17 + Tomcat 11. | Spring Boot 4.0.x (manages Tomcat 11.0.x, Servlet 6.1, Java 17 baseline). PoC WAR gate before Release 2 exit. | Build, deployment |
| AMB-03 | Inbound email method (IMAP vs mailbox API vs webhook). | IMAP polling adapter implemented as default (widely supported, on-premises friendly); mailbox-API and webhook adapters share the same `InboundMailAdapter` port. | Release 3 |
| AMB-04 | Currency conversion policy for multi-currency pipelines. | No implicit conversion: aggregates are grouped per currency; a configured FX source/rate date is required to consolidate. | Dashboard, reports |
| AMB-05 | Exact service catalogue, price book, approved claims and case studies. | Seeded with a documented, clearly-marked placeholder catalogue that admins must edit; AI fact-checking is disabled until approved facts are loaded. | Release 5 |
| AMB-06 | Retention periods and lawful-basis wording. | Configurable per workspace; defaults documented in SECURITY-PLAN §Data protection; legal review required. | Release 3/8 |
| AMB-07 | Copilot as runtime provider. | Not enabled: no officially supported general-purpose production inference API is assumed. Adapter interface reserved. | Release 5 |
| AMB-08 | MFA enforcement scope when an enterprise IdP is unavailable. | Local TOTP mandatory for privileged capabilities; optional otherwise. | Release 1 |
| AMB-09 | MariaDB exact version / `SKIP LOCKED` availability. | Target MariaDB 10.11 LTS (supports `FOR UPDATE SKIP LOCKED`); job claim implemented defensively with an atomic conditional UPDATE so it also works on 10.6+. | Release 6 |
| AMB-10 | RPO/RTO and backup location. | Documented template: RPO 15 min (binlog), RTO 4 h; requires business sign-off. | Release 8 |

## 9. Constraints and assumptions

Constraints: no Redis; no MongoDB/Firebase as primary store; no replacement of SMTP or Calendly
without approval; no hardcoded credentials; no bypass of backend validation; no simulated provider
success. Assumptions: Infeara is the initial workspace; customer supplies SMTP and Calendly
credentials; AI providers are accessed through adapters; Calendly is the scheduling source of truth.

## 10. Acceptance

Release exit criteria and mandatory end-to-end scenarios are in `docs/testing/TEST-PLAN.md`
(TC-E2E-001…020 mirror the specification's 20 mandatory scenarios). A requirement is complete only
when its linked automated tests pass and evidence is retained in `docs/releases/`.
