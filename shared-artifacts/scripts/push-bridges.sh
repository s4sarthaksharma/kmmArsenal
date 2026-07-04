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
#   bash scripts/push-bridges.sh                  # generate + copy + compile-check generated Android & iOS code
#   bash scripts/push-bridges.sh --publish        # same + yalc push to consumer apps
#   bash scripts/push-bridges.sh --skip-check     # skip both compile checks (and their implied yalc push)
#   bash scripts/push-bridges.sh --skip-ios-check # skip only the iOS compile check (pod install + xcodebuild)
#
# The compile checks build the generated bridges via the check app: Android through the
# :<package> Gradle project, iOS through the pod's Xcode scheme. They need the fresh sources
# in the app's node_modules, so a local `yalc push` runs even without --publish.

set -euo pipefail

ARTIFACTS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(cd "$ARTIFACTS_ROOT/.." && pwd)"
REGISTRY="$ARTIFACTS_ROOT/registry.json"

# App whose android project is used to compile-check the generated bridge code.
CHECK_APP="${CHECK_APP:-$ROOT/expofirst/android}"
# iOS side of the same check app; scheme name is the pod's (npm name pascal-cased).
CHECK_APP_IOS="${CHECK_APP_IOS:-$ROOT/expofirst/ios}"
CHECK_IOS_SCHEME="${CHECK_IOS_SCHEME:-KmpBridge}"

PUBLISH=false
SKIP_CHECK=false
SKIP_IOS_CHECK=false
for arg in "$@"; do
  [[ "$arg" == "--publish" ]] && PUBLISH=true
  [[ "$arg" == "--skip-check" ]] && SKIP_CHECK=true
  [[ "$arg" == "--skip-ios-check" ]] && SKIP_IOS_CHECK=true
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

  echo "==> [1/6] Building iOS XCFramework (SKIE applied in shared)"
  (cd "$SHARED" && ./gradlew "assemble${KMP_FRAMEWORK}ReleaseXCFramework" -q)

  echo "==> [2/6] Copying $KMP_FRAMEWORK.xcframework"
  rm -rf "$CONSUMER/ios/Frameworks/$KMP_FRAMEWORK.xcframework"
  mkdir -p "$CONSUMER/ios/Frameworks"
  cp -r "$SHARED/build/XCFrameworks/release/$KMP_FRAMEWORK.xcframework" \
        "$CONSUMER/ios/Frameworks/"
  echo "    $KMP_FRAMEWORK.xcframework"

  echo "==> [3/6] Generating platform bridges"
  (cd "$ARTIFACTS_ROOT" && ./gradlew generatePlatformBridges $GRADLE_PROPS -q)

  echo "==> [4/6] Copying shared.aar"
  (cd "$ARTIFACTS_ROOT" && ./gradlew resolveAndroidAar $GRADLE_PROPS -q)
  mkdir -p "$CONSUMER/android/libs"
  cp "$ARTIFACTS_ROOT/build/outputs/android/$KMP_ARTIFACT.aar" \
     "$CONSUMER/android/libs/$KMP_ARTIFACT.aar"
  echo "    $KMP_ARTIFACT.aar"

  if [ "$PUBLISH" = "true" ] || [ "$SKIP_CHECK" = "false" ]; then
    echo ""
    echo "==> [5/6] Publishing $RELATIVE_PATH via yalc"
    (cd "$CONSUMER" && npm run push:local)
  fi

  if [ "$SKIP_CHECK" = "false" ]; then
    echo ""
    echo "==> [6/6] Compile check: generated Android bridge (:$KMP_NAME in $CHECK_APP)"
    (cd "$CHECK_APP" && ./gradlew ":$KMP_NAME:compileDebugKotlin" -q)
    echo "    Android compile check passed"

    if [ "$SKIP_IOS_CHECK" = "false" ] && command -v xcodebuild >/dev/null 2>&1; then
      echo ""
      echo "==> [6b/6] Compile check: generated iOS bridge ($CHECK_IOS_SCHEME in $CHECK_APP_IOS)"
      WORKSPACE=$(ls -d "$CHECK_APP_IOS"/*.xcworkspace 2>/dev/null | head -1)
      if [ -n "$WORKSPACE" ]; then
        # pod install picks up newly generated .swift files in the pod's source glob.
        (cd "$CHECK_APP_IOS" && pod install --silent)
        (cd "$CHECK_APP_IOS" && xcodebuild -workspace "$(basename "$WORKSPACE")" \
          -scheme "$CHECK_IOS_SCHEME" -sdk iphonesimulator -configuration Debug build -quiet)
        echo "    iOS compile check passed"
      else
        echo "    skipped — no .xcworkspace found in $CHECK_APP_IOS (run pod install once)"
      fi
    elif [ "$SKIP_IOS_CHECK" = "true" ]; then
      echo "==> [6b/6] iOS compile check skipped (--skip-ios-check)"
    fi
  else
    echo "==> [6/6] Compile checks skipped (--skip-check)"
  fi

done <<< "$CONSUMER_PATHS"

echo ""
echo "==> All consumers processed."
echo ""
echo "    Android:  cd <expo-app> && npx expo run:android"
echo "    iOS:      cd <expo-app>/ios && pod install && cd .. && npx expo run:ios"
