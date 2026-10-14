#!/usr/bin/env bash
# Canary for the offline-policy gate: proves scripts/check-permissions.sh FAILS when INTERNET leaks in.
#
# Usage: scripts/canary-internet.sh [repo-root]
#
# Replaces the INTERNET `tools:node="remove"` entry in the app manifest with a plain
# <uses-permission android:name="android.permission.INTERNET" />, rebuilds the release APK,
# runs the gate and expects it to fail. The manifest is always restored on exit.
# Exit 0 = canary caught (gate works). Exit 1 = gate passed a manifest with INTERNET.

set -uo pipefail

ROOT="$(cd "${1:-$(dirname "$0")/..}" && pwd)"
cd "$ROOT" || exit 2

MANIFEST="app/src/main/AndroidManifest.xml"
BACKUP="$(mktemp)"
cp "$MANIFEST" "$BACKUP"
restore() {
  cp "$BACKUP" "$MANIFEST"
  rm -f "$BACKUP"
  echo "canary: restored $MANIFEST"
}
trap restore EXIT

sed -i 's|<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />|<uses-permission android:name="android.permission.INTERNET" />|' "$MANIFEST"
if ! grep -q '<uses-permission android:name="android.permission.INTERNET" />' "$MANIFEST"; then
  echo "canary: ERROR could not inject INTERNET into $MANIFEST (manifest format changed?)" >&2
  exit 2
fi
echo "canary: injected INTERNET permission into $MANIFEST"

if ! ./gradlew assembleRelease --console=plain -q; then
  echo "canary: ERROR assembleRelease failed with the canary manifest" >&2
  exit 2
fi

scripts/check-permissions.sh "$ROOT"
status=$?
if [[ $status -eq 1 ]]; then
  echo "canary: OK - permission gate failed as expected (exit $status)"
  exit 0
fi
echo "canary: FAILED - permission gate exited $status with INTERNET in the manifest" >&2
exit 1
