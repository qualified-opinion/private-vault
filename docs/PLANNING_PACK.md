# Private Vault planning pack

Version 2 (2026-10-28). Owner: Jan. This is the reference that the code, `docs/plans/prN.md`, `docs/STATUS.md` and `docs/HANDOFF.md` cite as "planning pack §N".

**Where this came from.** The original pack was never saved in the repo. This version was rebuilt from what the code and docs already rely on, and keeps the same section numbers so every existing "§" reference still points to the right place. Rules that were already built and tested are stated as fact. Plans for PR5–PR8 that nobody had written down are marked **(proposed)** and need Jan's OK before that stage starts. When the code and this pack disagree, the code and its tests win; fix the pack in the same PR.

## Contents
1. Goal and scope
2. Architecture
3. File format
4. Keys and PIN
5. Storage
6. Stage plan (PR1–PR8)
7. Risks
8. Acceptance checks by area
9. How we work, and open questions

---

## 1. Goal and scope

A standalone Android app that keeps photos and videos encrypted in a folder on the phone's SD card. It works fully offline.

**Must have (MVP)**
- Set up a vault in a folder on the SD card, protected by a PIN.
- Import photos and videos from the gallery; browse them in a grid; view photos; play videos.
- Lock on demand, when the card is removed, and when the app goes to the background (PR7).
- A recovery phrase so the vault can be opened after reinstalling the app or on a new phone (PR8).

**Never**
- No internet access of any kind: no `INTERNET` permission, no network libraries. CI proves it on every change (§8 Build).
- No plaintext on disk: not in the vault folder, not in the app's cache, not in thumbnails.
- No cloud backup of app data (`allowBackup=false`, all backup domains excluded).
- No silent fallback to internal storage, and never creating a new vault over an existing one.

