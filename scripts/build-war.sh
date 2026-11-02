#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Builds the single deployable artefact: infeara-sales-os.war
#
#   frontend (Next.js static export)  ->  backend/src/main/resources/static/
#   backend  (Spring Boot WAR)        ->  backend/target/infeara-sales-os.war
#   contract verification             ->  scripts/verify-war.py
#
# The UI and the API are served by the same Tomcat 11 instance from the same WAR,
# so there is one process, one origin and no CORS in production (ADR-004).
#
# Usage:
#   ./scripts/build-war.sh              # tests skipped (fast)
#   ./scripts/build-war.sh --with-tests # runs JUnit + Flyway/Testcontainers ITs
#   ./scripts/build-war.sh --skip-ui    # reuse the already-exported UI
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
FRONTEND_DIR="$ROOT_DIR/frontend"
DIST_DIR="$ROOT_DIR/dist"

with_tests=0
skip_ui=0
for arg in "$@"; do
  case "$arg" in
    --with-tests) with_tests=1 ;;
    --skip-ui) skip_ui=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

log() { printf '\033[1;34m[build-war]\033[0m %s\n' "$*"; }

log "Checking toolchain"
"$ROOT_DIR/scripts/verify-toolchain.sh" || {
  echo >&2
  echo "The pinned toolchain is not installed. Run: sudo ./scripts/install-toolchain.sh" >&2
  echo "In an egress-restricted environment this is expected; see docs/operations/sandbox-environment.md" >&2
  exit 1
}

if [ "$skip_ui" -eq 0 ]; then
  log "Building the Next.js static export"
  cd "$FRONTEND_DIR"
  if [ ! -d node_modules ]; then
    npm ci
  fi
  # next build + scripts/export-to-backend.mjs, which copies out/ into
  # backend/src/main/resources/static so the WAR contains the UI.
  npm run build
fi

log "Packaging the WAR"
cd "$BACKEND_DIR"
if [ "$with_tests" -eq 1 ]; then
  mvn -B clean package
else
  mvn -B clean package -DskipTests
fi

war="$BACKEND_DIR/target/infeara-sales-os.war"
[ -f "$war" ] || { echo "WAR not produced at $war" >&2; exit 1; }

log "Verifying the packaging contract"
python3 "$ROOT_DIR/scripts/verify-war.py" "$war"

mkdir -p "$DIST_DIR"
cp "$war" "$DIST_DIR/infeara-sales-os.war"
# Tomcat derives the context path from the WAR file name; the UI's absolute asset
# URLs require the ROOT context, so ship a ROOT.war alongside it.
cp "$war" "$DIST_DIR/ROOT.war"

log "Artefacts:"
ls -lh "$DIST_DIR/infeara-sales-os.war" "$DIST_DIR/ROOT.war" | awk '{print "  " $9 " (" $5 ")"}'
log "Deploy with: sudo ./scripts/host-app.sh"
