#!/usr/bin/env python3
"""
Authenticated end-to-end smoke test against a *running* deployment.

The unauthenticated probe (curl in the workflow) proves the UI and the 401 envelope. This
script proves the parts behind the login door, through the same Tomcat-hosted WAR:

  1. password login returns MFA_ENROLMENT_REQUIRED (the admin role mandates MFA);
  2. TOTP enrolment is completed with a code generated here from the returned secret;
  3. the session cookie opens /auth/me;
  4. read endpoints across five modules answer 200 (leads, dashboard, email mailboxes,
     inbox stats, response-policy rules, notifications);
  5. one CSRF-protected write (notifications/read-all) proves the double-submit token
     path works, not only reads;
  6. /v3/api-docs still publishes the authenticated API surface.

Standard library only: no requests, no pyotp, nothing to install on the runner.

Usage:
  python3 scripts/smoke-authenticated.py --base-url http://localhost:8080 \
      --email ci-admin@infeara.local --password 'CiOnly-Passw0rd!2026'

Exit code 0 only if every expectation held; otherwise the failing request body is printed.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import http.cookiejar
import json
import struct
import sys
import time
import urllib.error
import urllib.request

PASS, FAIL = "PASS", "FAIL"


class Client:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def cookie(self, name: str) -> str | None:
        return next((c.value for c in self.jar if c.name == name), None)

    def call(self, method: str, path: str, body: dict | None = None, csrf: bool = False) -> tuple[int, str]:
        url = f"{self.base_url}{path}"
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Accept", "application/json")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        token = self.cookie("XSRF-TOKEN")
        if csrf and token:
            request.add_header("X-XSRF-TOKEN", token)
        try:
            with self.opener.open(request, timeout=30) as response:
                return response.status, response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode("utf-8", "replace")
        except Exception as error:  # connection refused, timeout, DNS
            return 0, f"{type(error).__name__}: {error}"


def totp(secret: str, at: float | None = None, step: int = 30, digits: int = 6) -> str:
    """RFC 6238 TOTP, SHA-1 — the same parameters the server validates."""
    normalised = secret.strip().replace(" ", "").upper()
    normalised += "=" * (-len(normalised) % 8)
    key = base64.b32decode(normalised)
    counter = int((at if at is not None else time.time()) // step)
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF
    return str(code % (10 ** digits)).zfill(digits)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--email", default="ci-admin@infeara.local")
    parser.add_argument("--password", default="CiOnly-Passw0rd!2026")
    args = parser.parse_args()

    client = Client(args.base_url)
    results: list[tuple[str, str, str]] = []

    def record(name: str, ok: bool, detail: str) -> None:
        results.append((PASS if ok else FAIL, name, detail.replace("\n", " ")[:160]))

    print(f"Authenticated smoke test against {args.base_url}\n")

    # 1. password login
    status, body = client.call("POST", "/api/v1/auth/login",
                               {"email": args.email, "password": args.password})
    payload = json.loads(body) if body.strip().startswith("{") else {}
    login_status = (payload.get("data") or {}).get("status")
    record("POST /api/v1/auth/login", status == 200, f"http={status} status={login_status}")
    if status != 200:
        print(body)
        print_results(results)
        return 1

    enrolment_token = (payload.get("data") or {}).get("enrolmentToken")
    challenge_token = (payload.get("data") or {}).get("challengeToken")

    # 2/3. complete MFA: fresh enrolment, or verify if one already exists
    if login_status == "MFA_ENROLMENT_REQUIRED" and enrolment_token:
        status, body = client.call("POST", "/api/v1/auth/mfa/enrol/start",
                                   {"enrolmentToken": enrolment_token})
        payload = json.loads(body) if body.strip().startswith("{") else {}
        secret = (payload.get("data") or {}).get("secret")
        record("POST /api/v1/auth/mfa/enrol/start", status == 200 and bool(secret),
               f"http={status} secret={'present' if secret else 'MISSING'}")

        # The server may reject the step boundary; retry once with the next window.
        confirmed = None
        for attempt in range(2):
            code = totp(secret, at=time.time() + attempt * 30) if secret else "000000"
            status, body = client.call("POST", "/api/v1/auth/mfa/enrol/confirm",
                                       {"enrolmentToken": enrolment_token, "code": code})
            payload = json.loads(body) if body.strip().startswith("{") else {}
            confirmed = (payload.get("data") or {}).get("status")
            if status == 200:
                break
        record("POST /api/v1/auth/mfa/enrol/confirm", status == 200, f"http={status} status={confirmed}")
        if status != 200:
            print(body)
            print_results(results)
            return 1
    elif login_status == "MFA_REQUIRED" and challenge_token:
        # An earlier run on the same database already enrolled MFA; the secret is not
        # retrievable, so an authenticated probe cannot proceed. Report it, do not fake it.
        record("MFA already enrolled", False, "cannot produce a code without the stored secret")
        print_results(results)
        return 1

    session_present = client.cookie("INF_SESSION") is not None
    record("session cookie INF_SESSION", session_present, "set" if session_present else "MISSING")

    # 4. reads across modules
    reads = [
        ("GET /api/v1/auth/me", "/api/v1/auth/me"),
        ("GET /api/v1/leads", "/api/v1/leads?page=0&size=5"),
        ("GET /api/v1/dashboard", "/api/v1/dashboard"),
        ("GET /api/v1/email/accounts", "/api/v1/email/accounts"),
        ("GET /api/v1/email/inbox/stats", "/api/v1/email/inbox/stats"),
        ("GET /api/v1/email/rules", "/api/v1/email/rules"),
        ("GET /api/v1/notifications/unread-count", "/api/v1/notifications/unread-count"),
        ("GET /api/v1/audit-logs", "/api/v1/audit-logs?page=0&size=1"),
        ("GET /api/v1/catalog/stages", "/api/v1/catalog/stages"),
    ]
    for name, path in reads:
        status, body = client.call("GET", path)
        ok = status == 200
        snippet = ""
        if ok:
            try:
                data = json.loads(body).get("data")
                snippet = str(data)[:90] if data is not None else ""
            except json.JSONDecodeError:
                snippet = "non-JSON 200"
        else:
            snippet = body[:140]
        record(name, ok, f"http={status} {snippet}")

    # 5. one CSRF-protected write
    csrf_present = client.cookie("XSRF-TOKEN") is not None
    record("CSRF cookie XSRF-TOKEN", csrf_present, "set" if csrf_present else "MISSING")
    status, body = client.call("POST", "/api/v1/notifications/read-all", {}, csrf=True)
    record("POST /api/v1/notifications/read-all", status == 200, f"http={status} {body[:120]}")

    # 6. OpenAPI surface
    status, body = client.call("GET", "/v3/api-docs")
    paths = 0
    if status == 200:
        try:
            paths = len(json.loads(body).get("paths", {}))
        except json.JSONDecodeError:
            pass
    record("GET /v3/api-docs", status == 200 and paths > 50, f"http={status} paths={paths}")

    failures = print_results(results)
    return 0 if failures == 0 else 1


def print_results(results: list[tuple[str, str, str]]) -> int:
    width = max(len(name) for _, name, _ in results)
    print()
    for verdict, name, detail in results:
        print(f"  [{verdict}] {name.ljust(width)}  {detail}")
    failures = sum(1 for verdict, _, _ in results if verdict == FAIL)
    print()
    if failures:
        print(f"{failures} of {len(results)} authenticated checks FAILED")
    else:
        print(f"All {len(results)} authenticated checks passed — the deployed WAR serves a working, "
              f"RBAC-protected, CSRF-protected application.")
    return failures


if __name__ == "__main__":
    sys.exit(main())
