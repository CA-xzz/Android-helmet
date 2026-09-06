package com.example.helmet.feature.location

import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFixQueueTest {
    @Test
    fun callbackOverflowIsCountedAndDoesNotReplaceTheAcceptedFix() {
        runBlocking {
            val queue = LocationFixQueue(capacity = 1)
            val first = fix("first")

            assertTrue(queue.offer(first).accepted)
            val rejected = queue.offer(fix("rejected"))
            assertFalse(rejected.accepted)
            assertEquals(1, rejected.overflowCount)
            assertEquals(first, queue.fixes.first())
            assertTrue(queue.offer(fix("after-drain")).accepted)
            queue.close()
        }
    }

    private fun fix(id: String) = LocationFix(
        fixId = id,
        deviceId = "device-a",
        occurredAtEpochMillis = 1,
        elapsedRealtimeNanos = 1,
        source = LocationSource.ANDROID_GNSS,
        quality = FixQuality.STANDARD,
        latitude = 30.0,
        longitude = 114.0,
    )
}
