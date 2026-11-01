# Release 2 — CRM core

**Scope:** leads, duplicate detection, scoring, accounts, contacts, pipeline/opportunities,
activities and tasks.

## Implemented

| Area | Code | Notes |
|---|---|---|
| Lead capture/update/archive | `crm/lead/LeadService` | `Idempotency-Key`, optimistic locking, audit + outbox events |
| Lifecycle state machine | `crm/lead/LeadStatus` + service | Only the allowed graph transitions; `CONVERTED` only via conversion |
| Duplicate detection | `crm/dedupe/DuplicateService` | Exact email/phone → strong; domain+company → candidate; never auto-merges |
| Merge with audit | `LeadService.merge`, `MergeAudit` | Two-step preview/commit, field decisions persisted |
| Scoring | `crm/scoring/ScoringService` | 7 factors → 100, bands, versioned model row, history per calculation |
| Score override | `LeadService.overrideScore` | Reason mandatory, audited |
| Accounts / contacts | `crm/account/*`, `crm/contact/*` | Normalised name/domain/email/phone columns |
| Pipeline & opportunities | `sales/pipeline/OpportunityService` | 9 stages, gates, board, forecast, stage history with `ruleVersion` |
| Board & forecast | `OpportunityService.board/forecast` | Per-currency totals, no FX summation |
| Activities / tasks | `crm/activity/*`, `crm/task/*` | Append-only timeline; overdue detection |

## Migrations

`V1__core_schema.sql` (leads, lead_duplicates, lead_score_history, merge_audits, accounts, contacts,
opportunities, stage_history, activities, tasks).

## Verification

* `tools/schema_report.py`: 42 entities / 75 tables, no column mismatches for mapped tables.
* `tools/static_audit.py`: repository methods called by services all resolve.
* **Not compiled, not executed.**

## Known issues

1. Lead → opportunity conversion is implemented in the service layer but not exposed as its own
   endpoint; the UI converts via opportunity creation.
2. Fuzzy duplicate matching uses a normalised-string comparison rather than a phonetic algorithm;
   documented as a deliberate trade-off (deterministic, explainable, no false merges).
3. Board loads at most the workspace's open + recently updated deals; no server-side column paging
   yet (documented limitation returned in the payload).

## Completion statement

Code, migrations, endpoints and UI authored; verification pending a JDK.
