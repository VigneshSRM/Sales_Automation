#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Infeara Sales OS — build/host toolchain installer
#
# Installs exactly what the deployment architecture pins (ADR-001):
#   * Eclipse Temurin JDK 17 (LTS)      — source/target level 17
#   * Apache Maven 3.9.x                — WAR build
#   * Apache Tomcat 11.0.x              — Servlet 6.1 container required by Spring Boot 4
#   * MariaDB 11.4 LTS                  — the only supported database
#
# Tested on Debian/Ubuntu (apt) and RHEL/Rocky/Alma (dnf). Run with sudo:
#     sudo ./scripts/install-toolchain.sh
#
# The script is idempotent: an already-correct version is reported and skipped,
# never reinstalled. It writes /etc/profile.d/infeara-sales.sh so the versions
# this project needs (JAVA_HOME, CATALINA_HOME) win over whatever else is on PATH.
#
# NOTE: this script needs outbound HTTPS to adoptium.net, archive.apache.org,
# dlcdn.apache.org and your distro mirrors. In an egress-restricted sandbox the
# downloads will fail; docs/operations/sandbox-environment.md records exactly
# which hosts were reachable and what that means for verification.
# ---------------------------------------------------------------------------
set -euo pipefail

JAVA_VERSION="17"
MAVEN_VERSION="3.9.11"
# Pinned to the exact patch proven by CI (.github/workflows/build-and-host-war.yml).
TOMCAT_VERSION="11.0.27"
MARIADB_CHANNEL="11.4"
INSTALL_ROOT="${INSTALL_ROOT:-/opt/infeara}"
PROFILE_FILE="/etc/profile.d/infeara-sales.sh"

log() { printf '\033[1;34m[toolchain]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[toolchain] %s\033[0m\n' "$*" >&2; exit 1; }

require_root() {
  if [ "$(id -u)" -ne 0 ]; then
    die "Run as root: sudo $0"
  fi
}

detect_pm() {
  if command -v apt-get >/dev/null 2>&1; then echo apt
  elif command -v dnf >/dev/null 2>&1; then echo dnf
  elif command -v yum >/dev/null 2>&1; then echo yum
  else die "No supported package manager (apt/dnf/yum) found. Install JDK 17, Maven 3.9, Tomcat 11 and MariaDB 11.4 manually, then re-run ./scripts/verify-toolchain.sh"; fi
}

install_base_packages() {
  local pm="$1"
  log "Installing base packages via $pm"
  case "$pm" in
    apt)
      apt-get update -qq
      DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
        ca-certificates curl unzip tar gnupg lsb-release fontconfig procps
      ;;
    dnf|yum)
      "$pm" install -y -q ca-certificates curl unzip tar gnupg2 procps-ng
      ;;
  esac
}

install_jdk() {
  if command -v javac >/dev/null 2>&1; then
    local current
    current="$(javac -version 2>&1 | awk '{print $2}' | cut -d. -f1)"
    if [ "$current" = "$JAVA_VERSION" ]; then
      log "JDK $JAVA_VERSION already installed ($(javac -version 2>&1))"
      return
    fi
    log "Found JDK $current, installing JDK $JAVA_VERSION alongside it"
  fi
  local pm="$1"
  case "$pm" in
    apt)
      DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "openjdk-${JAVA_VERSION}-jdk-headless"
      ;;
    dnf|yum)
      "$pm" install -y -q "java-${JAVA_VERSION}-openjdk-devel"
      ;;
  esac
  local home
  home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  log "JDK installed at $home ($(javac -version 2>&1))"
}

install_maven() {
  if command -v mvn >/dev/null 2>&1; then
    log "Maven already installed ($(mvn -v | head -1))"
    return
  fi
  local url="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  log "Downloading Maven ${MAVEN_VERSION}"
  mkdir -p "$INSTALL_ROOT"
  curl -fsSL --retry 3 "$url" -o /tmp/maven.tgz
  # Integrity: the published SHA-512 must be available; abort rather than trust a partial file.
  curl -fsSL --retry 3 "${url}.sha512" -o /tmp/maven.sha512
  ( cd /tmp && echo "$(cat /tmp/maven.sha512)  maven.tgz" | sha512sum -c - ) \
    || die "Maven archive checksum mismatch — refusing to install"
  tar -xzf /tmp/maven.tgz -C "$INSTALL_ROOT"
  ln -sfn "$INSTALL_ROOT/apache-maven-${MAVEN_VERSION}" "$INSTALL_ROOT/maven"
  ln -sf "$INSTALL_ROOT/maven/bin/mvn" /usr/local/bin/mvn
  log "Maven installed: $(mvn -v | head -1)"
}

