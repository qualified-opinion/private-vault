#!/usr/bin/env bash
# Static check for the minified instrumented tests (planning pack §6 PR3, releaseCheck build type).
#
# Usage: scripts/check-releasecheck-refs.sh [repo-root]
# Run after `./gradlew :app:assembleReleaseCheck :app:assembleReleaseCheckAndroidTest`.
#
# The androidTest APK runs in the same class loader as the R8-minified app. If R8 removed something
# the tests (or androidx.test) call, the run fails on the device with NoSuchMethodError. This traces
# app + test APK together with R8's tracereferences and fails on any missing reference that the
# platform doesn't provide. Exit 0 = clean, 1 = missing references, 2 = could not run.

set -uo pipefail
ROOT="$(cd "${1:-$(dirname "$0")/..}" && pwd)"
cd "$ROOT" || exit 2

APP="app/build/outputs/apk/releaseCheck/app-releaseCheck.apk"
TEST="app/build/outputs/apk/androidTest/releaseCheck/app-releaseCheck-androidTest.apk"
for f in "$APP" "$TEST"; do
  [[ -f "$f" ]] || { echo "ERROR: $f not found (build releaseCheck and its androidTest first)" >&2; exit 2; }
done

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -z "$sdk" && -f local.properties ]] && sdk="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -n1)"
compile_sdk="$(sed -n 's/^ *compileSdk = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -n1)"
android_jar="$(ls -d "$sdk"/platforms/android-"$compile_sdk"*/android.jar 2>/dev/null | sort -V | tail -n1)"
[[ -f "$android_jar" ]] || { echo "ERROR: android.jar for compileSdk $compile_sdk not found" >&2; exit 2; }

# R8 ships inside AGP's builder jar; use the one this build resolved.
agp="$(sed -n 's/^agp = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
r8="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2" -name "builder-$agp.jar" 2>/dev/null | head -n1)"
[[ -f "$r8" ]] || { echo "ERROR: builder-$agp.jar (R8) not found in the Gradle cache" >&2; exit 2; }
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"

out="$("$java_bin" -cp "$r8" com.android.tools.r8.tracereferences.TraceReferences --check \
  --lib "$android_jar" --source "$APP" --source "$TEST" 2>&1)"
trace_status=$?
if [[ $trace_status -ne 0 ]] && ! grep -qE 'Missing (class|method|field) ' <<<"$out"; then
  echo "ERROR: TraceReferences failed (exit $trace_status):" >&2
  echo "$out" >&2
  exit 2
fi

# Gaps the device fills: hidden or newer framework API (the libraries guard these calls), optional
# platform libraries (android.test.*) and OEM window extensions. Plus two JVM-only packages that
# only kotlinx-coroutines' JVM debug agent (kotlinx.coroutines.debug.internal.AgentPremain)
# references; Android never loads that class.
PLATFORM='(android\.[a-z]+\.|androidx\.window\.(extensions|sidecar)\.|androidx\.test\.internal\.runner\.hidden\.|java\.lang\.instrument\.|sun\.misc\.)'
missing="$(grep -oE 'Missing (class|method|field) [^(]+' <<<"$out" | sed 's/ *$//' | sort -u \
  | grep -vE "^Missing class $PLATFORM" \
  | grep -vE "^Missing (method|field) [^ ]+ $PLATFORM" || true)"

if [[ -n "$missing" ]]; then
  echo "FAIL: the minified app lacks code the androidTest APK needs:" >&2
  echo "$missing" >&2
  exit 1
fi
echo "releaseCheck references OK: app + androidTest APK trace clean (only platform-provided gaps)."