**Not in the MVP**
- Biometrics, multiple vaults, sharing or exporting out of the vault, editing photos, duplicate detection, deleting originals from the gallery after import (Jan's decision, 2026-10-28; may come later as its own small PR).

**Target device**
- minSdk 26, compile and target SDK 37.
- An older phone with a removable SD card. Its exact model, API level and card filesystem are still open (§9, Q1).

## 2. Architecture

| Module | Package | Holds |
|---|---|---|
| `:app` | `com.qualifiedopinion.privatevault` | `AppGraph` (manual DI, one per process), Compose UI, `VaultViewModel`, lock orchestration |
| `:core:crypto` | `.crypto`, `.format` | Tink streaming encryption, file header and `vault.meta` codecs, Argon2id, Keystore wrap |
| `:core:vault` | `.vault` | Storage backends (SAF, File), vault layout, items, index, session, location, card monitor |
| `:core:media` | `.media` | Thumbnail cache and player data source (PR5/PR6) |
| `:feature:import` | `.importer` (`import` is a Kotlin keyword) | Picker sources, media probing, the import loop |

**Runtime state**
- `VaultSession` holds the data keyset (DK) and its cipher while the vault is unlocked. Every open channel is registered with it.
- `lock()` drops the key references, closes all tracked channels, clears the index and runs the lock listeners. Later stages add the image cache (PR5) and the player (PR6) to those listeners.
- The JVM can't reliably wipe the Tink keyset from memory; dropping every reference is what we can do (risk 7).
- `VaultIndex` is the in-memory list of items. It is rebuilt on every unlock by decrypting each `.vmeta`. There is no persistent index file in the MVP; add an encrypted index cache only if the phone measurement says unlock is too slow (F4).
- `AppGraph` locks the session whenever the card monitor reports the folder unavailable, whatever screen is showing.
- One write lock covers everything that writes items (import, startup clean-up, later delete and PIN change).

**Dependency rules**
- No network libraries. The denylist in `scripts/check-permissions.sh` is a backup to the permission check.
- Every dependency is pinned in `gradle/libs.versions.toml`.
- Log calls are stripped from release builds by R8.

## 3. File format

All multi-byte numbers are big-endian. Ids are 16 random bytes from `SecureRandom`, shown as 32 lowercase hex characters.

### 3.1 Folder layout
```
<vault folder>/
  vault.meta            keys and KDF settings (§3.3)
  vault.meta.bak        previous vault.meta (written by atomic replace)
  items/<id>.vdat       encrypted original (type 02)
  items/<id>.vthm       encrypted JPEG thumbnail (type 03)
  items/<id>.vmeta      encrypted item metadata (type 04)
  staging/<id>.vdat.part  data being written; moved into items/ only when complete and synced
  journal/              reserved for multi-step operations (type 05)
```
- An item exists once its `.vmeta` exists. Create order: data, thumbnail, then `.vmeta` last. Delete order: `.vmeta` first.
- Startup clean-up removes `staging/*.part`, plus `.vdat`/`.vthm` files with no `.vmeta` (F8). It holds the write lock, so it never runs during an import (F9).
- `vault.meta` is replaced atomically: write `.tmp`, sync, read back, keep the old file as `.bak`, then rename. On read, fall back to `.bak` only when `vault.meta` is missing or doesn't parse. An I/O error means "folder unavailable", not "bad file".

### 3.2 File header (32 bytes, every file)
```
0   4   magic "PVLT"
4   1   file type: 01 vault.meta, 02 data, 03 thumbnail, 04 item meta, 05 journal, 06 recovery
5   1   format major = 1
6   2   flags = 0 (reserved)
8   16  vault id (types 01, 05, 06) or item id (02–04)
24  8   reserved = 0
```
- Readers reject unknown magic, unknown type, a major they don't know, and non-zero flags or reserved bytes. They never guess.
- Decrypt calls take the *expected* header, built from the file name and type, and reject a file whose header differs before decryption starts. So a renamed or swapped file fails.

### 3.3 `vault.meta`
```
0   32  header (type 01, vault id)
32  1   kdf id: 01 = Argon2id v1.3
33  4   memory KiB (u32)
37  4   iterations (u32)
41  1   parallelism
42  1   salt length = 16
43  16  salt
59  1   wrap scheme: 01 = KS(PIN(DK)) (§4.2)
60  4   outer blob length N (u32, 1..4096)
64  N   outer blob
```
- Bytes `[0,60)` are the AAD of both wrap layers, so the header and KDF settings can't be edited.
- Defaults are 64 MiB, 3 passes, then calibrated (§4.3).
- Parse limits stop a damaged file from exhausting memory: at most 1 GiB, 64 passes, 16 lanes.

### 3.4 Streaming encryption (`.vdat`, `.vthm`, `.vmeta`)
- Tink `AES256_GCM_HKDF_1MB` streaming AEAD.
- The file is the 32-byte header followed by Tink's ciphertext. AAD = the header bytes, which binds type, version and id.
- The DK keyset must hold exactly one enabled key of that type. `VaultCrypto` uses the single-key primitive, because Tink's keyset-level seekable channel fails on `size()` before the first read, and the video player needs `size()` first.
- `OffsetSeekableChannel` hides the 32-byte header so Tink sees its ciphertext at position 0.
- `EofVerifyingChannel` authenticates the last segment before returning end-of-file. So a file cut short fails instead of reading as a shorter file; this also covers `position()` chaining (F1).
- Bulk data goes through channels with 64–256 KiB buffers, never a whole file in memory. Tink itself holds two 1 MiB segment buffers per channel.

### 3.5 Item metadata (`.vmeta` plaintext, JSON v1)
```json
{"v":1,"id":"<hex>","kind":"PHOTO|VIDEO","mime":"image/jpeg","name":"IMG_0001.jpg",
 "size":12345,"sha256":"<hex>","w":4000,"h":3000,"rot":90,"durMs":null,
 "takenAt":1700000000000,"importedAt":1700000000000}
```
- Parsing is strict: unknown keys or another version are rejected; the id must match the file name; at most 64 KiB.
- `toString()` of metadata objects never includes the name, hash or id, so they can't leak into a log.

### 3.6 Recovery file (proposed, PR8)
- `recovery.vrec`, type 06, holds the DK wrapped under a key derived from the recovery phrase with Argon2id. It does not use the Keystore.
- With it, a reinstall or new phone can open the vault using the phrase alone, then re-wrap the DK under a new PIN and Keystore key.

## 4. Keys and PIN

### 4.1 Key hierarchy
- **DK** (data key): one Tink keyset per vault, created at setup, never stored unwrapped. It encrypts every item file.
- **KEK_pin**: Argon2id(PIN, salt, settings from `vault.meta`). It lives only in memory and is wiped after use.
- **Keystore key**: a non-exportable AES-256-GCM key in Android Keystore, alias `pv_kek_<vault id hex>`. It has no user-authentication requirement and doesn't ask for StrongBox (older phone).

### 4.2 Layered wrap `KS(PIN(DK))`
1. Inner layer: DK as a Tink encrypted keyset under AES-GCM with KEK_pin.
2. Outer layer: the inner blob encrypted with the Keystore key (`iv(12) ‖ ciphertext ‖ tag(16)`, IV chosen by Keystore).

Both layers use AAD = `vault.meta[0,60)`.

**What this means**
- The SD card alone can't be opened without this phone's Keystore and the PIN.
- If the app is uninstalled or the phone replaced, the Keystore key is gone, and only recovery (PR8) can open the vault.
- Errors are typed: `WrongPinException` when the inner layer fails (feeds throttling, PR7), `KeyWrapperKeyMissingException` when the Keystore key is gone (leads to recovery, PR8).

### 4.3 PIN and Argon2
- The PIN is 6–12 digits.
- It is only ever held as a `ByteArray` or `CharArray`, never a `String`, and wiped after use.
- Argon2id floor: 32 MiB memory and 2 passes. Setup refuses anything below the floor.
- At setup, calibration aims for about 0.8–1.5 s on the phone, up to 10 passes. If it runs out of memory, it steps the memory down, never below the floor.

### 4.4 Lock, throttling and PIN change (PR7)
- **Auto-lock**: lock when the app goes to the background or the screen turns off. An optional grace period (proposed: none by default).
- **Throttling** (proposed): after 5 wrong PINs, wait 30 s, doubling each time up to 1 hour. Keep the counter in `noBackupFilesDir` so restarting the app doesn't reset it. Wrong-PIN detection is the typed exception from §4.2.
- **Change PIN**: re-wrap the DK under the new PIN and write `vault.meta` with the atomic replace (§3.1).
- **Backup fallback**: if unlocking with `vault.meta` fails, try `vault.meta.bak` before saying "wrong PIN" (F6).
- **Interrupted save**: on startup, a complete `vault.meta.tmp` is put back in place (F7).
- **Warning**: show a one-time warning if the Keystore is software-only on this phone.

### 4.5 Data-loss rules
- Setup makes the user confirm, with a checkbox, that a forgotten PIN plus a lost recovery phrase means the data is gone.
- Until PR8 ships, the app must not be the only copy of any photo. The PR4 import keeps originals for this reason.

## 5. Storage

### 5.1 Where the vault lives
- The vault is a folder picked with the system folder picker (`OpenDocumentTree`), with a persisted read/write grant.
- Only trees from `com.android.externalstorage.documents` are accepted.
- Listing uses the provider's child-documents query.

### 5.2 Channels, not streams
- All file I/O goes through `VaultStorage`, which has two backends: `SafVaultStorage` on the phone, and `FileVaultStorage` for tests and benchmarks.
- Files open as `FileChannel` from a `ParcelFileDescriptor`. Writes use mode `"rw"`, never `"w"`, which can truncate early on some providers. Every descriptor is closed.
- The provider can silently rename a file on a name clash (`x (1)`), so create and rename check the name they actually got.

### 5.3 Location and volume binding
- `noBackupFilesDir/location.json` stores the tree URI, the volume (`primary` or the card's UUID) and the vault id. The vault id isn't secret.
- On API 29+, the picker opens at the SD card root.
- An SD card set up as "adopted" internal storage is not supported (§9, Q4).

### 5.4 Availability and the error screen
- **Card removed**: a receiver for the card being ejected, unmounted or removed marks the vault unavailable at once, and the app locks. Mounting the card again triggers a full check.
- **Quick check**: grant plus volume state, every second while the app is in the foreground.
- **Before every provider call**: grant, volume and a fresh vault-id check. Whether this costs too much on a 1,000-item vault is still open (F4).
- **Full check**: the quick check, plus `vault.meta` exists with the bound vault id, plus read-only flags. It runs at start, on resume and on Retry. A check that started before a removal can't mark the vault available afterwards (F3).
- **Provider errors**: any provider error counts as "vault gone".
- **Error screen**: "Vault folder not available. Insert the card or pick the folder again", with Retry and Re-pick. Re-pick accepts only a folder holding the same vault id.
- **Read-only card or grant**: view only. Import, delete and PIN change are blocked.

### 5.5 Internal storage
- A folder on internal storage is allowed only as an explicit choice at setup, after a warning. It is never used as a fallback when the card is missing.

## 6. Stage plan

Each stage is one PR with its own plan in `docs/plans/prN.md`, written before coding. Jan merges every PR.

| PR | Stage | State (2026-10-28) |
|---|---|---|
| PR1 | Scaffold, CI, lint, no-INTERNET check | Merged (#1) |
| PR2 | Crypto core and tests | Merged (#2); F1 fix merged (#4) |
| PR3 | Vault storage on the SD card, setup, unlock | Draft PR #3; F2/F3/F5 fixes merged into it; waiting for the phone checks |
| PR4 | Import photos and videos | Draft PR #8, stacked on PR3, CI green; defaults confirmed by Jan |
| PR5 | Grid and photo viewer | Not started |
| PR6 | Video player | Not started |
| PR7 | Auto-lock, PIN limits, change PIN | Not started |
| PR8 | Recovery phrase | Not started |

**PR1. Scaffold** (done)
- Five modules, Compose theme, a hardened manifest, R8 with Log stripping, detekt, lint, and CI.
- The permission gate and an INTERNET canary that proves the gate works.

**PR2. Crypto** (done)
- §3.2–3.4, §4.1–4.3.
- Tests: round trips at every segment boundary, tamper cases, a 256 MiB run in CI (3 GiB on demand) under a 64 MiB test heap, and key-wrap cases.

**PR3. Storage, setup, unlock** (in review)
- §3.1, §3.5 and §5, plus setup and unlock screens and the error screen.
- An emulator CI job and an SD-card benchmark to run on the phone.
- Acceptance: §8 Storage.

**PR4. Import** (in review)
- System photo picker with no permissions. Items are encrypted one at a time through `staging/` with a 256 KiB copy buffer.
- A 320 px thumbnail made in memory. Item is skipped if `encrypted size + 16 MiB` exceeds the free space.
- Locking cancels the import and removes the partial item.
- Originals are kept. There is no duplicate check.
- Includes F8 and F9. Details in `docs/plans/pr4.md`.

**PR5. Grid and photo viewer** (proposed)
- A lazy grid of decrypted thumbnails, newest first by `takenAt`, then `importedAt`.
- A full-screen photo viewer with EXIF rotation. Delete with confirmation.
- Images load through Coil with a custom fetcher that decrypts from the vault. Memory cache only: no disk cache, and the cache is cleared on lock.
- Large photos are decoded downsampled to the screen size.
- `FLAG_SECURE` must be in place (F5, already merged into PR3).
- Acceptance: §8 Viewer.

**PR6. Video player** (proposed)
- Media3 ExoPlayer with a custom `DataSource` over `ItemRepository.openData` (a seekable decrypting channel). No network data sources.
- Seeking works; the player is released on lock and when the app is backgrounded.
- A file cut short shows an error at the cut point instead of just stopping (relies on F1 and F2).
- Acceptance: §8 Player.

**PR7. Auto-lock, PIN limits, change PIN** (proposed)
- §4.4, with F6, F7 and F10. Optionally F4 if the phone measurement says unlock is slow.

**PR8. Recovery phrase** (proposed)
- At setup, or later from settings for existing vaults, show a 24-word phrase once and make the user confirm a few words. Write `recovery.vrec` (§3.6).
- Restore: pick the folder, enter the phrase, set a new PIN. That re-creates the Keystore key and `vault.meta`.
- This is the biggest open risk: until it ships, uninstalling the app or changing phone means the vault can't be opened. Consider doing PR8 before PR5/PR6 (review recommendation, 2026-10-24).

**Small items to fold in when convenient**
- F11 (sync `location.json` before renaming).
- F12 (delete the Keystore key if setup fails).
- F15 (wipe Argon2 input buffers).
- F16 (longer network-library denylist).
- F17 (release signing and README build steps, before publishing).
- F13 (check the docs after PR3 merges).

## 7. Risks

Numbers are fixed because the code cites them. Some numbers were inferred from those citations when this pack was rebuilt.

| # | Risk | Mitigation | Status |
|---|---|---|---|
| 1 | Keystore misbehaves on the older target phone, or its key is lost | Instrumented round-trip test on the phone; recovery (PR8) for a lost key | Phone test pending (F14) |
| 2 | Uninstall, factory reset or a new phone loses the Keystore key, so the vault can't be opened | Recovery phrase (PR8); keep originals until then | Open |
| 3 | SD card removed mid-write corrupts an item | Staging, then move; `.vmeta` last; startup clean-up (F8) | Done (PR3/PR4) |
| 4 | Provider renames, fsync and durability differ between phones | Name checks; atomic `vault.meta` replace; phone checks | Partly open |
| 5 | Plaintext reaches disk (cache, thumbnails, logs) | In-memory thumbnails; no disk image cache; Log stripped in release; `toString()` hides names | Ongoing rule |
| 6 | A network dependency sneaks in | Manifest remove-markers, APK permission gate, denylist, canary | Done (PR1) |
| 7 | The Tink keyset can't be wiped from JVM memory | Drop all references on lock; lock on background (PR7) | Accepted |
| 8 | Argon2 too slow or too memory-hungry on the phone | Calibration with floor and OOM step-down; measure on the phone | Phone test pending |
| 9 | Native argon2kt wrong or stripped by R8 in release | Reference-vector test on the device; consumer rules; minified unlock test | Emulator CI green; phone pending |
| 10 | Unlock slow on a large vault (one provider open per `.vmeta`) | Measure with 1,000 items; fix F4 or add an encrypted index cache if over about 2 s | Open |
| 11 | Screenshots or the app switcher show private photos | `FLAG_SECURE` (F5) | Done (PR3) |
| 12 | Someone holding the unlocked phone, or guessing PINs | Auto-lock and throttling (PR7) | Open |

## 8. Acceptance checks by area

**Build (every PR)**
- `./gradlew lint detekt testDebugUnitTest assembleRelease assembleDebug` passes. Lint aborts on error; detekt allows 0 issues.
- `scripts/check-permissions.sh` passes on the real release APK.
- The emulator CI job (SAF, Keystore, minified unlock) passes.

**Crypto (PR2)**
- Round trip at every segment boundary, through both channel and stream.
- Every tamper case fails: header bytes, mid-file byte, last tag, truncation, segment swap, swapped id, changed type.
- The 256 MiB streaming test stays under 48 MiB of heap.
- Key wrap: wrong PIN, tampered settings and a missing key fail with typed errors.

**Storage (PR3)**
- Atomic `vault.meta` with `.bak` fallback.
- Read-only blocks writes. A re-pick of a different vault is refused.
- Card eject or a revoked grant locks within 1 s. Reinserting the card and tapping Retry works.
- The grant survives reboot and app update. The vault folder survives uninstall.
- Benchmark on the phone: SAF at least 0.8× File speed for write and read; average seek under 50 ms; listing 1,000 items under 2 s.

**Import (PR4)**
- Photos and videos are imported and read back identical.
- Unsupported, too-big and unreadable items are counted and skipped, and the run continues.
- Locking mid-item leaves nothing in `items/` or `staging/`.
- Originals are untouched.

**Viewer (PR5)** (proposed)
- 1,000 items scroll smoothly on the phone.
- Nothing about any item is written to the app's cache or files folders (checked with `adb shell run-as`).
- Lock clears the screen and the memory cache.
- Delete removes all three files.

**Player (PR6)** (proposed)
- A 1 GB video starts within 2 s and seeks within 1 s on the phone.
- A file cut short shows an error.
- Locking or backgrounding stops playback and closes the channel.

**Lock and PIN (PR7)** (proposed)
- Going to the background locks.
- Throttling survives an app restart.
- Changing the PIN keeps every item readable.
- A damaged `vault.meta` falls back to `.bak` without saying "wrong PIN".

**Recovery (PR8)** (proposed)
- Uninstall, reinstall, phrase, new PIN: all items open.
- A wrong phrase fails cleanly.
- The phrase is never written to disk or logs.

## 9. How we work, and open questions

### Working method
- **Plan first**: each stage starts with `docs/plans/prN.md` (scope, files, tests, open questions). Code follows the plan, and the plan is updated when reality differs.
- **Small PRs**: Jan merges every PR. Bug fixes found in review go as small PRs into the stage branch, so the stage is merged once.
- **Switching engines** (Claude Code, Codex, or a new session):
  - before switching, commit `wip(prN): <state>` and add an entry at the top of `docs/HANDOFF.md`: branch, done, in progress, failing tests, decisions with reasons, do-not-touch list, and open questions;
  - the incoming engine reads HANDOFF, STATUS and the stage plan, then runs the tests before changing anything.
- **Build and test**:
  - every Gradle run is bounded (`timeout 20m ./gradlew … --no-daemon --console=plain`);
  - cloud sessions without the Android SDK rely on GitHub CI as the build of record, and say so.
- **Keep fixed**: the 64 MiB test heaps on `:core:crypto`, `:core:vault` and `:feature:import`, the PR1 permission markers and gate, and the Log stripping rule.
- **Explaining to Jan**: plain language, with a phone test for every user-visible change.

### Open questions for Jan
1. **Target phone**: model, Android version (API level) and the SD card's filesystem (FAT32 limits files to 4 GiB; exFAT doesn't). *Open.*
2. **App id**: `com.qualifiedopinion.privatevault` as the permanent application id. *In use since PR1; please confirm before publishing.*
3. **PIN length**: 6–12 digits. *Built that way in PR2; please confirm.*
4. **SD card mode**: the card is used as portable storage, not "adopted" as internal storage. *Open; please check in the phone's storage settings.*
5. **Change PIN in PR7**: yes, together with auto-lock and throttling. *Planned.*
6. **Recovery timing**: move PR8 before PR5/PR6? *Recommended in the 2026-10-24 review; Jan to decide.*
7. **Throttling numbers**: 5 tries, then 30 s doubling to 1 hour (§4.4)? *Proposed.*

### Decisions log
- 2026-10-14: compile/target SDK 37; Gradle 9.8.1; AGP 9 built-in Kotlin; detekt 1.23.8; release unsigned, debug APK sideloaded.
- 2026-10-15: single-key Tink primitive and the EOF check (§3.4); Argon2 floor enforced at setup only (§4.3); only ExternalStorageProvider trees; internal storage only as a warned choice.
- 2026-10-24: code review findings F1–F17 (report: "Private Vault code review and roadmap", Oct 2026). F1, F2, F3 and F5 are fixed.
- 2026-10-28: PR4 defaults confirmed by Jan: originals stay in the gallery, and there's no duplicate check. This pack was rebuilt (version 2).
