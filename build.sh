#!/usr/bin/env bash
#
# build.sh — build the RemoteAndroid screen-sharing APK.
#
# Usage:
#   ./build.sh                       # assembleDebug  -> dist/remote_android-debug.apk
#   ./build.sh release               # assembleRelease -> dist/remote_android-release.apk
#   ./build.sh --clean               # clean first
#   ./build.sh --gen-cert            # also emit a build-time self-signed PKCS12 (TLS fallback)
#   ./build.sh --gen-cert --cert-ip 192.168.1.50 --cert-dns myphone.local
#
# Requirements (checked on startup):
#   - Java 17+ (AGP 8.x)
#   - Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT / ~/Android/Sdk)
#   - Gradle wrapper (./gradlew), created when the project is scaffolded
#
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

SCRIPT_NAME="$(basename "$0")"
VARIANT="debug"
DO_CLEAN=0
GEN_CERT=0
CERT_DNS=()
CERT_IP=()
CERT_PASS="remoteandroid"
CERT_OUT="app/src/main/res/raw/server.p12"
KEYSTORE_ALIAS="remoteandroid"
KEYSTORE_VALIDITY=3650
APK_DIR="dist"

# ---------------------------------------------------------------- helpers
have_tty() { [ -t 1 ]; }

info() { printf '%s\n' "$*"; }
ok()   { printf '%s\n' "$*"; }
warn() { printf '%s\n' "$*"; }
die()  { printf '%s\n' "$*" >&2; exit 1; }

if have_tty; then
  info() { printf '\033[1;34m[info]\033[0m %s\n' "$*"; }
  ok()   { printf '\033[1;32m[ ok ]\033[0m %s\n' "$*"; }
  warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*"; }
  die()  { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }
fi

usage() {
  cat <<EOF
Usage: $SCRIPT_NAME [OPTIONS] [VARIANT]

Build the RemoteAndroid APK.

VARIANT (optional; default: debug)
  debug     assembleDebug   (default signing key, no extra setup)
  release   assembleRelease (requires signingConfig in app/build.gradle.kts)

OPTIONS
  --clean            run Gradle clean before building
  --gen-cert         generate a self-signed PKCS12 for the build-time TLS fallback
                     and place it in $CERT_OUT
  --cert-dns NAME    SAN DNS entry (repeatable). default: localhost
  --cert-ip  IP      SAN IP entry  (repeatable). default: 127.0.0.1
  --cert-pass PASS   keystore password. default: remoteandroid
  -h, --help         show this help and exit
EOF
}

# ---------------------------------------------------------------- arg parsing
while [ $# -gt 0 ]; do
  case "$1" in
    debug|release) VARIANT="$1" ;;
    --clean)       DO_CLEAN=1 ;;
    --gen-cert)    GEN_CERT=1 ;;
    --cert-dns)    [ $# -ge 2 ] || die "--cert-dns requires a value"; CERT_DNS+=("$2"); shift ;;
    --cert-dns=*)  CERT_DNS+=("${1#*=}") ;;
    --cert-ip)     [ $# -ge 2 ] || die "--cert-ip requires a value";  CERT_IP+=("$2");  shift ;;
    --cert-ip=*)   CERT_IP+=("${1#*=}") ;;
    --cert-pass)   [ $# -ge 2 ] || die "--cert-pass requires a value"; CERT_PASS="$2"; shift ;;
    --cert-pass=*) CERT_PASS="${1#*=}" ;;
    -h|--help)     usage; exit 0 ;;
    *)             die "Unknown argument: $1 (see --help)" ;;
  esac
  shift
done

# ---------------------------------------------------------------- prereq: java
JAVA_BIN="$(command -v java || true)"
[ -n "$JAVA_BIN" ] || die "Java not found. Install a JDK 17+ (e.g. OpenJDK 17/21)."

JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | awk -F'[\".]' '/version/ {print $2; exit}')"
case "$JAVA_MAJOR" in
  1.*) JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | awk -F'[\".]' '/version/ {print $3; exit}')" ;;
esac
info "Java: $("$JAVA_BIN" -version 2>&1 | head -1)"
[ "${JAVA_MAJOR:-0}" -ge 17 ] || die "Java $JAVA_MAJOR too old; AGP needs JDK 17+."

# ---------------------------------------------------------------- prereq: sdk
find_sdk() {
  local candidates=(
    "${ANDROID_HOME:-}"
    "${ANDROID_SDK_ROOT:-}"
    "$HOME/Android/Sdk"
    /opt/android-sdk
    /usr/lib/android-sdk
  )
  local d
  for d in "${candidates[@]}"; do
    [ -n "$d" ] && [ -d "$d/platform-tools" ] && { printf '%s' "$d"; return 0; }
  done
  # fall back to what local.properties already says
  if [ -f local.properties ]; then
    local sdkp; sdkp="$(sed -n 's/^sdk.dir=//p' local.properties | head -1)"
    [ -n "$sdkp" ] && [ -d "$sdkp/platform-tools" ] && { printf '%s' "$sdkp"; return 0; }
  fi
  return 1
}

