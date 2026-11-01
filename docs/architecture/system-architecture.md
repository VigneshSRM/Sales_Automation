# System Architecture — Infeara Sales OS

| Field | Value |
|---|---|
| Document ID | INFEARA-ARC-001 |
| Version | 1.0 |
| Status | Baseline for Release 1+ |
| Related | `architecture-decisions.md` (ADRs), `../database/ERD.md`, `../api/API-SPECIFICATION.md` |

## 1. Architectural style

A **modular monolith** deployed as a single WAR on Apache Tomcat 11, with clear domain modules and
compile-time-enforced boundaries, plus **database-backed asynchronous workers** (transactional
outbox + job tables) running inside the same JVM. No Redis, no external broker, no microservices.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Browser (desktop / tablet / mobile)                                          │
│  Next.js 16 + React 19 + TypeScript, same-origin static UI (/ui)             │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                │ HTTPS (TLS terminated at Tomcat or reverse proxy)
┌───────────────────────────────▼──────────────────────────────────────────────┐
│ Apache Tomcat 11 (Servlet 6.1) — infeara-sales-os.war                        │
│                                                                              │
│  Filter chain: CorrelationId → Security (session/CSRF) → RateLimit           │
│               → RequestSizeLimit → WorkspaceContext                          │
│                                                                              │
│  ┌──────────────────────────── API layer (/api/v1) ───────────────────────┐  │
│  │ thin @RestControllers · DTO records · Bean Validation · OpenAPI         │  │
│  └───────────────────────────────┬─────────────────────────────────────────┘  │
│  ┌───────────────────────────────▼─────────────────────────────────────────┐  │
│  │ Application services (use cases, @Transactional boundaries, audit,      │  │
│  │ outbox publishing)                                                     │  │
│  └───────┬─────────────────┬──────────────────┬──────────────────┬─────────┘  │
│  ┌───────▼──────┐ ┌────────▼───────┐ ┌────────▼───────┐ ┌────────▼────────┐  │
│  │ Domain       │ │ Repositories   │ │ Provider       │ │ Job / Workflow  │  │
│  │ services &   │ │ Spring Data    │ │ adapters:      │ │ engine (workers)│  │
│  │ policies     │ │ JPA            │ │ SMTP, IMAP, AI,│ │ claim·lease·retry│ │
│  │ (invariants) │ │                │ │ Calendly, PDF  │ │ ·dead-letter    │  │
│  └───────┬──────┘ └────────┬───────┘ └────────┬───────┘ └────────┬────────┘  │
└──────────┼─────────────────┼──────────────────┼──────────────────┼───────────┘
           │                 │                  │                  │
           │        ┌────────▼────────┐  ┌──────▼───────┐  ┌───────▼─────────┐
           │        │ MariaDB 10.11   │  │ SMTP relays  │  │ Outbox/job      │
           │        │ InnoDB/utf8mb4  │  │ IMAP mailbox │  │ tables (MariaDB)│
           │        │ (system of      │  │ OpenRouter   │  │ same schema     │
           │        │  record)        │  │ OpenAI       │  │                 │
           │        └─────────────────┘  │ Calendly API │  └─────────────────┘
           └──────────────────────────────┴──────────────┘
