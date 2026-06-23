#!/usr/bin/env bash
# Rebuilds the KMP `shared` module and publishes the Android AAR to mavenLocal.
# Run before `expo run:android` (wired into `npm run android`).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHARED="$(cd "$ROOT/../shared" && pwd)"

echo "==> [Android] publishing KMP module to mavenLocal"
( cd "$SHARED" && ./gradlew publishToMavenLocal )

echo "==> done."
