# REST API specification

Base path `/api/v1` · JSON only · OpenAPI 3.1 served by springdoc at `/v3/api-docs` and
`/swagger-ui.html` (closed in the `prod` profile).

> **Endpoint list:** the authoritative, generated list of every implemented route, its handler and
> its required capability is `docs/api/endpoint-inventory.md` (produced by
> `tools/api_inventory.py` from the controllers — it cannot drift from the code). This document
> defines the conventions those endpoints follow.

## 1. Conventions

| Topic | Rule |
|---|---|
| Identifiers | UUID strings |
| Timestamps | ISO-8601 UTC on the wire (`2026-11-01T09:30:00Z`); rendering in the user's IANA timezone is a UI concern |
| Money | Decimal in the record's own currency; never converted server-side |
| Paging | `page` (0-based), `size` (max 100), `sort`, `direction=asc\|desc` |
| Sorting | Only allow-listed fields; anything else → `INVALID_SORT_FIELD` |
| Filtering | Allow-listed query parameters; applied filters are echoed in `meta`/`appliedFilters` |
| Concurrency | Mutating bodies may carry `expectedRowVersion`; mismatch → `409 STALE_VERSION` |
| Idempotency | `Idempotency-Key` header on capture endpoints; replay returns the stored response |
| Auth | `INF_SESSION` HttpOnly cookie + `X-XSRF-TOKEN` echo of the `XSRF-TOKEN` cookie for unsafe methods |
| Correlation | Every response carries `meta.requestId`, also stamped on logs and audit rows |

## 2. Envelopes

```json
{ "success": true, "data": { }, "meta": { "requestId": "b1f0…", "generatedAt": "2026-11-01T09:30:00Z" } }
```

```json
{ "success": false,
  "error": { "code": "STAGE_GATE_FAILED",
             "message": "The stage gate for Closed Won is not satisfied",
             "fieldErrors": [ { "field": "decisionContactId", "code": "REQUIRED",
                                "message": "A decision-maker contact is required" } ],
             "requestId": "b1f0…" } }
```

Paged payload (`PageResponse`): `items`, `page`, `size`, `totalElements`, `totalPages`, `sort`,
`appliedFilters`.

## 3. Status codes

| Code | Meaning | Typical error codes |
|---|---|---|
| 200 | OK | — |
| 201 | Created | — |
| 202 | Accepted (queued / approval required) | `APPROVAL_REQUIRED` |
| 400 | Malformed or unsupported request | `MALFORMED_REQUEST`, `INVALID_SORT_FIELD`, `INVALID_STAGE_TRANSITION` |
| 401 | No/invalid session | `UNAUTHENTICATED`, `SESSION_EXPIRED`, `MFA_REQUIRED`, `INVALID_CREDENTIALS` |
| 403 | Authenticated but not permitted | `CAPABILITY_REQUIRED`, `RECORD_SCOPE_DENIED`, `ACCOUNT_LOCKED`, `MFA_NOT_ENROLLED` |
| 404 | Not found, or not visible in this workspace | `LEAD_NOT_FOUND`, `ACCOUNT_NOT_FOUND`, `OPPORTUNITY_NOT_FOUND`, `NOT_FOUND` |
| 409 | State conflict | `CONFLICT`, `STALE_VERSION`, `DUPLICATE_RESOURCE`, `DUPLICATE_LEAD`, `IDEMPOTENCY_CONFLICT` |
| 422 | Business rule violated | `VALIDATION_FAILED`, `STAGE_GATE_FAILED`, `RECIPIENT_SUPPRESSED`, `CONSENT_MISSING`, `TEMPLATE_RENDER_FAILED` |
| 429 | Rate limited / sending cap | `RATE_LIMITED`, `SENDING_LIMIT_REACHED` |
| 500 | Unexpected | `INTERNAL_ERROR` |
| 502/503 | Upstream/dependency failure | `PROVIDER_ERROR`, `SMTP_TEST_FAILED`, `SERVICE_UNAVAILABLE` |

The complete code set is `com.infeara.sales.common.error.ErrorCode`; adding a code requires a row in
this table (enforced by review, and the codes are cross-checked by `tools/static_audit.py`).

## 4. Module map