```

## 2. Module map (Java packages)

`com.infeara.sales` — each package below is a module with `api` (public ports) and internal service
packages; cross-module calls go through public service interfaces only (enforced by ArchUnit tests).

| Module | Responsibility | Key public ports |
|---|---|---|
| `common` | Response envelope, errors, pagination, correlation ids, `Clock`, JSON codec, `Money`, `EmailAddress` value types | `ApiResponse`, `ApiException`, `PageQuery` |
| `config` | Spring/Security/JPA/Jackson/OpenAPI/Scheduler configuration, properties binding | — |
| `security` | Spring Security wiring, session store, MFA, rate limiting, workspace context, capability evaluator, encryption service | `CurrentPrincipal`, `CapabilityEvaluator`, `SecretCipher` |
| `workspace` | Workspace boundary, settings, membership | `WorkspaceService` |
| `identity` | Users, roles, capabilities, teams, sessions, login/lockout, password reset | `AuthService`, `UserDirectory` |
| `audit` | Append-only audit log, hash-chained records | `AuditRecorder` |
| `notification` | Notification creation, preferences, delivery fan-out | `Notifier` |
| `crm.lead` | Lead aggregate, lifecycle policy, intake, import, bulk ops, merge | `LeadService`, `LeadIntakePort` |
| `crm.account` / `crm.contact` | Accounts, contacts, relationship mapping | `AccountService`, `ContactService` |
| `crm.scoring` | Scoring models, calculation, history | `ScoringService` |
| `crm.dedupe` | Normalisation, candidate search, merge orchestration | `DuplicateService` |
| `crm.activity` / `crm.task` | Timeline events, tasks, reminders | `ActivityRecorder`, `TaskService` |
| `sales.pipeline` / `sales.opportunity` | Stages, opportunities, transitions, forecast | `OpportunityService`, `PipelineQuery` |
| `engagement.smtp` | SMTP profiles, credential encryption, transport health | `SmtpProfileService`, `MailSenderPort` |
| `engagement.template` | Templates, allowlisted merge fields, renderer | `TemplateService`, `TemplateRenderer` |
| `engagement.message` | Outbound messages, events, reconciliation, composer | `EmailSendService` |
| `engagement.inbound` | Inbound mailbox sync (IMAP adapter), reply matching, unmatched queue | `InboundSyncService` |
| `engagement.campaign` | Campaigns (P1) | `CampaignService` |
| `engagement.sequence` | Sequence definitions/versions, enrollments, step runs, stop conditions | `SequenceService`, `EnrollmentService` |
| `engagement.consent` | Consent records, suppression list, unsubscribe handling | `SuppressionService` |
| `meeting.calendly` | Connection, event types, booking links, webhook verification, sync | `CalendlyPort`, `CalendlyWebhookService` |
| `meeting.core` | Meetings, history, timeline, reminders | `MeetingService` |
| `ai.gateway` | Provider-neutral gateway: routing, budget, rate limit, schema validation, usage persistence | `AiGateway`, `AiProvider` |
| `ai.agent` | Agent registry, execution engine, tool registry, scoped tools | `AgentExecutionService`, `AgentTool` |
| `ai.approval` | Approval requests, binding/hash validation, decisions | `ApprovalService` |
| `automation.workflow` | Workflow definitions/versions, graph validation, run orchestration | `WorkflowService`, `WorkflowRunner` |
| `automation.job` | Outbox relay, job claiming/leases, retry/backoff, dead letter, replay | `JobEngine`, `OutboxPublisher` |
| `commercial.proposal` | Proposal drafts/versions, commercial approvals, send evidence | `ProposalService` |
| `customer.lifecycle` | Onboarding checklists, contracts, renewals, expansion suggestions | `OnboardingService`, `RenewalService` |
| `analytics` / `reporting` | Metric definitions, dashboard queries, exports | `DashboardQuery`, `ReportService` |
| `admin` | Service catalogue, sources, stages, integrations health, failed jobs, system settings | `AdminService` |
| `web` | Static UI serving, SPA fallback, health endpoints | — |

Dependency direction: `web → {module application services} → {domain, repositories, provider ports}`;
`automation`, `ai`, `engagement` may depend on `crm`/`sales` public ports but never on another
module's repositories. `common` and `security` are depended on by everyone and depend on nothing
domain-specific.

## 3. Layering rules (enforced)

1. Controller: HTTP mapping, DTO validation, no business logic, never touches entities.
2. Application service: transaction boundary, capability + scope checks, orchestration, audit +
   outbox emission, returns DTO/domain result.
3. Domain service/policy: pure invariants (transition graphs, scoring, send eligibility, forecast),
   unit-testable without Spring.
4. Repository: Spring Data JPA; all finders workspace-scoped; no business decisions.
5. Provider adapter: external I/O only, timeout/retry/secret handling, maps provider errors to
   `ProviderException` taxonomy. No adapter is called inside a DB transaction.

## 4. Request lifecycle (example: create lead)

```
POST /api/v1/leads  (Idempotency-Key, session cookie, CSRF token)
 → CorrelationIdFilter            (requestId MDC)
 → SecurityFilterChain            (authN, CSRF)
 → RateLimitFilter                (per-user buckets)
 → LeadController.create          (@Valid CreateLeadRequest)
 → LeadApplicationService.create  @Transactional:
      1. capability leads.create + workspace from principal
      2. validate references (service/source/owner in workspace, active)
      3. idempotency lookup (unique key) → replay existing result
      4. normalise email/phone/domain; duplicate candidates (BR-DUP)
      5. persist lead (+source evidence, consent ref) + timeline activity
      6. audit row + outbox event LEAD_CREATED (same transaction)
 → 201 {success:true, data:{id,status,duplicates,rowVersion}, meta.requestId}
