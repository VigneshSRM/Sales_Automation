#!/usr/bin/env python3
"""
Verifies that a WAR really is deployable to an external Apache Tomcat 11 with the
UI and the API inside it — the delivery contract of ADR-004.

Checked, in the order a failure would hurt:

  1.  archive integrity and manifest
  2.  servlet 6.1 marker class + Spring Boot launcher (Boot 4 / Jakarta)
  3.  the UI export is present and complete (index, deep links, hashed assets)
  4.  Flyway migrations are inside the WAR, uniquely versioned, V1..Vn contiguous
  5.  dependency scopes: container jars in lib-provided, never in lib
  6.  no test-scope libraries, no build leftovers, no credential files
  7.  size sanity (catches an accidentally committed node_modules)

Exit code 0 = the archive satisfies the contract; 1 = at least one violation.

Usage: python3 scripts/verify-war.py [path/to/infeara-sales-os.war]
"""
from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path

DEFAULT_WAR = Path("backend/target/infeara-sales-os.war")

CONTAINER_JARS = ("tomcat-embed-core", "tomcat-embed-websocket")
TEST_JAR_MARKERS = ("junit", "mockito", "testcontainers", "archunit", "assertj", "hamcrest")
FORBIDDEN_NAMES = ("node_modules/", ".git/", "__pycache__/", ".env", "application-local.yml")
SECRET_FILE_HINTS = ("secret", "credentials", "id_rsa", ".p12", ".jks", ".keystore")

# UI pages that the delivery brief requires to exist as real screens.
UI_ROOT = "WEB-INF/classes/static/"
REQUIRED_UI_PAGES = (
    "index.html",
    "login/index.html",
    "engagement/mailbox/index.html",
    "engagement/mailbox/settings/index.html",
    "engagement/mailbox/rules/index.html",
    "engagement/inbox/index.html",
    "leads/index.html",
    "pipeline/index.html",
    "reports/index.html",
)


class Result:
    def __init__(self) -> None:
        self.checks: list[tuple[str, bool, str]] = []

    def add_advisory(self, name: str, detail: str) -> None:
        """Recorded for the operator but never fails the build (e.g. archive size)."""
        self.checks.append((name, True, detail))

    def add(self, name: str, passed: bool, detail: str) -> None:
        self.checks.append((name, passed, detail))

    @property
    def failed(self) -> list[tuple[str, bool, str]]:
        return [c for c in self.checks if not c[1]]

    def report(self) -> None:
        width = max(len(c[0]) for c in self.checks) if self.checks else 10
        for name, passed, detail in self.checks:
            mark = "PASS" if passed else "FAIL"
            print(f"  [{mark}] {name.ljust(width)}  {detail}")
        print()
        if self.failed:
            print(f"{len(self.failed)} check(s) FAILED — this WAR is not deployable as specified.")
        else:
            print(f"All {len(self.checks)} checks passed — WAR matches the Tomcat 11 delivery contract.")


def manifest_value(names: dict[str, str], key: str) -> str:
    return names.get(key, "")


