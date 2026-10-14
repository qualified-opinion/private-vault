# Status

| PR | Branch | State |
|---|---|---|
| PR1 Scaffold, CI, lint, no-INTERNET gate | `feat/pr1-scaffold` | **Merged** (squash) as PR #1 → `main` @ 5ef91a6. |
| PR2 Crypto core and tests | `feat/pr2-crypto` | Merged (squash) as PR #2 → main @ 74bfd5e |
| PR3 Vault storage on SD (SAF folder) | `feat/pr3-storage` | Implemented locally; build, JVM checks and APK gates verified. Not pushed. Emulator/phone acceptance and completion commits pending (`.git` is read-only in this session), then Kiln review and Jan's OK. |
| PR4–PR8 | — | Not started |

## PR3 summary

Plan: `docs/plans/pr3.md`. Storage lives in `:core:vault`; setup/unlock and the phone benchmark live in `:app`.

- Added `VaultStorage`, `FileVaultStorage`, `SafIo`, `SafVaultStorage`, `VaultLocation`/`LocationStore`, `VolumeMonitor`, `VaultFiles`, `ItemRepository`/`ItemMeta`, `VaultIndex`, `VaultSession`, `VaultKeys` and typed storage exceptions. SAF uses `"rw"` PFD-backed channels, with descriptor ownership through auto-close streams. Location is persisted in `noBackupFilesDir/location.json` with tree URI, volume and vault id.
- `vault.meta` writes use synced `.tmp`, read-back, rename and `.bak` fallback. Startup cleans `.part` files. Item operations encrypt all three files; delete removes all three and updates the memory index. Read-only storage blocks mutations. Every monitored provider call checks the grant, volume and fresh vault id; writes refresh read-only flags. This adds provider queries, so full index-build latency needs measuring on the phone.
- Added `AppGraph`, setup/unlock/error screens and a byte-array PIN pad. Setup calibrates Argon2, confirms data-loss risk and explicitly warns for a primary-volume folder. Re-pick requires the same vault id. No silent internal fallback. The availability/session collector also locks an unwrap that finishes after removal was reported.
- Added crypto consumer rules for argon2kt JNI and Tink protobuf fields. `releaseCheck` is minified, non-debuggable and debug-signed; the app's `testBuildType` is `releaseCheck`. Test-only keep rules preserve our callable APIs and shared test runtime libraries, without blanket keeps for Tink or argon2kt. `scripts/check-releasecheck-refs.sh` checks the combined app/test APK references and rejects tracer failures.
- Added an API 34 x86_64 emulator job using `reactivecircus/android-emulator-runner@v2` and its documented KVM udev rule. It runs crypto, SAF storage and minified app instrumentation. `SafBenchmarkTest` is explicitly excluded. The existing build job, permission gate and canary are unchanged; the permission gate still checks the real unsigned release APK. The new job has not run on GitHub (nothing pushed).

### PR3 verification log (local, 2026-10-15)

The final command uses JDK 17 and the installed SDK:

```bash
export JAVA_HOME=/home/box/tools/jdk17
export ANDROID_HOME=/home/box/tools/android-sdk
timeout 20m ./gradlew lint detekt testDebugUnitTest assembleRelease assembleDebug \
  :core:crypto:assembleDebugAndroidTest :core:vault:assembleDebugAndroidTest \
  :app:assembleReleaseCheck :app:assembleReleaseCheckAndroidTest \
  --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process
```

