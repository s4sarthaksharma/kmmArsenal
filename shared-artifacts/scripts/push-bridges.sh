#!/usr/bin/env bash
# Iterates registry.json, reads kmp metadata from each consumer,
# builds artifacts via shared-artifacts, and pushes them in.
#
# Usage:
#   bash scripts/push-bridges.sh            # artifacts only
#   bash scripts/push-bridges.sh --publish  # artifacts + npm run push:local
set -euo pipefail

ARTIFACTS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REGISTRY="$ARTIFACTS_ROOT/registry.json"

PUBLISH=false
for arg in "$@"; do
  case $arg in
    --publish) PUBLISH=true ;;
  esac
done

CONSUMER_PATHS=$(node -e "require('$REGISTRY').forEach(p => console.log(p))")

while IFS= read -r RELATIVE_PATH; do
  CONSUMER="$(cd "$ARTIFACTS_ROOT/$RELATIVE_PATH" && pwd)"
  PACKAGE_JSON="$CONSUMER/package.json"

  echo ""
  echo "==> Processing: $RELATIVE_PATH"

  KMP_GROUP=$(node -e "console.log(require('$PACKAGE_JSON').kmp.group)")
  KMP_ARTIFACT=$(node -e "console.log(require('$PACKAGE_JSON').kmp.artifact)")
  KMP_VERSION=$(node -e "console.log(require('$PACKAGE_JSON').kmp.version)")
  KMP_FRAMEWORK=$(node -e "console.log(require('$PACKAGE_JSON').kmp.frameworkName)")

  GRADLE_PROPS="-PkmpGroup=$KMP_GROUP -PkmpArtifact=$KMP_ARTIFACT -PkmpVersion=$KMP_VERSION -PkmpFrameworkName=$KMP_FRAMEWORK"

  echo "==> [Android] resolving $KMP_GROUP:$KMP_ARTIFACT-android:$KMP_VERSION"
  ( cd "$ARTIFACTS_ROOT" && ./gradlew resolveAndroidAar $GRADLE_PROPS )
  mkdir -p "$CONSUMER/android/libs"
  cp "$ARTIFACTS_ROOT/build/outputs/android/$KMP_ARTIFACT.aar" "$CONSUMER/android/libs/$KMP_ARTIFACT.aar"
  echo "==> [Android] pushed $KMP_ARTIFACT.aar"

  echo "==> [iOS] assembling $KMP_FRAMEWORK XCFramework"
  ( cd "$ARTIFACTS_ROOT" && ./gradlew "assemble${KMP_FRAMEWORK}ReleaseXCFramework" $GRADLE_PROPS )
  rm -rf "$CONSUMER/ios/Frameworks/$KMP_FRAMEWORK.xcframework"
  mkdir -p "$CONSUMER/ios/Frameworks"
  cp -R "$ARTIFACTS_ROOT/build/XCFrameworks/release/$KMP_FRAMEWORK.xcframework" "$CONSUMER/ios/Frameworks/"
  touch "$CONSUMER/ios/Frameworks/$KMP_FRAMEWORK.xcframework"
  echo "==> [iOS] pushed $KMP_FRAMEWORK.xcframework"

  if [ "$PUBLISH" = "true" ]; then
    echo "==> publishing $RELATIVE_PATH via yalc"
    ( cd "$CONSUMER" && npm run push:local )
  fi

done <<< "$CONSUMER_PATHS"

echo ""
echo "==> All bridges processed."
