#!/usr/bin/env python3
"""
Self-test for scripts/verify-war.py.

A packaging contract is only trustworthy if the checker can fail. This script builds two
synthetic archives — one that matches the Tomcat 11 / single-WAR contract and one that
violates it in the ways that actually break a deployment — and asserts that the verifier
accepts the first and rejects the second with the expected messages.

It assembles the archives from the repository's *real* static UI export, *real* Flyway
migrations and *real* application.yml, so it also proves those artefacts sit at the paths
the WAR contract requires.

Run:  python3 scripts/verify-war-selftest.py
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATIC_DIR = ROOT / "backend/src/main/resources/static"
MIGRATIONS = ROOT / "backend/src/main/resources/db/migration"
CONFIG = ROOT / "backend/src/main/resources/application.yml"

RUNTIME_JARS = [
    "spring-boot-4.0.9.jar", "spring-core-7.0.10.jar", "spring-web-7.0.10.jar",
    "spring-webmvc-7.0.10.jar", "spring-context-7.0.10.jar", "spring-beans-7.0.10.jar",
    "spring-tx-7.0.10.jar", "spring-jdbc-7.0.10.jar", "spring-orm-7.0.10.jar",
    "spring-security-core-7.0.4.jar", "spring-security-web-7.0.4.jar",
    "spring-security-config-7.0.4.jar", "spring-data-jpa-4.0.5.jar",
    "hikaricp-6.3.3.jar", "HikariCP-6.3.3.jar", "mariadb-java-client-3.5.6.jar",
    "hibernate-core-7.1.4.Final.jar", "jakarta.persistence-api-3.2.0.jar",
    "jakarta.servlet-api-6.1.0.jar" , "flyway-core-11.13.2.jar",
    "flyway-mysql-11.13.2.jar", "jackson-databind-3.0.2.jar", "jackson-core-3.0.2.jar",
    "jackson-annotations-3.0.2.jar", "springdoc-openapi-starter-webmvc-ui-3.0.3.jar",
    "bcprov-jdk18on-1.78.1.jar", "logback-classic-1.5.19.jar", "slf4j-api-2.0.17.jar",
    "micrometer-core-1.15.5.jar", "validation-api-3.1.0.jar",
]
PROVIDED_JARS = ["tomcat-embed-core-11.0.11.jar", "tomcat-embed-el-11.0.11.jar",
                 "tomcat-embed-websocket-11.0.11.jar"]


def add_tree(archive: zipfile.ZipFile, source: Path, prefix: str) -> int:
    count = 0
    for path in sorted(source.rglob("*")):
        if path.is_file():
            archive.write(path, f"{prefix}/{path.relative_to(source).as_posix()}")
            count += 1
    return count


def build_golden(target: Path) -> None:
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF",
                         "Manifest-Version: 1.0\r\nStart-Class: com.infeara.sales.SalesApplication\r\n\r\n")
        # A real launcher class references SpringApplicationBuilder in configure().
        archive.writestr("WEB-INF/classes/com/infeara/sales/SalesApplication.class",
                         b"\xca\xfe\xba\xbe\x00\x00\x00\x41" + b"SpringApplicationBuilder" + b"\x00" * 64)
        archive.writestr("WEB-INF/web.xml",
                         "<web-app xmlns='https://jakarta.ee/xml/ns/jakartaee' version='6.1'/>")
        add_tree(archive, MIGRATIONS, "WEB-INF/classes/db/migration")
        archive.write(CONFIG, "WEB-INF/classes/application.yml")
        for jar in RUNTIME_JARS:
            archive.writestr(f"WEB-INF/lib/{jar}", b"PK\x05\x06" + b"\x00" * 18)
        for jar in PROVIDED_JARS:
            archive.writestr(f"WEB-INF/lib-provided/{jar}", b"PK\x05\x06" + b"\x00" * 18)
        # The real Next.js export, exactly where the build script puts it.
        moved = add_tree(archive, STATIC_DIR, "WEB-INF/classes/static")
        print(f"  golden WAR: {moved} UI file(s) from {STATIC_DIR.relative_to(ROOT)}")


def build_broken(target: Path) -> None:
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n")
        # No launcher class, no UI, no migrations.
        archive.writestr("WEB-INF/lib/tomcat-embed-core-11.0.11.jar", b"x")
        archive.writestr("WEB-INF/lib/tomcat-embed-el-11.0.11.jar", b"x")
        archive.writestr("WEB-INF/lib/junit-jupiter-5.11.4.jar", b"x")
        archive.writestr("WEB-INF/lib/node_modules/left-pad/index.js", b"x")
        # A compiled class whose name contains "secret" must NOT be reported as a secret file.
        archive.writestr("WEB-INF/classes/com/infeara/sales/security/SecretCipher.class", b"x")
        archive.writestr("WEB-INF/classes/application.yml",
                         "app:\n  security:\n    encryption-key: c3VwZXItc2VjcmV0LWtleS1tYXRlcmlhbA==\n")


def run_verifier(war: Path) -> tuple[int, str]:
    process = subprocess.run([sys.executable, str(ROOT / "scripts/verify-war.py"), str(war)],
                             capture_output=True, text=True)
    return process.returncode, process.stdout + process.stderr


def main() -> int:
    if not (STATIC_DIR / "index.html").is_file():
        print("Build the UI first: cd frontend && npm run build", file=sys.stderr)
        return 2

    work = Path(tempfile.mkdtemp(prefix="war-selftest-"))
    golden = work / "infeara-sales-os.war"
    broken = work / "broken.war"
    failures = 0
    try:
        print("Assembling fixtures")
        build_golden(golden)
        build_broken(broken)

        print("\nCase 1 — archive that matches the contract")
        code, output = run_verifier(golden)
        print(output)
        if code != 0:
            failures += 1
            print("EXPECTED exit 0, got", code)
        else:
            print("-> verifier accepted the conforming archive")

        print("\nCase 2 — archive that must be rejected")
        code, output = run_verifier(broken)
        print(output)
        required_failures = ["boot.launcher", "ui.required-pages", "flyway.present",
                             "deps.container-provided", "deps.no-test-libs",
                             "hygiene.no-build-output", "config.no-hardcoded-secrets"]
        missing = [name for name in required_failures if f"[FAIL] {name}" not in output]
        if code == 0:
            failures += 1
            print("EXPECTED a non-zero exit code, got 0")
        elif missing:
            failures += 1
            print("Verifier did not flag:", ", ".join(missing))
        else:
            print("-> verifier rejected the archive for every expected reason")
    finally:
        shutil.rmtree(work, ignore_errors=True)

    print()
    if failures:
        print(f"SELFTEST FAILED ({failures} problem(s))")
        return 1
    print("SELFTEST PASSED — the WAR contract checker accepts good archives and rejects bad ones.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
