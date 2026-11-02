# Release 3b — Addendum: two-way email, AI triage and auto-response

**Source:** the *updated* specification PDF, addendum §§1–11, which supersedes the original document
for the modules it adds. Baseline: release 3 (outbound engagement).

**Why a separate release:** the addendum changes the meaning of "email integration". Sending-only was
acceptable before; it is explicitly not acceptable now. A mailbox must be *read*.

## 1. What the addendum required, and what was built

| Addendum requirement | Implementation | Verified? |
|---|---|---|
| Real two-way mailbox integration, not SMTP-only (§1) | `engagement/mailbox/ImapInboundConnector` opens a real IMAP session (TLS, `checkserveridentity=true`, bounded timeouts, `getMessagesByUID`) | No — never run against a server |
| Inbound messages must not be simulated in production (§1) | There is no ingest-by-API route for inbound mail; the only writers are the IMAP connector and the `MAILBOX_SYNC` job. `inbound_key` is unique per workspace | Structurally: no such endpoint exists |
| Separate inbound credentials from outbound SMTP (§2) | `email_accounts` (IMAP secret, encrypted) with an optional `smtp_profile_id`; `email_messages.smtp_profile_id` became nullable for inbound-only mailboxes | No |
| Cannot enable a mailbox without a successful test (§2) | `MailboxService.setEnabled` refuses unless `last_health_at` is set and `last_error` is null; credential changes reset to `PENDING_TEST` | No |
| Unsupported providers must fail loudly (§2) | `GMAIL_API`/`MICROSOFT_GRAPH` are accepted values but return `501 UNSUPPORTED_OPERATION`; never a silent IMAP fallback | Reasoned (code path explicit) |
| Incremental sync with UIDVALIDITY handling (§3) | `email_sync_state(uid_validity, last_uid)`; a UIDVALIDITY change refetches from 0 and dedupe absorbs the overlap | No |
| No duplicate activities/jobs on re-sync (§3) | `uk_email_inbound_key (workspace_id, inbound_key)`; `inbound_key = account:<rfcId>` else `account:<uidValidity>:<uid>` | No |
| Conversation threading (§3) | `ConversationMatcher`: `References` root → `In-Reply-To` → `Message-ID` → normalised subject; `uk_email_thread_key` | No |
| CRM matching must not guess (§4) | Exactly one contact → `MATCHED_CONTACT`; more than one → `AMBIGUOUS_CONTACT` and left unassigned; else lead; else `NO_MATCH` | No |
| AI classification with schema-constrained output (§5) | `EmailIntelligenceService` + `AiPrompt` + `AiGateway` + `JsonSchemaValidator`; invalid JSON is discarded with `AI_SCHEMA_VALIDATION_FAILED` | No — no provider called |
| Prompt injection must not be able to trigger a send (§5) | Email body is passed as fenced untrusted data, truncated at 12 000 chars; the model has no tool access and cannot choose a recipient | Reasoned |
| AI may not send unless policy authorises it (§6) | `EmailPolicyService` returns `AUTOMATIC`/`APPROVAL_REQUIRED`/`HUMAN`/`NO_RESPONSE`; `NEVER_AUTO` = unsubscribe, bounce, out-of-office, spam; pricing/negotiation/objection/complaint/support are downgraded to approval; outside business hours everything needs approval | No |
| Four-eyes approval with a bound payload (§6) | `email_approvals.payload_hash` = SHA-256(subject + body); approval recomputes it and fails with `APPROVAL_INVALIDATED`; 72 h expiry; requester cannot approve | No |
| Sending happens through the existing, guarded dispatcher (§6) | Approved drafts become `QUEUED`; `EmailDispatcher`/`EmailService.deliver` re-check suppression, consent and limits | No |
| Unified inbox UI with real endpoints only (§7) | `/engagement/mailbox`, `/engagement/mailbox/settings`, `/engagement/mailbox/rules` — every control calls a route listed in `docs/api/API-SPECIFICATION.md` §5 | UI: build + tests ✅, API: unverified |
| Draft vs approved vs sent must be visible (§7) | Message rows carry `status` and render distinct badges (`Draft · awaiting approval`, `Queued`, `Sent`) plus the approval card | Build ✅ |
| Audit everything (§9) | `EMAIL_ACCOUNT_CREATED/ENABLED/TESTED`, `EMAIL_SYNC_REQUESTED`, `EMAIL_REPLY_DRAFTED`, `EMAIL_DRAFT_APPROVED/REJECTED`, `EMAIL_ANALYZED(_MANUALLY)`, `EMAIL_AUTO_REPLY_QUEUED`, `EMAIL_DRAFT_AWAITING_APPROVAL`, `EMAIL_RULE_*` | No |
| Frontend and backend in one WAR hosted on Tomcat 11 (§10/§11) | `scripts/build-war.sh` (export → `static/` → `mvn package` → `verify-war.py`), `scripts/host-app.sh` (DB, setenv, ROOT.war, health wait) | Packaging contract ✅; Tomcat run ❌ |

