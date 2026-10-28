# Handoff log

Protocol (planning pack §9, `docs/PLANNING_PACK.md`): before an engine switch, commit `wip(prN): <state>`, then add an entry at the top of this file. The incoming engine reads this file, `docs/STATUS.md` and `docs/plans/prN.md` first, then runs the tests before changing anything.

## Handoff 2026-10-28 Claude Code (cloud)→next session
Branch: `claude/project-thread-av3x1w`, based on `feat/pr3-storage` @ baa3aba (PR3 still a draft, so PR4 stacks on it).
Done: PR4 import (plan `docs/plans/pr4.md`), plus review findings F8 (orphan cleanup) and F9 (one write lock for import and startup cleanup).
Failing tests: see the PR's CI. No Android SDK in this session, so nothing was built locally.
Decisions made: PR4 defaults in the plan's table, confirmed by Jan 2026-10-28 (originals kept, picker, no duplicate check, lock cancels import). The planning pack was rebuilt as `docs/PLANNING_PACK.md` (v2) with the same section numbers.
Open questions for Jan: planning pack §9 questions; run the phone checks in the PR description after PR3's phone checks.

## Handoff 2026-10-15 07:26 Asia/Manila Codex→next session / Kiln
Branch: `feat/pr3-storage` @ `8adb86f` + uncommitted completion changes; main remains `74bfd5e`.
Done: PR3 implementation, CI and documentation from `/tmp/pr3prompt.txt` and `docs/plans/pr3.md`.
- Existing commits: `9307e3d` plan first; `0a0e8a7` vault storage; `8adb86f` app/setup/unlock WIP.
- Completed interrupted build fixes, including separate `PinBuffer.kt`, SAF test constructor and releaseCheck shared-runtime keeps.
- Monitored SAF access now validates a fresh vault id before provider I/O and refreshes read-only flags before mutation. AppGraph observes both availability and session state, so a slow unwrap finishing after removal is locked too. Regression instrumentation added.
- Added API 34 x86_64 emulator CI with KVM udev rule. It runs crypto/vault debug instrumentation and minified app releaseCheck instrumentation; `SafBenchmarkTest` is explicitly excluded. Existing build job, permission gate and canary are byte-for-byte unchanged.
- Final timed Gradle check: lint, detekt, testDebugUnitTest, assembleRelease, assembleDebug, crypto/vault debug androidTest, app releaseCheck + releaseCheck androidTest: BUILD SUCCESSFUL in 1m 58s. JVM: 102 passed (60 crypto, 39 vault, 3 scaffold), zero failures/errors/skips. APK permission and releaseCheck-reference scripts passed. YAML parsed; simulated tracer failure exits 2.
- STATUS has PR2 merged at PR #2/main 74bfd5e, PR3 evidence, adb benchmark/unlock commands, honest local instrumentation limit and the exact PR6 truncation note.
In progress / blocked:
- Completion commits could not be created: `git add` failed because `.git/index.lock` is read-only under this session's filesystem policy. Changes are unstaged in the working tree, including new `ui/common/PinBuffer.kt`. Do not discard them. No Git history was rewritten.
- In a session with Git write access, create these logical commits (paths shown below), then verify a clean tree. There is no need to rerun successful builds unless code changes.

| Suggested commit | Paths |
|---|---|
| `fix(vault): revalidate storage and lock late unlocks after removal` | `AppGraph.kt`, `ReleaseUnlockTest.kt`, `VolumeMonitor.kt`, `SafVaultStorage.kt`, `SafVaultStorageTest.kt` |
| `fix(app): finish PIN UI checks and releaseCheck test linkage` | `VaultViewModel.kt`, `ui/common/MessagePanel.kt`, `PinPad.kt`, new `PinBuffer.kt`, `app/proguard-releasecheck.pro`, `scripts/check-releasecheck-refs.sh` |
| `ci(pr3): run SAF and minified unlock tests on API 34` | `.github/workflows/ci.yml` |
| `docs(pr3): record verification and phone acceptance handoff` | `docs/STATUS.md`, `docs/HANDOFF.md`, `docs/plans/pr3.md` |

