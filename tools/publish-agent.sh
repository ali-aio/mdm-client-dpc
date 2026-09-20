#!/usr/bin/env bash
# Build the DPC agent and publish it to an MDM server as the hosted agent APK —
# what a factory-reset device downloads during QR provisioning, and what the
# dashboard's "Update agent" installs.
#
#   tools/publish-agent.sh -s https://mdm-stage.dev.aioapp.com -k "$ADMIN_API_KEY"
#   tools/publish-agent.sh -s ... -k ... --apk path/to/app-release.apk   # skip the build
#
# Signing: set KEYSTORE_FILE / KEYSTORE_PASS / KEY_ALIAS / KEY_PASS (keystore/signing.env
# holds them locally). An unsigned build is refused — Android will not install one, and
# an agent update must carry the same signing key as the build it replaces.
set -euo pipefail

SERVER=""; KEY=""; APK=""
while [ $# -gt 0 ]; do
  case "$1" in
    -s|--server) SERVER="${2%/}"; shift 2 ;;
    -k|--key) KEY="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    -h|--help) sed -n 2,11p "$0"; exit 0 ;;
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

echo "→ publishing $(stat -c %s "$APK") bytes to $SERVER"
curl -fsS -X POST "$SERVER/api/v1/agent-apk?name=$(basename "$APK")" \
  -H "X-API-Key: $KEY" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$APK"
echo
echo "→ devices on an older build now show \"Update agent\" on their device page."