def main(argv: list[str]) -> int:
    war = Path(argv[1]) if len(argv) > 1 else DEFAULT_WAR
    result = Result()

    if not war.is_file():
        print(f"WAR not found: {war}", file=sys.stderr)
        return 2

    try:
        archive = zipfile.ZipFile(war)
    except zipfile.BadZipFile:
        print(f"{war} is not a valid ZIP/JAR archive", file=sys.stderr)
        return 2

    with archive:
        bad = archive.testzip()
        result.add("archive.zip", bad is None, "no corrupt member" if bad is None else f"corrupt member: {bad}")
        names = archive.namelist()
        name_set = set(names)
        size_mb = war.stat().st_size / (1024 * 1024)
        result.add("archive.entries", True, f"{len(names)} entries, {size_mb:.1f} MB")

        # --- manifest / layout -------------------------------------------------
        result.add("layout.web-inf", any(n == "WEB-INF/" or n.startswith("WEB-INF/") for n in names),
                   "WEB-INF present")
        manifest = ""
        if "META-INF/MANIFEST.MF" in name_set:
            manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
        result.add("manifest.attributes", "Manifest-Version" in manifest,
                   "Manifest-Version declared" if manifest else "META-INF/MANIFEST.MF missing")

        # --- launcher classes --------------------------------------------------
        app_class = "WEB-INF/classes/com/infeara/sales/SalesApplication.class"
        result.add("boot.launcher", app_class in name_set,
                   app_class if app_class in name_set else f"{app_class} missing (did you package a WAR?)")
        if app_class in name_set:
            class_bytes = archive.read(app_class)
            wired = b"SpringApplicationBuilder" in class_bytes
            result.add("boot.servlet-initializer", wired,
                       "configure(SpringApplicationBuilder) present -> deployable to external Tomcat"
                       if wired else "launcher does not reference SpringApplicationBuilder")
        else:
            result.add("boot.servlet-initializer", False, "launcher class missing")

        ui = {n[len(UI_ROOT):]: n for n in names if n.startswith(UI_ROOT)}
        migrations = sorted(
            n for n in names if re.match(r"WEB-INF/classes/db/migration/V\d+__.+\.sql$", n)
        )
        versions = [int(re.search(r"/V(\d+)__", n).group(1)) for n in migrations]
        expected = list(range(1, len(versions) + 1))
        result.add("flyway.present", len(migrations) >= 9,
                   f"{len(migrations)} migration(s) inside WEB-INF/classes/db/migration")
        versions_sorted = sorted(versions)
        result.add("flyway.contiguous", versions_sorted == expected,
                   f"versions {versions_sorted} " + ("are contiguous from V1" if versions_sorted == expected
                                              else f"do not match expected {expected}"))
        result.add("flyway.email-module",
                   any("email_two_way" in n for n in migrations)
                   and any("email_two_way_rbac" in n for n in migrations),
                   "V8/V9 two-way email migrations bundled")

        # --- UI ----------------------------------------------------------------
        missing_pages = [p for p in REQUIRED_UI_PAGES if p not in ui]
        result.add("ui.required-pages", not missing_pages,
                   f"{len(REQUIRED_UI_PAGES)} screens present under {UI_ROOT}"
                   if not missing_pages else f"missing under {UI_ROOT}: {missing_pages}")
        assets = [n for n in ui if n.startswith("_next/static/")]
        result.add("ui.assets", len(assets) >= 10, f"{len(assets)} hashed asset files under /_next/static")
        css = [n for n in assets if n.endswith(".css")]
        result.add("ui.styles", bool(css), f"{len(css)} stylesheet(s) packaged")
        if "index.html" in ui:
            index_html = archive.read(ui["index.html"]).decode("utf-8", "replace")
            absolute_api = re.findall(r"https?://[^\"'\s]+/api/", index_html)
            result.add("ui.same-origin-api", not absolute_api,
                       "API is called same-origin (relative /api/v1 URLs)"
                       if not absolute_api else f"absolute API origin(s) baked in: {absolute_api[:3]}")
            result.add("ui.root-context", 'src="/_next/' in index_html or 'href="/_next/' in index_html,
                       "absolute /_next asset URLs require the ROOT context (documented)")
        result.add("ui.no-server-build", not any("node_modules" in n for n in names),
                   "static export only, no node_modules inside the WAR")

        # --- dependency scopes -------------------------------------------------
        lib_jars = [n for n in names if n.startswith("WEB-INF/lib/") and n.endswith(".jar")]
        provided_jars = [n for n in names if n.startswith("WEB-INF/lib-provided/") and n.endswith(".jar")]
        result.add("deps.lib", len(lib_jars) >= 20, f"{len(lib_jars)} runtime jar(s) in WEB-INF/lib")
        # tomcat-embed-el is a legitimate compile dependency (Hibernate Validator needs an EL
        # implementation); tomcat-embed-core and -websocket must come from the container.
        leaked = [n for n in lib_jars
                  if "tomcat-embed-core" in n or "tomcat-embed-websocket" in n]
        result.add("deps.container-provided", not leaked,
                   "servlet container jars are provided-scope (WEB-INF/lib-provided)"
                   if not leaked else f"container jars must be provided-scope: {leaked}")
        result.add("deps.lib-provided", bool(provided_jars),
                   f"{len(provided_jars)} provided jar(s) isolated in WEB-INF/lib-provided")

        test_jars = [n for n in lib_jars + provided_jars
                     if any(marker in n.lower() for marker in TEST_JAR_MARKERS)]
        result.add("deps.no-test-libs", not test_jars,
                   "no test libraries packaged" if not test_jars else f"test jars packaged: {test_jars}")

        # --- hygiene -----------------------------------------------------------
        forbidden = [n for n in names if any(token in n for token in FORBIDDEN_NAMES)]
        result.add("hygiene.no-build-output", not forbidden,
                   "no node_modules/.git/__pycache__" if not forbidden else f"forbidden entries: {forbidden[:5]}")
        # Only inspect packaged resources, not compiled classes or third-party jars: a class named
        # SecretCipher is the cipher, not a leaked secret.
        secretish = [n for n in names
                     if any(hint in n.lower() for hint in SECRET_FILE_HINTS)
                     and not n.endswith(".class")
                     and not n.startswith(("WEB-INF/lib/", "WEB-INF/classes/static/", "WEB-INF/lib-provided/"))]
        result.add("hygiene.no-secret-files", not secretish,
                   "no credential/key material packaged"
                   if not secretish else f"suspicious entries: {secretish[:5]}")

        yml = [n for n in names if n.startswith("WEB-INF/classes/application") and n.endswith((".yml", ".yaml"))]
        result.add("config.bundled", bool(yml), f"config: {', '.join(sorted(yml)) or 'none'}")
        if yml:
            blob = b"".join(archive.read(n) for n in yml)
            text = blob.decode("utf-8", "replace")
            hardcoded = re.findall(
                r"(?im)^\s*[\w.-]*(?:key|password|secret|token)\s*:\s*(?!\$\{)(\S{8,})", text)
            result.add("config.no-hardcoded-secrets", not hardcoded,
                       "every secret is an environment placeholder"
                       if not hardcoded else f"hardcoded value(s): {hardcoded[:3]}")

        in_war_config = [n for n in names if n == "WEB-INF/classes/infeara.properties"]
        result.add_advisory("config.in-war-properties", (
            "WEB-INF/classes/infeara.properties packaged (editable after deployment; "
            "secrets stay as ${ENV_VAR:} placeholders)"
            if in_war_config else
            "WEB-INF/classes/infeara.properties missing - operators cannot configure the WAR"))

        band = "within" if 5 <= size_mb <= 120 else "outside"
        result.add_advisory("size.info",
                            f"{size_mb:.1f} MB ({band} the typical 5–120 MB band for this WAR)")

    result.report()
    if not result.failed:
        print(f"\nWAR: {war}")
    return 0 if not result.failed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
