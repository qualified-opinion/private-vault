# PR1 plan: Scaffold, CI, lint and no-INTERNET check

## Scope
- Gradle version catalog + multi-module skeleton (:app, :core:crypto, :core:vault, :core:media, :feature:import)
- Compose theme + empty NavHost in :app
- Manifest hardening (no INTERNET, allowBackup=false, backup rules)
- CI workflow, lint, detekt
- Permission gate script + dependency denylist check

## Out of scope
- Any crypto, vault, media, or import implementation
- UI beyond empty theme + NavHost shell
- Secrets / release signing (debug signing for prototype)

## Acceptance
1. `./gradlew lint detekt testDebugUnitTest assembleRelease` passes
2. `scripts/check-permissions.sh` fails a canary that adds INTERNET (prove once, note in PR)
3. minSdk 26; compileSdk/targetSdk = latest stable installed
4. R8 on for release; `-assumenosideeffects` strips `android.util.Log` in release
5. applicationId / package = `com.qualifiedopinion.privatevault`
6. No INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE in APK or merged manifest
7. Fail if okhttp / coil-network / media3-datasource-okhttp on releaseRuntimeClasspath

## Files
- settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle.properties
- Each module build.gradle.kts + empty/placeholder sources
- app/src/main/AndroidManifest.xml + backup XML
- .github/workflows/ci.yml, scripts/check-permissions.sh
- config/detekt.yml, app/lint.xml
- docs/STATUS.md, docs/HANDOFF.md (update this plan as done)

## Status
Done on `feat/pr1-scaffold` (not pushed). All acceptance items 1–7 were verified locally. Evidence and versions are in `docs/STATUS.md`.
Additions beyond the original file list:
- `scripts/canary-internet.sh` runs the INTERNET canary (CI + local)
- `gradlew` wrapper (Gradle 9.8.1)
- CI also runs `assembleDebug` and uploads the debug APK for sideloading
