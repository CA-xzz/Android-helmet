package com.example.helmet.service.runtime

import com.example.helmet.hardware.api.HardwareEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HardwareEventProcessingTest {
    @Test
    fun oneRejectedEventDoesNotPreventTheNextEventFromBeingProcessed() = runBlocking {
        val processed = mutableListOf<Long>()
        val failures = mutableListOf<Long>()
        val first = HardwareEvent.Heartbeat(1)
        val second = HardwareEvent.Heartbeat(2)

        listOf(first, second).forEach { event ->
            processHardwareEventSafely(
                event = event,
                process = {
                    if (it.monotonicMillis == 1L) error("conflicting durable ID")
                    processed += it.monotonicMillis
                },
                onFailure = { failed, _ -> failures += failed.monotonicMillis },
            )
        }

        assertEquals(listOf(1L), failures)
        assertEquals(listOf(2L), processed)
    }

    @Test
    fun cancellationIsNeverConvertedIntoAnEventFailure() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                processHardwareEventSafely(
                    event = HardwareEvent.Heartbeat(1),
                    process = { throw CancellationException("stop") },
                    onFailure = { _, _ -> error("must not report cancellation") },
                )
            }
        }
    }
}
