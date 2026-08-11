package com.example.helmet.webrtc

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.helmet.core.model.CallMediaMode
import com.example.helmet.core.model.IceConfiguration
import com.example.helmet.core.model.IceServerConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebRtcCallEngineInstrumentedTest {
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun nativeLibraryLoadsOnTargetAbi() {
        assertTrue(WebRtcCallEngine.nativeLibraryAvailable(context))
    }

    @Test
    fun audioOfferContainsDtlsSrtpFingerprint() = runBlocking {
        val listener = RecordingListener()
        val now = System.currentTimeMillis()
        val engine = WebRtcCallEngine(
            context = context,
            callId = "call-board-audio",
            mediaMode = CallMediaMode.AUDIO,
            iceConfiguration = IceConfiguration(
                callId = "call-board-audio",
                requesterId = "device-board",
                servers = listOf(IceServerConfig(listOf("stun:127.0.0.1:3478"), null, null)),
                issuedAtEpochMillis = now - 1_000,
                expiresAtEpochMillis = now + 600_000,
            ),
            listener = listener,
            captureAudio = false,
        )
        try {
            val offer = engine.createOffer()
            assertTrue(offer.audioEnabled)
            assertTrue(!offer.videoEnabled)
            assertTrue(offer.degradedReason == "AUDIO_CAPTURE_DISABLED_FOR_PROBE")
            assertTrue(offer.sdp.contains("a=fingerprint:"))
            assertTrue(offer.sdp.contains("UDP/TLS/RTP/SAVPF"))
            assertTrue(engine.setLowBandwidthMode(true))
            assertTrue(engine.setLowBandwidthMode(false))
            assertTrue(listener.statuses.any { it.state == WebRtcMediaState.OFFER_READY })
        } finally {
            engine.close()
        }
    }

    private class RecordingListener : WebRtcCallListener {
        val statuses = mutableListOf<WebRtcStatus>()
        override fun onStatus(status: WebRtcStatus) {
            synchronized(statuses) { statuses += status }
        }

        override fun onLocalIceCandidate(candidate: LocalIceCandidate) = Unit
        override fun onIceGatheringComplete() = Unit
    }
}
