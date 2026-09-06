package com.example.helmet.hardware.api

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareEventQueueTest {
    @Test
    fun derivedFrameEventsAreQueuedAtomicallyAndOverflowIsCounted() = runBlocking {
        val queue = HardwareEventQueue(capacity = 1)
        val firstBatch = listOf(
            HardwareEvent.Heartbeat(1),
            HardwareEvent.ProtocolFrame(1, 1, 0, 1, 1, byteArrayOf()),
        )

        assertTrue(queue.offer(firstBatch).accepted)
        val rejected = queue.offer(listOf(HardwareEvent.Heartbeat(2)))
        assertFalse(rejected.accepted)
        assertEquals(1, rejected.overflowCount)
        assertEquals(firstBatch, queue.events.take(2).toList())
        assertTrue(queue.offer(listOf(HardwareEvent.Heartbeat(3))).accepted)
    }

    @Test
    fun successfulAckWaitsForQueueAdmissionAndBackpressureRemainsRetryable() {
        val accepted = mutableSetOf<Pair<Int, Int>>()
        val admission = ReliableInboundAdmission(
            alreadyAccepted = { type, sequence -> type to sequence in accepted },
            rememberAccepted = { type, sequence -> accepted += type to sequence },
        )
        var offerSucceeds = false

        val backpressured = admission.admit(type = 0x10, sequence = 7) { offerSucceeds }
        assertEquals(ReliableInboundDisposition.BACKPRESSURED, backpressured.disposition)
        assertFalse(backpressured.sendSuccessAcknowledgement)
        assertTrue(accepted.isEmpty())

        offerSucceeds = true
        val delivered = admission.admit(type = 0x10, sequence = 7) { offerSucceeds }
        assertEquals(ReliableInboundDisposition.DELIVERED, delivered.disposition)
        assertTrue(delivered.sendSuccessAcknowledgement)
        assertEquals(setOf(0x10 to 7), accepted)

        val duplicate = admission.admit(type = 0x10, sequence = 7) {
            throw AssertionError("a duplicate must not enter the event queue")
        }
        assertEquals(ReliableInboundDisposition.DUPLICATE, duplicate.disposition)
        assertTrue(duplicate.sendSuccessAcknowledgement)
    }
}
