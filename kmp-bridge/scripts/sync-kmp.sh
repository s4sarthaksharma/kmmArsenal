#!/usr/bin/env bash
# Syncs the KMP `shared` module for both platforms.
# Usage: npm run build  (from kmp-bridge root)
set -euo pipefail

BRIDGE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

bash "$BRIDGE_ROOT/scripts/sync-kmp-android.sh"
bash "$BRIDGE_ROOT/scripts/sync-kmp-ios.sh"
