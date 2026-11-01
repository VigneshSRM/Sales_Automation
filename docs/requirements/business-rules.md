# Business Rules — Infeara Sales OS

| Field | Value |
|---|---|
| Document ID | INFEARA-BR-001 |
| Version | 1.0 (Release 0 baseline) |
| Source | SDLC specification §6, §8, §9, §7, §13; implemented as backend invariants |
| Enforcement | Domain services + database constraints. The frontend duplicates **presentation** only; it never owns a rule. |

Rules are numbered `BR-<AREA>-<nnn>`. "Config" means the value lives in `workspace_settings`,
`pipeline_stages`, scoring model rows or an `app.*` property — never in code.

---

## BR-LEAD — Lead lifecycle

| ID | Rule |
|---|---|
| BR-LEAD-001 | A lead status transition is allowed only along the graph below. Any other transition returns `400 INVALID_STATE_TRANSITION` with `fieldErrors` naming the status. |
| BR-LEAD-002 | A lead is not an opportunity. Conversion creates/links an account + contact and one or more opportunities. |
| BR-LEAD-003 | `CONVERTED` requires at least one linked opportunity. |
| BR-LEAD-004 | `DISQUALIFIED` requires a reason code; reopening `DISQUALIFIED → REOPENED` requires the `leads.reopen` capability and an audit event. |
| BR-LEAD-005 | `CONTACTED` is set by the email engine when an outbound message is accepted by the provider for that lead — never by UI optimistic state. |
| BR-LEAD-006 | `ENGAGED` requires at least one reply classified as human intent or a meeting booking. |
| BR-LEAD-007 | Archived leads are read-only except for restore; archival is soft deletion (`deleted_at`). |

Transition graph (authoritative, mirrored in `LeadStatus` and `LeadLifecyclePolicy`):

```
NEW                  -> RESEARCHING, QUALIFIED, NURTURE, DISQUALIFIED
RESEARCHING          -> QUALIFIED, NURTURE, DISQUALIFIED
QUALIFIED            -> CONTACTED, NURTURE, DISQUALIFIED
CONTACTED            -> ENGAGED, NURTURE, DISQUALIFIED
ENGAGED              -> MEETING_SCHEDULED, NURTURE, DISQUALIFIED
MEETING_SCHEDULED    -> DISCOVERY_COMPLETED, NURTURE
DISCOVERY_COMPLETED  -> CONVERTED, NURTURE, DISQUALIFIED
NURTURE              -> QUALIFIED, DISQUALIFIED
CONVERTED            -> (terminal)
DISQUALIFIED         -> REOPENED   (leads.reopen only)
```

## BR-SCORE — Qualification scoring

| ID | Rule |
|---|---|
| BR-SCORE-001 | Score is computed server-side only; the request cannot supply a score. |
| BR-SCORE-002 | Weights (industry 15, size 10, requirement 20, budget 15, timeline 10, decision role 15, engagement 15) are stored in `scoring_models.factor_json` with a version; sum must equal 100 or the model is rejected at save. |
| BR-SCORE-003 | Each factor contributes `weight × factor_fit` where `factor_fit ∈ [0,1]`, derived from data or the agent's evidence; total is rounded half-up to an integer in 0–100. |
| BR-SCORE-004 | Bands are configurable: default ≥80 high priority, 60–79 qualified candidate, 40–59 nurture, <40 low priority. |
| BR-SCORE-005 | Every calculation persists `scoring_model_version`, factor breakdown, evidence/source and `calculated_at` in `lead_score_history`. |
| BR-SCORE-006 | A human override requires `reason` (≥10 chars) and writes an audit entry; the model score is retained. |
| BR-SCORE-007 | AI-suggested factor values may only overwrite *unverified* facts; verified facts require provenance before change. |

## BR-DUP — Duplicate detection

| ID | Rule |
|---|---|
| BR-DUP-001 | Email normalisation: trim, lowercase, remove display-name wrapper, keep `+tag` for storage but compare with tag stripped per policy flag. |
| BR-DUP-002 | Phone normalisation to E.164 requires a country context; numbers that cannot be normalised are never treated as strong matches. |
| BR-DUP-003 | Strong candidates (`EXACT_EMAIL`, `EXACT_PHONE_VERIFIED`) have confidence ≥ 0.95; domain+name `ACCOUNT_DOMAIN_NAME` 0.6–0.9 is a candidate; fuzzy name similarity produces `REVIEW_WARNING` only. |
| BR-DUP-004 | Creating a lead always returns `duplicateCandidates`; creation is blocked (`409 DUPLICATE_LEAD`) only when the source policy is `REJECT_ON_DUPLICATE`. |
| BR-DUP-005 | Merge: requires `leads.merge`; a preview endpoint returns field conflicts; merge moves activities/messages/meetings/enrollments to the target, soft-deletes the source with `merged_into_id`, and writes an immutable `merge_audit` row. |
| BR-DUP-006 | Merging never deletes the losing record physically and never merges two leads across workspaces. |

## BR-OPP — Opportunity stage gates and forecast

