# Requirement traceability matrix

Every functional requirement from `SRS.md` mapped to the code that implements it and the test that
proves it. **Test status legend.** A ✅ prefix marks a suite that exists in this repository *and* names a
command that runs it. Everything else is *planned*: the implementation exists, the test does not yet.
Current reality (see `DEVELOPMENT_STATUS.md` §1):

* Frontend — 22 Vitest tests in `frontend/lib/__tests__` and `frontend/components/__tests__`
  (`npm test`) — **executed, passing**.
* Backend — 6 JUnit classes + 1 Testcontainers IT exist (`PasswordPolicyTest`, `PageQueryTest`,
  `SecretCipherTest`, `TotpServiceTest`, `LeadStatusTest`, `ArchitectureTest`, `SchemaMigrationIT`).
  They are **authored but never executed**: the authoring environment has no JDK, Maven or Docker.

| ID | Requirement | Implementation | Test | State |
|---|---|---|---|---|
| FR-AUTH-001 | Password login with lockout after repeated failures | `identity/AuthService`, `identity/PasswordPolicy`, `security/SessionAuthenticationFilter` | `AuthServiceTest`| Implemented |
| FR-AUTH-002 | TOTP MFA for privileged roles, replay-safe | `security/TotpService`, `identity/AuthService` | ✅ `TotpServiceTest`| Implemented |
| FR-AUTH-003 | Server-side session revocation | `identity/SessionService`, `identity/UserSession` | `SessionServiceTest`| Implemented |
| FR-WS-001 | Workspace provisioning and isolation | `workspace/WorkspaceBootstrapService`, all repositories scoped by `workspaceId` | `WorkspaceBootstrapServiceTest`| Implemented |
| FR-RBAC-001 | Capability-based authorisation | `security/CapabilityEvaluator`, `V6__seed_rbac.sql` | `CapabilityEvaluatorTest`| Implemented |
| FR-RBAC-002 | Record-level scope (own/team/workspace) | `security/ScopeGuard` | `ScopeGuardTest`| Implemented |
| FR-LEAD-001 | Capture a lead with duplicate detection | `crm/lead/LeadService`, `crm/dedupe/DuplicateService` | `LeadServiceTest`, `DuplicateServiceTest`| Implemented |
| FR-LEAD-002 | Lifecycle state machine | `crm/lead/LeadStatus`, `LeadService.transition` | `LeadLifecycleTest`| Implemented |
| FR-LEAD-003 | Manual merge with audit trail | `LeadService.merge`, `crm/lead/MergeAudit` | `LeadMergeTest`| Implemented |
| FR-LEAD-004 | Deterministic 7-factor scoring, versioned weights | `crm/scoring/ScoringService`, `scoring_models` | `ScoringServiceTest`| Implemented |
| FR-LEAD-005 | Score override requires reason + audit | `LeadService.overrideScore` | `LeadOverrideTest`| Implemented |
| FR-ACC-001 | Account create/list/detail/update | `crm/account/*` | `AccountServiceTest`| Implemented |
| ADD-MBX-001 | Real two-way mailbox integration (inbound reading, not SMTP-only) | `engagement/mailbox/ImapInboundConnector`, `MailboxSyncService` | — (needs a live IMAP server) | Code complete, unverified |
| ADD-MBX-002 | Inbound mail is never simulated in production | no ingest API exists; only the connector and the `MAILBOX_SYNC` job write inbound messages | — | By construction |
| ADD-MBX-003 | Mailbox cannot be enabled before a successful connection test | `MailboxService.setEnabled`, `email_accounts.last_health_at/last_error` | — | Code complete, unverified |
| ADD-MBX-004 | Incremental UID sync with UIDVALIDITY reset handling | `email_sync_state`, `MailboxService.markSyncSuccess` | — | Code complete, unverified |
| ADD-MBX-005 | No duplicate message/activity/job on re-sync | `uk_email_inbound_key`, `InboundEmailService.inboundKey` | — | Code complete, unverified |
| ADD-MBX-006 | Conversation threading by RFC headers with subject fallback | `ConversationMatcher.threadKey` | — | Code complete, unverified |
| ADD-MBX-007 | CRM matching never guesses; ambiguity is surfaced | `ConversationMatcher.match` → AMBIGUOUS_CONTACT | — | Code complete, unverified |
| ADD-AI-001 | Schema-constrained classification; invalid output discarded | `ai/AiGateway`, `ai/JsonSchemaValidator`, `EmailIntelligenceService` | — (no provider called) | Code complete, unverified |
| ADD-AI-002 | Prompt injection cannot trigger a send | fenced untrusted data + 12k cap + no tool access | — | By construction |
| ADD-AI-003 | AI may not send unless a response policy allows it | `EmailPolicyService.decide`, `NEVER_AUTO` set, business-hours gate | — | Code complete, unverified |
| ADD-APR-001 | Four-eyes approval bound to a payload hash, with expiry | `EmailApproval.payloadHash`, `ConversationService.approve/reject` | — | Code complete, unverified |
| ADD-UI-001 | Unified inbox: queues, timeline, AI panel, draft/approved/sent state | `/engagement/mailbox*` pages, `/api/v1/email/*` | `lib/__tests__/mailbox-api.test.ts` ✅ (API contract) | UI built; API unverified |
| ADD-OPS-001 | Frontend + backend in one WAR hosted on Tomcat 11 | `scripts/build-war.sh`, `scripts/host-app.sh`, `deploy/tomcat/*` | `scripts/verify-war-selftest.py` ✅ (packaging contract) | Deployment scripted, not executed |
| FR-CON-001 | Contact create/detail with consent state | `crm/contact/*` | `ContactServiceTest`| Implemented |
| FR-OPP-001 | Opportunity CRUD with optimistic locking | `sales/pipeline/OpportunityService` | `OpportunityServiceTest`| Implemented |
| FR-OPP-002 | Stage gates (WON, LOST, DISCOVERY, PROPOSAL_SENT) | `OpportunityService.transition`, `OpportunityGateQueries` | `OpportunityGateTest`| Implemented |
| FR-OPP-003 | Immutable stage history with policy version | `sales/pipeline/StageHistory` | `OpportunityGateTest`| Implemented |
| FR-OPP-004 | Pipeline board with per-currency totals | `OpportunityService.board` | `OpportunityBoardTest`| Implemented |
| FR-OPP-005 | Forecast & win rate with metric definitions | `OpportunityService.forecast` | `ForecastTest`| Implemented |
| FR-ACT-001 | Activity timeline per record | `crm/activity/*` | `ActivityServiceTest`| Implemented |
| FR-TASK-001 | Tasks with overdue detection | `crm/task/TaskService` | `TaskServiceTest`| Implemented |
| FR-EML-001 | Multiple SMTP accounts, encrypted secrets, live test before activation | `engagement/smtp/*`, `security/SecretCipher` | `SmtpProfileServiceTest`| Implemented |
| FR-EML-002 | Templates with allow-listed merge fields + approval | `engagement/template/*` | `TemplateServiceTest`| Implemented |
| FR-EML-003 | Compose, four-eyes approval, send, cancel, schedule | `engagement/email/*` | `EmailServiceTest`| Implemented |
| FR-EML-004 | Eligibility re-checked at send time (suppression, consent, disqualification) | `engagement/consent/EligibilityService`, `EmailService` | `EligibilityServiceTest`| Implemented |
| FR-EML-005 | Daily/hourly sending caps | `SmtpProfileService`, `EmailService` | `SendingLimitTest`| Implemented |
| FR-SEQ-001 | Versioned multi-step cadence, one active enrolment per lead | `engagement/sequence/*` | `SequenceServiceTest`| Implemented |
| FR-SEQ-002 | Stop on reply/unsubscribe/bounce/booking | `SequenceService`, `EmailService.recordInbound` | `SequenceStopTest`| Implemented |
| FR-SEQ-003 | Durable scheduled engine with lease-based claim | `SequenceEngine`, `automation/job/*` | `SequenceEngineTest`| Implemented |
| FR-CMP-001 | Campaigns | — | ✅ —| **Not implemented** (schema only) |
| FR-REP-001 | Reply classification | — | ✅ —| **Not implemented** |
| FR-INB-001 | IMAP/mailbox inbound sync | — | ✅ —| **Not implemented** |
| FR-CAL-001 | Calendly connect, event types, booking links | — | ✅ —| **Not implemented** (schema only) |
| FR-CAL-002 | Webhook signature verification, persist-before-process, dedupe | — | ✅ —| **Not implemented** (schema only) |
| FR-MTG-001 | Meeting timeline, cancel/reschedule, opportunity association | — | ✅ —| **Not implemented** (schema only) |
| FR-AI-001 | `AiProvider` adapters (OpenRouter, OpenAI) | — | ✅ —| **Not implemented** (schema only) |
| FR-AI-002 | `AiGateway`: budget, timeout, schema validation, usage persistence | — | ✅ —| **Not implemented** |
| FR-AI-003 | Ten agent definitions with tools/data scope/limits | — | ✅ —| **Not implemented** (schema only) |
| FR-AI-004 | Approval queue binding payload hash + object version | — | ✅ —| **Not implemented** (schema only) |
| FR-WF-001 | Durable outbox + jobs + retries + dead letter + replay | `automation/outbox/*`, `automation/job/*`, `web/AdminController` | `OutboxRelayTest`, `JobEngineTest`| Implemented |
| FR-WF-002 | User-configurable workflow builder & interpreter | — | ✅ —| **Not implemented** (schema only) |
| FR-PRP-001 | Proposal versions, approval, send evidence | — | ✅ —| **Not implemented** (schema only) |
| FR-ONB-001 | Customer onboarding checklists | — | ✅ —| **Not implemented** (schema only) |
| FR-RNW-001 | Renewals and expansion suggestions | — | ✅ —| **Not implemented** (schema only) |
| FR-DASH-001 | Executive dashboard with definitions and limitations | `analytics/DashboardService`, `analytics/DashboardController` | `DashboardServiceTest`| Implemented |
| FR-DASH-002 | Global search (capability-filtered) | `analytics/GlobalSearchService`, `web/SearchController` | `GlobalSearchTest`| Implemented |
| FR-NOT-001 | Deduped in-app notifications with preferences | `notification/*` | `NotificationServiceTest`| Implemented |
| FR-AUD-001 | Tamper-evident audit chain | `audit/AuditRecorder` (`SHA-256` chain) | `AuditChainTest`| Implemented |
| FR-AUD-002 | Filterable audit API | `web/AuditController` | `AuditControllerTest`| Implemented |
| NFR-PERF-001 | Server-side pagination, capped page size | `common/paging/PageQuery` | ✅ `PageQueryTest`| Implemented |
| NFR-SEC-001 | Deny-by-default, CSRF, security headers, rate limiting | `config/SecurityConfig`, `security/RateLimitFilter` | `SecurityConfigTest`| Implemented |
| NFR-SEC-002 | Secrets encrypted at rest, never returned | `security/SecretCipher`, write-only DTOs | ✅ `SecretCipherTest`| Implemented |
| NFR-DATA-001 | UUIDs, DECIMAL money, UTC, soft delete, `@Version` | migrations V1–V7, entity conventions | `SchemaParityIT`| Implemented |

