package com.qualifiedopinion.privatevault.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KdfCalibratorTest {

    /** Simulated device: cost = msPerMiBPass × MiB × passes; OOM above [maxMemoryKiB]. */
    private class SimulatedDevice(private val msPerMiBPass: Double, private val maxMemoryKiB: Int = Int.MAX_VALUE) {
        var now = 0L
        val tried = mutableListOf<KdfParams>()

        val kdf = PinKdf { _, _, params ->
            tried += params
            if (params.memoryKiB > maxMemoryKiB) throw OutOfMemoryError("simulated")
            now += (msPerMiBPass * params.memoryKiB / 1024 * params.iterations * 1_000_000).toLong()
            ByteArray(32)
        }

        fun calibrate() = KdfCalibrator(kdf, nanoTime = { now }).calibrate()

        fun msFor(p: KdfParams) = msPerMiBPass * p.memoryKiB / 1024 * p.iterations
    }

    @Test
    fun midRangeDeviceKeepsDefaults() {
        val d = SimulatedDevice(msPerMiBPass = 5.0) // 64 MiB × 3 = 960 ms
        assertEquals(KdfParams.DEFAULT, d.calibrate())
    }

    @Test
    fun fastDeviceGetsMorePassesWithinTheWindow() {
        val d = SimulatedDevice(msPerMiBPass = 2.0) // 128 ms per pass
        val p = d.calibrate()
        assertEquals(KdfParams.DEFAULT_MEMORY_KIB, p.memoryKiB)
        assertTrue("t=${p.iterations}", p.iterations > KdfParams.DEFAULT_ITERATIONS)
        assertTrue(d.msFor(p) in 800.0..1500.0)
    }

    @Test
    fun veryFastDeviceIsCappedAtMaxIterations() {
        val p = SimulatedDevice(msPerMiBPass = 0.1).calibrate()
        assertEquals(10, p.iterations)
    }

    @Test
    fun slowDeviceStepsDownToTheFloorButNotBelow() {
        val d = SimulatedDevice(msPerMiBPass = 40.0)
        assertEquals(KdfParams.FLOOR, d.calibrate())
        assertTrue(d.tried.all { it.meetsFloor() })
    }

    @Test
    fun slowishDeviceDropsPassesBeforeMemory() {
        val d = SimulatedDevice(msPerMiBPass = 10.0) // 64 MiB: 3 passes 1920 ms, 2 passes 1280 ms
        assertEquals(KdfParams(KdfParams.DEFAULT_MEMORY_KIB, 2), d.calibrate())
    }

    @Test
    fun outOfMemoryStepsMemoryDown() {
        val d = SimulatedDevice(msPerMiBPass = 5.0, maxMemoryKiB = 40 * 1024)
        val p = d.calibrate()
        assertEquals(KdfParams.FLOOR_MEMORY_KIB, p.memoryKiB)
        assertTrue(p.meetsFloor())
        assertTrue(d.msFor(p) in 800.0..1500.0)
    }

    @Test
    fun outOfMemoryAtTheFloorIsAnError() {
        val d = SimulatedDevice(msPerMiBPass = 5.0, maxMemoryKiB = 16 * 1024)
        assertThrows(KdfUnavailableException::class.java) { d.calibrate() }
        assertTrue(d.tried.all { it.meetsFloor() })
    }
}
