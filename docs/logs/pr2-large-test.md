# PR2 large streaming test log

Planning pack §6 PR2 acceptance: "The 3 GiB run log is attached (time, peak heap)."

## 3 GiB run

- Date: 2026-10-15 06:08 Asia/Manila
- Commit: `feat/pr2-crypto` @ 8f3cc8b
- Command: `./gradlew :core:crypto:testDebugUnitTest --tests '*LargeStreamingTest' -PlargeTests --rerun`
- Machine: build box, Intel Xeon, 8 vCPU, AES-NI. JDK Temurin 17.0.20.1. Test JVM `-Xmx64m` (asserted in the test).
- Result: **PASSED**

| Metric | Value |
|---|---|
| Plaintext size | 3,221,225,472 bytes (3 GiB) |
| Test time (JUnit) | **33.0 s** (Gradle wall clock 36 s, warm daemon) |
| Encrypt + write (64 KiB chunks) | 17.7 s |
| Sequential decrypt (stream) + compare | 6.6 s |
| Seek check | 8.4 s for 17,364 reads (2,000 seeded random positions + every boundary), 1–70,000 bytes each |
| **Peak heap** | **20.0 MiB** (limit < 48 MiB, max heap 64 MiB) |
| Temp file | Under `core/crypto/build/tmp/testDebugUnitTest/`; deleted after the run (dir empty) |

Raw test output:

```
LARGE_TEST bytes=3221225472 maxHeapMiB=64 peakHeapMiB=20.0 encryptMs=17653 decryptStreamMs=6624 seekMs=8435 seeks=17364
```

## 256 MiB run (default, every PR)

Same machine, during `./gradlew testDebugUnitTest`:

```
LARGE_TEST bytes=268435456 maxHeapMiB=64 peakHeapMiB=18.0 encryptMs=4782 decryptStreamMs=4359 seekMs=3986 seeks=3284
```

## How peak heap is measured

`System.gc()`, then `resetPeakUsage()` on every heap `MemoryPoolMXBean`. After the run, the test sums `peakUsage.used` over those pools. The pools peak at different times, so the sum is an upper bound on the real peak. It includes the Gradle test worker's own heap.

## What the test checks

- Plaintext `byteAt(i) = (splitmix64(i ushr 3) ushr ((i and 7)*8)).toByte()`, streamed through `VaultCrypto.newEncryptingChannel` to a temp file in 64 KiB chunks.
- The file size equals `VaultCrypto.fileSizeFor(size)`.
- A full sequential decrypt through `newDecryptingStream` matches `byteAt`.
- Through `newSeekableDecryptingChannel`: `size()` equals the plaintext length, and reads at 2,000 seeded random positions plus every boundary (`k·segPlain ± 1`, each real segment edge −1/0/+1 starting at the 1,048,520-byte first segment, byte 0, the last byte and EOF) match `byteAt`. A read at EOF and past EOF returns −1.
