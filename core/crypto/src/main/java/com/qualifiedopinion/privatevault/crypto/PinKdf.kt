package com.qualifiedopinion.privatevault.crypto

/**
 * Argon2id v1.3 cost parameters as stored in `vault.meta` (planning pack §3.3).
 *
 * The constructor only checks hard limits, so a corrupted file can't make the app allocate
 * gigabytes. The security floor ([meetsFloor]) is enforced when a vault is created.
 */
data class KdfParams(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int = 1,
) {
    init {
        require(parallelism in 1..MAX_PARALLELISM) { "parallelism out of range" }
        require(memoryKiB in MIN_MEMORY_KIB_PER_LANE * parallelism..MAX_MEMORY_KIB) { "memory out of range" }
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }
    }

    fun meetsFloor(): Boolean = memoryKiB >= FLOOR_MEMORY_KIB && iterations >= FLOOR_ITERATIONS

    companion object {
        /** KDF id byte in `vault.meta`: Argon2id v1.3. */
        const val KDF_ARGON2ID_V13 = 0x01

        /** Floor from planning pack §4.3: m = 32 MiB, t = 2. */
        const val FLOOR_MEMORY_KIB = 32 * 1024
        const val FLOOR_ITERATIONS = 2

        /** Defaults from §3.3; calibration starts here. */
        const val DEFAULT_MEMORY_KIB = 64 * 1024
        const val DEFAULT_ITERATIONS = 3

        /** Hard upper limits accepted when parsing (1 GiB, 64 passes, 16 lanes). */
        const val MAX_MEMORY_KIB = 1024 * 1024
        const val MAX_ITERATIONS = 64
        const val MAX_PARALLELISM = 16
        private const val MIN_MEMORY_KIB_PER_LANE = 8

        val DEFAULT = KdfParams(DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS, 1)
        val FLOOR = KdfParams(FLOOR_MEMORY_KIB, FLOOR_ITERATIONS, 1)
    }
}

/** Derives the 32-byte KEK_pin from a PIN. Production uses [Argon2Kdf]; JVM tests use a pure-Java Argon2id. */
fun interface PinKdf {
    /**
     * Returns a new 32-byte key. The caller owns (and must zeroize) both [pin] and the result.
     * May throw [OutOfMemoryError] if [params] need more memory than is available.
     */
    fun deriveKey(pin: ByteArray, salt: ByteArray, params: KdfParams): ByteArray

    companion object {
        const val KEY_SIZE = 32
        const val SALT_SIZE = 16
    }
}

/**
 * Picks Argon2id parameters that take about [targetMinMs]..[targetMaxMs] on this device
 * (planning pack §4.3). Starts at [KdfParams.DEFAULT]. On [OutOfMemoryError] it halves the memory.
 * It never returns parameters below [KdfParams.FLOOR]; if the floor is too slow it returns the floor,
 * and if the floor runs out of memory it throws [KdfUnavailableException].
 */
class KdfCalibrator(
    private val kdf: PinKdf,
    private val nanoTime: () -> Long = System::nanoTime,
    private val targetMinMs: Long = 800,
    private val targetMaxMs: Long = 1500,
    private val maxIterations: Int = 10,
) {
    fun calibrate(): KdfParams {
        var params = KdfParams.DEFAULT
        var elapsed = measure(params)
        while (elapsed == OUT_OF_MEMORY && params.memoryKiB > KdfParams.FLOOR_MEMORY_KIB) {
            params = params.halveMemory()
            elapsed = measure(params)
        }
        // Too slow: fewer passes first, then less memory, never below the floor.
        while (elapsed > targetMaxMs && params.iterations > KdfParams.FLOOR_ITERATIONS) {
            params = params.copy(iterations = params.iterations - 1)
            elapsed = measure(params)
        }
        while (elapsed > targetMaxMs && params.memoryKiB > KdfParams.FLOOR_MEMORY_KIB) {
            params = params.halveMemory()
            elapsed = measure(params)
        }
        if (elapsed == OUT_OF_MEMORY) {
            throw KdfUnavailableException("Argon2id at ${params.memoryKiB} KiB does not fit in memory")
        }
        // Too fast: more passes (time is linear in t), aiming at the middle of the target window.
        if (elapsed < targetMinMs && params.iterations < maxIterations) {
            val perPass = maxOf(elapsed / params.iterations, 1L)
            val wanted = ((targetMinMs + targetMaxMs) / 2 + perPass - 1) / perPass
            params = params.copy(iterations = wanted.toInt().coerceIn(params.iterations + 1, maxIterations))
        }
        return params
    }

    private fun KdfParams.halveMemory() = copy(memoryKiB = maxOf(memoryKiB / 2, KdfParams.FLOOR_MEMORY_KIB))

    /** Elapsed milliseconds, or [OUT_OF_MEMORY]. */
    private fun measure(params: KdfParams): Long {
        val pin = ByteArray(CALIBRATION_PIN_LENGTH) { '0'.code.toByte() }
        val salt = ByteArray(PinKdf.SALT_SIZE)
        val start = nanoTime()
        return try {
            kdf.deriveKey(pin, salt, params).fill(0)
            (nanoTime() - start) / NANOS_PER_MILLI
        } catch (@Suppress("SwallowedException") e: OutOfMemoryError) {
            OUT_OF_MEMORY
        }
    }

    private companion object {
        const val CALIBRATION_PIN_LENGTH = 8
        const val NANOS_PER_MILLI = 1_000_000L
        const val OUT_OF_MEMORY = Long.MAX_VALUE
    }
}

/** Even the Argon2id floor can't run on this device. */
class KdfUnavailableException(message: String) : Exception(message)
