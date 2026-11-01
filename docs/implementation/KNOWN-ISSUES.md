# Known issues

Grouped by severity, with the release that owns each fix. Nothing here is hidden or softened: an item
stays on this list until the code that fixes it is on `main` **and** verified.

## Blocker — must be closed before anyone calls this "done"

| # | Issue | Impact | Owner |
|---|---|---|---|
| B-1 | **The backend has never been compiled.** No JDK/Maven in the authoring environment, Maven Central unreachable (HTTP 000). | Compile errors are likely; runtime behaviour is unproven. | Release 8 gate |
| B-2 | **Flyway has never run.** No MariaDB instance, no Docker daemon. | Migration ordering, CHECK constraint syntax and `ddl-auto=validate` are unproven. | Release 8 gate |
| B-3 | **No test suite has ever executed** (JUnit, Testcontainers, Vitest, Playwright). | Acceptance criteria are asserted on paper only. | Release 8 gate |
| B-4 | **No end-to-end run against a real SMTP server.** | Deliverability, TLS negotiation and bounce handling are unproven. | Release 3 sign-off |
| B-5 | **The WAR has never been built and Tomcat has never run it.** The packaging contract is proven by `scripts/verify-war-selftest.py`, but `mvn package`, Flyway and Tomcat itself are unavailable in the authoring sandbox. | "Hosted on Tomcat" is scripted, not demonstrated; see `docs/operations/sandbox-environment.md` §4. | Release 8 gate |
| B-6 | **No email has ever been read from a real mailbox and no AI provider has ever been called.** | The two-way path (IMAP → threading → AI → policy → reply) is unproven end to end; every failure mode is handled in code but none has been observed. | Release 3b sign-off |

## High

| # | Issue | Impact | Owner |
|---|---|---|---|
| H-1 | Meetings/Calendly not implemented (V3 schema only). | No booking flow, no meeting timeline, no webhook processing. | Release 4 |
| H-2 | AI workforce and approvals not implemented (V4 schema only). | Modules 21–22 absent; the Proposal/Pipeline analyst agents do not exist. | Release 5 |
| H-3 | Workflow builder/interpreter not implemented (V7 schema only); only the outbox/job engine exists. | Users cannot define automations; triggers are published but nothing user-configurable consumes them. | Release 6 |
| H-4 | Inbound mail is implemented **for IMAP only**; `GMAIL_API` and `MICROSOFT_GRAPH` return `UNSUPPORTED_OPERATION`. | Gmail/Microsoft 365 tenants that forbid IMAP (or require OAuth) cannot be connected. | Release 5 |
| H-7 | Inbound attachments are recorded as metadata with `scan_status = SKIPPED`; there is no download route and no malware scan. | Users are told an attachment exists but cannot open it, and no AV gate exists. Deliberately not faked. | Release 7 (file storage) |
| H-8 | `email_accounts` sync is guarded by an in-process lock plus a job lease. Two Tomcat nodes may open two redundant IMAP sessions for the same mailbox. | Wasted connections, no data corruption (unique `inbound_key`). | Release 8 |
| H-9 | Reply latency: analysis runs on the worker tick (default 5 s polling) and `AUTOMATIC` replies are queued, not sent inline. | A customer-facing auto-reply is not instant; acceptable, but stated so nobody expects sub-second. | Product |
| H-5 | Proposals, onboarding, renewals not implemented (V5 schema only). | Proposal Sent gate blocks (correctly) but cannot be satisfied. | Release 7 |
| H-6 | Campaigns schema-only; reply classification absent; public unsubscribe endpoint absent. | Module 17/19 partially missing; unsubscribe relies on suppression APIs. | Release 7 |

## Medium

| # | Issue | Impact | Owner |
|---|---|---|---|
| M-1 | Converting a lead to an opportunity has no dedicated endpoint (conversion is a service-level command used by opportunity creation). | Lifecycle stays correct, but the UI cannot show a first-class "Convert" action yet. | Release 7 |
| M-2 | Opportunity board returns the whole workspace's deals (bounded in practice); no column-level paging. | Could become slow at very large volumes. | Release 8 |
| M-3 | Saved reports and `report_definitions` are unused; `/reports` computes from the dashboard endpoint. | No saved/shared report definitions. | Release 7 |
| M-4 | `docs/security/security-checklist.md` lists open items: pen test, dependency scanning in CI, key-rotation rehearsal, retention/erasure runbook. | Compliance evidence incomplete. | Release 8 |
| M-5 | Frontend tests exist for helpers only; component and E2E suites are not authored yet. | UI regressions would not be caught automatically. | Release 8 |
| M-6 | Search covers leads, accounts, contacts and opportunities; templates, sequences and **inbound conversations** are not indexed. | Discoverability gap in the unified inbox search (which only matches subject/mailbox). | Release 7 |
| M-7 | `JsonSchemaValidator` implements a subset of JSON Schema 2020-12 (type, enum, required, properties, items, min/max, lengths). `patternProperties`, `oneOf`, `$ref` are not supported. | Agent schemas must stay within the supported subset; an unsupported keyword is silently ignored. | Release 5 |
| M-8 | Business hours are evaluated in the caller's timezone (session, else `app.default-timezone`), not the mailbox's. | A distributed team may see "outside hours" decided by the wrong clock. | Release 5 |
| M-10 | `email.attachments.read` is seeded but no route uses it yet (attachment metadata is stored, download is not). | A capability exists that no screen exercises; admins may grant a right that does nothing. | Release 7 (file storage) |
| M-9 | Conversation assignment does not enforce queues: `queueKey` is free text on the mailbox and conversation. | No round-robin or eligibility routing; the shared queue is informational. | Release 5 |

## Low

| # | Issue | Impact | Owner |
|---|---|---|---|
| L-1 | Bootstrap seeds are flagged `requiresProductOwnerReview` (AMB-05) — role→capability mapping and scoring weights need product sign-off. | Configuration may need tuning after UAT. | Product |
| L-2 | No file/attachment module yet; V5 tables are unused. | Cannot attach documents to records. | Release 7 |
| L-3 | CSV import not implemented. | Bulk onboarding of existing CRM data is manual. | Release 7 |
| L-4 | OpenAPI is generated but not published as a versioned artifact per release. | Consumers must read the running service. | Release 8 |
| L-5 | `docs/api/endpoint-inventory.md` is generated on demand; it is not wired into CI. | Could go stale if someone forgets to regenerate. | Release 8 |

## Explicitly *not* issues (decisions, not defects)

* **No Redis / MongoDB / Firebase.** Async work uses the MariaDB outbox + job tables by design
  (ADR-002). Slower throughput ceiling, much simpler operations.
* **No FX conversion.** Money is never summed across currencies (AMB-04). Deliberate.
* **No demo/sample data mode.** Rejected: it would fake integrations the specification forbids.
* **No non-SMTP sender, no non-Calendly booking, no mock AI providers.** Rejected by the brief.
* **Detail pages use `?id=` query strings.** Consequence of `output: "export"` static hosting inside
  the WAR (ADR-004); documented rather than worked around with a Node runtime.
* **The UI is served at the ROOT context, not `/ui`.** Next.js writes absolute `/_next/...` URLs; a
  named context would need a matching `NEXT_BASE_PATH` rebuild. See the deployment guide §2.
* **`AUTOMATIC` replies are still queued through the normal dispatcher** rather than sent inline by the
  AI service. That is the design: one send path, one set of eligibility checks.
* **No simulated inbound endpoint.** There is deliberately no "inject a test email" API; a fake
  integration is worse than a missing one. Test with a real mailbox.
