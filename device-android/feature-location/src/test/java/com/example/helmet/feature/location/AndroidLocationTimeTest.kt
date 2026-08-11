package com.example.helmet.feature.location

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidLocationTimeTest {
    @Test
    fun derivesFixEpochFromTheCalibratedClockAndMonotonicAge() {
        assertEquals(
            1_786_000_009_750L,
            locationEpochFromMonotonicAge(
                observedAtEpochMillis = 1_786_000_010_000L,
                observedAtElapsedRealtimeNanos = 5_000_000_000L,
                fixElapsedRealtimeNanos = 4_750_000_000L,
            ),
        )
    }

    @Test
    fun futureMonotonicFixDoesNotMoveEpochIntoTheFuture() {
        assertEquals(
            1_786_000_010_000L,
            locationEpochFromMonotonicAge(
                observedAtEpochMillis = 1_786_000_010_000L,
                observedAtElapsedRealtimeNanos = 5_000_000_000L,
                fixElapsedRealtimeNanos = 5_100_000_000L,
            ),
        )
    }
}