## 2. Migrations

* `V8__email_two_way.sql` — `email_accounts`, `email_sync_state`, `email_conversations`,
  `email_attachments`, `email_analysis`, `email_automation_rules`, `email_approvals`, plus an
  `ALTER TABLE email_messages` that adds direction/threading/RFC-header/UID/AI columns and makes
  `smtp_profile_id` nullable.
* `V9__email_two_way_rbac.sql` — eight capabilities and their role grants.
* `V10__missing_capabilities.sql` — unrelated but found by this release's
  `tools/capability_audit.py`: `leads.reopen`, `opportunities.assign` and `notifications.read` were
  enforced by guards but never seeded, so six endpoints returned 403 to every role.

Both are forward-only and additive, so release 3 remains deployable without them only if the code is
rolled back at the same time (the entities expect the new columns).

## 3. Deviations recorded

1. **UI is deployed at the ROOT context**, not `/ui` under `/infeara-sales-os`. The Next.js export
   emits absolute `/_next/...` asset URLs; a named context would require rebuilding with a matching
   base path. `scripts/verify-war.py` asserts the invariant and the deployment guide explains both
   options.
2. **Attachment bytes are not stored.** Metadata (filename, media type, size, SHA-256) is persisted
   with `scan_status = SKIPPED`, and the UI says "attachment" without implying it was scanned or is
   downloadable. File storage is a later release; pretending otherwise would be a fake integration.
3. **Gmail API and Microsoft Graph are declared but not implemented.** They return
   `UNSUPPORTED_OPERATION`; only IMAP reads mail in this release.
4. **The ten named AI agents are still schema-only.** The addendum's email analyst is implemented; the
   V4 agent catalogue, budget enforcement and generic approval objects are not.

## 4. Verification actually performed

| Check | Result |
|---|---|
| `npx tsc --noEmit` | clean |
| `npx vitest run` | 28 tests passing (incl. 6 mailbox API contract tests) |
| `npm run build` | 22 routes exported; UI copied into the WAR resource root |
| `python3 tools/java_symbol_audit.py` | 215 types, 1011 call sites, 0 missing methods |
| `python3 tools/schema_report.py` | 49 entities / 82 tables, no column mismatches |
| `python3 tools/capability_audit.py` | 0 capabilities enforced without a seed row |
| `python3 tools/data_dictionary.py` | 82 tables regenerated |
| `python3 scripts/verify-war-selftest.py` | packaging contract checker proven both ways |
| `mvn package` / Tomcat / Flyway / real IMAP / real AI call | **not executed** — no JDK, Maven, Tomcat, MariaDB or network route to them in the authoring sandbox |

## 5. Known gaps (see `docs/implementation/KNOWN-ISSUES.md` §High)

* The Java has never been compiled.
* IMAP behaviour against a hostile server (malformed MIME, huge messages, charset edge cases) is
  reasoned about but untested; the parser is defensive and truncates, but only a real mailbox proves it.
* Business-hours evaluation uses the caller's session timezone (falling back to
  `app.default-timezone`) rather than a per-mailbox timezone; a team in two locales will be evaluated
  against the reviewer's clock, not the mailbox's.
* Attachments: no download route, no scanning.