- Lint, detekt, unit tests and all listed APK assemblies: **passed** (`BUILD SUCCESSFUL in 1m 58s`). The app instrumentation APK is the releaseCheck variant because of `testBuildType`; no separate debug app instrumentation variant is configured.
- JVM results: **102 tests, 0 failures/errors/skips**: crypto 60, vault 39, app 1, media 1, import 1. Vault tests cover metadata replace/crash/backup fallback, staging cleanup, id mismatch, read-only blocking, encrypted items, three-file deletion, index/session and location/availability rules. Crypto and vault test heaps remain 64 MiB.
- `scripts/check-permissions.sh`: **passed**. The real release APK and merged manifests contain no network permissions; no denylisted network dependency is present. The script's internal Gradle call was bounded through a temporary `BASH_ENV` function, preserving the gate script unchanged. An initial attempt to export that function failed and let one dependency query run without the timeout; the corrected gate run passed with the timeout enforced.
- CI YAML parsed locally; existing build job, gate and canary confirmed byte-for-byte unchanged.
- `scripts/check-releasecheck-refs.sh`: **passed**. A simulated tracer startup failure correctly returned exit 2 instead of passing. This is a static reference check, not proof of runtime JNI/Keystore behaviour. Mapping confirms `VaultViewModel` is obfuscated in releaseCheck.
- **Instrumented tests could not run locally: no usable `/dev/kvm` and no connected target phone.** APKs compile only. SAF fixture tests, including fresh-id/read-only checks, and the minified real-Argon2/Keystore unlock test still require an emulator run. The 1 GiB benchmark has not run.
- Compiler execution was switched to in-process for the final command because the Kotlin daemon tried to create its marker in the read-only home directory. Existing Gradle/detekt deprecation and unstripped JNI-library warnings remain; no build hang occurred.

Completion commits are pending: `git add` failed with `Unable to create '/workspace/private-vault/.git/index.lock': Read-only file system`. HEAD remains `8adb86f`; the implementation and documentation changes are in the working tree. No push, GitHub operation or change to main was made. `docs/HANDOFF.md` records the logical commit groups.

Kiln focus: runtime JNI/Keystore and SAF tests still need execution; measure OEM provider behaviour and the extra fresh-vault-id queries during a full index build; SAF rename/fsync durability remains provider-dependent. Recovery is not implemented until PR8, and background/screen-off lock and throttling remain PR7. Static APK checks cannot establish the phone's removal latency or card durability.

### Run the SAF benchmark on the target phone

Install the minified app and its test APK. These use the debug signing key; use a test install, and preserve the installed key/data so you do not lose the vault's Keystore alias. Open the app and pick/setup an SD-card folder first so it holds a persisted read/write tree grant. Substitute that exact granted URI below (the sample URI is a placeholder).

```bash
adb install -r app/build/outputs/apk/releaseCheck/app-releaseCheck.apk
adb install -r app/build/outputs/apk/androidTest/releaseCheck/app-releaseCheck-androidTest.apk
adb shell am instrument -w -r \
  -e class com.qualifiedopinion.privatevault.SafBenchmarkTest \
  -e treeUri 'content://com.android.externalstorage.documents/tree/1234-ABCD%3APrivateVault' \
  -e sizeMiB 1024 -e items 1000 \
  com.qualifiedopinion.privatevault.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s PV-BENCH:I '*:S'
```

The benchmark encrypts/writes and decrypts/reads 1 GiB through SAF and a plain File in app-specific external storage on the **same volume**, then performs 200 random 64 KiB reads and lists 1,000 items (3,000 files). It asserts SAF/File throughput ≥ 0.80 for write and read, average SAF seek < 50 ms and listing < 2 s; full `.vmeta` index-decryption time is logged separately. It works in a random scratch subfolder, cleans it on completion and needs at least about 2 GiB free space. Missing `treeUri` produces an explicit skip; CI excludes the class entirely. An unrooted phone cannot flush the page cache, so 1 GiB reads can be partly cached; record this limitation with the results.

Also run the minified unlock test on the phone:

```bash
adb shell am instrument -w -r \
  -e class com.qualifiedopinion.privatevault.ReleaseUnlockTest \
  com.qualifiedopinion.privatevault.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s PV-RELEASE:I '*:S'
```

Phone acceptance still pending: grant survives reboot/update; eject and revoke lock within 1 s; reinsertion + Retry; wrong-vault re-pick rejected; read-only view; startup cleanup; three-file deletion; vault folder survives uninstall. Uninstall loses the Keystore alias: PIN restore after reinstall remains PR8 work. Recovery, background/screen-off auto-lock and attempt throttling remain PR8/PR7 scope.

### PR6 notes / carry-over

PR6 note: the reported file size isn't verified until the end of the file is read, so the player must show an error at a cut-off point (truncated file) instead of just stopping.

## PR2 summary

Plan: `docs/plans/pr2.md`. All code is in `:core:crypto`.

