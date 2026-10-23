#!/usr/bin/env bash
# Build the DPC agent and publish it to an MDM server as the hosted agent APK —
# what a factory-reset device downloads during QR provisioning, and what the
# dashboard's "Update agent" installs.
#
#   tools/publish-agent.sh -s https://mdm-stage.dev.aioapp.com -k "$ADMIN_API_KEY"
#   tools/publish-agent.sh -s ... -k ... --apk path/to/app-release.apk   # skip the build
#   tools/publish-agent.sh -s ... -k ... --changelog "What changed"       # name it yourself
#
# The changelog (what the dashboard's MDM clients tab shows against the version) defaults to
# the subject of the last commit that changed app/build.gradle.kts, i.e. the version bump,
# without its "dpc 0.9.1 (code 33): " lead. Commit the bump before publishing.
#
# Signing: set KEYSTORE_FILE / KEYSTORE_PASS / KEY_ALIAS / KEY_PASS (keystore/signing.env
# holds them locally). An unsigned build is refused — Android will not install one, and
# an agent update must carry the same signing key as the build it replaces.
set -euo pipefail

SERVER=""; KEY=""; APK=""; CHANGELOG_IN=""
while [ $# -gt 0 ]; do
  case "$1" in
    -s|--server) SERVER="${2%/}"; shift 2 ;;
    -k|--key) KEY="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --changelog) CHANGELOG_IN="$2"; shift 2 ;;
    -h|--help) sed -n 2,16p "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$SERVER" ] && [ -n "$KEY" ] || { echo "need -s SERVER and -k ADMIN_API_KEY (see -h)" >&2; exit 2; }

cd "$(dirname "$0")/.."

if [ -z "$APK" ]; then
  [ -n "${KEYSTORE_FILE:-}" ] || { echo "KEYSTORE_FILE is unset — source keystore/signing.env first" >&2; exit 2; }
  echo "→ building release APK"
  ./gradlew :app:assembleRelease -PmdmServerUrl="$SERVER" --console=plain -q
  APK=app/build/outputs/apk/release/app-release.apk
fi
[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 1; }

# A build with no signature installs nowhere, so catch it here rather than on the device.
if command -v apksigner >/dev/null; then
  apksigner verify "$APK" >/dev/null 2>&1 || { echo "$APK is not signed" >&2; exit 1; }
fi

CHANGELOG_TEXT="$CHANGELOG_IN"
if [ -z "$CHANGELOG_TEXT" ]; then
  CHANGELOG_TEXT=$(git log -1 --pretty=%s -- app/build.gradle.kts 2>/dev/null | sed -E 's/^dpc [^ ]+( \(code [0-9]+\))?: *//')
  echo "→ changelog from the version commit: \"$CHANGELOG_TEXT\""
fi
CHANGELOG=$(printf '%s' "$CHANGELOG_TEXT" | python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.stdin.read().strip()))')

echo "→ publishing $(stat -c %s "$APK") bytes to $SERVER"
# A 502/503/504 is the server being recreated by a deploy: come back shortly. A 4xx (bad
# key, rejected file) is a real answer and is not retried.
OUT=$(mktemp)
for attempt in 1 2 3 4 5; do
  code=$(curl -sS -o "$OUT" -w '%{http_code}' -X POST \
    "$SERVER/api/v1/agent-apk?slot=dpc&name=$(basename "$APK")&changelog=$CHANGELOG" \
    -H "X-API-Key: $KEY" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@$APK") || code=000
  case "$code" in
    2*) cat "$OUT"; rm -f "$OUT"; break ;;
    502|503|504|000) echo "→ server answered $code, retrying in 20 s ($attempt/5)" >&2; sleep 20 ;;
    *) cat "$OUT" >&2; rm -f "$OUT"; echo "publish failed: HTTP $code" >&2; exit 1 ;;
  esac
  [ "$attempt" = 5 ] && { rm -f "$OUT"; echo "publish failed: server kept answering $code" >&2; exit 1; }
done
echo
echo "→ devices on an older build now show \"Update agent\" on their device page."
