package com.example.helmet.service.runtime

import com.example.helmet.communication.sync.CallSyncReceipt
import com.example.helmet.core.model.CallDirection
import com.example.helmet.core.model.CallMediaMode
import com.example.helmet.core.model.CallSession
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.DeliveryState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateSyncRecoveryTest {
    @Test
    fun immediateTerminalSyncFailureKeepsOutboxPendingAndSchedulesRecovery() = runBlocking {
        val call = failedCall()
        var markedDelivered = false
        var recoveryScheduled = false

        val result = syncCallStateOrDefer(
            call = call,
            sync = { error("network unavailable") },
            markDelivered = { _, _ -> markedDelivered = true; true },
            defer = { recoveryScheduled = true },
        )

        assertFalse(result.delivered)
        assertFalse(markedDelivered)
        assertTrue(recoveryScheduled)
    }

    @Test
    fun mismatchedReceiptIsNotAllowedToClearTheOutbox() = runBlocking {
        val call = failedCall()
        var recoveryScheduled = false

        val result = syncCallStateOrDefer(
            call = call,
            sync = {
                CallSyncReceipt(
                    callId = call.callId,
                    state = CallState.FAILED.name,
                    stateSequence = 4,
                    acknowledgedState = CallState.CONNECTED.name,
                    acknowledgedStateSequence = 3,
                    deduplicated = false,
                )
            },
            markDelivered = { _, _ -> error("must not mark mismatched receipt delivered") },
            defer = { recoveryScheduled = true },
        )

        assertFalse(result.delivered)
        assertTrue(recoveryScheduled)
    }

    private fun failedCall() = CallSession(
        callId = "call-terminal-sync",
        deviceId = "device-1",
        direction = CallDirection.OUTGOING_DEVICE,
        mediaMode = CallMediaMode.AUDIO,
        state = CallState.FAILED,
        stateSequence = 4,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 4,
        relatedEventId = null,
        simulated = false,
        lastReason = "WEBRTC_FAILED",
        deliveryState = DeliveryState.PENDING,
        attemptCount = 0,
    )
}
