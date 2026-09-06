package com.example.helmet.service.runtime

import com.example.helmet.core.model.CallDirection
import com.example.helmet.core.model.CallMediaMode
import com.example.helmet.core.model.CallSession
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.webrtc.WebRtcException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateObservationGateTest {
    @Test
    fun requestedStateIsRecoveredAndLaterRemoteStatesAreHandledOnce() {
        val gate = CallStateObservationGate()
        assertTrue(gate.shouldHandle(call(CallState.REQUESTED, 1)))
        assertTrue(gate.shouldHandle(call(CallState.ACCEPTED, 2)))
        assertFalse(gate.shouldHandle(call(CallState.ACCEPTED, 2).copy(attemptCount = 1)))
        assertTrue(gate.shouldHandle(call(CallState.CONNECTING, 3)))
        assertTrue(gate.shouldHandle(call(CallState.FAILED, 4)))
    }

    @Test
    fun activeInitialStateIsRecoveredWithoutReplayingInitialTerminalState() {
        assertTrue(CallStateObservationGate().shouldHandle(call(CallState.ACCEPTED, 2)))
        assertTrue(CallStateObservationGate().shouldHandle(call(CallState.CONNECTING, 3)))
        assertTrue(CallStateObservationGate().shouldHandle(call(CallState.CONNECTED, 4)))
        assertTrue(CallStateObservationGate().shouldHandle(call(CallState.RINGING, 2)))
        assertFalse(CallStateObservationGate().shouldHandle(call(CallState.ENDED, 4)))
        assertFalse(CallStateObservationGate().shouldHandle(null))
    }

    @Test
    fun mediaStartStatesIncludeConnectedRecoveryOnly() {
        assertTrue(isCallMediaStartState(CallState.ACCEPTED))
        assertTrue(isCallMediaStartState(CallState.CONNECTING))
        assertTrue(isCallMediaStartState(CallState.CONNECTED))
        assertFalse(isCallMediaStartState(CallState.REQUESTED))
        assertFalse(isCallMediaStartState(CallState.ENDED))
        assertFalse(isCallMediaStartState(CallState.FAILED))
    }

    @Test
    fun activeTrackerReportsThePreviousCallWhenItBecomesTerminalOrIsReplaced() {
        val tracker = ActiveCallObservationTracker()
        val first = call(CallState.REQUESTED, 1)
        val second = first.copy(callId = "call-replacement")

        assertTrue(tracker.activeChanged(first) == null)
        assertTrue(tracker.activeChanged(first.copy(state = CallState.ACCEPTED, stateSequence = 2)) == null)
        assertTrue(tracker.activeChanged(null) == first.callId)
        assertTrue(tracker.activeChanged(second) == null)
        assertTrue(tracker.activeChanged(first) == second.callId)
    }

    @Test
    fun webRtcFailureUsesStableCapabilityCodeWithoutNativeDetail() {
        assertTrue(
            callMediaFailureCode(WebRtcException("MICROPHONE_UNAVAILABLE")) ==
                "MICROPHONE_UNAVAILABLE",
        )
        assertTrue(
            callMediaFailureCode(WebRtcException("SDP_OFFER_FAILED:native detail")) ==
                "SDP_OFFER_FAILED",
        )
        assertTrue(callMediaFailureCode(IllegalStateException("detail")) == "IllegalStateException")
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
