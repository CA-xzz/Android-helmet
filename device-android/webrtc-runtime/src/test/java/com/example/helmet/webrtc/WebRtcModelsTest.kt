package com.example.helmet.webrtc

import com.example.helmet.core.model.IceConfiguration
import com.example.helmet.core.model.IceServerConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcModelsTest {
    @Test
    fun videoIsReadyOnlyAfterTrackAttachmentAndFirstFrame() {
        val readiness = VideoCaptureReadiness()
        assertFalse(readiness.isReady())
        assertFalse(readiness.onFirstFrameAvailable())
        assertTrue(readiness.onTrackAttached())
        assertFalse(readiness.onCameraOpening())
        assertTrue(readiness.onFirstFrameAvailable())
        assertFalse(readiness.reset())
        assertFalse(readiness.isReady())
    }

    @Test
    fun iceCredentialMustHaveSafeRemainingLifetime() {
        val configuration = IceConfiguration(
            callId = "call-1",
            requesterId = "device-1",
            servers = listOf(IceServerConfig(listOf("turn:turn.example.test:3478"), "user", "password")),
            issuedAtEpochMillis = 1_000,
            expiresAtEpochMillis = 101_000,
        )

        assertTrue(configuration.isUsableAt(50_000))
        assertFalse(configuration.isUsableAt(80_000))
        assertTrue(configuration.isUsableAt(999))
    }
}