## Front-end traceability

| Screen | Route | Backing endpoints |
|---|---|---|
| Dashboard | `/` | `GET /dashboard`, `GET /dashboard/activity` |
| Leads | `/leads`, `/leads/detail` | `/leads*` |
| Accounts / contacts | `/accounts*`, `/contacts*` | `/accounts*`, `/contacts*` |
| Pipeline | `/pipeline` | `GET /opportunities/board`, `POST /opportunities/{id}/transition` |
| Opportunity workspace | `/opportunities/detail` | `GET/PATCH /opportunities/{id}`, `POST /opportunities/{id}/transition` |
| Reports | `/reports` | `GET /dashboard?from&to` |
| Email inbox | `/engagement/inbox` | `/emails*` |
| Templates | `/engagement/templates` | `/email-templates*` |
| Sequences | `/engagement/sequences` | `/sequences*` |
| Sending accounts | `/engagement/smtp` | `/smtp-profiles*` |
| Unified inbox | `/engagement/mailbox` | `/email/inbox`, `/email/conversations/*`, `/email/approvals/*` |
| Mailbox connections | `/engagement/mailbox/settings` | `/email/accounts*` |
| Response policy | `/engagement/mailbox/rules` | `/email/rules*` |
| Administration | `/admin/*` | `/users`, `/roles`, `/catalog/*`, `/audit-logs`, `/admin/jobs` |

No route exists for an unimplemented module — the sidebar only renders entries whose capability the
session actually holds, and no screen is reachable for AI, meetings, proposals or workflows.
