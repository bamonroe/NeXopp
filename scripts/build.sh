#!/usr/bin/env sh
# Containerized build/check loop. Runs the Gradle tasks inside the pinned SDK image.
# Usage: scripts/build.sh [gradle tasks...]   (default: unit tests + debug APK)
# Docker first; falls back to Podman. See docs/tools.md.
set -eu

cd "$(dirname "$0")/.."

if command -v docker >/dev/null 2>&1; then
    ENGINE=docker
elif command -v podman >/dev/null 2>&1; then
    ENGINE=podman
else
    echo "Need docker or podman on PATH." >&2
    exit 1
fi

if [ "$#" -eq 0 ]; then
    exec "$ENGINE" compose run --rm build
else
    exec "$ENGINE" compose run --rm build ./gradlew --no-daemon "$@"
fi