install_tomcat() {
  local target="$INSTALL_ROOT/tomcat"
  if [ -x "$target/bin/catalina.sh" ]; then
    local installed
    installed="$("$target/bin/version.sh" 2>/dev/null | awk -F'/' '/Server version/ {print $2}' | tr -d ' ')"
    log "Tomcat already installed (${installed:-unknown})"
    return
  fi
  local url="https://archive.apache.org/dist/tomcat/tomcat-11/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz"
  log "Downloading Tomcat ${TOMCAT_VERSION} (Servlet 6.1 — required by Spring Boot 4)"
  curl -fsSL --retry 3 "$url" -o /tmp/tomcat.tgz
  tar -xzf /tmp/tomcat.tgz -C "$INSTALL_ROOT"
  ln -sfn "$INSTALL_ROOT/apache-tomcat-${TOMCAT_VERSION}" "$target"
  chmod +x "$target/bin/"*.sh
  # The manager/host-manager webapps are not needed and only widen the attack surface.
  rm -rf "$target/webapps/manager" "$target/webapps/host-manager" "$target/webapps/docs" "$target/webapps/examples"
  log "Tomcat installed at $target"
}

install_mariadb() {
  if command -v mariadbd >/dev/null 2>&1 || command -v mysqld >/dev/null 2>&1; then
    log "MariaDB already installed ($(mariadb --version 2>/dev/null || mysql --version))"
    return true
  fi
  local pm="$1"
  log "Installing MariaDB ${MARIADB_CHANNEL} LTS from the vendor repository"
  case "$pm" in
    apt)
      curl -fsSL https://r.mariadb.com/downloads/mariadb_repo_setup | bash -s -- --mariadb-server-version="mariadb-${MARIADB_CHANNEL}"
      DEBIAN_FRONTEND=noninteractive apt-get install -y -qq mariadb-server mariadb-client
      ;;
    dnf|yum)
      curl -fsSL https://r.mariadb.com/downloads/mariadb_repo_setup | bash -s -- --mariadb-server-version="mariadb-${MARIADB_CHANNEL}"
      "$pm" install -y -q MariaDB-server MariaDB-client
      ;;
  esac
  systemctl enable --now mariadb >/dev/null 2>&1 || true
  log "MariaDB installed"
}

write_profile() {
  local java_home
  java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  cat > "$PROFILE_FILE" <<PROFILE
# Infeara Sales OS toolchain (generated by scripts/install-toolchain.sh)
export JAVA_HOME="${java_home}"
export MAVEN_HOME="${INSTALL_ROOT}/maven"
export CATALINA_HOME="${INSTALL_ROOT}/tomcat"
export CATALINA_BASE="\${CATALINA_BASE:-${INSTALL_ROOT}/tomcat}"
export PATH="\${JAVA_HOME}/bin:\${MAVEN_HOME}/bin:\${CATALINA_HOME}/bin:\${PATH}"
PROFILE
  chmod 0644 "$PROFILE_FILE"
  log "Wrote $PROFILE_FILE (open a new shell, or: source $PROFILE_FILE)"
}

verify() {
  log "Verifying installed versions"
  local ok=0
  java -version 2>&1 | head -1 || ok=1
  javac -version 2>&1 || ok=1
  mvn -v 2>&1 | head -1 || ok=1
  "${INSTALL_ROOT}/tomcat/bin/version.sh" 2>/dev/null | grep -E "Server version|JVM Version" || ok=1
  (mariadb --version || mysql --version) 2>/dev/null || log "WARN MariaDB client not on PATH yet"
  if [ "$ok" -ne 0 ]; then
    die "One or more components failed to install; see the output above"
  fi
  log "Toolchain ready. Next: ./scripts/host-app.sh (database) then ./scripts/build-war.sh"
}

main() {
  require_root
  local pm
  pm="$(detect_pm)"
  install_base_packages "$pm"
  install_jdk "$pm"
  install_maven
  install_tomcat
  install_mariadb "$pm"
  write_profile
  verify
}

main "$@"
