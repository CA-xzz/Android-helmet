package com.example.helmet.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HslReliabilityTest {
    @Test
    fun retriesThreeTimesThenTimesOut() {
        val frame = HslFrame(
            flags = HslFlags.ACK_REQUIRED,
            type = HslMessageType.SET_OUTPUT,
            sequence = 9,
            payload = byteArrayOf(1),
        )
        val tracker = HslReliableCommandTracker()
        tracker.track(frame, nowMillis = 0)

        assertEquals(listOf(frame), tracker.poll(200).retryFrames)
        assertEquals(listOf(frame), tracker.poll(400).retryFrames)
        assertEquals(listOf(frame), tracker.poll(600).retryFrames)
        val timeout = tracker.poll(800)
        assertTrue(timeout.retryFrames.isEmpty())
        assertEquals(listOf(9), timeout.timedOutSequences)
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun acknowledgementRemovesPendingCommand() {
        val tracker = HslReliableCommandTracker()
        tracker.track(
            HslFrame(HslFrameCodec.SUPPORTED_VERSION, HslFlags.ACK_REQUIRED, HslMessageType.HELLO, 7, byteArrayOf()),
            nowMillis = 0,
        )

        assertTrue(tracker.acknowledge(7))
        assertFalse(tracker.acknowledge(7))
        assertTrue(tracker.poll(1_000).timedOutSequences.isEmpty())
    }

    @Test
    fun heartbeatTransitionsToDegradedAndFault() {
        val monitor = HslHeartbeatMonitor()
        assertEquals(HslLinkState.DISCONNECTED, monitor.state(0))
        monitor.onLinkStarted(100)
        assertEquals(HslLinkState.CONNECTED, monitor.state(3_099))
        assertEquals(HslLinkState.DEGRADED, monitor.state(3_100))
        assertEquals(HslLinkState.FAULT, monitor.state(5_100))
        monitor.onHeartbeat(6_000)
        assertEquals(HslLinkState.CONNECTED, monitor.state(6_001))
        monitor.reset()
        assertEquals(HslLinkState.DISCONNECTED, monitor.state(7_000))
    }

    @Test
    fun duplicateWindowRejectsRecentDuplicateAndEvictsOldest() {
        val window = HslDuplicateWindow(capacity = 2)

        assertTrue(window.accept(HslMessageType.KEY_EVENT, 1))
        assertFalse(window.accept(HslMessageType.KEY_EVENT, 1))
        assertTrue(window.accept(HslMessageType.KEY_EVENT, 2))
        assertTrue(window.accept(HslMessageType.KEY_EVENT, 3))
        assertTrue(window.accept(HslMessageType.KEY_EVENT, 1))
    }

    @Test
    fun duplicateWindowCanCheckWithoutConsumingAndResetForANewModuleSession() {
        val window = HslDuplicateWindow(2)

        assertFalse(window.contains(HslMessageType.KEY_EVENT, 7))
        assertTrue(window.accept(HslMessageType.KEY_EVENT, 7))
        assertTrue(window.contains(HslMessageType.KEY_EVENT, 7))
        window.reset()
        assertFalse(window.contains(HslMessageType.KEY_EVENT, 7))
        assertTrue(window.accept(HslMessageType.KEY_EVENT, 7))
    }
}