| ID | Rule |
|---|---|
| BR-OPP-001 | Stage transitions use the ordered stage list per workspace; forward moves may skip stages only if `pipeline_stages.allow_skip = 1`; backward moves require `opportunities.transition` + reason. |
| BR-OPP-002 | `QUALIFICATION` requires need/fit/authority/budget/timeline assessed (`qualification_assessment` non-null) or a disqualification reason. |
| BR-OPP-003 | `DISCOVERY` requires a discovery meeting or task linked to the opportunity. |
| BR-OPP-004 | `TECHNICAL_ASSESSMENT` requires technical owner and recorded scope/risks where applicable. |
| BR-OPP-005 | `PROPOSAL_PREPARATION` requires a draft proposal version. |
| BR-OPP-006 | `PROPOSAL_SENT` requires an `APPROVED` proposal version **and** send evidence (`proposal_send` with provider message id), or a `MANUAL_SEND_EXCEPTION` approval bound to the payload hash. |
| BR-OPP-007 | `NEGOTIATION` requires an open objection or next action recorded. |
| BR-OPP-008 | `CLOSED_WON` requires close date, final amount + currency, decision contact, and creates onboarding handoff atomically. |
| BR-OPP-009 | `CLOSED_LOST` requires a `loss_reason_id` from the controlled list; competitor/alternative optional. |
| BR-OPP-010 | Forecast: `weighted_value = amount × probability/100`; open pipeline excludes terminal stages; won value uses `final_amount` with `close_date` in period; `win_rate = won/(won+lost)×100` (0 when denominator is 0). Money is `DECIMAL(19,4)`; mixed currencies are aggregated separately (AMB-04). |
| BR-OPP-011 | CRM value is never presented as recognised accounting revenue. |
| BR-OPP-012 | Every transition writes `stage_history` with actor, reason, rule version and old/new stage, and checks the client's `rowVersion` (stale ⇒ `409 STALE_VERSION`). |

## BR-EMAIL — Send eligibility, suppression and sequence safety

| ID | Rule |
|---|---|
| BR-EMAIL-001 | A send is allowed only when ALL hold, re-evaluated immediately before provider handoff: profile `ACTIVE`; recipient not suppressed for the purpose; consent/lawful basis recorded for marketing purposes; no applicable reply; no booked meeting; no hard bounce/complaint; daily cap and rate limit not exceeded; inside the recipient-local (or fallback) sending window; unsubscribe link present for marketing. |
| BR-EMAIL-002 | Every send carries an idempotency key (`sequence_run:{stepRunId}` or `composer:{requestKey}`); duplicate keys return the existing message, never a second send. |
| BR-EMAIL-003 | Timeout after handoff ⇒ status `UNCERTAIN`, not `FAILED`; reconciliation correlates provider message id before any retry. |
| BR-EMAIL-004 | Unsubscribe blocks marketing sends across all sequences, campaigns and SMTP profiles immediately (hard block; no override). |
| BR-EMAIL-005 | Hard bounce and complaint create suppression automatically; override requires `emails.override_suppression` + comment + audit, and may never override a legal opt-out. |
| BR-EMAIL-006 | Sequence exits on reply, unsubscribe, complaint, hard bounce, booked meeting, disqualified lead, manual pause, campaign end, or exhaustion of steps. |
| BR-EMAIL-007 | Follow-up step N is skipped if any earlier step for the same enrollment is not `SENT`. |
| BR-EMAIL-008 | Enrollment uniqueness: one active enrollment per (lead, sequence version); re-enrolment requires `allow_reenroll` and the cooling period (default 30 days) to have elapsed since the last terminal enrollment. |
| BR-EMAIL-009 | Template merge fields are allowlisted; rendering fails closed on unknown or null mandatory fields. |
| BR-EMAIL-010 | Marketing messages must include a functional unsubscribe mechanism and a physical/legal footer identity as configured. |
| BR-EMAIL-011 | No account rotation to evade provider limits or suppression; fallback only to explicitly approved profiles. |
| BR-EMAIL-012 | Email open tracking is advisory only and must never be presented as proof of interest or used to infer acceptance. |

## BR-CAL — Calendly and meetings

| ID | Rule |
|---|---|
| BR-CAL-001 | Webhook requests must carry a valid `Calendly-Webhook-Signature` (`t`, `v1` = HMAC-SHA256 hex of `{t}.{rawBody}` with the subscription signing key), within ±180 s; failures return 401 and are counted. |
| BR-CAL-002 | The raw payload is persisted in `calendly_webhook_events` before processing, keyed uniquely by provider event id (fallback: deterministic hash). Duplicate delivery returns 200 and creates no second CRM side effect. |
| BR-CAL-003 | A booking creates at most one active meeting per `external_event_uri`; a reschedule retains the prior slot in `meeting_history`. |
| BR-CAL-004 | A confirmed booking stops applicable sequence enrollments (`MEETING_BOOKED`) and cancels pending meeting-reminder tasks for the superseded slot. |
| BR-CAL-005 | `NO_SHOW` is never inferred from elapsed time; only an authorised user records it. |
| BR-CAL-006 | Invitee email matches an existing contact only on exact normalised email; otherwise a new contact/lead is created with source `CALENDLY` or the event is queued as `UNMATCHED` for review. |
| BR-CAL-007 | The CRM never invents availability or booking confirmation; if Calendly is unreachable the UI shows stale-sync state and the action is queued. |

