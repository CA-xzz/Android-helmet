package com.example.helmet.service.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareDegradedStartupTest {
    @Test
    fun hardwareBindFailureIsRecordedBeforeIndependentRuntimeStillStarts() = runBlocking {
        val expected = IllegalStateException("failed to bind hardware service")
        val calls = mutableListOf<String>()
        var startAccepted: Boolean? = null

        val result = startRuntimeWithHardwareDegradation(
            startHardware = {
                calls += "hardware"
                throw expected
            },
            onHardwareUnavailable = { failure ->
                assertSame(expected, failure)
                calls += "unavailable"
            },
            startIndependentRuntime = { accepted ->
                startAccepted = accepted
                calls += "network-location-queues"
            },
        )

        assertSame(expected, result.hardwareFailure)
        assertNull(result.failureReportingFailure)
        assertFalse(startAccepted!!)
        assertEquals(listOf("hardware", "unavailable", "network-location-queues"), calls)
    }

    @Test
    fun failureToPersistHardwareErrorCannotBlockIndependentRuntime() = runBlocking {
        val reportingFailure = IllegalStateException("database unavailable")
        var independentRuntimeStarted = false

        val result = startRuntimeWithHardwareDegradation(
            startHardware = { throw IllegalStateException("bind failed") },
            onHardwareUnavailable = { throw reportingFailure },
            startIndependentRuntime = { independentRuntimeStarted = true },
        )

        assertTrue(independentRuntimeStarted)
        assertSame(reportingFailure, result.failureReportingFailure)
    }

    @Test
    fun successfulHardwareStartAlsoStartsIndependentRuntime() = runBlocking {
        var startAccepted = false

        val result = startRuntimeWithHardwareDegradation(
            startHardware = {},
            onHardwareUnavailable = { throw AssertionError("must not be called") },
            startIndependentRuntime = { startAccepted = it },
        )

        assertTrue(startAccepted)
        assertNull(result.hardwareFailure)
        assertNull(result.failureReportingFailure)
    }

    @Test
    fun coroutineCancellationIsNotConvertedToHardwareUnavailability() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                startRuntimeWithHardwareDegradation(
                    startHardware = { throw CancellationException("cancelled") },
                    onHardwareUnavailable = {},
                    startIndependentRuntime = { throw AssertionError("must not be called") },
                )
            }
        }
    }
}
