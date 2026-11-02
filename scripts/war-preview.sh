#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Serves the UI **exactly as it is packaged inside a WAR**, without a servlet container.
#
#   ./scripts/war-preview.sh dist/infeara-sales-os.FIXTURE.war 3000
#
# Why this exists: when no JDK/Tomcat can be installed (see
# docs/operations/sandbox-environment.md), this is the closest honest demonstration of the
# single-WAR delivery: it unpacks the artefact and serves the bytes that Tomcat would serve
# from `classpath:/static`, so the packaged UI, its hashed assets and its deep links are
# proven to work from the archive rather than from a source checkout.
#
# What it is NOT: no servlet container, no Spring context, no /api/v1, no database. Every
# API call fails with 404 and the UI shows its error state. It does not prove the backend
# runs — nothing can, here.
#
# The payload is unpacked into dist/.preview (inside the workspace, gitignored with dist/)
# rather than a mktemp directory. A temp directory is deleted with the process and is not
# part of a workspace snapshot: if the sandbox restarts while the server is still alive, a
# temp docroot turns the preview into a 404 shell. Keeping it in dist/ means a restart of
# this script is idempotent and the process can be managed independently of the shell.
# ---------------------------------------------------------------------------
set -euo pipefail

WAR="${1:-dist/infeara-sales-os.FIXTURE.war}"
PORT="${2:-${PORT:-3000}}"

[ -f "$WAR" ] || { echo "WAR not found: $WAR" >&2; exit 1; }

WORK="$(cd "$(dirname "$WAR")" && pwd)/.preview"
rm -rf "$WORK"
mkdir -p "$WORK"
python3 - "$WAR" "$WORK" <<'PY'
import sys, zipfile
war, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(war) as z:
    z.extractall(out)
PY

# A Spring Boot WAR keeps the UI in WEB-INF/classes/static (classpath:/static).
if [ -f "$WORK/WEB-INF/classes/static/index.html" ]; then
  ROOT="$WORK/WEB-INF/classes/static"
elif [ -f "$WORK/index.html" ]; then
  ROOT="$WORK"
else
  echo "This WAR contains no static UI (looked in WEB-INF/classes/static and the archive root)." >&2
  exit 1
fi

echo "---------------------------------------------------------------"
echo " WAR PAYLOAD PREVIEW  (no servlet container, no API, no database)"
echo "   war    : $WAR"
echo "   content: $ROOT"
echo "   served : http://0.0.0.0:${PORT}/"
echo "   /api/v1/* -> 404 here. For the real deployment:"
echo "     sudo ./scripts/host-app.sh   (Tomcat 11 + MariaDB, health-checked)"
echo "---------------------------------------------------------------"
exec python3 -m http.server "$PORT" --bind 0.0.0.0 --directory "$ROOT"