- `.format`: `FileHeader` (32-byte PVLT header, §3.2; rejects unknown magic, unknown type, major ≠ 1, non-zero flags or reserved bytes; ids are 16 random bytes from `SecureRandom`), `VaultMetaCodec` (§3.3; strict parse with hard limits on KDF params and lengths), `VaultFormatException` (an `IOException`).
- `.crypto`:
  - `VaultCrypto`: Tink `AES256_GCM_HKDF_1MB` with AAD = header `[0,32)`. Provides `newEncryptingChannel`, `newSeekableDecryptingChannel` and `newDecryptingStream`. Decrypting takes the expected `FileHeader`; a different header is rejected.
  - `OffsetSeekableChannel(base, 32)`.
  - `KdfParams`, `PinKdf`, `KdfCalibrator`, and `Argon2Kdf` (argon2kt).
  - `KeyWrapper` and `KeystoreKeyWrapper`.
  - `LayeredKeyWrap`: `KS(PIN(DK))`, both layers with AAD = `vault.meta[0,60)`.
  - `Pins`.
- Decisions:
  - **Single-key primitive, not the keyset wrapper.** Tink's keyset-level seekable channel throws on `size()` before the first `read()`, and Media3 needs `size()` first. So `VaultCrypto.fromKeyset` requires exactly one enabled `AES256_GCM_HKDF_1MB` key and uses `AesGcmHkdfStreaming.create(key)`.
  - **EOF fix.** Tink's seekable channel returns 0 instead of −1 at EOF unless the last segment happens to be loaded (always 0 for an empty plaintext), and its `size()` is unauthenticated. `VaultCrypto` wraps the channel: before returning −1, it authenticates the final segment. A file truncated at a segment boundary, or down to an empty ciphertext, therefore fails instead of reading short. Tests cover both cases.
  - **Floor enforced on wrap.** `wrap` requires params ≥ floor (m = 32 MiB, t = 2) and a 6–12 digit PIN. `unwrap` accepts the stored params because they are AAD-bound, but it rejects absurd values (m > 1 GiB, t > 64, p > 16) so a corrupted file can't OOM the app.
  - **Typed failures.** `WrongPinException` is thrown when the inner (PIN) layer fails, for throttling in PR7. `KeyWrapperKeyMissingException` is thrown when the Keystore alias is gone, which is the PR8 restore path.
  - **argon2kt transitive deps.** argon2kt declares `appcompat` and `core-ktx` as runtime dependencies but only uses `androidx.annotation.Keep`, so both are excluded.
- CI: the existing job is unchanged and still runs the 256 MiB test on every PR. It now also compiles the crypto androidTest. A manual `workflow_dispatch` run with `largeTests: true` adds `-PlargeTests`.
- No code of ours allocates a buffer over 64 KiB per stream. Tink allocates two buffers of exactly 1 MiB per channel (one ciphertext segment and one plaintext segment). Nothing writes plaintext to disk; tests write only ciphertext fixtures, to the Gradle temp dir, and delete them.

### PR2 verification log (local, 2026-10-15)

