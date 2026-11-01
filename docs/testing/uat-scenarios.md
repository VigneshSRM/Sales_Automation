# User acceptance test scenarios

The specification mandates 20 end-to-end scenarios. Each one is mapped to its implementation state
so a UAT run does not start against unfinished features.

Legend: 🟢 implementable today · 🟡 partially implementable · 🔴 blocked by an unimplemented module.

| # | Scenario | Steps (abridged) | Expected outcome | State |
|---|---|---|---|---|
| 1 | Sign in and MFA | Rep signs in; admin must enrol TOTP; wrong code rejected | Session issued only after MFA for privileged roles; 5 failures lock the account | 🟢 |
| 2 | Capture a lead | Rep captures a prospect from a source with evidence | Lead created (idempotent), scored, owner assigned, `LEAD_CREATED` event published | 🟢 |
| 3 | Duplicate detected | Same email captured twice | Second capture returns the existing candidate as a strong duplicate; nothing merged | 🟢 |
| 4 | Deliberate merge | Manager merges the duplicate after preview | Single surviving lead, source archived, `merge_audits` row with field decisions | 🟢 |
| 5 | Qualification and override | Rep qualifies; director overrides the score | Factor breakdown stored; override requires a reason and is audited | 🟢 |
| 6 | Lead → opportunity | Conversion after discovery | Opportunity created at New Opportunity; lead becomes CONVERTED only through the conversion command | 🟡 (conversion command in progress) |
| 7 | Stage gate blocks | Rep moves deal to Closed Won with no decision contact | 422 with field error `decisionContactId`; stage unchanged | 🟢 |
| 8 | Backwards stage move | Manager moves Negotiation → Discovery with a reason | Allowed and recorded in stage history with the reason | 🟢 |
| 9 | Board and forecast | Director opens pipeline and forecast | Per-stage and per-currency totals reconcile with the list; definitions shown | 🟢 |
| 10 | SMTP profile setup | Ops creates a profile, tests it, activates it | Cannot activate before a passing test; secret never returned | 🟢 |
| 11 | Template approval | Marketing creates a template; director approves | Author cannot approve their own template | 🟢 |
| 12 | Compose and approve email | Rep composes from the template; manager approves; rep sends | Merge fields resolved; approval recorded; send blocked if the recipient is suppressed | 🟢 |
| 13 | Suppression wins | Marketing enrols a lead who unsubscribed earlier | Enrolment rejected with the suppression reason | 🟢 |
| 14 | Reply stops sequence | Invitee replies; engine runs | Reply recorded, enrolment stopped, no further step queued | 🟢 |
| 15 | Sending limit | Profile daily limit reached | Send blocked with `SENDING_LIMIT_REACHED`; message stays queued | 🟢 |
| 16 | Meeting booked via Calendly | Invitee books a slot | Webhook verified, meeting persisted, contact matched, opportunity associated, sequence stopped | 🔴 release 4 |
| 17 | AI research with approval | Agent researches a lead | Structured JSON validated against schema; output waits for approval before any side effect | 🔴 release 5 |
| 18 | Automation loop guard | Workflow triggers on stage change and updates the stage | Run depth/root-run guard prevents the loop; run recorded as THROTTLED | 🔴 release 6 |
| 19 | Proposal gate | Deal moves to Proposal Sent | Blocked until an approved proposal version has send evidence | 🔴 release 7 |
| 20 | Audit integrity | Auditor filters the audit log and verifies the chain | Hash chain recomputes cleanly; no edit/delete endpoint exists | 🟢 |

## How to run what is green

1. `docker compose -f deploy/docker-compose.dev.yml up -d`
2. Start the backend with `--spring.profiles.active=dev` and bootstrap admin credentials.
3. Start the frontend (`npm run dev`) and walk the scenarios above in order.
4. Record screenshots and API responses in the UAT log; each scenario becomes a Playwright spec in
   `frontend/e2e/` as it is automated.
