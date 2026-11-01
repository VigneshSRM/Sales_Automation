#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Serves ONLY the built UI (backend/src/main/resources/static) on 0.0.0.0:${PORT:-3000}.
#
# Purpose: design review of the screens where a JDK cannot be installed (this sandbox).
# There is NO backend behind it: every /api/v1 call fails, and the UI shows its
# network-error state. That is the honest result — it is not a hosted application.
#
# The real deployment is Apache Tomcat 11 serving the single WAR (API + UI):
#     ./scripts/build-war.sh && sudo ./scripts/host-app.sh
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UI_DIR="$ROOT_DIR/backend/src/main/resources/static"
PORT="${PORT:-3000}"

[ -f "$UI_DIR/index.html" ] || {
  echo "No UI build found at $UI_DIR" >&2
  echo "Build it first: cd frontend && npm ci && npm run build" >&2
  exit 1
}

cat <<BANNER
---------------------------------------------------------------
 UI-ONLY PREVIEW (no API, no backend, no database)
   serving : $UI_DIR
   on      : http://0.0.0.0:${PORT}/
   note    : /api/v1/* returns the static 404; the app reports
             NETWORK_UNAVAILABLE, which is expected here.
   use     : ./scripts/build-war.sh && sudo ./scripts/host-app.sh
             for the real Tomcat 11 deployment.
---------------------------------------------------------------
BANNER

# --bind 0.0.0.0 so the sandbox preview proxy can reach it. No directory listing.
exec python3 -m http.server "$PORT" --bind 0.0.0.0 --directory "$UI_DIR"