Failing tests: none in completed local checks. Instrumented tests **not run locally** (no usable `/dev/kvm`, no connected phone); APKs compile only. CI has not run on GitHub. Phone benchmark and manual grant/eject/revoke/reboot/update/uninstall acceptance remain unverified.
Decisions made (with reason):
- Every Gradle invocation must be `timeout 20m ./gradlew ... --no-daemon --console=plain`. The final build also uses `-Pkotlin.compiler.execution.strategy=in-process` because the daemon marker location in home is read-only. No hang occurred; one detekt ThrowsCount finding from the new guard was fixed before the final successful build.
- The unchanged permission script calls Gradle internally. To honor the timeout without modifying the gate, run it with `BASH_ENV` pointing to a temporary file containing `function ./gradlew() { timeout 20m ./gradlew "$@" --no-daemon --console=plain; }`. An initial attempt to export the function failed, causing one unbounded dependency query; the corrected bounded gate run passed.
- releaseCheck is minified/non-debuggable but debug-signed. Test-only rules keep our public APIs and shared Kotlin/coroutines/test runtime; they do not blanket-keep Tink/argon2kt. Reference tracing rejects missing non-platform calls and tool startup failures; it does not prove runtime JNI works.
- Only ExternalStorageProvider trees are supported in production. Internal storage is an explicit setup choice with a warning, never a fallback. Fresh vault-id checks add provider queries; full index-build time needs phone evidence.
- Follow PR2's expected-header rule and consume/wipe PIN arrays. Recovery remains PR8; background/screen-off lock, throttling and software-Keystore warning remain PR7.
Do not touch: main; PR1 gate/canary/permission-removal markers and Log stripping; local.properties; crypto/vault 64 MiB unit-test heaps. Do not push or use gh.
Open questions for Jan/Kiln:
- Run emulator CI in an authorized future push, and run the minified unlock test and 1 GiB benchmark on the target phone (STATUS commands). Benchmark uses a persisted tree grant and app-specific baseline on the same card; record page-cache limits and full index-build latency.
- Verify eject/revoke lock within 1 s, reinsertion Retry, wrong-vault re-pick, read-only view, reboot/update grants, cleanup/delete and vault survival after uninstall. Uninstall loses the Keystore alias; PIN-only reinstall recovery is not supported before PR8.
- Review provider-dependent rename/fsync durability and the static-check exceptions/shared-runtime keep rules. Planning-pack §9 device/API/filesystem questions remain open.

