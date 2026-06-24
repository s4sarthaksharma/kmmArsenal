#!/usr/bin/env bash
# Builds the XCFramework via shared-artifacts and copies it into kmp-bridge.
# Run before `expo run:ios`.
set -euo pipefail

BRIDGE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACTS="$(cd "$BRIDGE_ROOT/../shared-artifacts" && pwd)"
FRAMEWORK_DEST="$BRIDGE_ROOT/ios/Frameworks"

echo "==> [iOS] assembling release XCFramework via shared-artifacts"
( cd "$ARTIFACTS" && ./gradlew assembleSharedReleaseXCFramework )

echo "==> [iOS] copying Shared.xcframework into kmp-bridge"
rm -rf "$FRAMEWORK_DEST/Shared.xcframework"
mkdir -p "$FRAMEWORK_DEST"
cp -R "$ARTIFACTS/build/XCFrameworks/release/Shared.xcframework" "$FRAMEWORK_DEST/"
touch "$FRAMEWORK_DEST/Shared.xcframework"

echo "==> done."