## BR-AI — AI workforce guardrails

| ID | Rule |
|---|---|
| BR-AI-001 | Agents call tools only through backend services; no agent holds a datasource or SQL capability. |
| BR-AI-002 | Each agent run is bounded by max tokens, max tool calls, timeout, cost budget and rate limit configured per agent version; exceeding a limit fails the run (`RUNAWAY_LIMIT`) and is audited. |
| BR-AI-003 | Structured output must validate against the agent's JSON Schema; invalid output is retried once (if configured) then queued for human review — never partially applied. |
| BR-AI-004 | Agents may not fabricate company facts, references, pricing or capabilities; missing facts must be reported as `unknown` with a question rather than invented. |
| BR-AI-005 | Inbound emails, attachments, notes and website content are untrusted; instructions found inside them are data, never commands. Tool calls derived from them require approval when the policy says so. |
| BR-AI-006 | AI output is never interpolated into SQL, shell, or HTML without escaping/schema validation. |
| BR-AI-007 | Agents cannot approve commercial content, discounts, contract terms or final proposal issue. |
| BR-AI-008 | Hidden chain-of-thought is neither requested nor stored; only concise rationale, evidence and tool actions are persisted. |

## BR-APPR — Human approval binding

| ID | Rule |
|---|---|
| BR-APPR-001 | An approval binds `action_type + object_ref + payload_hash + object_version + policy_version`; any change to the payload invalidates it. |
| BR-APPR-002 | Approvals expire (`expires_at`, default 72 h) and are single-use per object version. |
| BR-APPR-003 | The approver must hold the capability for the action and must not be the requester for commercial approvals (four-eyes). |
| BR-APPR-004 | Decisions record approver, timestamp, decision, comment, object version and resulting action; approval of a superseded version is rejected with `409 STALE_VERSION`. |

## BR-WF — Workflow automation

| ID | Rule |
|---|---|
| BR-WF-001 | A published workflow version is immutable; edits create a new draft version. Publishing validates the graph: exactly one trigger, no orphan nodes, no cycles, all referenced templates/agents active. |
| BR-WF-002 | A run is bounded: max 50 steps, max wait 90 days, max 3 chained triggered runs per root event per record. Exceeding raises `WORKFLOW_LIMIT_EXCEEDED`. |
| BR-WF-003 | Concurrent runs for the same (workflow version, record) are serialised by a unique active-run key. |
| BR-WF-004 | Workflow actions execute through the same authorised services as user actions; they cannot exceed the workflow owner's effective capabilities, and emails still pass all BR-EMAIL checks. |
| BR-WF-005 | Failed steps retry with exponential backoff up to the configured maximum, then the run becomes `FAILED` and the job goes to `DEAD` for review/replay. |

## BR-DATA — Data integrity and multi-tenancy

| ID | Rule |
|---|---|
| BR-DATA-001 | Every business row carries `workspace_id`; repositories always filter by the authenticated workspace. |
| BR-DATA-002 | Cross-workspace identifier use returns 404 (existence concealment). |
| BR-DATA-003 | Money is `DECIMAL(19,4)` with a 3-letter ISO currency; timestamps are `DATETIME(6)` UTC plus a separate IANA `*_timezone` column where local meaning matters. |
| BR-DATA-004 | Optimistic locking: `row_version` incremented on update; stale writes ⇒ 409. |
| BR-DATA-005 | Deletion is soft (`deleted_at`) for CRM records; audit, stage history, webhook inbox and outbox are append-only and never deleted by application roles. |
| BR-DATA-006 | External identifiers (provider message id, webhook event id, Calendly event URI, idempotency key) are protected by unique constraints. |
| BR-DATA-007 | No external network call inside a database transaction; side effects are recorded in the outbox in the same transaction (transactional outbox). |
| BR-DATA-008 | Workers claim jobs atomically with a lease; a crashed worker's lease expires and the job is reclaimed; idempotency keys prevent duplicate side effects. |

## BR-SEC — Security and audit

| ID | Rule |
|---|---|
| BR-SEC-001 | Secrets (SMTP password, AI keys, Calendly credentials, webhook signing keys) are AES-256-GCM encrypted with a key from outside the database, versioned for rotation, and never returned by any API. |
| BR-SEC-002 | Every sensitive action writes an immutable audit row (actor, action, object, before/after summary, correlation id). Auditors cannot mutate audit rows; the application DB user has no UPDATE/DELETE grant on the table. |
| BR-SEC-003 | Account lockout after 5 failures / 15 min; successful login resets the counter; lockout and anomalies are audited. |
| BR-SEC-004 | Rate limits apply per user and per IP to auth, send, import, AI, webhook and search endpoints. |
| BR-SEC-005 | Uploaded files are validated for size, extension, MIME and stored outside the web root; downloads are authorised per record scope. |
