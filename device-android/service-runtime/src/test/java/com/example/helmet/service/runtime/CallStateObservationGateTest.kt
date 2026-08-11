package com.example.helmet.service.runtime

import com.example.helmet.core.model.CallDirection
import com.example.helmet.core.model.CallMediaMode
import com.example.helmet.core.model.CallSession
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.DeliveryState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateObservationGateTest {
    @Test
    fun requestedStateIsIgnoredButLaterRemoteStatesAreHandledOnce() {
        val gate = CallStateObservationGate()
        assertFalse(gate.shouldHandle(call(CallState.REQUESTED, 1)))
        assertTrue(gate.shouldHandle(call(CallState.ACCEPTED, 2)))
        assertFalse(gate.shouldHandle(call(CallState.ACCEPTED, 2).copy(attemptCount = 1)))
        assertTrue(gate.shouldHandle(call(CallState.CONNECTING, 3)))
        assertTrue(gate.shouldHandle(call(CallState.FAILED, 4)))
    }

    @Test
    fun activeInitialStateIsRecoveredWithoutReplayingInitialTerminalState() {
        assertTrue(CallStateObservationGate().shouldHandle(call(CallState.ACCEPTED, 2)))
        assertTrue(CallStateObservationGate().shouldHandle(call(CallState.CONNECTING, 3)))
        assertFalse(CallStateObservationGate().shouldHandle(call(CallState.CONNECTED, 4)))
        assertFalse(CallStateObservationGate().shouldHandle(call(CallState.ENDED, 4)))
        assertFalse(CallStateObservationGate().shouldHandle(null))
    }

    private fun call(state: CallState, sequence: Long) = CallSession(
        callId = "call-observed",
        deviceId = "device-observed",
        direction = CallDirection.OUTGOING_DEVICE,
        mediaMode = CallMediaMode.VIDEO_UPLINK,
        state = state,
        stateSequence = sequence,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000 + sequence,
        relatedEventId = null,
        simulated = false,
        lastReason = null,
        deliveryState = DeliveryState.DELIVERED,
        attemptCount = 0,
    )
}