## Handoff 2026-10-15 06:20 Asia/Manila Claude Code→next session
Branch: feat/pr2-crypto @ (this docs commit; code at 8f3cc8b), cut from main @ 5ef91a6 (PR1 merged as PR #1)
Done: All of PR2 scope in `docs/plans/pr2.md`.
- `:core:crypto`: FileHeader, VaultMetaCodec, VaultCrypto, OffsetSeekableChannel, Argon2Kdf + KdfCalibrator, KeyWrapper + KeystoreKeyWrapper, LayeredKeyWrap, Pins.
- 60 JVM tests green under -Xmx64m.
- 3 GiB run passed: 33.0 s, peak heap 20.0 MiB (`docs/logs/pr2-large-test.md`).
- lint, detekt and the permission gate are green.
- CI gained a `largeTests` workflow_dispatch input.
- Nothing is pushed.
In progress: nothing. Next steps:
1. Run `./gradlew :core:crypto:connectedDebugAndroidTest` on an emulator and on the target phone. Record `adb logcat -s PV-CRYPTO`: Keystore secure-hardware flag, unwrap ms at floor params, calibrated params and ms, and 64 MiB encrypt/decrypt ms. Put the results in STATUS.md / the PR.
2. Kiln review, then Jan's OK.
3. Push and open the PR.
Failing tests: none on the JVM. Instrumented tests have not run (no device on the build box).
Decisions made (with reason):
- VaultCrypto uses `AesGcmHkdfStreaming.create(primaryKey)`, not `handle.getPrimitive(StreamingAead)`. The keyset-level seekable channel throws on `size()` before the first read, and PR6's Media3 DataSource needs `size()` first. The DK must be exactly one enabled AES256_GCM_HKDF_1MB key.
- The seekable channel is wrapped (`EofVerifyingChannel`). Tink returns 0, not −1, at EOF unless the last segment is loaded, and its `size()` is unauthenticated. The wrapper authenticates the final segment before the first −1, so truncation can't read as a shorter file.
- Decrypt APIs take the expected FileHeader (type + id) and reject a mismatch before Tink runs. PR3/PR6 must pass the header built from the file name/index (`FileHeader(type, id)`), never the header read from the file.
- `wrap` enforces the floor and the PIN rules. `unwrap` accepts stored params (AAD-bound) within hard limits (≤ 1 GiB, t ≤ 64, p ≤ 16).
- PIN arrays passed to wrap/unwrap are zeroized by those calls (ByteArray and CharArray overloads). The caller must not reuse them.
- JVM tests use BouncyCastle Argon2id (testImplementation only), checked against the phc-winner-argon2 vectors; androidTest checks argon2kt against the same vectors. tink-android runs on the JVM, so the `tink` jar isn't needed.
- argon2kt's `appcompat`/`core-ktx` runtime deps are excluded (only `androidx.annotation.Keep` is used).
Do not touch: the PR1 items listed below. Also keep `maxHeapSize = "64m"` on `:core:crypto` tests: it is the streaming memory gate.
Open questions for Jan/Kiln:
- Planning-pack §9 questions 1–5 are still open.
- R8 + argon2kt JNI in release: argon2kt ships no consumer rules and relies on `@Keep` plus AGP's default `native` keep rule. PR3 is the first release build that calls it, so verify unlock on a release build there.
- Calibration target 0.8–1.5 s with a 10-pass cap: confirm on the target phone with the instrumented log.

## Handoff 2026-10-14 22:40 Asia/Manila Claude Code→Codex/Claude Code (next session)
Branch: feat/pr1-scaffold @ 4693ad4 (+ this docs commit)
Done: All of PR1 scope in `docs/plans/pr1.md`. Gradle 9.8.1 wrapper, version catalog, 5 modules, Compose theme + empty NavHost, hardened manifest + no-backup rules, R8 + Log stripping, detekt + lint config, `scripts/check-permissions.sh`, `scripts/canary-internet.sh`, CI workflow. Verified locally: lint/detekt/tests/assemble green, gate passes, canary caught (see STATUS.md log).
In progress: nothing. Next is the PR1 review (Kiln), then Jan's OK. After plan approval, push the branch and open the PR, pasting the STATUS.md verification log as evidence. PR2 starts at `docs/plans/pr2.md` on `feat/pr2-crypto` cut from main.
Failing tests: none.
Decisions made (with reason):
- compileSdk/targetSdk 37: `android-37.0` is a final (non-preview) platform and AGP 9.4.1 builds against it cleanly.
- Gradle 9.8.1, not the local 9.3.1: AGP 9.4.1 won't load on 9.3.1.
- AGP 9 built-in Kotlin. KGP is pinned to 2.4.21 by a root `apply false` of `kotlin-android`. Modules must NOT apply `org.jetbrains.kotlin.android`.
- detekt 1.23.8 (stable), applied to every subproject from the root build. 2.0 is alpha.
- All core/feature modules are Android libraries. They will need Keystore, SAF, Media3 and WorkManager.
- `:feature:import` package is `.importer`, because `import` is a Kotlin keyword. This matches plan §2.
- Canary lives in a script so CI and local runs are identical. It always restores the manifest via `trap`. In CI it runs after the APK upload, so the artifact is never the canary build.
- Release APK is unsigned and no keystore is in the repo. Jan sideloads the debug APK artifact.
Do not touch: the `tools:node="remove"` permission lines (the canary's sed matches their exact text), `app/proguard-rules.pro` Log rule, and `local.properties` (gitignored, machine-specific).
Open questions for Jan/Kiln:
- Planning-pack §9 questions 1–5 are still open (target phone API level and SD filesystem, applicationId OK, PIN length, SD card not adopted, change-PIN in PR7).
- CI not yet run on GitHub. Confirm on the first push that `sdkmanager` installs `platforms;android-37.0` on ubuntu-latest.
