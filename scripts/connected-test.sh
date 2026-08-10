#!/usr/bin/env sh
# Run the instrumented (androidTest) suite on the shared /data/android emulator.
#
# Gradle's own `connectedDebugAndroidTest` can't see the emulator — it runs adb inside
# the throwaway build container, a different adb world than the in-container emulator
# ("No connected devices!"). This delegates to the toolchain's connected-test.sh, which
# builds the APKs in the builder then installs + instruments through the emulator's own
# adb. See docs/tools.md.
#
# Usage: scripts/connected-test.sh [extra `am instrument` args...]
#        scripts/connected-test.sh -e class com.nexopp.SmokeTest
set -eu

REPO="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN="${ANDROID_TOOLCHAIN:-/data/android}"
RUNNER="$TOOLCHAIN/.claude/skills/android-dev/scripts/connected-test.sh"

if [ ! -x "$RUNNER" ]; then
    echo "Shared connected-test runner not found at $RUNNER (set ANDROID_TOOLCHAIN)." >&2
    exit 1
fi

exec "$RUNNER" "$REPO" "$@"