1. `./gradlew lint detekt testDebugUnitTest assembleRelease assembleDebug :core:crypto:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**.
   - Lint: "No issues found".
   - detekt: 0 issues. The 22 initial findings were fixed in code; `config/detekt.yml` is unchanged.
   - The androidTest APK compiles.
2. `:core:crypto` JVM tests: **60 run, 60 pass, 0 failures**. The test JVM runs with `-Xmx64m`.

   | Class | Tests | What it covers |
   |---|---|---|
   | `VaultCryptoRoundTripTest` | 9 | Round trips at 0, 1, 2, firstSeg±1, seg±1, firstSeg+seg±1, 2·seg±1. `fileSizeFor` and `size()`. EOF returns −1. Truncation to an empty ciphertext. Wrong expected header. Another DK. Keyset shape. |
   | `TamperTest` | 7 | Each case is checked through both the seekable channel and the stream. Header byte flips (ours and Tink's), mid-file byte, last tag byte, truncation (−1 byte, −500 bytes, at a segment boundary), swapped segments, item id swapped in the AAD, type byte changed. |
   | `LargeStreamingTest` | 1 | 256 MiB: peak heap 18.0 MiB, 3,284 seek reads. |
   | `LayeredKeyWrapTest` | 14 | BouncyCastle Argon2id matches the reference vector. Round trip at floor params. Wrong PIN. Tampered m, t, p, salt, id and blob. Tampered params with the outer layer re-wrapped (fails in the inner layer). Other device. Missing key. Unknown magic, version, kdf and scheme. PIN and KEK zeroized. Floor and PIN rules. Fresh salt and IV. |
   | `KdfCalibratorTest` | 7 | Simulated devices: keeps defaults, more passes on fast devices, cap, steps down to the floor, OOM step-down, OOM at the floor. |
   | `FileHeaderTest`, `VaultMetaCodecTest`, `OffsetSeekableChannelTest`, `PinsTest` | 10, 7, 3, 2 | Layouts, strict rejects, offset maths, PIN rules. |
3. A mutation check: disabling the EOF verification made `VaultCryptoRoundTripTest` fail (truncation to an empty ciphertext). Restored.
4. `scripts/check-permissions.sh`: **passed** (exit 0). `aapt2` shows only `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Tink and argon2kt add no permissions, and both merged release manifests are clean. `:core:crypto` releaseRuntimeClasspath: tink-android 1.23.0 (with gson 2.13.2, jsr305, error_prone_annotations, annotation-jvm), argon2kt 1.6.0, kotlin-stdlib. No appcompat.
5. **3 GiB** (`-PlargeTests`): **passed** in 33.0 s. Peak heap was **20.0 MiB** of the 64 MiB max. 17,364 seek reads. Details: `docs/logs/pr2-large-test.md`.
6. Instrumented `KeystoreKeyWrapperTest`: compiled only. To do on the emulator and the target phone (see HANDOFF).

Known build warnings:
- "Unable to strip libargon2jni.so / libargon2native.so": no NDK is installed. The libraries are packaged unstripped, which is harmless.


## PR1 summary

- Modules: `:app`, `:core:crypto`, `:core:vault`, `:core:media`, `:feature:import`. The four non-app modules are Android libraries holding only a placeholder object and one unit test. No crypto, vault, media or import code.
- Packages: `com.qualifiedopinion.privatevault` (app; applicationId is the same), `.crypto`, `.vault`, `.media`, `.importer` (`import` is a Kotlin keyword).
- `:app`: `MainActivity` → `PrivateVaultTheme` (`ui/theme`) → `PrivateVaultNavHost` with one blank `home` destination.
- Manifest: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` use `tools:node="remove"`. `allowBackup="false"`. `dataExtractionRules="@xml/no_backup"` and `fullBackupContent="@xml/no_backup_legacy"` exclude every domain.
- Release: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-rules.pro` strips `android.util.Log` (`v/d/i/w/e/wtf/println/isLoggable`) through `-assumenosideeffects`. Release is unsigned (`app-release-unsigned.apk`); debug uses the default debug key.
- PR1 runtime dependencies: core-ktx, activity-compose, navigation-compose, Compose BOM (ui, material3). Test: JUnit 4. PR2 adds the crypto dependencies listed in the versions table.

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
| tink-android | 1.23.0 | PR2. `api` of `:core:crypto`. Runs on the JVM for unit tests too, so no separate `tink` jar is needed. |
| argon2kt | 1.6.0 | PR2. Native Argon2id. Excludes `appcompat` and `core-ktx`. |
| bcprov-jdk18on | 1.86 | PR2. **testImplementation only**: JVM Argon2id stand-in, checked against the reference vectors. |
| androidx.test runner / ext-junit | 1.7.0 / 1.3.0 | PR2. androidTest only. |
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
./gradlew :core:crypto:testDebugUnitTest --tests '*LargeStreamingTest' -PlargeTests --rerun   # 3 GiB run
./gradlew :core:crypto:connectedDebugAndroidTest   # device/emulator; numbers in `adb logcat -s PV-CRYPTO`
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

When this log was written, CI (`.github/workflows/ci.yml`) had not run, because nothing had been pushed. PR1 has since been merged as PR #1.
