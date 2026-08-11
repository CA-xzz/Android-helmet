package com.example.helmet.service.runtime

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTimeAuthorityTest {
    @Test
    fun systemClockIsExplicitlyUnsynchronizedWithoutCalibration() {
        val discipline = DeviceTimeDiscipline(MemoryStore(), "boot-a", { 1234 }, { 50 })

        val reading = discipline.read()

        assertEquals(1234L, reading.epochMillis)
        assertEquals(DeviceTimeSource.SYSTEM, reading.source)
        assertFalse(reading.synchronized)
        assertNull(reading.uncertaintyMillis)
    }

    @Test
    fun serverCalibrationUsesRoundTripMidpointAndTracksDrift() {
        var elapsed = 1_100L
        val store = MemoryStore()
        val discipline = DeviceTimeDiscipline(store, "boot-a", { 1 }, { elapsed })

        val calibrated = discipline.calibrateFromServer(
            serverReceivedAtEpochMillis = 1_786_000_000_000,
            requestStartedAtElapsedRealtimeMillis = 1_000,
            responseReceivedAtElapsedRealtimeMillis = 1_100,
        )

        assertEquals(1_786_000_000_050, calibrated.epochMillis)
        assertEquals(DeviceTimeSource.SERVER, calibrated.source)
        assertTrue(calibrated.synchronized)
        assertEquals(50L, calibrated.uncertaintyMillis)
        assertEquals(1L, calibrated.calibrationSequence)

        elapsed += 10_000
        val later = discipline.read()
        assertEquals(1_786_000_010_050, later.epochMillis)
        assertEquals(10_000L, later.calibrationAgeMillis)
        assertEquals(51L, later.uncertaintyMillis)
    }

    @Test
    fun calibrationPersistsWithinTheSameBoot() {
        var elapsed = 200L
        val store = MemoryStore()
        DeviceTimeDiscipline(store, "boot-a", { 1 }, { elapsed }).calibrateFromServer(
            1_786_000_000_000,
            100,
            200,
        )
        elapsed = 1_200

        val restored = DeviceTimeDiscipline(store, "boot-a", { 2 }, { elapsed }).read()

        assertEquals(1_786_000_001_050, restored.epochMillis)
        assertTrue(restored.synchronized)
    }

    @Test
    fun calibrationFromAnotherBootIsNotUsed() {
        val store = MemoryStore(
            DeviceTimeCalibration(
                DeviceTimeSource.SERVER,
                1_786_000_000_000,
                100,
                10,
                "boot-a",
                1,
            ),
        )

        val reading = DeviceTimeDiscipline(store, "boot-b", { 900 }, { 200 }).read()

        assertEquals(900L, reading.epochMillis)
        assertFalse(reading.synchronized)
    }

    @Test
    fun systemSourceCannotBeLoadedAsASynchronizedCalibration() {
        val store = MemoryStore(
            DeviceTimeCalibration(
                DeviceTimeSource.SYSTEM,
                1_786_000_000_000,
                100,
                10,
                "boot-a",
                1,
            ),
        )

        val reading = DeviceTimeDiscipline(store, "boot-a", { 900 }, { 200 }).read()

        assertEquals(DeviceTimeSource.SYSTEM, reading.source)
        assertFalse(reading.synchronized)
        assertEquals(900L, reading.epochMillis)
    }

    @Test
    fun rejectsImplausibleServerTimeAndExcessiveRoundTrip() {
        val discipline = DeviceTimeDiscipline(MemoryStore(), "boot-a", { 1 }, { 40_000 })

        assertThrows(IllegalArgumentException::class.java) {
            discipline.calibrateFromServer(1_000, 0, 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            discipline.calibrateFromServer(1_786_000_000_000, 0, 30_001)
        }
    }

    @Test
    fun firstServerCalibrationPublishesSignificantAdjustment() = runBlocking {
        val discipline = DeviceTimeDiscipline(MemoryStore(), "boot-a", { 1_000 }, { 1_100 })
        val authority = DeviceTimeAuthority(discipline)
        val adjustment = async(start = CoroutineStart.UNDISPATCHED) {
            authority.significantAdjustments.first()
        }

        val calibration = authority.calibrateFromServer(
            serverReceivedAtEpochMillis = 1_786_000_000_000,
            requestStartedAtElapsedRealtimeMillis = 1_000,
            responseReceivedAtElapsedRealtimeMillis = 1_100,
        )
        authority.announceSignificantAdjustment(calibration)
        val published = withTimeout(1_000) { adjustment.await() }

        assertEquals(DeviceTimeSource.SERVER, calibration.reading.source)
        assertEquals(DeviceTimeSource.SYSTEM, published.previous.source)
        assertEquals(DeviceTimeSource.SERVER, published.current.source)
        assertEquals(calibration.reading, published.current)
    }

    @Test
    fun onlySourceChangesAndMaterialCorrectionsRequestImmediateStatus() {
        val system = reading(10_000, DeviceTimeSource.SYSTEM, false, null)
        val server = reading(10_050, DeviceTimeSource.SERVER, true, 25)
        val smallRefresh = reading(10_550, DeviceTimeSource.SERVER, true, 25)
        val largeCorrection = reading(12_100, DeviceTimeSource.SERVER, true, 25)

        assertTrue(shouldPublishTimeAdjustment(system, server))
        assertFalse(shouldPublishTimeAdjustment(server, smallRefresh))
        assertTrue(shouldPublishTimeAdjustment(server, largeCorrection))
    }

    @Test
    fun statusTimeIsMonotonicWithinCalibrationAndCanStepBackwardAfterCalibration() {
        assertEquals(2_001L, selectNextDeviceStatusTime(1_900, 2_000, 4, 4))
        assertEquals(1_900L, selectNextDeviceStatusTime(1_900, 2_000, 4, 5))
        assertEquals(1_800L, selectNextDeviceStatusTime(1_800, 2_000, 5, -1))
    }

    private fun reading(
        epochMillis: Long,
        source: DeviceTimeSource,
        synchronized: Boolean,
        uncertaintyMillis: Long?,
    ) = DeviceTimeReading(
        epochMillis = epochMillis,
        source = source,
        synchronized = synchronized,
        uncertaintyMillis = uncertaintyMillis,
        calibrationAgeMillis = if (synchronized) 0 else null,
        calibrationSequence = if (synchronized) 1 else null,
    )

    private class MemoryStore(
        private var calibration: DeviceTimeCalibration? = null,
    ) : DeviceTimeCalibrationStore {
        override fun load(): DeviceTimeCalibration? = calibration

        override fun save(calibration: DeviceTimeCalibration): Boolean {
            this.calibration = calibration
            return true
        }
    }
}