| Module | Base paths |
|---|---|
| Authentication | `/auth/login`, `/auth/mfa/*`, `/auth/logout`, `/auth/me`, `/auth/password` |
| Dashboard | `/dashboard`, `/dashboard/activity` |
| Search | `/search?q=` |
| Leads | `/leads`, `/leads/{id}`, `/leads/{id}/{transition,assign,qualify,score-override,archive}`, `/leads/merge`, `/leads/bulk-assign` |
| Accounts & contacts | `/accounts`, `/accounts/{id}`, `/contacts`, `/contacts/{id}` |
| Pipeline | `/opportunities`, `/opportunities/board`, `/opportunities/forecast`, `/opportunities/{id}`, `/opportunities/{id}/transition` |
| Activities & tasks | `/activities`, `/records/{type}/{id}/activities`, `/tasks`, `/tasks/{id}/complete`, `/tasks/{id}/reopen` |
| Email | `/emails`, `/emails/{id}`, `/emails/{id}/{approve,send,cancel}`, `/emails/eligibility`, `/emails/inbound-reply`, `/emails/bounce` |
| Templates | `/email-templates`, `/email-templates/{id}`, `/email-templates/{id}/{submit,approve}`, `/email-templates/preview` |
| SMTP accounts | `/smtp-profiles`, `/smtp-profiles/{id}`, `/smtp-profiles/{id}/{test,status}` |
| Sequences | `/sequences`, `/sequences/{id}/{activate,pause,enroll}`, `/sequences/enrollments`, `/sequences/enrollments/{id}/stop` |
| Catalogues | `/catalog/{services,stages,sources,industries,loss-reasons}` |
| Administration | `/users`, `/users/{id}`, `/roles`, `/audit-logs`, `/admin/jobs`, `/admin/jobs/{id}/replay`, `/admin/automation-health`, `/admin/integration-health` |
| Notifications | `/notifications`, `/notifications/unread-count`, `/notifications/{id}/read`, `/notifications/read-all` |
| Two-way email (addendum) | `/email/accounts`, `/email/accounts/{id}`, `/email/accounts/{id}/{test,status,sync,sync-now,sync-state}`, `/email/inbox`, `/email/inbox/stats`, `/email/conversations/{id}`, `/email/conversations/{id}/{read,assign,note,close,reply}`, `/email/messages/{id}/analyze`, `/email/approvals`, `/email/approvals/{id}/{approve,reject}`, `/email/rules`, `/email/rules/{id}/enabled` |

## 5. Two-way email module (addendum §§3–8)

Routes are under `/api/v1/email`. Capabilities are listed per route and are enforced in the service
layer, not only at the edge.

### 5.1 Mailbox connections (`MailboxAccountController`)

| Method & path | Capability | Behaviour |
|---|---|---|
| `GET /email/accounts` | `email.accounts.read` | Mailbox list with sync cursor, failure count and last error. Credentials are never returned. |
| `POST /email/accounts` | `email.accounts.manage` | Creates a mailbox in `PENDING_TEST`. `secret`/`oauthRefreshToken` are write-only and encrypted immediately. `409 DUPLICATE_RESOURCE` on a duplicate name. |
| `PATCH /email/accounts/{id}` | `email.accounts.manage` | Partial update with optional `expectedRowVersion` (`409 STALE_VERSION`). Changing host/port/credentials moves the mailbox back to `PENDING_TEST`. |
| `POST /email/accounts/{id}/test` | `email.accounts.manage` | Opens a real IMAP session (TLS + `checkserveridentity`), returns `{ok, detail, messageCount, …}`. Failure marks the mailbox `ERROR`. |
| `POST /email/accounts/{id}/status` | `email.accounts.manage` | `{enabled:true|false}`. Enable fails with `400 BUSINESS_RULE_VIOLATION` unless the last connection test succeeded. |
| `POST /email/accounts/{id}/sync` | `email.accounts.manage` | Queues a `MAILBOX_SYNC` job (lease-based, idempotency key per minute) and returns the job id. |
| `POST /email/accounts/{id}/sync-now` | `email.accounts.read` | Runs one synchronisation in-request for diagnostics; returns `{fetched, persisted, duplicates, failed}`. |
| `GET /email/accounts/{id}/sync-state` | `email.accounts.read` | `{uidValidity, lastUid, lastSyncAt, lastSuccessAt, consecutiveFailures, lastError, reconciliationStatus}`. |

`GMAIL_API` and `MICROSOFT_GRAPH` are accepted as values but are **not implemented**: any sync attempt
returns `501 UNSUPPORTED_OPERATION` rather than silently falling back to IMAP.

### 5.2 Inbox and conversations (`InboxController`)

