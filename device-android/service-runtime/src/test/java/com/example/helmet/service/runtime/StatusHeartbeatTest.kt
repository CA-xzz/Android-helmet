package com.example.helmet.service.runtime

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusHeartbeatTest {
    @Test
    fun heartbeatRepeatsAtTheConfiguredCadence() = runBlocking {
        val beats = withTimeout(1_000) {
            statusHeartbeatFlow(intervalMillis = 1)
                .take(3)
                .toList()
        }

        assertEquals(3, beats.size)
    }
}
