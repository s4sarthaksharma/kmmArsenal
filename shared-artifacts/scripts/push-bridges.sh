#!/usr/bin/env bash
# scripts/push-bridges.sh
#
# Generates platform bridge code from the KMP klib and pushes it into each
# registered consumer package (listed in registry.json).
#
# Prerequisite — run once in shared/ whenever the KMP source changes:
#   ./gradlew publishToMavenLocal
#
# Usage (run from shared-artifacts/):
#   bash scripts/push-bridges.sh            # generate + build XCFramework + copy everything
#   bash scripts/push-bridges.sh --publish  # same + yalc push to consumer apps

set -euo pipefail

ARTIFACTS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(cd "$ARTIFACTS_ROOT/.." && pwd)"
REGISTRY="$ARTIFACTS_ROOT/registry.json"

PUBLISH=false
for arg in "$@"; do
  [[ "$arg" == "--publish" ]] && PUBLISH=true
done

CONSUMER_PATHS=$(node -e "require('$REGISTRY').forEach(p => console.log(p))")

while IFS= read -r RELATIVE_PATH; do
  CONSUMER="$(cd "$ARTIFACTS_ROOT/$RELATIVE_PATH" && pwd)"
  PACKAGE_JSON="$CONSUMER/package.json"

  KMP_NAME=$(node -e "console.log(require('$PACKAGE_JSON').name)")
  KMP_ARTIFACT=$(node -e "console.log(require('$PACKAGE_JSON').kmp.artifact)")
  KMP_GROUP=$(node -e "console.log(require('$PACKAGE_JSON').kmp.group)")
  KMP_VERSION=$(node -e "console.log(require('$PACKAGE_JSON').kmp.version)")
  KMP_FRAMEWORK=$(node -e "console.log(require('$PACKAGE_JSON').kmp.frameworkName)")
  KMP_ANDROID_PKG=$(node -e "console.log('expo.modules.' + '$KMP_NAME'.replace(/-/g, ''))")

  SHARED="$ROOT/$KMP_ARTIFACT"

  GRADLE_PROPS="-PkmpGroup=$KMP_GROUP -PkmpArtifact=$KMP_ARTIFACT -PkmpVersion=$KMP_VERSION"
  GRADLE_PROPS="$GRADLE_PROPS -PkmpFrameworkName=$KMP_FRAMEWORK"
  GRADLE_PROPS="$GRADLE_PROPS -PkmpAndroidPackage=$KMP_ANDROID_PKG"
  GRADLE_PROPS="$GRADLE_PROPS -PkmpConsumerDir=$CONSUMER"

  echo ""
  echo "==> Consumer: $RELATIVE_PATH  ($KMP_GROUP:$KMP_ARTIFACT:$KMP_VERSION)"
  echo "    Android package: $KMP_ANDROID_PKG"
  echo ""

  echo "==> [1/5] Building iOS XCFramework (SKIE applied in shared)"
  (cd "$SHARED" && ./gradlew "assemble${KMP_FRAMEWORK}ReleaseXCFramework" -q)

  echo "==> [2/5] Copying $KMP_FRAMEWORK.xcframework"
  rm -rf "$CONSUMER/ios/Frameworks/$KMP_FRAMEWORK.xcframework"
  mkdir -p "$CONSUMER/ios/Frameworks"
  cp -r "$SHARED/build/XCFrameworks/release/$KMP_FRAMEWORK.xcframework" \
        "$CONSUMER/ios/Frameworks/"
  echo "    $KMP_FRAMEWORK.xcframework"

  echo "==> [3/5] Generating platform bridges"
  (cd "$ARTIFACTS_ROOT" && ./gradlew generatePlatformBridges $GRADLE_PROPS -q)

  echo "==> [4/5] Copying shared.aar"
  (cd "$ARTIFACTS_ROOT" && ./gradlew resolveAndroidAar $GRADLE_PROPS -q)
  mkdir -p "$CONSUMER/android/libs"
  cp "$ARTIFACTS_ROOT/build/outputs/android/$KMP_ARTIFACT.aar" \
     "$CONSUMER/android/libs/$KMP_ARTIFACT.aar"
  echo "    $KMP_ARTIFACT.aar"

  echo "==> [5/5] Done generating for $RELATIVE_PATH"

  if [ "$PUBLISH" = "true" ]; then
    echo ""
    echo "==> Publishing $RELATIVE_PATH via yalc"
    (cd "$CONSUMER" && npm run push:local)
  fi

done <<< "$CONSUMER_PATHS"

echo ""
echo "==> All consumers processed."
echo ""
echo "    Android:  cd <expo-app> && npx expo run:android"
echo "    iOS:      cd <expo-app>/ios && pod install && cd .. && npx expo run:ios"
