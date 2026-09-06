package com.example.helmet.feature.location

import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidNmeaQualityTrackerTest {
    @Test
    fun otherSentenceTypesDoNotExtendExpiredGgaQuality() {
        var now = 0L
        val tracker = AndroidNmeaQualityTracker(
            monotonicClockMillis = { now },
            freshnessMillis = 100L,
        )
        tracker.accept(requireNotNull(NmeaParser.parse(sentence(
            "GNGGA,123519,3112.0000,N,12124.0000,E,4,12,0.7,12.3,M,8.1,M,0.8,0042",
        ))))
        now = 90L
        tracker.accept(requireNotNull(NmeaParser.parse(sentence("GNGSV,1,1,18,01,40,083,42"))))
        now = 101L

        val snapshot = tracker.snapshot()

        assertNull(snapshot.fixQuality)
        assertNull(snapshot.gnss.satellitesUsed)
        assertEquals(18, snapshot.gnss.satellitesVisible)
    }

    @Test
    fun noFixNmeaCannotCreateNoFixWithAndroidCoordinates() {
        assertEquals(
            FixQuality.UNVALIDATED,
            androidLocationFixQuality(LocationSource.ANDROID_GNSS, false, FixQuality.NO_FIX),
        )
        assertEquals(
            FixQuality.STANDARD,
            androidLocationFixQuality(LocationSource.ANDROID_GNSS, false, null),
        )
    }

    private fun sentence(body: String): String {
        val checksum = body.fold(0) { value, character -> value xor character.code }
        return "$" + body + "*" + checksum.toString(16).uppercase().padStart(2, '0')
    }
}
