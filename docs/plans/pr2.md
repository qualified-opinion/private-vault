# PR2 plan: Crypto core and tests

Branch: `feat/pr2-crypto` (cut from `main` @ 5ef91a6). Planning pack: §3 (format), §4 (keys), §6 PR2, §8 Crypto.

## Scope
- `VaultCrypto`: `newEncryptingChannel`, `newSeekableDecryptingChannel`, `newDecryptingStream`. Tink `StreamingAead`, `AES256_GCM_HKDF_1MB`, AAD = header `[0,32)`.
- `OffsetSeekableChannel(base, 32)`: shifts `position()`/`size()` so Tink sees ciphertext at position 0.
- `FileHeader` codec (§3.2): 32 bytes, rejects unknown magic, unknown type, major > 1, non-zero flags/reserved. Random 16-byte ids from `SecureRandom`.
- `Argon2Kdf` (argon2kt, Argon2id v1.3) behind a `PinKdf` interface, plus calibration (~0.8–1.5 s, floor m = 32 MiB, t = 2, `OutOfMemoryError` steps m down, never below the floor).
- `KeyWrapper` interface + `KeystoreKeyWrapper` (AES-256-GCM, randomized IV, no user auth, no StrongBox, alias `pv_kek_<hex(vaultId)>`).
- Layered wrap/unwrap `KS(PIN(DK))` (§4.2): inner = Tink encrypted keyset under `AesGcmJce(KEK_pin)`, outer = `KeyWrapper`. Both use AAD = `vault.meta[0,60)`.
- `VaultMetaCodec` (§3.3).
- PIN is a `ByteArray`/`CharArray`, never a `String`. KEK and PIN arrays are zeroized.

Out of scope: SAF I/O, `vault.meta` atomic replace, `ItemMetaCodec`/JSON (no `kotlinx-serialization` yet), throttling, UI.

## Files
- `core/crypto/src/main/java/com/qualifiedopinion/privatevault/crypto/`: `VaultCrypto.kt`, `OffsetSeekableChannel.kt`, `PinKdf.kt` (interface + `KdfParams` + calibration), `Argon2Kdf.kt`, `KeyWrapper.kt`, `KeystoreKeyWrapper.kt`, `LayeredKeyWrap.kt`, `Pins.kt`
- `.../format/`: `FileHeader.kt`, `VaultMetaCodec.kt`, `VaultFormatException.kt`
- `core/crypto/src/test/...`: round trip, large streaming + seek, tamper, header, vault.meta, key wrap, calibration, offset channel tests; test helpers (splitmix64 generator, software `KeyWrapper`, BouncyCastle Argon2id `PinKdf`)
- `core/crypto/src/androidTest/.../KeystoreKeyWrapperTest.kt`
- `gradle/libs.versions.toml`, `core/crypto/build.gradle.kts`, `.github/workflows/ci.yml` (optional `largeTests` input)
- `docs/STATUS.md`, `docs/HANDOFF.md`, `docs/logs/pr2-large-test.md`

## Design notes
- Tink's keyset-level seekable channel throws on `size()` before the first `read()`. Media3 needs `size()` first (§6 PR6), so `VaultCrypto` takes the single primary key and uses `AesGcmHkdfStreaming.create(key)`. The keyset must hold exactly one enabled `AES256_GCM_HKDF_1MB` key; anything else is rejected.
- Decrypt calls take the expected `FileHeader` (type + id). The file's 32 header bytes must parse and equal the expected header exactly; then AAD = those bytes. A renamed or swapped file fails before Tink runs; a forged header fails the Tink tag.
- `vault.meta` floor: wrap (setup) requires params ≥ floor. Unwrap accepts what's stored (AAD-bound) but rejects absurd values (m > 1 GiB etc.) so a corrupted file can't OOM the app.
- argon2kt only uses `androidx.annotation.Keep` from AndroidX but declares `appcompat` + `core-ktx` as runtime deps; exclude them.

## Tests (JVM, `testDebugUnitTest`, test JVM `-Xmx64m`)
- Round trips: 0, 1, segPlain−1, segPlain, segPlain+1, firstSegPlain−1/±0/+1, 2·segPlain±1, via channel and stream.
- Large streaming: splitmix64 `byteAt`, 64 KiB chunks, temp file under the Gradle test task's temp dir, 256 MiB by default, 3 GiB with `-PlargeTests`. Peak heap (sum of heap `MemoryPoolMXBean` peaks) < 48 MiB. Seek check: 2,000 seeded positions + every boundary, reads of 1–70,000 bytes vs `byteAt`, read at EOF = −1, `size()` = plaintext length.
- Tamper (IOException or GeneralSecurityException): header byte flips, mid-file ciphertext byte, last tag byte, truncation (by 1 byte and at a segment boundary), segment swap, item id swapped in the AAD, type byte changed.
- Key wrap: round trip (fake `KeyWrapper` + BouncyCastle Argon2id), wrong PIN, tampered KDF param (also with the outer layer re-wrapped, so the inner layer is tested alone), unknown magic/version/kdf/scheme rejected, KEK/PIN zeroized, below-floor params refused on wrap.
- Calibration logic with a fake clock/KDF (OOM step-down, floor, targets).
- Instrumented (compile only here): Keystore wrap round trip, randomized IV, real argon2kt vs. a known Argon2id vector, layered wrap with real Keystore + argon2kt.

## Open questions
- Argon2 calibration target is 0.8–1.5 s at setup. Needs measuring on the target phone (instrumented test logs it).
- Instrumented tests can't run on this box (no emulator); Jan runs them on the device.
