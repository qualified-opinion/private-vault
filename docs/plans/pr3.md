# PR3 plan: Vault storage on SD (SAF folder)

Branch: `feat/pr3-storage` (cut from `main` @ 74bfd5e, PR1 and PR2 merged). Planning pack: §3 (format), §4 (keys), §5 (storage), §6 PR3, §8 Storage, §9.

## Scope
- `:core:vault` (package `.vault`):
  - `VaultStorage`: a small file-store interface (list, open read/write as `FileChannel`, create, rename, move, delete, mkdir). Two backends:
    - `SafVaultStorage` over `SafIo` (production);
    - `FileVaultStorage` over `java.io.File` (JVM tests, the minified unlock test, the benchmark baseline).
  - `SafIo`: `openRead`/`openWrite` return a `FileChannel` from a `ParcelFileDescriptor`. Writes use mode `"rw"`, never `"w"`. Create, rename, move, delete and list go through `DocumentsContract`. Every PFD is closed. The provider can silently rename on a name clash (`x (1)`), so create and rename check the display name they get back.
  - `VaultLocation`: the persisted tree URI, the volume binding (`primary` or the SD UUID) and the vault id, stored in `noBackupFilesDir/location.json`. Also the persisted-grant check, and the rule that only `com.android.externalstorage.documents` trees are accepted.
  - `VolumeMonitor`: a dynamic receiver for `MEDIA_EJECT/UNMOUNTED/REMOVED/BAD_REMOVAL/MOUNTED` (scheme `file`), a grant + mounted check, and a `StateFlow<VaultAvailability>`. Any provider `FileNotFoundException`/`SecurityException`/`IOException` reported by `SafVaultStorage` counts as "vault gone".
  - `VaultFiles`: the §3.1 layout. Atomic `vault.meta` write (`.tmp` → fsync → old → `.bak` → `.tmp` → `vault.meta`). On read, falls back to `.bak` when `vault.meta` is missing or doesn't parse. Startup cleanup of `staging/*.part`. Vault id check.
  - `ItemRepository`: create (encrypt the data via `staging/<id>.vdat.part`, then `.vthm`, then `.vmeta` last), list (decrypt every `.vmeta`), delete (`.vmeta` first, then `.vdat` and `.vthm`).
  - `ItemMeta` (JSON v1, §3.5, kotlinx-serialization), `VaultIndex` (in memory), `VaultSession` (DK + `VaultCrypto`, `isUnlocked: StateFlow`, `lock()`), `VaultKeys` (create and unlock with PR2's `LayeredKeyWrap`).
  - Read-only volume (or a read-only grant): view only. Import and delete throw `VaultReadOnlyException`.
- `:app`:
  - `AppGraph` (manual DI) and an `Application` class.
  - Setup flow: pick the folder (`OpenDocumentTree` + `takePersistableUriPermission`, initial URI = SD root on 29+). Warn if the folder is on the primary volume (internal storage is an explicit choice). PIN pad (6–12 digits, `ByteArray`), confirm the PIN, confirm "forgot PIN and lost phrase = data lost", Argon2 calibrate, create `vault.meta`.
  - Unlock flow (PIN pad → unwrap). A minimal "Vault unlocked" screen.
  - Error UI: "Vault folder not available. Insert the card or pick the folder again." with Retry and Re-pick folder. Re-pick accepts only the same vault id. Never create a new vault silently. No runtime fallback to internal storage.
  - Lock on eject or lost permission (receiver + a 1 s grant/volume poll while in the foreground).
  - No new permissions.
- R8: consumer rules in `:core:crypto` for argon2kt (its JNI sets `ByteBufferTarget.byteBuffer` by name) and Tink. A `releaseCheck` build type (`initWith(release)`, debug-signed, `matchingFallbacks += release`) is the `:app` `testBuildType`, so the instrumented unlock test runs on R8 output.
- CI: a new emulator job (`reactivecircus/android-emulator-runner`, API 34 x86_64, KVM via udev) runs the `:core:crypto`, `:core:vault` and `:app` instrumented tests. The existing build job, gate and canary don't change. The 1 GiB benchmark is excluded.

Out of scope: import (PR4), the grid and viewer (PR5), auto-lock on background and throttling (PR7), recovery (PR8).

## Files
- `core/vault/src/main/.../vault/`: `VaultStorage.kt`, `FileVaultStorage.kt`, `SafIo.kt`, `SafVaultStorage.kt`, `VaultLocation.kt`, `VolumeMonitor.kt`, `VaultFiles.kt`, `ItemMeta.kt`, `ItemRepository.kt`, `VaultIndex.kt`, `VaultSession.kt`, `VaultKeys.kt`, `VaultExceptions.kt`
- `core/vault/src/test/...`: VaultFiles, ItemRepository, VaultIndex, VaultKeys, ItemMeta, LocationStore, tree parsing, availability rules
- `core/vault/src/androidTest/...`: `TestDocumentsProvider` (a temp-dir provider with ExternalStorageProvider's rename-on-clash behaviour) + `SafVaultStorageTest`
- `core/crypto/consumer-rules.pro`
- `app/.../AppGraph.kt`, `PrivateVaultApp.kt`, `ui/VaultViewModel.kt`, `ui/setup/*`, `ui/lock/*`, `ui/unlocked/*`, `ui/common/PinPad.kt`
- `app/proguard-releasecheck.pro`, `app/src/androidTest/...`: `ReleaseUnlockTest`, `SafBenchmarkTest`
- `gradle/libs.versions.toml`, module build files, `.github/workflows/ci.yml`
- `docs/STATUS.md`, `docs/HANDOFF.md`

## Tests
- JVM (`:core:vault`, real Tink, fake KDF and key wrapper, `FileVaultStorage` on a temp dir):
  - atomic replace keeps `.bak`; `.bak` fallback when `vault.meta` is missing or corrupt; stale `.tmp` handling;
  - staging cleanup; vault-id mismatch rejected; create refuses a folder that already has a vault;
  - read-only blocks create, delete and vault creation;
  - delete removes all 3 files and updates the index; list skips orphans and corrupt `.vmeta`; round trip of item data;
  - ItemMeta strict JSON; `location.json` round trip; tree/volume parsing; availability rules.
- Instrumented:
  - `:core:vault` `SafVaultStorageTest` on a test DocumentsProvider: PFDs closed, `"rw"` doesn't truncate before our explicit truncate, rename-clash detection, atomic `vault.meta`, item create/list/delete through SAF;
  - `:app` `ReleaseUnlockTest` on R8 output: create → lock → unlock (right PIN) / wrong PIN, with real argon2kt + Keystore on `FileVaultStorage`;
  - `:app` `SafBenchmarkTest`: 1 GiB SAF vs File, 200 seeks, list 1,000 items; `PV-BENCH` log; needs `-e treeUri`; skipped otherwise and excluded in CI.

## Open questions
- The benchmark and the eject, revoke and uninstall checks need the target phone (no KVM here).
- Listing 1,000 items: the acceptance number applies to the directory listing. The full index build (1,000 `.vmeta` decrypts, each a provider open) is logged too, and may need the encrypted index cache that was cut from the MVP.
- releaseCheck needs extra keep rules for our own classes that only tests call. They must never reach Tink or argon2kt.

## Status
Implemented locally on `feat/pr3-storage`; not pushed. Build/JVM/APK gates are recorded in `docs/STATUS.md`. Instrumentation compiles but remains unrun locally (no KVM); CI is configured, and phone acceptance remains pending.

Completion details:
- PIN-buffer implementation moved to `ui/common/PinBuffer.kt` to satisfy source-file lint/detekt rules.
- Monitored provider calls validate a fresh vault id and read-only flags before I/O; the session collector also handles an unwrap finishing after removal.
- `scripts/check-releasecheck-refs.sh` verifies minified app/test references, with explicit handling of tracer execution failures. Shared Kotlin/coroutines/test runtime keeps apply only to releaseCheck.
- The app's only instrumentation target is releaseCheck, as required for R8 verification. The 1 GiB phone benchmark is explicitly excluded from CI.
