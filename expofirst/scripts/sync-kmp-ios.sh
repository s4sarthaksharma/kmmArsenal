#!/usr/bin/env bash
# Rebuilds the KMP `shared` module XCFramework and copies it into the Expo module.
# Run before `expo run:ios` (wired into `npm run ios`).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHARED="$(cd "$ROOT/../shared" && pwd)"
FRAMEWORK_DEST="$ROOT/modules/kmp-bridge/ios/Frameworks"

echo "==> [iOS] assembling release XCFramework"
( cd "$SHARED" && ./gradlew assembleSharedReleaseXCFramework )

echo "==> [iOS] copying Shared.xcframework into the Expo module"
rm -rf "$FRAMEWORK_DEST/Shared.xcframework"
mkdir -p "$FRAMEWORK_DEST"
cp -R "$SHARED/build/XCFrameworks/release/Shared.xcframework" "$FRAMEWORK_DEST/"

echo "==> done. If Podfile/podspec changed, run: npx pod-install"