Outbox relay (worker) → LEAD_CREATED → workflow triggers (async, bounded)
```

## 5. Asynchronous processing (no Redis)

**Transactional outbox** — `outbox_events` rows are written in the same transaction as the business
change. A relay worker claims rows atomically:

```sql
UPDATE outbox_events
   SET status='PROCESSING', locked_by=:worker, locked_until=:now+lease, attempt_count=attempt_count+1
 WHERE id = (SELECT id FROM (
        SELECT id FROM outbox_events
         WHERE status IN ('PENDING','RETRY') AND available_at <= :now
           AND (locked_until IS NULL OR locked_until < :now)
         ORDER BY available_at, id LIMIT 1) t)
   AND status IN ('PENDING','RETRY');
```

MariaDB 10.11 supports `SELECT … FOR UPDATE SKIP LOCKED`; the conditional-UPDATE claim above is used
because it is portable and still atomic (single-statement compare-and-set).

**Jobs** — `jobs` (typed async tasks: send email, sync calendly, run agent, run workflow step, render
proposal) with `attempt_count`, `max_attempts`, `next_attempt_at`, `locked_until`, `locked_by`,
`idempotency_key` (unique), `dead` status and `job_executions` history. Backoff:
`min(base×2^(attempt-1), cap)` with jitter; exhausted ⇒ `DEAD` + notification + admin replay.

**Leases** — a worker that dies leaves an expired lease; the row is reclaimed after `locked_until`.
Idempotency keys and unique provider-id constraints make reclamation side-effect safe
(spec §14 "worker crash ⇒ no duplicate email or booking").

**Scheduling** — Spring `@Scheduled` polling is bounded (batch size, per-tick budget, single-flight
per job type) and disabled on instances configured as `app.worker.enabled=false`.

## 6. Integration boundaries

| Integration | Protocol | Adapter | Failure semantics |
|---|---|---|---|
| SMTP outbound | SMTP (STARTTLS / implicit TLS) via Jakarta Mail, one `JavaMailSender` per profile | `SmtpMailAdapter` | timeout after handoff ⇒ `UNCERTAIN` + reconciliation; auth failure ⇒ profile `ERROR` + pause sequences + alert |
| Inbound email | IMAP (default), mailbox API or webhook behind `InboundMailAdapter` | `ImapInboundAdapter` | bounded fetch window, UIDVALIDITY/UID watermark, dedupe on Message-ID |
| AI providers | HTTPS JSON (OpenRouter `/api/v1/chat/completions`-style, OpenAI `/v1/chat/completions`) | `OpenRouterProvider`, `OpenAiProvider` | respect `Retry-After`, bounded retry, schema-fail ⇒ human review |
| Calendly | HTTPS REST + signed webhooks | `CalendlyApiClient`, `CalendlyWebhookVerifier` | webhook persisted first; API outage ⇒ pending sync job + stale indicator |
| Browser UI | Same-origin static assets | Tomcat default servlet + `SpaForwardController` | — |

## 7. Data architecture

MariaDB 10.11 InnoDB, `utf8mb4_general_ci` (or `utf8mb4_0900_ai_ci` where supported) at DB level,
`CHAR(36)` UUIDv4 identifiers, `DATETIME(6)` UTC timestamps, `DECIMAL(19,4)` money,
`row_version BIGINT` optimistic locks, soft delete via `deleted_at`, append-only tables for audit /
stage history / outbox / inbox webhook events. Full entity catalogue: `docs/database/ERD.md`.

Schema ownership: Flyway (`backend/src/main/resources/db/migration/V*__*.sql`). Hibernate
`ddl-auto=validate` in tests and `none` in production.

## 8. Frontend architecture

Next.js App Router (TypeScript) compiled to a **static export** and copied into the WAR at
`/ui`; SPA fallback forwards unknown non-API paths to `index.html` so deep links work.

- Route groups: `(auth)` login/MFA, `(app)` shell with persistent sidebar, header, notification
  centre, quick-create, global search.
- Server state via TanStack Query with typed API client; mutations invalidate precisely.
- Design tokens in CSS variables matching the specification palette; component library
  (`Button`, `Input`, `Select`, `Table`, `Dialog`, `Badge`, `Tabs`, `Toast`, `EmptyState`,
  `ErrorState`, `Skeleton`) built once and reused.
- All data comes from `/api/v1`; the UI never invents values, never applies a business rule, and
  always surfaces server validation errors inline + in a summary.
- Accessibility: semantic landmarks, labelled controls, focus-visible rings, keyboard-operable
  menus/dialogs/drag-and-drop alternative (move-to-stage menu), `aria-live` for async feedback.

## 9. Security architecture (summary)

Spring Security session cookies (HttpOnly, Secure, SameSite=Lax) + CSRF token for mutations; TOTP
MFA for privileged capabilities; Argon2id password hashing; capability + scope authorization at the
service layer; AES-256-GCM credential encryption with key id rotation; rate limiting filter;
correlation-id logging with secret redaction; append-only audit; strict CSP/security headers; SSRF
guards on server-side fetches. Details: `docs/security/SECURITY-PLAN.md`.

## 10. Observability and operations

Actuator `health` (liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness` with
DB + Flyway checks), Micrometer metrics (HTTP, pool, job backlog, oldest pending job, retries, dead
jobs, SMTP outcomes, AI latency/cost, webhook lag), structured JSON logs with `requestId`,
`workspaceId`, `userId`; admin integration-health screen. Runbooks in
`docs/deployment/DEPLOYMENT-GUIDE.md` and `docs/security/SECURITY-PLAN.md`.

## 11. Deployment topology

Single Tomcat 11 instance (horizontal scaling supported because all state is in MariaDB):
`/opt/tomcat/webapps/infeara-sales-os.war` + `setenv.sh` for JVM/secret injection, MariaDB on the
same or a dedicated host, reverse proxy (nginx/Apache HTTPD) terminating TLS, off-host encrypted
backups. Dev environment: `docker compose` (MariaDB + MailHog-style SMTP capture) — see
`deploy/docker-compose.yml`; production remains WAR-on-Tomcat (explicitly not container-only).

## 12. Extension points

New AI provider ⇒ implement `AiProvider` + register in `application.yml` allowlist.
New inbound mail source ⇒ implement `InboundMailAdapter`.
New workflow action ⇒ implement `WorkflowAction` bean and reference by code in the graph.
New booking platform ⇒ **requires an approved change request** (Calendly is mandated).
