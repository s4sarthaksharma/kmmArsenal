#!/usr/bin/env bash
# Rebuilds the KMP `shared` module and publishes its artifacts so the app can
# consume them. Run this after changing anything under `shared/`, and before
# building the native apps.
#
#   Android: publishes the AAR to your local Maven repo (~/.m2)
#   iOS:     builds the release XCFramework and copies it into the Expo module
#
# Usage: ./scripts/sync-kmp.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHARED="$(cd "$ROOT/../shared" && pwd)"
FRAMEWORK_DEST="$ROOT/modules/kmp-bridge/ios/Frameworks"

echo "==> [Android] publishing KMP module to mavenLocal"
( cd "$SHARED" && ./gradlew publishToMavenLocal )

echo "==> [iOS] assembling release XCFramework"
( cd "$SHARED" && ./gradlew assembleSharedReleaseXCFramework )

echo "==> [iOS] copying Shared.xcframework into the Expo module"
rm -rf "$FRAMEWORK_DEST/Shared.xcframework"
mkdir -p "$FRAMEWORK_DEST"
cp -R "$SHARED/build/XCFrameworks/release/Shared.xcframework" "$FRAMEWORK_DEST/"

echo "==> done. If the iOS Podfile/podspec changed, run: npx pod-install"
