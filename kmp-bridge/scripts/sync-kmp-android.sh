#!/usr/bin/env bash
# Resolves the shared AAR via shared-artifacts and copies it into kmp-bridge.
# Run before `expo run:android`.
set -euo pipefail

BRIDGE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACTS="$(cd "$BRIDGE_ROOT/../shared-artifacts" && pwd)"
AAR_DEST="$BRIDGE_ROOT/android/libs"

echo "==> [Android] resolving shared AAR from Maven Local"
( cd "$ARTIFACTS" && ./gradlew resolveAndroidAar )

echo "==> [Android] copying shared.aar into kmp-bridge"
mkdir -p "$AAR_DEST"
cp "$ARTIFACTS/build/outputs/android/shared.aar" "$AAR_DEST/shared.aar"

echo "==> done."
