#!/usr/bin/env bash
# Verifies the host has exactly the versions this project pins (ADR-001) and fails loudly
# when they do not. Safe to run without sudo, and safe to run in CI.
set -uo pipefail

status=0
note() { printf '%-14s %s\n' "$1" "$2"; }
fail() { printf '%-14s \033[1;31mFAIL\033[0m %s\n' "$1" "$2"; status=1; }
ok()   { printf '%-14s \033[1;32mOK\033[0m   %s\n' "$1" "$2"; }

check_cmd() {
  local name="$1" cmd="$2"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    fail "$name" "not on PATH"
    return 1
  fi
  return 0
}

if check_cmd java java; then
  java_major="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
  if [ "$java_major" = "17" ]; then ok java "$(java -version 2>&1 | head -1)"; else fail java "expected 17, found $java_major"; fi
fi

if check_cmd javac javac; then
  ok javac "$(javac -version 2>&1)"
else
  fail javac "no compiler — a JRE alone cannot build this WAR"
fi

if check_cmd maven mvn; then
  if mvn -v 2>/dev/null | head -1 | grep -qE 'Apache Maven 3\.(9|[1-9][0-9])'; then
    ok maven "$(mvn -v | head -1)"
  else
    fail maven "expected 3.9+, found $(mvn -v | head -1)"
  fi
fi

tomcat_home="${CATALINA_HOME:-/opt/infeara/tomcat}"
if [ -x "$tomcat_home/bin/version.sh" ]; then
  version_line="$("$tomcat_home/bin/version.sh" 2>/dev/null | grep 'Server version' || true)"
  if echo "$version_line" | grep -qE 'Apache Tomcat/11|Apache Tomcat/11\.[0-9]'; then
    ok tomcat "$version_line"
  else
    fail tomcat "expected Tomcat 11 (Servlet 6.1), found: ${version_line:-unknown}"
  fi
else
  fail tomcat "not found at $tomcat_home (set CATALINA_HOME)"
fi

if command -v mariadb >/dev/null 2>&1 || command -v mysql >/dev/null 2>&1; then
  ok mariadb "$((mariadb --version || mysql --version) 2>/dev/null)"
else
  fail mariadb "client not on PATH"
fi

if command -v node >/dev/null 2>&1; then
  node_major="$(node -v | tr -d 'v' | cut -d. -f1)"
  if [ "$node_major" -ge 20 ]; then ok node "$(node -v)"; else fail node "expected Node 20+, found $(node -v)"; fi
fi

echo
if [ "$status" -eq 0 ]; then
  note result "toolchain matches the pinned stack"
else
  note result "toolchain incomplete — run sudo ./scripts/install-toolchain.sh"
fi
exit "$status"