SDK_DIR="$(find_sdk || true)"
if [ -z "$SDK_DIR" ]; then
  die "Android SDK not found. Set ANDROID_HOME or install to ~/Android/Sdk."
fi
ok "Android SDK: $SDK_DIR"

# Ensure local.properties points at the SDK (Gradle + AGP read it).
if [ -f local.properties ] && grep -q '^sdk.dir=' local.properties; then
  sed -i.bak "s#^sdk.dir=.*#sdk.dir=${SDK_DIR}#" local.properties && rm -f local.properties.bak
else
  printf 'sdk.dir=%s\n' "$SDK_DIR" >> local.properties
fi
ok "local.properties -> sdk.dir=$SDK_DIR"

# ---------------------------------------------------------------- prereq: gradle
[ -x ./gradlew ] || die "Gradle wrapper (./gradlew) not found. Scaffold the project first (settings.gradle.kts + gradle wrapper)."

# ---------------------------------------------------------------- generate cert
generate_cert() {
  command -v keytool >/dev/null 2>&1 || die "keytool not found (part of the JDK)."
  mkdir -p "$(dirname "$CERT_OUT")"

  local dn="CN=RemoteAndroid, O=RemoteAndroid, C=US"
  [ ${#CERT_DNS[@]} -eq 0 ] && CERT_DNS=(localhost)
  [ ${#CERT_IP[@]}  -eq 0 ] && CERT_IP=(127.0.0.1)

  # Build SAN extension list.
  local san="dns:${CERT_DNS[0]}"
  local d i
  for d in "${CERT_DNS[@]:1}"; do san="$san,dns:$d"; done
  for i in "${CERT_IP[@]}";    do san="$san,ip:$i";  done

  info "Generating self-signed PKCS12 -> $CERT_OUT"
  rm -f "$CERT_OUT"
  keytool -genkeypair \
    -alias "$KEYSTORE_ALIAS" \
    -keyalg RSA -keysize 2048 \
    -validity "$KEYSTORE_VALIDITY" \
    -dname "$dn" \
    -keystore "$CERT_OUT" \
    -storetype PKCS12 \
    -storepass "$CERT_PASS" \
    -keypass "$CERT_PASS" \
    -ext "SAN=$san" \
    >/dev/null
  ok "Cert generated (SAN=$san, password=$CERT_PASS)"
}

if [ "$GEN_CERT" -eq 1 ]; then
  generate_cert
fi

# ---------------------------------------------------------------- build
GRADLE_TASKS=()
[ "$DO_CLEAN" -eq 1 ] && GRADLE_TASKS+=(clean)
case "$VARIANT" in
  debug)   GRADLE_TASKS+=(assembleDebug) ;;
  release) GRADLE_TASKS+=(assembleRelease) ;;
esac

info "Running: ./gradlew ${GRADLE_TASKS[*]}"
./gradlew --no-daemon "${GRADLE_TASKS[@]}"

# ---------------------------------------------------------------- collect apk
mkdir -p "$APK_DIR"
case "$VARIANT" in
  debug)   SRC="app/build/outputs/apk/debug/app-debug.apk" ;;
  release) SRC="app/build/outputs/apk/release/app-release.apk" ;;
esac
SRC="$(find app/build/outputs/apk -name "app-${VARIANT}.apk" | head -1 || true)"
[ -n "$SRC" ] || die "APK not found under app/build/outputs/apk."

DST="$APK_DIR/remote_android-${VARIANT}.apk"
cp -f "$SRC" "$DST"

# ---------------------------------------------------------------- summary
cat <<EOF

Build complete.
  variant : $VARIANT
  apk     : $DST

Install on a connected device:
  adb install -r "$DST"

Start sharing (run the app, tap "Start"), then open in a browser:
  https://<device-ip>:8443/
EOF

# Optional: adb is available -> offer to install if exactly one device is attached
# and we are running interactively.
if command -v adb >/dev/null 2>&1; then
  if adb devices 2>/dev/null | grep -q '^[0-9a-fA-F:.-]*[[:space:]]*device$' && have_tty; then
    info "Device detected — install now? (y/N)"
    read -r ans || true
    case "$ans" in
      y|Y|yes) adb install -r "$DST" ;;
      *) warn "Skipping install." ;;
    esac
  fi
fi