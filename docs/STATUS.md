# Status

| PR | Branch | State |
|---|---|---|
| PR1 Scaffold, CI, lint, no-INTERNET gate | `feat/pr1-scaffold` | Implemented locally, all checks green. Not pushed (planning phase). Awaiting Kiln review + Jan OK. |
| PR2–PR8 | — | Not started |

## PR1 summary

- Modules: `:app`, `:core:crypto`, `:core:vault`, `:core:media`, `:feature:import`. The four non-app modules are Android libraries holding only a placeholder object and one unit test. No crypto, vault, media or import code.
- Packages: `com.qualifiedopinion.privatevault` (app; applicationId is the same), `.crypto`, `.vault`, `.media`, `.importer` (`import` is a Kotlin keyword).
- `:app`: `MainActivity` → `PrivateVaultTheme` (`ui/theme`) → `PrivateVaultNavHost` with one blank `home` destination.
- Manifest: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` use `tools:node="remove"`. `allowBackup="false"`. `dataExtractionRules="@xml/no_backup"` and `fullBackupContent="@xml/no_backup_legacy"` exclude every domain.
- Release: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-rules.pro` strips `android.util.Log` (`v/d/i/w/e/wtf/println/isLoggable`) through `-assumenosideeffects`. Release is unsigned (`app-release-unsigned.apk`); debug uses the default debug key.
- Only these runtime dependencies: core-ktx, activity-compose, navigation-compose, Compose BOM (ui, material3). Test: JUnit 4.

## Versions (pinned in `gradle/libs.versions.toml`)

| Item | Version | Note |
|---|---|---|
| Gradle wrapper | 9.8.1 | Current stable. `distributionSha256Sum` pinned. AGP 9.4.1 does not load on the local Gradle 9.3.1 (`NoClassDefFoundError: org/gradle/features/binding/ProjectTypeBinding`). |
| AGP | 9.4.1 | Latest stable (9.5 is alpha). Uses AGP 9 built-in Kotlin, so modules don't apply `kotlin-android`. |
| Kotlin (KGP + Compose compiler plugin) | 2.4.21 | Root `apply false` of `org.jetbrains.kotlin.android` upgrades AGP's bundled KGP 2.2.10 → 2.4.21. Check with `./gradlew buildEnvironment`. |
| Compose BOM | 2026.09.00 | |
| activity-compose | 1.13.0 | |
| navigation-compose | 2.10.2 | |
| core-ktx | 1.19.1 | |
| detekt | 1.23.8 | Latest stable. 2.0 is still alpha. |
| JUnit | 4.13.2 | |
| JDK | 17 (Temurin) | |
| minSdk / compileSdk / targetSdk | 26 / 37 / 37 | See below. |

**Why compileSdk/targetSdk 37:** the installed `platforms;android-37.0` package is a final release, not a preview: its `source.properties` has an empty `AndroidVersion.CodeName` and `PreviewSdkInt=0`. AGP 9.4.1 builds against it with no "untested compileSdk" warning, and lint is clean. So 37 is the latest stable API that works here, and 36 wasn't needed as a fallback. CI installs `platforms;android-37.0` and `build-tools;37.0.0` with `sdkmanager`.

**Known warnings (not failures):**
- Manifest merger says the `tools:node="remove"` permissions have "no other declaration present". This is expected, because no current dependency declares them. The markers protect against future dependencies.
- detekt 1.23.8 calls Gradle APIs that are deprecated for Gradle 10/11 (`ReportingExtension.file`, `Configuration.setVisible`). Revisit when detekt 2.0 is stable.

## How to build

```bash
export JAVA_HOME=/path/to/jdk17          # Android SDK via ANDROID_HOME or local.properties (sdk.dir)
./gradlew lint detekt testDebugUnitTest assembleRelease assembleDebug
scripts/check-permissions.sh              # offline-policy gate; run after assembleRelease
scripts/canary-internet.sh                # optional: proves the gate fails if INTERNET leaks in
```

APKs: `app/build/outputs/apk/debug/app-debug.apk` (sideload), `app/build/outputs/apk/release/app-release-unsigned.apk`.

`check-permissions.sh` exit codes: 0 = clean, 1 = policy failure (the APK or a merged manifest is missing, a network permission is present, or a denylisted library is on the classpath), 2 = gate could not run (no SDK or aapt2).

## PR1 verification log (local, 2026-10-14)

1. `./gradlew lint detekt testDebugUnitTest assembleRelease assembleDebug`: **BUILD SUCCESSFUL**. Lint reported "No issues found" in all 5 modules. detekt found 0 issues after fixing one finding (`MatchingDeclarationName`, so `Routes` moved to `Routes.kt`). Unit tests: 5/5 pass, one per module.
2. `scripts/check-permissions.sh`: **passed** (exit 0). `aapt2` (build-tools 37.0.0) shows only the AndroidX `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Both merged release manifests are clean. The classpath denylist is clean.
3. Missing-APK check: with the APK moved aside, the script **failed** (exit 1) with `release APK not found`.
4. **Canary** (`scripts/canary-internet.sh`): this replaced the `INTERNET` remove-marker with a plain `<uses-permission android:name="android.permission.INTERNET" />` and rebuilt the release. The gate **failed as required** (exit 1, 3 problems):
   - `aapt2`: `uses-permission: name='android.permission.INTERNET'` in `app-release-unsigned.apk`
   - `merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml:12` has INTERNET
   - `merged_manifests/release/processReleaseManifest/AndroidManifest.xml:12` has INTERNET

   The canary script exited 0 ("gate failed as expected"). The manifest was restored and its md5 is unchanged (`9da2202e…`).
5. Denylist check: temporarily adding `com.squareup.okhttp3:okhttp:4.12.0` to `:app` made the gate fail with `denylisted network library on releaseRuntimeClasspath: com.squareup.okhttp3:okhttp:4.12.0`. Reverted.
6. After the canary, a clean rebuild ran and the gate passed again (step 2 output).

CI (`.github/workflows/ci.yml`) has not run yet because nothing is pushed in the planning phase.
