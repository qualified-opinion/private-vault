#!/usr/bin/env bash
# Offline-policy gate (HANDOFF §6 PR1, §8 Policy).
#
# Usage: scripts/check-permissions.sh [repo-root]
#
# Run after `./gradlew assembleRelease`. Fails (exit 1) if:
#   1. the release APK is missing, or `aapt2 dump permissions` lists a network permission;
#   2. a merged release manifest is missing or declares a network permission;
#   3. a denylisted network library is on :app releaseRuntimeClasspath.
# Exit 2 means the gate could not run (missing tools/SDK). Exit 0 means clean.

set -uo pipefail

ROOT="$(cd "${1:-$(dirname "$0")/..}" && pwd)"
cd "$ROOT" || exit 2

FORBIDDEN_PERMS='android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE)\b'
FORBIDDEN_DEPS='okhttp|coil-network|media3-datasource-okhttp'
APK="app/build/outputs/apk/release/app-release-unsigned.apk"

failures=0
fail() { echo "FAIL: $*" >&2; failures=$((failures + 1)); }
ok() { echo "ok:   $*"; }

# --- Locate aapt2 (latest build-tools) -------------------------------------------------
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" && -f local.properties ]]; then
  sdk="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -n1)"
fi
if [[ -z "$sdk" || ! -d "$sdk/build-tools" ]]; then
  echo "ERROR: Android SDK not found. Set ANDROID_HOME (or sdk.dir in local.properties)." >&2
  exit 2
fi
bt="$(ls -1 "$sdk/build-tools" | sort -V | tail -n1)"
AAPT2="$sdk/build-tools/$bt/aapt2"
if [[ ! -x "$AAPT2" ]]; then
  echo "ERROR: aapt2 not found at $AAPT2" >&2
  exit 2
fi
echo "Using aapt2 from build-tools $bt"

# --- 1. Permissions in the built release APK ------------------------------------------
if [[ ! -f "$APK" ]]; then
  fail "release APK not found: $APK (run ./gradlew assembleRelease first)"
else
  if ! perms="$("$AAPT2" dump permissions "$APK" 2>&1)"; then
    fail "aapt2 dump permissions failed on $APK:"$'\n'"$perms"
  elif hits="$(grep -E "$FORBIDDEN_PERMS" <<<"$perms")"; then
    fail "forbidden permission(s) in $APK:"$'\n'"$hits"
  else
    ok "APK declares no network permissions"
  fi
fi

# --- 2. Merged release manifests --------------------------------------------------------
mapfile -t manifests < <(
  find app/build/intermediates/merged_manifests/release \
       app/build/intermediates/merged_manifest/release \
       -name AndroidManifest.xml -type f 2>/dev/null | sort
)
if [[ ${#manifests[@]} -eq 0 ]]; then
  fail "no merged release manifest under app/build/intermediates/merged_manifest{,s}/release"
else
  for m in "${manifests[@]}"; do
    if hits="$(grep -nE "$FORBIDDEN_PERMS" "$m")"; then
      fail "forbidden permission(s) in merged manifest $m:"$'\n'"$hits"
    else
      ok "merged manifest clean: $m"
    fi
  done
fi

# --- 3. Network-library denylist on releaseRuntimeClasspath --------------------------
if ! deps="$(./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath 2>&1)"; then
  fail "could not resolve :app releaseRuntimeClasspath:"$'\n'"$deps"
elif hits="$(grep -E "$FORBIDDEN_DEPS" <<<"$deps")"; then
  fail "denylisted network library on releaseRuntimeClasspath:"$'\n'"$hits"
else
  ok "releaseRuntimeClasspath has no okhttp / coil-network / media3-datasource-okhttp"
fi

if [[ $failures -gt 0 ]]; then
  echo "Permission gate FAILED with $failures problem(s)." >&2
  exit 1
fi
echo "Permission gate passed."
