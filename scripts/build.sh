#!/usr/bin/env sh
# Build/check loop. Delegates to the shared Android toolchain in /data/android — the one
# front door for building every APK on this box (baked android-builder:local image, JDK 21 +
# SDK; no JDK/SDK/Gradle on the host). See docs/tools.md.
#
# Usage: scripts/build.sh [gradle tasks...]   (default: unit tests + debug APK)
set -eu

REPO="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN="${ANDROID_TOOLCHAIN:-/data/android}"

if [ ! -x "$TOOLCHAIN/build.sh" ]; then
    echo "Shared Android toolchain not found at $TOOLCHAIN (set ANDROID_TOOLCHAIN)." >&2
    exit 1
fi

if [ "$#" -eq 0 ]; then
    set -- testDebugUnitTest assembleDebug
fi

exec "$TOOLCHAIN/build.sh" "$REPO" "$@"
