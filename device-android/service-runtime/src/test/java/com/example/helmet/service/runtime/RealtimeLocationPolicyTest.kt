package com.example.helmet.service.runtime

import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeLocationPolicyTest {
    @Test
    fun freshRtkFixIsNotImmediatelyReplacedByLowerQualityAndroidFix() {
        val current = fix("rtk", 1_000_000_000L, LocationSource.EXTERNAL_NMEA, FixQuality.RTK_FIXED)
        val android = fix("android", 2_000_000_000L, LocationSource.ANDROID_GNSS, FixQuality.STANDARD)

        assertFalse(select(current, android, now = 2_000_000_000L))
        assertTrue(select(current, android.copy(elapsedRealtimeNanos = 7_000_000_000L), now = 7_000_000_000L))
    }

    @Test
    fun newerFixFromSameReceiverCanReportQualityDegradation() {
        val current = fix("fixed", 1_000_000_000L, LocationSource.EXTERNAL_NMEA, FixQuality.RTK_FIXED)
        val degraded = fix("float", 2_000_000_000L, LocationSource.EXTERNAL_NMEA, FixQuality.RTK_FLOAT)

        assertTrue(select(current, degraded, now = 2_000_000_000L))
        assertFalse(select(degraded, current, now = 2_000_000_000L))
    }

    @Test
    fun expiredCandidateCannotBecomeRealtimePosition() {
        val candidate = fix("stale", 1_000_000_000L, LocationSource.ANDROID_GNSS, FixQuality.STANDARD)

        assertFalse(select(null, candidate, now = 17_000_000_000L))
        assertFalse(isFreshLocationFix(candidate, 17_000_000_000L, 15_000_000_000L))
    }

    @Test
    fun receiverNoFixInvalidatesPreviouslySelectedExternalPosition() {
        val external = fix("rtk", 1_000_000_000L, LocationSource.EXTERNAL_NMEA, FixQuality.RTK_FIXED)

        assertFalse(isLocationFixValidForReceiverState(external, FixQuality.NO_FIX))
        assertTrue(isLocationFixValidForReceiverState(external, FixQuality.RTK_FIXED))
    }

    @Test
    fun safetyAssociationRejectsStaleMockAndReceiverInvalidPositions() {
        val current = fix("current", 1_000_000_000L, LocationSource.ANDROID_GNSS, FixQuality.STANDARD)
        assertSame(
            current,
            usableLocationFix(current, FixQuality.NO_FIX, 16_000_000_000L, 16_001L, 15_000L),
        )
        assertNull(
            usableLocationFix(current, FixQuality.NO_FIX, 16_000_000_001L, 16_001L, 15_000L),
        )
        assertNull(
            usableLocationFix(
                current.copy(isMock = true),
                FixQuality.NO_FIX,
                1_000_000_000L,
                1_001L,
                15_000L,
            ),
        )
        val external = current.copy(source = LocationSource.EXTERNAL_NMEA, provider = "external")
        assertNull(
            usableLocationFix(external, FixQuality.NO_FIX, 1_000_000_000L, 1_001L, 15_000L),
        )
    }

    private fun select(current: LocationFix?, candidate: LocationFix, now: Long): Boolean =
        shouldSelectRealtimeLocationFix(
            current = current,
            candidate = candidate,
            observedAtElapsedRealtimeNanos = now,
            maximumCandidateAgeNanos = 15_000_000_000L,
            preferredSourceHoldNanos = 5_000_000_000L,
        )

    private fun fix(
        id: String,
        elapsedNanos: Long,
        source: LocationSource,
        quality: FixQuality,
    ) = LocationFix(
        fixId = id,
        deviceId = "device",
        occurredAtEpochMillis = elapsedNanos / 1_000_000L + 1,
        elapsedRealtimeNanos = elapsedNanos,
        source = source,
        quality = quality,
        latitude = 30.0,
        longitude = 114.0,
        provider = if (source == LocationSource.EXTERNAL_NMEA) "external" else "gps",
    )
}