| Method & path | Capability | Behaviour |
|---|---|---|
| `GET /email/inbox` | `email.conversations.read` | Filters: `status`, `assignee`, `unassigned`, `unreadOnly`, `accountId`, `q`; `page`/`size` (≤100). Ordered by `lastMessageAt desc`. |
| `GET /email/inbox/stats` | `email.conversations.read` | `{totalOpen, unread, unassigned, awaitingApproval, analysedToday}` for the tiles. |
| `GET /email/conversations/{id}` | `email.conversations.read` | Timeline (inbound + outbound), latest validated analysis, pending approval, and the policy decision that produced it. |
| `POST /email/conversations/{id}/read` | `email.conversations.read` | `{read:true|false}`. |
| `POST /email/conversations/{id}/assign` | `email.conversations.assign` | `{userId, queueKey, note}`; audited with before/after owner. |
| `POST /email/conversations/{id}/note` | `email.conversations.read` | Internal note written to the linked lead/contact timeline. Fails if the conversation is not matched to a CRM record. |
| `POST /email/conversations/{id}/close` | `email.conversations.assign` | `status = CLOSED`, unread cleared. |
| `POST /email/conversations/{id}/reply` | `email.compose` | `{body, subject?, sendImmediately?}`. Threading headers (`In-Reply-To`, `References`) are derived from the last inbound message. With `email.send` + `sendImmediately` the draft is queued; otherwise it is stored as `PENDING_APPROVAL` with an approval row. |
| `POST /email/messages/{id}/analyze` | `email.analyze` | Manual (re-)analysis. `503 AI_PROVIDER_UNAVAILABLE` when no provider is configured — never a fabricated result. |

### 5.3 Draft approvals

| Method & path | Capability | Behaviour |
|---|---|---|
| `GET /email/approvals?status=PENDING` | `email.conversations.read` | Approval queue, newest first. |
| `POST /email/approvals/{id}/approve` | `email.drafts.approve` | Recomputes the payload hash; `409 APPROVAL_INVALIDATED` if the draft changed, `409 APPROVAL_EXPIRED` after 72 h, `403` when the approver is the requester (four-eyes). On success the message becomes `QUEUED`; the dispatcher sends it. |
| `POST /email/approvals/{id}/reject` | `email.drafts.approve` | `{reason}`; the draft becomes `CANCELLED` with `failure_code = DRAFT_REJECTED`. |

### 5.4 Response policy

| Method & path | Capability | Behaviour |
|---|---|---|
| `GET /email/rules` | `email.read` | Rules in evaluation order (`priority asc`). |
| `POST /email/rules` | `email.rules.manage` | `{name, priority, mode, allowedIntents[], blockedIntents[], minConfidence, businessDays[], businessHoursStart, businessHoursEnd, delaySeconds, enabled}`. |
| `POST /email/rules/{id}/enabled` | `email.rules.manage` | `{enabled}`. |

Hard rules that no API call can override: `AUTO_GENERATED` messages and the intents `UNSUBSCRIBE`,
`BOUNCE`, `OUT_OF_OFFICE`, `SPAM` are never answered; `COMPLAINT`, `NEGOTIATION`, `OBJECTION`,
`PRICING_ENQUIRY`, `PROPOSAL_REQUEST`, `CUSTOMER_SUPPORT` are downgraded to `APPROVAL_REQUIRED` even
when a rule says `AUTOMATIC`; outside business hours everything becomes `APPROVAL_REQUIRED`.

### 5.5 Error codes added by this module

`AI_PROVIDER_UNAVAILABLE`, `AI_SCHEMA_VALIDATION_FAILED`, `APPROVAL_EXPIRED`,
`APPROVAL_INVALIDATED`, `UNSUPPORTED_OPERATION` (non-IMAP adapter), `SERVICE_UNAVAILABLE`
(credential encryption not configured), `BUSINESS_RULE_VIOLATION` (enable before test, reply without
SMTP profile, analyse an outbound message).

## 6. Not implemented (no server-side surface exists yet)

`/meetings*`, `/calendly/*` webhooks, `/ai/*` (agent catalogue — the *email* analyst exists, the ten
named agents do not), `/approvals/*` (generic AI approval surface; the email draft queue at
`/email/approvals` is implemented), `/workflows/*`, `/proposals/*`, `/customers/*`, `/reports/*`,
`/files/*`, `/imports/*`, `/campaigns/*`, `/email/attachments/*` (metadata is stored, download is not).

The schema, capability codes and business rules for these exist so their release adds code only. The
UI does not link to them (`DEVELOPMENT_STATUS.md` §3).
