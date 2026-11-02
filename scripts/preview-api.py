#!/usr/bin/env python3
"""
Design-preview server: the packaged UI **plus** a fixture API on one port.

Why it exists
-------------
`scripts/war-preview.sh` serves the real static export, but the SPA boots by calling
`GET /api/v1/auth/me`; with no servlet container that call fails and the shell renders
"The workspace could not be loaded. The API may be unavailable." That is correct behaviour
and a useless thing to review. This server answers those calls with **sample data** so the
screens can be walked through on a machine with no JDK, no MariaDB and no Tomcat.

What it is NOT
--------------
It is not the product and it never ships: nothing here is packaged into the WAR, and every
response carries `X-Infeara-Preview: fixtures` plus a dismissible banner on every page.
No fixture performs a send, a mailbox read or an AI call — writes return a synthetic success
and do not persist anything. The verified, real deployment is the Tomcat 11 run in CI
(`.github/workflows/build-and-host-war.yml`, evidence on issue #1).

Usage
-----
    python3 scripts/preview-api.py --port 3000 --ui backend/src/main/resources/static
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import posixpath
import re
import urllib.parse
import uuid
from datetime import datetime, timedelta, timezone
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

NOW = datetime.now(timezone.utc)


def ts(days: float = 0, hours: float = 0) -> str:
    return (NOW + timedelta(days=days, hours=hours)).isoformat().replace("+00:00", "Z")


def rid() -> str:
    return str(uuid.uuid4())


CAPABILITIES = """
leads.read leads.create leads.update leads.assign leads.qualify leads.override_score leads.merge
leads.archive leads.export leads.reopen accounts.read accounts.create accounts.update
contacts.read contacts.create contacts.update opportunities.read opportunities.create
opportunities.update opportunities.transition opportunities.close opportunities.assign
activities.read activities.create tasks.manage email.read email.compose email.send email.approve
templates.manage sequences.manage campaigns.manage meetings.read meetings.manage proposals.read
proposals.manage proposals.approve proposals.send ai.read ai.run ai.approve workflows.read
reports.read reports.export users.manage audit.read jobs.manage notifications.read
smtp.manage catalog.manage email.accounts.manage email.accounts.read email.conversations.read
email.conversations.assign email.analyze email.rules.manage email.drafts.approve email.attachments.read
""".split()

USER_ID = "11111111-1111-4111-8111-111111111111"
WORKSPACE_ID = "22222222-2222-4222-8222-222222222222"


def page(items, size=25):
    return {"items": items, "page": 0, "size": size, "totalElements": len(items),
            "totalPages": 1 if items else 0, "sort": "", "appliedFilters": []}


# --------------------------------------------------------------------------- fixtures
LEADS = [
    {"id": "l-1001", "fullName": "Priya Raghavan", "companyName": "Astra Precision Works", "email": "priya@astra-precision.example",
     "phone": "+91 44 4000 1122", "status": "QUALIFIED", "score": 88, "scoreBand": "HOT", "ownerName": "Vignesh S",
     "ownerUserId": USER_ID, "sourceName": "Website enquiry", "primaryServiceName": "Industrial Automation",
     "updatedAt": ts(hours=-3), "rowVersion": 4},
    {"id": "l-1002", "fullName": "Daniel Okoye", "companyName": "Northwind Logistics", "email": "d.okoye@northwind.example",
     "phone": "+234 1 220 4410", "status": "CONTACTED", "score": 66, "scoreBand": "WARM", "ownerName": "Meera Iyer",
     "ownerUserId": "33333333-3333-4333-8333-333333333333", "sourceName": "Referral",
     "primaryServiceName": "Fleet Telematics", "updatedAt": ts(days=-1), "rowVersion": 2},
    {"id": "l-1003", "fullName": "Sofia Marchetti", "companyName": "Marchetti Foods", "email": "s.marchetti@marchetti.example",
     "phone": None, "status": "NEW", "score": 41, "scoreBand": "COOL", "ownerName": None,
     "ownerUserId": None, "sourceName": "Trade show", "primaryServiceName": "Cold Chain Monitoring",
     "updatedAt": ts(hours=-9), "rowVersion": 1},
    {"id": "l-1004", "fullName": "Arjun Nair", "companyName": "Vertex Components", "email": "arjun@vertex.example",
     "phone": "+91 80 4123 8890", "status": "QUALIFIED", "score": 79, "scoreBand": "WARM", "ownerName": "Vignesh S",
     "ownerUserId": USER_ID, "sourceName": "Inbound call", "primaryServiceName": "Industrial Automation",
     "updatedAt": ts(days=-2), "rowVersion": 6},
    {"id": "l-1005", "fullName": "Hannah Weiss", "companyName": "Weiss Robotics", "email": "h.weiss@weiss-robotics.example",
     "phone": None, "status": "NURTURE", "score": 32, "scoreBand": "COOL", "ownerName": "Meera Iyer",
     "ownerUserId": "33333333-3333-4333-8333-333333333333", "sourceName": "Webinar",
     "primaryServiceName": "Predictive Maintenance", "updatedAt": ts(days=-5), "rowVersion": 3},
]

OPPS = [
    {"id": "o-2001", "name": "Astra — line 3 retro-fit", "accountName": "Astra Precision Works", "amount": 4800000,
     "currency": "INR", "probability": 60, "weightedAmount": 2880000, "stageCode": "PROPOSAL_SENT",
     "stageName": "Proposal sent", "expectedCloseDate": ts(days=21), "ownerName": "Vignesh S",
     "rowVersion": 5, "ageDays": 18},
    {"id": "o-2002", "name": "Northwind — depot telematics", "accountName": "Northwind Logistics", "amount": 96000,
     "currency": "USD", "probability": 50, "weightedAmount": 48000, "stageCode": "DISCOVERY",
     "stageName": "Discovery", "expectedCloseDate": ts(days=40), "ownerName": "Meera Iyer",
     "rowVersion": 2, "ageDays": 9},
    {"id": "o-2003", "name": "Vertex — vision QA pilot", "accountName": "Vertex Components", "amount": 1750000,
     "currency": "INR", "probability": 35, "weightedAmount": 612500, "stageCode": "QUALIFICATION",
     "stageName": "Qualification", "expectedCloseDate": ts(days=55), "ownerName": "Vignesh S",
     "rowVersion": 1, "ageDays": 4},
]

STAGES = [
    ("NEW_OPPORTUNITY", "New opportunity", 1, "OPEN"), ("QUALIFICATION", "Qualification", 2, "OPEN"),
    ("DISCOVERY", "Discovery", 3, "OPEN"), ("PROPOSAL_SENT", "Proposal sent", 4, "OPEN"),
    ("NEGOTIATION", "Negotiation", 5, "OPEN"), ("WON", "Won", 6, "WON"), ("LOST", "Lost", 7, "LOST"),
]

CONVERSATIONS = [
    {"id": "c-3001", "subject": "Re: Line 3 retro-fit — lead time and commissioning", "mailboxAddress": "sales@infeara.example",
     "accountId": "a-9001", "leadId": "l-1001", "leadName": "Priya Raghavan", "contactId": None, "contactName": None,
     "assignedUserId": USER_ID, "assignedUserName": "Vignesh S", "queueKey": "inbound-sales", "status": "OPEN",
     "priority": "HIGH", "unread": True, "messageCount": 3, "lastMessageAt": ts(hours=-1), "matchReason": "MATCHED_LEAD",
     "awaitingApproval": True},
    {"id": "c-3002", "subject": "Depot telematics — pricing for 40 vehicles", "mailboxAddress": "sales@infeara.example",
     "accountId": "a-9001", "leadId": "l-1002", "leadName": "Daniel Okoye", "contactId": None, "contactName": None,
     "assignedUserId": "33333333-3333-4333-8333-333333333333", "assignedUserName": "Meera Iyer",
     "queueKey": "inbound-sales", "status": "OPEN", "priority": "NORMAL", "unread": True, "messageCount": 1,
     "lastMessageAt": ts(hours=-6), "matchReason": "MATCHED_LEAD", "awaitingApproval": False},
    {"id": "c-3003", "subject": "Automatic reply: out of office until Monday", "mailboxAddress": "sales@infeara.example",
     "accountId": "a-9001", "leadId": None, "leadName": None, "contactId": None, "contactName": None,
     "assignedUserId": None, "assignedUserName": None, "queueKey": None, "status": "OPEN", "priority": "LOW",
     "unread": False, "messageCount": 1, "lastMessageAt": ts(days=-1), "matchReason": "UNASSIGNED",
     "awaitingApproval": False},
]

MESSAGES = {
    "c-3001": [
        {"id": "m-1", "direction": "INBOUND", "fromEmail": "priya@astra-precision.example", "fromName": "Priya Raghavan",
         "toEmail": "sales@infeara.example", "subject": "Line 3 retro-fit — lead time and commissioning",
         "bodyText": "Hello,\n\nWe are planning to retro-fit line 3 in Q1. Could you share the typical lead time for the "
                     "automation package and whether commissioning is included?\n\nRegards,\nPriya",
         "bodyHtml": None, "receivedAt": ts(days=-2), "sentAt": None, "status": "RECEIVED",
         "messageIdHeader": "<inbound-1@astra-precision.example>", "inReplyToHeader": None, "aiStatus": "ANALYZED",
         "attachments": [{"id": "att-1", "filename": "line3-layout.pdf", "mediaType": "application/pdf",
                          "sizeBytes": 412000, "scanStatus": "SKIPPED"}]},
        {"id": "m-2", "direction": "OUTBOUND", "fromEmail": "sales@infeara.example", "fromName": "Vignesh S",
         "toEmail": "priya@astra-precision.example", "subject": "Re: Line 3 retro-fit — lead time and commissioning",
         "bodyText": "Hi Priya,\n\nThanks for the details. A first pass on lead time and commissioning scope — happy to "
                     "walk through the layout with your team this week.\n\nBest,\nVignesh",
         "bodyHtml": None, "receivedAt": None, "sentAt": ts(days=-1), "status": "SENT",
         "messageIdHeader": "<outbound-1@infeara.example>", "inReplyToHeader": "<inbound-1@astra-precision.example>",
         "aiStatus": "NONE", "attachments": []},
        {"id": "m-3", "direction": "INBOUND", "fromEmail": "priya@astra-precision.example", "fromName": "Priya Raghavan",
         "toEmail": "sales@infeara.example", "subject": "Re: Line 3 retro-fit — lead time and commissioning",
         "bodyText": "Thursday 4pm works. One more thing — can commissioning be done during the plant shutdown in "
                     "week 12? That is the only window we have.",
         "bodyHtml": None, "receivedAt": ts(hours=-1), "sentAt": None, "status": "RECEIVED",
         "messageIdHeader": "<inbound-2@astra-precision.example>", "inReplyToHeader": "<outbound-1@infeara.example>",
         "aiStatus": "ANALYZED", "attachments": []},
    ],
    "c-3002": [
        {"id": "m-4", "direction": "INBOUND", "fromEmail": "d.okoye@northwind.example", "fromName": "Daniel Okoye",
         "toEmail": "sales@infeara.example", "subject": "Depot telematics — pricing for 40 vehicles",
         "bodyText": "We need a price for 40 vehicles with a 3-year service contract. What does onboarding look like?",
         "bodyHtml": None, "receivedAt": ts(hours=-6), "sentAt": None, "status": "RECEIVED",
         "messageIdHeader": "<inbound-3@northwind.example>", "inReplyToHeader": None, "aiStatus": "QUEUED",
         "attachments": []},
    ],
    "c-3003": [{"id": "m-5", "direction": "INBOUND", "fromEmail": "noreply@vertex.example", "fromName": "Vertex Mailer",
                 "toEmail": "sales@infeara.example", "subject": "Automatic reply: out of office until Monday",
                 "bodyText": "I am out of the office until Monday and will reply on my return.",
                 "bodyHtml": None, "receivedAt": ts(days=-1), "sentAt": None, "status": "RECEIVED",
                 "messageIdHeader": "<auto-1@vertex.example>", "inReplyToHeader": None, "aiStatus": "ANALYZED",
                 "attachments": []}],
}

ANALYSIS = {
    "intent": "PRICING_ENQUIRY", "subIntent": "commercial_terms", "sentiment": "POSITIVE", "urgency": "HIGH",
    "buyingSignal": "STRONG", "confidence": 0.93,
    "recommendedAction": "Confirm the Thursday walkthrough and answer the shutdown-window question without quoting a "
                         "commissioning date until engineering signs off.",
    "replyDraft": "Hi Priya,\n\nThursday 4pm works for us. On the shutdown window: we plan commissioning around the "
                  "plant shutdown in week 12, and I will confirm the exact sequence with our engineering team before "
                  "committing to dates.\n\nBest,\nVignesh",
    "provider": "OPENROUTER", "model": "openai/gpt-4o-mini", "promptVersion": "email-analysis-v1",
    "validated": True, "createdAt": ts(hours=-1),
    "extracted": [{"field": "plant_shutdown", "value": "week 12", "evidence": "during the plant shutdown in week 12"},
                  {"field": "meeting", "value": "Thursday 16:00", "evidence": "Thursday 4pm works"}],
    "questions": ["Can commissioning be done during the week 12 shutdown window?"],
    "risks": ["Customer is anchoring a delivery date before engineering sign-off"],
}

MAILBOXES = [
    {"id": "a-9001", "name": "Sales inbox", "provider": "IMAP", "mailboxAddress": "sales@infeara.example",
     "fromName": "Infeara Sales", "inboundEnabled": True, "outboundEnabled": True,
     "smtpProfileName": "Primary SMTP", "host": "imap.infeara.example", "port": 993, "tlsMode": "IMPLICIT_TLS",
     "folder": "INBOX", "authMethod": "PASSWORD", "username": "sales@infeara.example", "status": "ACTIVE",
     "pollIntervalSeconds": 300, "lastHealthAt": ts(minutes=0) if False else ts(hours=-1), "lastError": None,
     "hasSecret": True, "hasOauthToken": False, "ownerName": "Vignesh S", "queueKey": "inbound-sales",
     "sync": {"uidValidity": 1719220001, "lastUid": 4821, "lastSyncAt": ts(hours=-1), "lastSuccessAt": ts(hours=-1),
              "consecutiveFailures": 0, "lastError": None, "reconciliationStatus": "IDLE"}, "rowVersion": 7},
    {"id": "a-9002", "name": "Support desk", "provider": "IMAP", "mailboxAddress": "support@infeara.example",
     "fromName": "Infeara Support", "inboundEnabled": True, "outboundEnabled": False,
     "smtpProfileName": None, "host": "imap.infeara.example", "port": 993, "tlsMode": "IMPLICIT_TLS",
     "folder": "INBOX", "authMethod": "PASSWORD", "username": "support@infeara.example", "status": "PENDING_TEST",
     "pollIntervalSeconds": 300, "lastHealthAt": None, "lastError": None, "hasSecret": True, "hasOauthToken": False,
     "ownerName": None, "queueKey": "support",
     "sync": {"uidValidity": None, "lastUid": 0, "lastSyncAt": None, "lastSuccessAt": None, "consecutiveFailures": 0,
              "lastError": None, "reconciliationStatus": "NOT_STARTED"}, "rowVersion": 1},
]

RULES = [
    {"id": "r-1", "name": "Meeting requests — auto-acknowledge", "priority": 10, "enabled": True, "mode": "APPROVAL_REQUIRED",
     "allowedIntents": ["MEETING_REQUEST"], "blockedIntents": [], "minConfidence": 0.9, "businessDays": [],
     "businessHoursStart": "09:00", "businessHoursEnd": "18:00", "delaySeconds": 0, "createdAt": ts(days=-6), "rowVersion": 2},
    {"id": "r-2", "name": "Support questions — human queue", "priority": 20, "enabled": True, "mode": "HUMAN",
     "allowedIntents": ["CUSTOMER_SUPPORT", "TECHNICAL_QUESTION"], "blockedIntents": [], "minConfidence": 0.8,
     "businessDays": [], "businessHoursStart": None, "businessHoursEnd": None, "delaySeconds": 0,
     "createdAt": ts(days=-6), "rowVersion": 1},
]

APPROVALS = [
    {"id": "ap-1", "messageId": "m-ai-1", "status": "PENDING", "reasonCode": "INTENT_REQUIRES_APPROVAL",
     "requestedByName": "AI policy engine", "decidedByName": None, "decidedAt": None, "rejectionReason": None,
     "expiresAt": ts(days=2), "createdAt": ts(hours=-1)},
]

EMAILS = [
    {"id": "e-1", "status": "PENDING_APPROVAL", "subject": "Astra — proposal pack for line 3",
     "toEmail": "priya@astra-precision.example", "toName": "Priya Raghavan", "leadId": "l-1001", "opportunityId": "o-2001",
     "smtpProfileName": "Primary SMTP", "scheduledAt": None, "sentAt": None, "sentByName": None, "approvedAt": None,
     "failureCode": None, "failureDetail": None, "attemptCount": 0, "createdAt": ts(hours=-4), "rowVersion": 1},
    {"id": "e-2", "status": "SENT", "subject": "Northwind — depot telematics overview",
     "toEmail": "d.okoye@northwind.example", "toName": "Daniel Okoye", "leadId": "l-1002", "opportunityId": "o-2002",
     "smtpProfileName": "Primary SMTP", "scheduledAt": None, "sentAt": ts(days=-1), "sentByName": "Meera Iyer",
     "approvedAt": ts(days=-1), "failureCode": None, "failureDetail": None, "attemptCount": 1,
     "createdAt": ts(days=-1), "rowVersion": 4},
]

TASKS = [
    {"id": "t-1", "title": "Send commissioning scope to Priya", "description": "Include the week 12 shutdown option.",
     "dueAt": ts(hours=20), "status": "OPEN", "priority": "HIGH", "relatedType": "LEAD", "relatedId": "l-1001",
     "ownerName": "Vignesh S", "overdue": False, "completedAt": None, "rowVersion": 1},
    {"id": "t-2", "title": "Chase Northwind onboarding questions", "description": None, "dueAt": ts(days=-1),
     "status": "OPEN", "priority": "NORMAL", "relatedType": "LEAD", "relatedId": "l-1002", "ownerName": "Meera Iyer",
     "overdue": True, "completedAt": None, "rowVersion": 2},
]

FEED = [
    {"id": "f-1", "type": "EMAIL", "relatedType": "LEAD", "relatedId": "l-1001",
     "subject": "Reply received: Line 3 retro-fit — lead time and commissioning", "actorName": None, "occurredAt": ts(hours=-1)},
    {"id": "f-2", "type": "STAGE_CHANGE", "relatedType": "OPPORTUNITY", "relatedId": "o-2001",
     "subject": "Proposal sent → moved by Vignesh S", "actorName": "Vignesh S", "occurredAt": ts(days=-1)},
    {"id": "f-3", "type": "LEAD_CREATED", "relatedType": "LEAD", "relatedId": "l-1003",
     "subject": "Lead captured from trade show", "actorName": "Meera Iyer", "occurredAt": ts(hours=-9)},
]


def currency(amount: int, count: int, code: str = "INR"):
    return {"currency": code, "amount": amount, "count": count}


DASHBOARD = {
    "periodStart": ts(days=-30)[:10], "periodEnd": ts(days=0)[:10], "timezone": "Asia/Kolkata",
    "forecast": {
        "periodStart": ts(days=-30)[:10], "periodEnd": ts(days=0)[:10],
        "openByCurrency": [currency(7510000, 3), currency(96000, 1, "USD")],
        "weightedByCurrency": [currency(3492500, 3), currency(48000, 1, "USD")],
        "wonByCurrency": [currency(1250000, 2)], "wonCount": 2, "lostCount": 1, "winRatePercent": 66.7,
        "averageWonValue": 625000, "wonCurrency": "INR",
        "definitions": ["Weighted value = amount x stage probability / 100",
                        "Open pipeline excludes WON and LOST stages"],
        "generatedAt": ts(),
    },
    "openPipeline": [currency(7510000, 3), currency(96000, 1, "USD")],
    "weightedPipeline": [currency(3492500, 3), currency(48000, 1, "USD")],
    "wonValue": [currency(1250000, 2)],
    "definitions": ["Money is never summed across currencies; each currency is reported separately.",
                    "Win rate = won / (won + lost) inside the selected period."],
    "limitations": ["Figures are computed from stage probability, not from a committed forecast.",
                    "No FX conversion is applied anywhere in this platform."],
    "generatedAt": ts(),
}


def board():
    cols = []
    for code, name, order, terminal in STAGES[:5]:
        items = [opportunity_summary(o) for o in OPPS if o["stageCode"] == code]
        cols.append({
            "stageCode": code, "stageName": name, "order": order, "terminalType": terminal,
            "totalAmount": sum(o["amount"] for o in items if o["currency"] == "INR"),
            "currency": "INR", "count": len(items), "opportunities": items,
        })
    return {"columns": cols, "generatedAt": ts(),
            "limitations": ["Board shows open stages only; totals are per-currency (INR shown)."]}


def conversation_detail(cid: str):
    conv = next((c for c in CONVERSATIONS if c["id"] == cid), CONVERSATIONS[0])
    has_analysis = conv["id"] == "c-3001"
    return {"summary": conv, "messages": MESSAGES.get(conv["id"], []),
            "analysis": ANALYSIS if has_analysis else None,
            "approval": APPROVALS[0] if has_analysis else None,
            "policy": {"mode": "APPROVAL_REQUIRED", "reasonCode": "INTENT_REQUIRES_APPROVAL",
                       "ruleName": "Meeting requests — auto-acknowledge", "mayAutoSend": False, "delaySeconds": 0}
            if has_analysis else None,
            "aiDraft": ANALYSIS["replyDraft"] if has_analysis else None, "rowVersion": 3}


STAGE_REF = {code: {"code": code, "name": name, "orderNo": order, "terminalType": terminal}
             for code, name, order, terminal in STAGES}


def opportunity_summary(opp: dict) -> dict:
    return {"id": opp["id"], "name": opp["name"], "accountName": opp["accountName"], "accountId": "acc-1",
            "stage": STAGE_REF.get(opp["stageCode"]), "amount": opp["amount"], "currency": opp["currency"],
            "weightedValue": opp["weightedAmount"], "probability": opp["probability"],
            "expectedCloseDate": opp["expectedCloseDate"], "closeDate": None,
            "ownerName": opp["ownerName"], "updatedAt": ts(hours=-4), "rowVersion": opp["rowVersion"]}


ACCOUNT_DETAIL = {
    "id": "acc-1", "name": "Astra Precision Works", "legalName": "Astra Precision Works Pvt Ltd",
    "website": "https://astra-precision.example", "domain": "astra-precision.example",
    "industryCode": "MANUFACTURING", "sizeBand": "500-1000", "headquarters": "Chennai, India",
    "description": "Precision machining supplier with three production lines.",
    "relationshipStatus": "CUSTOMER", "ownerUserId": USER_ID, "ownerName": "Vignesh S",
    "contacts": [{"id": "ct-1", "fullName": "Priya Raghavan", "title": "Head of Manufacturing Engineering",
                  "email": "priya@astra-precision.example", "phone": "+91 44 4000 1122",
                  "decisionRole": "TECHNICAL_EVALUATOR", "consentStatus": "GRANTED",
                  "suppressionStatus": "NONE", "accountId": "acc-1", "accountName": "Astra Precision Works",
                  "updatedAt": ts(hours=-3), "rowVersion": 1}],
    "createdAt": ts(days=-180), "updatedAt": ts(hours=-3), "rowVersion": 2,
}

CONTACT_DETAIL = {
    "id": "ct-1", "fullName": "Priya Raghavan", "title": "Head of Manufacturing Engineering",
    "email": "priya@astra-precision.example", "phone": "+91 44 4000 1122",
    "decisionRole": "TECHNICAL_EVALUATOR", "consentStatus": "GRANTED", "suppressionStatus": "NONE",
    "accountId": "acc-1", "accountName": "Astra Precision Works", "updatedAt": ts(hours=-3), "rowVersion": 1,
    "activities": FEED[:2],
}

OPPORTUNITY_DETAIL = dict(
    opportunity_summary(OPPS[0]),
    accountId="acc-1", description="Retro-fit of line 3 with new drives, safety PLC and commissioning.",
    decisionContactName="Priya Raghavan", lossReasonName=None,
    history=[{"id": "sh-1", "fromStage": "DISCOVERY", "toStage": "PROPOSAL_SENT", "changedByName": "Vignesh S",
              "changedAt": ts(days=-1), "reason": "Proposal issued after technical review",
              "ruleVersion": "pipeline-policy-v1"}],
    gates={"blocksMoveToWon": ["finalAmount", "decisionContact"], "blocksMoveToLost": []},
)

ROUTES: list[tuple[re.Pattern[str], object]] = [
    (re.compile(r"^/api/v1/catalog/industries$"), lambda m, q: [
        {"id": "ind-1", "code": "MANUFACTURING", "name": "Manufacturing", "active": True, "rowVersion": 1},
        {"id": "ind-2", "code": "LOGISTICS", "name": "Logistics", "active": True, "rowVersion": 1}]),
    (re.compile(r"^/api/v1/catalog/sources$"), lambda m, q: [
        {"id": "src-1", "code": "WEBSITE", "name": "Website enquiry", "active": True, "rowVersion": 1},
        {"id": "src-2", "code": "REFERRAL", "name": "Referral", "active": True, "rowVersion": 1}]),
    (re.compile(r"^/api/v1/catalog/loss-reasons$"), lambda m, q: [
        {"id": "lr-1", "code": "PRICE", "name": "Price", "active": True, "rowVersion": 1},
        {"id": "lr-2", "code": "TIMING", "name": "Timing", "active": True, "rowVersion": 1}]),
    (re.compile(r"^/api/v1/accounts/([\w-]+)$"), lambda m, q: ACCOUNT_DETAIL),
    (re.compile(r"^/api/v1/contacts/([\w-]+)$"), lambda m, q: CONTACT_DETAIL),
    (re.compile(r"^/api/v1/opportunities/([\w-]+)$"), lambda m, q: OPPORTUNITY_DETAIL),
    (re.compile(r"^/api/v1/records/[^/]+/[^/]+/activities$"), lambda m, q: page(FEED)),
    (re.compile(r"^/api/v1/emails/eligibility$"), lambda m, q: {
        "allowed": True, "consentStatus": "GRANTED", "blockers": [], "warnings": []}),
    (re.compile(r"^/api/v1/email-templates/([\w-]+)$"), lambda m, q: {
        "id": "tpl-1", "name": "Proposal follow-up", "subject": "Following up on {{opportunity_name}}",
        "bodyHtml": "<p>Hi {{contact_first_name}},</p><p>Following up on {{opportunity_name}}.</p>",
        "bodyText": "Hi {{contact_first_name}}, following up on {{opportunity_name}}.",
        "status": "APPROVED", "version": 3, "mergeFields": ["contact_first_name", "opportunity_name"],
        "updatedAt": ts(days=-2), "rowVersion": 3}),
    (re.compile(r"^/api/v1/users/([\w-]+)$"), lambda m, q: {
        "id": USER_ID, "displayName": "Vignesh S", "email": "vignesh@infeara.example", "status": "ACTIVE",
        "scopeType": "workspace", "mfaEnabled": True, "roles": ["SUPER_ADMIN"], "lastLoginAt": ts(hours=-1),
        "rowVersion": 1}),
    (re.compile(r"^/api/v1/auth/me$"), lambda m, q: {
        "userId": USER_ID, "workspaceId": WORKSPACE_ID, "displayName": "Vignesh S",
        "email": "vignesh@infeara.example", "scopeType": "workspace", "timezone": "Asia/Kolkata",
        "mfaSatisfied": True, "capabilities": CAPABILITIES, "sessionExpiresAt": ts(hours=8)}),
    (re.compile(r"^/api/v1/auth/logout$"), lambda m, q: {"status": "LOGGED_OUT"}),
    (re.compile(r"^/api/v1/dashboard$"), lambda m, q: DASHBOARD),
    (re.compile(r"^/api/v1/dashboard/activity$"), lambda m, q: page(FEED, 15)),
    (re.compile(r"^/api/v1/leads$"), lambda m, q: page(LEADS)),
    (re.compile(r"^/api/v1/leads/([\w-]+)$"), lambda m, q: lead_detail(m.group(1))),
    (re.compile(r"^/api/v1/accounts$"), lambda m, q: page([{
        "id": "acc-1", "name": "Astra Precision Works", "domain": "astra-precision.example", "industryName": "Manufacturing",
        "ownerName": "Vignesh S", "employeeCount": 850, "updatedAt": ts(hours=-3), "rowVersion": 2}])),
    (re.compile(r"^/api/v1/contacts$"), lambda m, q: page([{
        "id": "ct-1", "fullName": "Priya Raghavan", "email": "priya@astra-precision.example", "phone": "+91 44 4000 1122",
        "accountName": "Astra Precision Works", "title": "Head of Manufacturing Engineering", "consentStatus": "GRANTED",
        "updatedAt": ts(hours=-3), "rowVersion": 1}])),
    (re.compile(r"^/api/v1/opportunities/board$"), lambda m, q: board()),
    (re.compile(r"^/api/v1/tasks$"), lambda m, q: page(TASKS)),
    (re.compile(r"^/api/v1/notifications/unread-count$"), lambda m, q: {"count": 2}),
    (re.compile(r"^/api/v1/notifications$"), lambda m, q: page([
        {"id": "n-1", "type": "EMAIL_APPROVAL_REQUIRED", "severity": "WARNING", "title": "AI draft needs approval",
         "body": "Astra line 3 reply is waiting for a decision.", "link": "/engagement/mailbox", "readAt": None,
         "createdAt": ts(hours=-1)},
        {"id": "n-2", "type": "TASK_OVERDUE", "severity": "CRITICAL", "title": "Task overdue",
         "body": "Chase Northwind onboarding questions.", "link": "/tasks", "readAt": None, "createdAt": ts(hours=-6)}])),
    (re.compile(r"^/api/v1/audit-logs$"), lambda m, q: page([
        {"id": "au-1", "actorType": "USER", "actorUserId": USER_ID, "action": "EMAIL_DRAFT_APPROVED",
         "objectType": "EMAIL_MESSAGE", "objectId": "e-1", "outcome": "SUCCESS", "correlationId": rid(),
         "occurredAt": ts(hours=-2), "recordHash": "9f2c…a71b", "prevHash": "31d0…88ce"},
        {"id": "au-2", "actorType": "SYSTEM", "actorUserId": None, "action": "EMAIL_ANALYZED",
         "objectType": "EMAIL_MESSAGE", "objectId": "m-3", "outcome": "SUCCESS", "correlationId": rid(),
         "occurredAt": ts(hours=-1), "recordHash": "b410…0cc2", "prevHash": "9f2c…a71b"}])),
    (re.compile(r"^/api/v1/users$"), lambda m, q: page([
        {"id": USER_ID, "displayName": "Vignesh S", "email": "vignesh@infeara.example", "status": "ACTIVE",
         "scopeType": "workspace", "mfaEnabled": True, "roles": ["SUPER_ADMIN"], "lastLoginAt": ts(hours=-1),
         "rowVersion": 1},
        {"id": "33333333-3333-4333-8333-333333333333", "displayName": "Meera Iyer", "email": "meera@infeara.example",
         "status": "ACTIVE", "scopeType": "team", "mfaEnabled": True, "roles": ["SALES_MANAGER"],
         "lastLoginAt": ts(hours=-5), "rowVersion": 1}])),
    (re.compile(r"^/api/v1/roles$"), lambda m, q: [
        {"code": "SUPER_ADMIN", "name": "Super administrator", "description": "Full control", "capabilities": CAPABILITIES},
        {"code": "SALES_MANAGER", "name": "Sales manager", "description": "Team pipeline and approvals",
         "capabilities": ["leads.read", "opportunities.read", "email.approve"]}]),
    (re.compile(r"^/api/v1/catalog/stages$"), lambda m, q: [
        {"id": f"st-{i}", "code": c, "name": n, "orderNo": o, "terminalType": t, "allowSkip": False, "active": True,
         "rowVersion": 1} for i, (c, n, o, t) in enumerate(STAGES, start=1)]),
    (re.compile(r"^/api/v1/catalog/services$"), lambda m, q: [
        {"id": "sv-1", "code": "INDUSTRIAL_AUTOMATION", "name": "Industrial Automation", "division": "Automation",
         "description": "PLC, drives and line retro-fits", "active": True, "claimsApproved": True, "rowVersion": 1},
        {"id": "sv-2", "code": "FLEET_TELEMATICS", "name": "Fleet Telematics", "division": "Mobility",
         "description": "Vehicle tracking and depot analytics", "active": True, "claimsApproved": True, "rowVersion": 1}]),
    (re.compile(r"^/api/v1/emails$"), lambda m, q: page(EMAILS)),
    (re.compile(r"^/api/v1/emails/([\w-]+)$"), lambda m, q: {
        "message": EMAILS[0], "bodyHtml": "<p>Hello Priya,</p><p>Attached is the proposal pack for line 3.</p>",
        "bodyText": "Hello Priya, attached is the proposal pack for line 3.",
        "events": [{"eventType": "APPROVED", "detail": "Approved by Vignesh S", "occurredAt": ts(hours=-2)}],
        "eligibility": {"allowed": True, "consentStatus": "GRANTED", "blockers": [], "warnings": []}}),
    (re.compile(r"^/api/v1/email-templates$"), lambda m, q: page([
        {"id": "tpl-1", "name": "Proposal follow-up", "subject": "Following up on {{opportunity_name}}",
         "status": "APPROVED", "version": 3, "updatedAt": ts(days=-2), "rowVersion": 3}])),
    (re.compile(r"^/api/v1/sequences$"), lambda m, q: [
        {"id": "sq-1", "name": "Inbound lead nurture", "description": "5 touches over 12 days", "status": "ACTIVE",
         "activeVersion": 2, "stepCount": 5,
         "steps": [{"stepNo": 1, "dayOffset": 0, "templateName": "Welcome"}, {"stepNo": 2, "dayOffset": 2, "templateName": "Case study"}],
         "updatedAt": ts(days=-3), "rowVersion": 4}]),
    (re.compile(r"^/api/v1/sequences/enrollments$"), lambda m, q: page([])),
    (re.compile(r"^/api/v1/smtp-profiles$"), lambda m, q: [
        {"id": "smtp-1", "name": "Primary SMTP", "fromName": "Infeara Sales", "fromEmail": "sales@infeara.example",
         "replyToEmail": "sales@infeara.example", "host": "smtp.infeara.example", "port": 587, "tlsMode": "STARTTLS",
         "username": "sales@infeara.example", "dailyLimit": 500, "hourlyLimit": 80, "status": "ACTIVE",
         "lastTestedAt": ts(days=-1), "lastTestDetail": "220 smtp.infeara.example ESMTP ready", "sentToday": 12,
         "rowVersion": 2}]),
    (re.compile(r"^/api/v1/email/inbox$"), lambda m, q: page(CONVERSATIONS, 50)),
    (re.compile(r"^/api/v1/email/inbox/stats$"), lambda m, q: {
        "totalOpen": 3, "unread": 2, "unassigned": 1, "awaitingApproval": 1, "analysedToday": 2}),
    (re.compile(r"^/api/v1/email/conversations/([\w-]+)$"), lambda m, q: conversation_detail(m.group(1))),
    (re.compile(r"^/api/v1/email/approvals$"), lambda m, q: page(APPROVALS)),
    (re.compile(r"^/api/v1/email/rules$"), lambda m, q: RULES),
    (re.compile(r"^/api/v1/email/accounts$"), lambda m, q: MAILBOXES),
    (re.compile(r"^/api/v1/email/accounts/([\w-]+)/sync-state$"), lambda m, q: MAILBOXES[0]["sync"]),
    (re.compile(r"^/api/v1/admin/jobs$"), lambda m, q: page([
        {"id": "j-1", "jobType": "EMAIL_ANALYZE", "status": "COMPLETED", "attemptCount": 1, "priority": 50,
         "availableAt": ts(hours=-1), "lastError": None, "createdAt": ts(hours=-1)}])),
    (re.compile(r"^/api/v1/admin/integration-health$"), lambda m, q: [
        {"integration": "SMTP", "status": "CONFIGURED", "release": "3", "detail": "1 active profile"},
        {"integration": "IMAP inbound", "status": "PREVIEW", "release": "3b", "detail": "fixture preview only"},
        {"integration": "AI providers", "status": "NOT_CONFIGURED", "release": "3b",
         "detail": "no API key in this preview"},
        {"integration": "Calendly", "status": "NOT_CONFIGURED", "release": "4", "detail": "not implemented"}]),
    (re.compile(r"^/api/v1/search$"), lambda m, q: [
        {"type": "LEAD", "id": "l-1001", "title": "Priya Raghavan", "subtitle": "Astra Precision Works"},
        {"type": "ACCOUNT", "id": "acc-1", "title": "Astra Precision Works", "subtitle": "astra-precision.example"}]),
]


def lead_detail(lead_id: str):
    lead = next((l for l in LEADS if l["id"] == lead_id), LEADS[0])
    detail = dict(lead)
    detail.update({
        "firstName": lead["fullName"].split(" ")[0], "lastName": lead["fullName"].split(" ")[-1],
        "jobTitle": "Head of Manufacturing Engineering", "linkedinUrl": None, "website": "https://astra-precision.example",
        "country": "India", "region": "Tamil Nadu", "city": "Chennai", "notes": "Looking at a Q1 line retro-fit.",
        "scoreFactors": [
            {"factor": "Service fit", "weight": 15, "points": 15, "evidence": "Industrial automation in scope"},
            {"factor": "Budget signal", "weight": 20, "points": 18, "evidence": "Q1 capex approved"},
        ],
        "qualification": {"qualifiedAt": ts(days=-2), "qualifiedBy": "Vignesh S", "discoveryNotes": "Line 3 retro-fit"},
        "duplicates": [], "opportunities": [opportunity_summary(OPPS[0])],
        "activities": FEED[:2],
    })
    return detail


BANNER = """
<div id="infeara-preview-banner" role="status">
  <strong>DESIGN PREVIEW</strong>
  Sample data served by <code>scripts/preview-api.py</code>. No backend, no database, no mail, no AI —
  nothing here sends, reads or writes anything real. The verified deployment is the Tomcat 11 run in CI
  (issue&nbsp;#1).
  <button type="button" onclick="document.getElementById('infeara-preview-banner').remove()">Dismiss</button>
</div>
<style>
  #infeara-preview-banner{position:fixed;left:0;right:0;bottom:0;z-index:9999;display:flex;gap:10px;
    align-items:center;flex-wrap:wrap;font:13px/1.45 system-ui,sans-serif;padding:10px 16px;
    background:#fdf3e6;color:#7c5210;border-top:2px solid #d98b25;box-shadow:0 -2px 8px rgba(0,0,0,.08)}
  #infeara-preview-banner strong{color:#8a4b00;letter-spacing:.04em}
  #infeara-preview-banner code{background:#fff;padding:1px 5px;border-radius:4px;border:1px solid #f2ddba}
  #infeara-preview-banner button{margin-left:auto;background:#fff;border:1px solid #e0c9a6;
    color:#7c5210;border-radius:6px;padding:3px 10px;font:inherit;cursor:pointer}
  body{padding-bottom:64px}
</style>
"""


class Handler(SimpleHTTPRequestHandler):
    server_version = "InfearaPreview/1.0"

    def __init__(self, *args, ui_root: str, **kwargs):
        super().__init__(*args, directory=ui_root, **kwargs)

    def log_message(self, fmt, *args):  # keep the preview log readable
        if "/api/v1" in (args[0] if args else ""):
            super().log_message(fmt, *args)

    # ---------------------------------------------------------------- API
    def _send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Infeara-Preview", "fixtures")
        self.end_headers()
        self.wfile.write(body)

    def _api(self, method: str, path: str, query: dict) -> None:
        for pattern, factory in ROUTES:
            match = pattern.match(path)
            if match:
                data = factory(match, query)
                if method != "GET":
                    data = {"status": "PREVIEW_ONLY"}
                self._send_json(200, {"success": True, "data": data, "meta": {"requestId": rid()}})
                return
        if method != "GET":
            self._send_json(200, {"success": True, "data": {"status": "PREVIEW_ONLY"},
                                  "meta": {"requestId": rid()}})
            return
        # Unknown read: an empty page keeps every list screen in its honest empty state.
        self._send_json(200, {"success": True, "data": page([]), "meta": {"requestId": rid()}})

    # ---------------------------------------------------------------- static
    def _html_with_banner(self, fs_path: Path) -> None:
        html = fs_path.read_text(encoding="utf-8", errors="replace")
        if "infeara-preview-banner" not in html:
            html = html.replace("</body>", BANNER + "</body>") if "</body>" in html else html + BANNER
        body = html.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Infeara-Preview", "fixtures")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _translate(self):
        parsed = urllib.parse.urlsplit(self.path)
        return urllib.parse.unquote(parsed.path), urllib.parse.parse_qs(parsed.query)

    def do_GET(self):
        path, query = self._translate()
        if path.startswith("/api/"):
            self._api("GET", path, query)
            return
        root = Path(self.directory).resolve()
        candidate = (root / path.lstrip("/")).resolve()
        if root in candidate.parents or candidate == root:
            target = candidate / "index.html" if candidate.is_dir() else candidate
            if target.is_file() and target.suffix in (".html", ".htm"):
                self._html_with_banner(target)
                return
        super().do_GET()

    def do_HEAD(self):
        if self._translate()[0].startswith("/api/"):
            self._api("GET", self._translate()[0], self._translate()[1])
            return
        super().do_HEAD()

    def _write(self, method: str):
        path, query = self._translate()
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if path.startswith("/api/"):
            self._api(method, path, query)
            return
        self.send_error(405)

    do_POST = lambda self: self._write("POST")
    do_PATCH = lambda self: self._write("PATCH")
    do_PUT = lambda self: self._write("PUT")
    do_DELETE = lambda self: self._write("DELETE")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=3000)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--ui", default="backend/src/main/resources/static",
                        help="built Next.js export (npm run build in frontend/)")
    args = parser.parse_args()

    root = Path(args.ui).resolve()
    if not (root / "index.html").is_file():
        print(f"No UI build at {root}. Run: cd frontend && npm run build", flush=True)
        return 2

    print("----------------------------------------------------------------", flush=True)
    print(" DESIGN PREVIEW — sample data, no backend, no database", flush=True)
    print(f"   ui      : {root}", flush=True)
    print(f"   api     : /api/v1/** answered by fixtures in this file", flush=True)
    print(f"   serving : http://{args.host}:{args.port}/", flush=True)
    print("   Every page carries a preview banner; the real deployment is the", flush=True)
    print("   Tomcat 11 CI run (.github/workflows/build-and-host-war.yml).", flush=True)
    print("----------------------------------------------------------------", flush=True)

    server = ThreadingHTTPServer((args.host, args.port), partial(Handler, ui_root=str(root)))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
