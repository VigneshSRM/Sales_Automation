# Release 3 — Engagement

**Scope:** SMTP accounts, templates, composer, approvals, delivery tracking, suppression/consent,
follow-up sequences.

## Implemented

| Area | Code | Notes |
|---|---|---|
| SMTP profiles | `engagement/smtp/*` | AES-256-GCM secret, write-only DTO, live test before ACTIVE, TLS rules, daily/hourly caps, `sent_today` counter |
| Sender | `engagement/smtp/SmtpSender` | Per-profile `JavaMailSender`, STARTTLS/IMPLICIT_TLS/NONE (NONE rejected in prod) |
| Templates | `engagement/template/*` | Allow-listed merge fields, HTML escaping, preview, submit/approve, edit-after-approval reverts to DRAFT with version bump |
| Composer & lifecycle | `engagement/email/*` | DRAFT → PENDING_APPROVAL → QUEUED → SENDING → SENT/DELIVERED/BOUNCED/REPLIED; cancel before hand-off |
| Approvals | `EmailService.approve` | Four-eyes: author cannot approve; eligibility re-checked |
| Delivery re-check | `EmailService.sendNow` / dispatcher | Suppression, consent, lead disqualification re-evaluated inside the send transaction; failures downgrade to CANCELLED with `failureCode` |
| Idempotency | `email_messages.message_key` | Prevents duplicate transport hand-off |
| Bounce/reply handling | `EmailService.recordBounce/recordInbound` | Hard bounce auto-suppresses; replies stop sequence enrolments |
| Consent & suppression | `engagement/consent/*` | Junction-table-backed history, "latest wins" evaluation |
| Sequences | `engagement/sequence/*` | Versioned cadence, one active enrolment per lead (`active_key`), step runs unique per enrollment+step, scheduled engine, stop reasons |

## Migrations

`V2__engagement_email.sql` — smtp_profiles, email_templates, email_messages, email_events,
suppression_entries, consent_records, email_sequences, sequence_versions, sequence_steps,
sequence_enrollments, sequence_step_runs, campaigns, inbound_sync_state, unsubscribe_tokens.

## UI

`/engagement/inbox`, `/engagement/templates`, `/engagement/sequences`, `/engagement/smtp` — all with
loading/empty/error/success states, capability-gated actions and server-side errors surfaced verbatim.

## Verification

* Frontend: `npx tsc --noEmit` clean; `npm run build` exports 22 routes including the four engagement
  screens.
* Backend: **not compiled, not executed** — SMTP paths therefore have *never* been exercised against a
  real server.

## Known issues

1. Inbound IMAP polling is not implemented (`engagement/inbound` is empty); replies are recorded via
   the API/webhook path only.
2. Reply classification is not implemented.
3. Campaigns are schema-only.
4. Unsubscribe tokens table exists; the public unsubscribe endpoint is not implemented yet.

## Completion statement

Feature code, migrations, endpoints, UI and tests are authored. Release is **not** marked verified.
