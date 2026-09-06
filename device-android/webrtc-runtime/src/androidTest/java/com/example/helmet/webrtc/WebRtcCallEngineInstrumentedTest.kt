package com.example.helmet.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
    fun audioOfferRequiresDeclaredMicrophoneAndContainsDtlsSrtpFingerprint() = runBlocking {
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
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
                val failure = runCatching { engine.createOffer(localIceGeneration = 1) }.exceptionOrNull()
                assertTrue(failure is WebRtcException && failure.message == "MICROPHONE_UNAVAILABLE")
                return@runBlocking
            }
            val offer = engine.createOffer(localIceGeneration = 1)
            assertTrue(offer.audioEnabled)
            assertTrue(!offer.videoEnabled)
            assertTrue(offer.degradedReason == "AUDIO_CAPTURE_DISABLED_FOR_PROBE")
            assertTrue(offer.sdp.contains("a=fingerprint:"))
            assertTrue(offer.sdp.contains("UDP/TLS/RTP/SAVPF"))
            assertTrue(engine.setLowBandwidthMode(true))
            assertTrue(engine.setLowBandwidthMode(false))
            val replacementIce = IceConfiguration(
                callId = "call-board-audio",
                requesterId = "device-board",
                servers = listOf(IceServerConfig(listOf("stun:127.0.0.1:3478"), null, null)),
                issuedAtEpochMillis = now,
                expiresAtEpochMillis = now + 900_000,
            )
            assertTrue(engine.replaceIceConfiguration(replacementIce))
            val restartOffer = engine.createIceRestartOffer(localIceGeneration = 2)
            assertTrue(restartOffer.sdp.contains("a=fingerprint:"))
            assertTrue(listener.statuses.any { it.state == WebRtcMediaState.OFFER_READY })
            assertTrue(listener.statuses.count { it.state == WebRtcMediaState.OFFER_READY } >= 2)
        } finally {
            engine.close()
        }
    }

    private class RecordingListener : WebRtcCallListener {
        val statuses = mutableListOf<WebRtcStatus>()
        override fun onStatus(status: WebRtcStatus) {
            synchronized(statuses) { statuses += status }
        }

        override fun onLocalIceCandidate(localIceGeneration: Long, candidate: LocalIceCandidate) = Unit
        override fun onIceGatheringComplete(localIceGeneration: Long) = Unit
    }
}
