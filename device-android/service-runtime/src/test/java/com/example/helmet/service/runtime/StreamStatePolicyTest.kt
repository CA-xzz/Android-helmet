package com.example.helmet.service.runtime

import com.example.helmet.core.model.StreamState
import com.example.helmet.webrtc.WebRtcMediaState
import com.example.helmet.webrtc.WebRtcStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStatePolicyTest {
    @Test
    fun mapsWebRtcLifecycleToRequiredStreamStates() {
        listOf(
            WebRtcMediaState.NEW,
            WebRtcMediaState.OFFER_READY,
            WebRtcMediaState.CONNECTING,
            WebRtcMediaState.DISCONNECTED,
        ).forEach { assertEquals(StreamState.STARTING, streamStateForWebRtcMediaState(it)) }
        assertEquals(StreamState.STREAMING, streamStateForWebRtcMediaState(WebRtcMediaState.CONNECTED))
        assertEquals(StreamState.FAILED, streamStateForWebRtcMediaState(WebRtcMediaState.FAILED))
        assertEquals(StreamState.IDLE, streamStateForWebRtcMediaState(WebRtcMediaState.CLOSED))
    }

    @Test
    fun captureFailuresAreNotMisclassifiedAsRecoverableIceFailures() {
        assertTrue(isNonRecoverableMediaFailure(status("AUDIO_RECORD_RUNTIME:no samples")))
        assertTrue(isNonRecoverableMediaFailure(status("CAMERA_DISCONNECTED")))
        assertFalse(isNonRecoverableMediaFailure(status("CAMERA_UNAVAILABLE")))
        assertFalse(isNonRecoverableMediaFailure(status("CAMERA_UNAVAILABLE", WebRtcMediaState.CONNECTED)))
        assertFalse(isNonRecoverableMediaFailure(status(null)))
    }

    private fun status(
        reason: String?,
        state: WebRtcMediaState = WebRtcMediaState.FAILED,
    ) = WebRtcStatus(
        callId = "call",
        state = state,
        audioEnabled = false,
        videoEnabled = false,
        degradedReason = reason,
        peerConnectionState = "FAILED",
        iceConnectionState = "FAILED",
    )
}
