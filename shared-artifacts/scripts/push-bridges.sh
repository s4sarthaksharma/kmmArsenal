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
  KMP_SOURCE_DIR="../${KMP_ARTIFACT}/src/commonMain"
  KMP_MODULE_NAME=$(node -e "console.log(require('$PACKAGE_JSON').kmp.moduleName || 'KmpBridge')")

  GRADLE_PROPS="-PkmpGroup=$KMP_GROUP -PkmpArtifact=$KMP_ARTIFACT -PkmpVersion=$KMP_VERSION -PkmpFrameworkName=$KMP_FRAMEWORK -PkmpSourceDir=$KMP_SOURCE_DIR -PkmpModuleName=$KMP_MODULE_NAME"

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

  echo "==> [Bridge] generating bridge code from $KMP_SOURCE_DIR"
  ( cd "$ARTIFACTS_ROOT" && ./gradlew generateBridgeCode $GRADLE_PROPS )

  BRIDGE_OUT="$ARTIFACTS_ROOT/build/generated/bridge"
  ANDROID_BRIDGE_DIR="$CONSUMER/android/src/main/java/expo/modules/kmpbridge"

  # Remove stale generated files before copying so deleted KMP classes don't linger.
  rm -f "$CONSUMER/ios/"*Module.swift
  find "$ANDROID_BRIDGE_DIR" -name "*Module.kt" -delete 2>/dev/null || true
  rm -f "$CONSUMER/src/"*Module.ts

  # iOS — one *Module.swift per KMP class, auto-discovered by the podspec *.swift glob.
  for f in "$BRIDGE_OUT/ios/"*Module.swift; do
    [ -f "$f" ] || continue
    name=$(basename "$f")
    cp "$f" "$CONSUMER/ios/$name"
    echo "==> [Bridge] pushed $name"
  done

  # Android — one *Module.kt per KMP class, auto-discovered by expo-module-gradle-plugin.
  mkdir -p "$ANDROID_BRIDGE_DIR"
  for f in "$BRIDGE_OUT/android/"*Module.kt; do
    [ -f "$f" ] || continue
    name=$(basename "$f")
    cp "$f" "$ANDROID_BRIDGE_DIR/$name"
    echo "==> [Bridge] pushed $name"
  done

  # TypeScript — one src/*Module.ts per KMP class + index.ts at the package root.
  mkdir -p "$CONSUMER/src"
  for f in "$BRIDGE_OUT/ts/src/"*Module.ts; do
    [ -f "$f" ] || continue
    name=$(basename "$f")
    cp "$f" "$CONSUMER/src/$name"
  done
  cp "$BRIDGE_OUT/ts/index.ts" "$CONSUMER/index.ts"
  # expo-module.config.json must list every native module by class name so the
  # Expo toolchain can generate ExpoModulesProvider at native build time.
  cp "$BRIDGE_OUT/expo-module.config.json" "$CONSUMER/expo-module.config.json"
  echo "==> [Bridge] pushed TypeScript bridge + expo-module.config.json"

  if [ "$PUBLISH" = "true" ]; then
    echo "==> publishing $RELATIVE_PATH via yalc"
    ( cd "$CONSUMER" && npm run push:local )
  fi

done <<< "$CONSUMER_PATHS"

echo ""
echo "==> All bridges processed."
