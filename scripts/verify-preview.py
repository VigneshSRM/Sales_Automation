#!/usr/bin/env python3
"""
Regression check for `scripts/preview-api.py`.

The preview exists so a reviewer can open every screen without a JDK, MariaDB or
Tomcat. The failure mode it was written to remove was subtle: pages returned HTTP
200 while the shell rendered "The workspace could not be loaded", because the page
itself loaded but the API call it boots with did not. So this script asserts both
halves:

  1. every HTML page in the export returns 200, is served with the preview marker
     header, and carries the DESIGN PREVIEW banner;
  2. every fixture route declared in preview-api.py answers 200 with a well-formed
     ``{"success": true, "data": ...}`` envelope;
  3. a write route answers 200 ``PREVIEW_ONLY`` (i.e. it is honest about not
     persisting) instead of pretending to have saved something.

Usage
-----
    # start the server yourself, and point the checker at it
    python3 scripts/preview-api.py --port 3000 --ui backend/src/main/resources/static &
    python3 scripts/verify-preview.py --base http://127.0.0.1:3000

Exit code 0 = preview is coherent, 1 = at least one check failed.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATIC = ROOT / "backend/src/main/resources/static"
DEFAULT_BASE = "http://127.0.0.1:3000"

# The fixtures use these ids; a route pattern such as /leads/([\w-]+) is exercised
# with whichever placeholder matches its segment position best.
PLACEHOLDERS = {
    "id": "l-1001",
    "leads": "l-1001",
    "records": "LEAD",
    "accounts": "acc-1",
    "contacts": "ct-1",
    "opportunities": "o-2001",
    "conversations": "cv-1",
    "messages": "msg-1",
    "email-templates": "tpl-1",
    "smtp-profiles": "smtp-1",
    "sequences": "seq-1",
    "users": "11111111-1111-4111-8111-111111111111",
    "emails": "em-1",
    "approvals": "ap-1",
    "notifications": "nt-1",
    "jobs": "job-1",
    "rules": "rule-1",
    "tasks": "task-1",
    "roles": "role-1",
    "stages": "stage-1",
    "services": "svc-1",
}


def fetch(url: str, method: str = "GET") -> tuple[int, dict[str, str], bytes]:
    request = urllib.request.Request(url, method=method)
    if method != "GET":
        request.add_header("Content-Type", "application/json")
        request.data = b"{}"
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()
    except Exception as error:  # connection refused, DNS, timeout ...
        print(f"  !! {method} {url} -> {error}")
        return 0, {}, b""


def page_paths() -> list[str]:
    pages: list[str] = []
    for html in sorted(STATIC.rglob("*.html")):
        relative = html.relative_to(STATIC).as_posix()
        if relative.startswith("_next/"):
            continue
        if relative.endswith("/index.html"):
            pages.append("/" + relative[: -len("index.html")])
        elif relative == "index.html":
            pages.append("/")
        elif relative.startswith("404"):
            continue  # exported not-found document, not a real route
        else:
            pages.append("/" + relative)
    return pages


def sample_path(pattern: str) -> str:
    # `[^/]+` contains a slash inside its character class, which would break a naive
    # split, so normalise any single-segment wildcard to the fixture id form first.
    pattern = pattern.strip("^$").replace("[^/]+", "([\\w-]+)")
    segments = pattern.split("/")
    resolved: list[str] = []
    for index, segment in enumerate(segments):
        if "[^/]" in segment:
            previous = segments[index - 1] if index else ""
            resolved.append(PLACEHOLDERS.get(previous, "sample-1"))
        elif segment.startswith("(") or "\\w" in segment or "[" in segment:
            previous = segments[index - 1] if index else ""
            resolved.append(PLACEHOLDERS.get(previous, "sample-1"))
        elif ".*" in segment or "?" in segment:
            resolved.append("sample")
        else:
            resolved.append(segment)
    return "/".join(resolved)


def fixture_routes() -> list[str]:
    spec = importlib.util.spec_from_file_location("preview_api", ROOT / "scripts/preview-api.py")
    module = importlib.util.module_from_spec(spec)  # type: ignore[arg-type]
    assert spec and spec.loader
    spec.loader.exec_module(module)  # type: ignore[union-attr]
    paths: list[str] = []
    for pattern, _handler in getattr(module, "ROUTES", []):
        paths.append(sample_path(pattern.pattern))
    return sorted(set(paths))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=DEFAULT_BASE)
    arguments = parser.parse_args()
    base = arguments.base.rstrip("/")

    failures: list[str] = []
    checks = 0

    print(f"Preview manifest check against {base}\n")

    print("Pages (HTML export)")
    for path in page_paths():
        checks += 1
        status, headers, body = fetch(base + path)
        ok = status == 200
        marker = headers.get("X-Infeara-Preview", "")
        if not ok:
            failures.append(f"{path} -> HTTP {status}")
        elif marker != "fixtures":
            failures.append(f"{path} -> missing X-Infeara-Preview header")
        elif b"DESIGN PREVIEW" not in body:
            failures.append(f"{path} -> banner not injected")
        print(f"  {'ok  ' if ok else 'FAIL'} {path:<36} {status}")

    print("\nFixture API routes")
    for path in fixture_routes():
        checks += 1
        status, _headers, body = fetch(base + path)
        detail = ""
        if status != 200:
            detail = f"HTTP {status}"
        else:
            try:
                payload = json.loads(body)
            except json.JSONDecodeError as error:
                payload = None
                detail = f"invalid JSON ({error})"
            if payload is not None and not payload.get("success"):
                detail = "envelope success=false"
        if detail:
            failures.append(f"{path} -> {detail}")
        print(f"  {'ok  ' if not detail else 'FAIL'} {path:<44} {status or '-'}")

    print("\nHonest write handling")
    checks += 1
    status, _headers, body = fetch(base + "/api/v1/notifications/read-all", method="POST")
    try:
        marker = json.loads(body)["data"]["status"]
    except Exception:
        marker = ""
    if status != 200 or marker != "PREVIEW_ONLY":
        failures.append(f"POST /notifications/read-all -> {status} {marker!r}, expected PREVIEW_ONLY")
        print(f"  FAIL POST /api/v1/notifications/read-all {status} {marker!r}")
    else:
        print("  ok   POST /api/v1/notifications/read-all  200 PREVIEW_ONLY")

    print("\n" + "-" * 64)
    if failures:
        print(f"FAILED: {len(failures)} of {checks} checks")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print(f"PASSED: {checks}/{checks} preview checks")
    print("Note: this verifies the *preview*. Product behaviour is verified by CI")
    print("(.github/workflows/build-and-host-war.yml) against real Tomcat + MariaDB.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
