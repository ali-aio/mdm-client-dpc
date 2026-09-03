#!/usr/bin/env bash
# Set up the Android build toolchain on Arch Linux for building the MDM agent (headless / CLI).
# Idempotent: safe to re-run. Requires an AUR helper (yay).
#
#   ./scripts/setup-arch-toolchain.sh
#
# After it finishes, either open a new shell or `source ~/.bashrc` so ANDROID_HOME is set,
# then build with:  ./gradlew assembleDebug
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
SDK_PLATFORM="platforms;android-35"
SDK_BUILD_TOOLS="build-tools;35.0.0"

log() { printf '\033[1;35m[setup]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[setup] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

command -v yay >/dev/null 2>&1 || die "yay not found. Install an AUR helper first (e.g. pacman -S --needed base-devel git && build yay)."

log "Installing JDK 17, Gradle, Android cmdline-tools, and platform-tools via yay..."
yay -S --needed --noconfirm \
    jdk17-openjdk \
    gradle \
    android-sdk-cmdline-tools-latest \
    android-platform-tools

# The AUR android-sdk packages install under /opt/android-sdk; make it writable by the user
# so sdkmanager can add packages without root.
if [[ ! -d "$ANDROID_HOME" ]]; then
    log "Creating $ANDROID_HOME"
    sudo mkdir -p "$ANDROID_HOME"
fi
if [[ ! -w "$ANDROID_HOME" ]]; then
    log "Taking ownership of $ANDROID_HOME (needs sudo)"
    sudo chown -R "$USER":"$USER" "$ANDROID_HOME"
fi

export ANDROID_HOME
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

command -v sdkmanager >/dev/null 2>&1 || die "sdkmanager not on PATH. Check that android-sdk-cmdline-tools-latest installed to \$ANDROID_HOME/cmdline-tools/latest/bin."

log "Accepting SDK licenses..."
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true

log "Installing SDK packages: platform-tools, $SDK_PLATFORM, $SDK_BUILD_TOOLS ..."
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "$SDK_PLATFORM" "$SDK_BUILD_TOOLS"

# Persist env for future shells (idempotent append).
RC="$HOME/.bashrc"
if ! grep -q 'ANDROID_HOME=/opt/android-sdk' "$RC" 2>/dev/null; then
    log "Appending ANDROID_HOME + PATH to $RC"
    {
        echo ''
        echo '# Android SDK (added by mdm-agent setup-arch-toolchain.sh)'
        echo "export ANDROID_HOME=$ANDROID_HOME"
        echo 'export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin'
    } >> "$RC"
fi

# Point Gradle at the SDK for this project (gitignored).
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_PROPS="$REPO_ROOT/local.properties"
if [[ ! -f "$LOCAL_PROPS" ]]; then
    log "Writing $LOCAL_PROPS (sdk.dir)"
    echo "sdk.dir=$ANDROID_HOME" > "$LOCAL_PROPS"
fi

# Generate the Gradle wrapper jar so ./gradlew works without a system Gradle later.
if [[ ! -f "$REPO_ROOT/gradlew" ]]; then
    log "Generating Gradle wrapper..."
    ( cd "$REPO_ROOT" && gradle wrapper --gradle-version 8.9 )
fi

log "Done. Open a new shell (or 'source ~/.bashrc'), then run: ./gradlew assembleDebug"
