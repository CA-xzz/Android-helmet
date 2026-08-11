package com.example.helmet.service.runtime

import android.content.Context
import com.example.helmet.communication.sync.HttpCommunicationClient
import com.example.helmet.core.model.CallSession
import com.example.helmet.core.model.CallSignal
import com.example.helmet.core.model.CallSignalType
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.data.local.CallStore
import com.example.helmet.data.local.EventStore
import com.example.helmet.feature.connectivity.ConnectivitySnapshot
import com.example.helmet.webrtc.LocalIceCandidate
import com.example.helmet.webrtc.WebRtcCallEngine
import com.example.helmet.webrtc.WebRtcCallListener
import com.example.helmet.webrtc.WebRtcMediaState
import com.example.helmet.webrtc.WebRtcStatus
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal fun shouldUseCallLowBandwidthMode(snapshot: ConnectivitySnapshot): Boolean =
    !snapshot.hasValidatedInternet ||
        snapshot.metered ||
        snapshot.roaming ||
        snapshot.downstreamKbps?.let { it in 1 until 1_000 } == true ||
        snapshot.upstreamKbps?.let { it in 1 until 500 } == true

internal class CallBandwidthPolicy {
    @Volatile
    private var lowBandwidth = false

    fun update(enabled: Boolean) {
        lowBandwidth = enabled
    }

    fun apply(setMode: (Boolean) -> Boolean): Boolean = setMode(lowBandwidth)
}

class CallMediaCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val calls: CallStore,
    private val events: EventStore,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
) : Closeable {
    private val appContext = context.applicationContext
    private val lifecycleMutex = Mutex()
    private val signalingMutex = Mutex()
    private var activeCallId: String? = null
    private var activeEngine: WebRtcCallEngine? = null
    private var activeJob: Job? = null
    private var offerSent = false
    private val pendingCandidates = mutableListOf<CallSignal>()
    private val bandwidthPolicy = CallBandwidthPolicy()

    suspend fun start(callId: String, runtimeConfig: RuntimeConfig) = lifecycleMutex.withLock {
        if (activeCallId == callId && activeJob?.isActive == true) return
        stopLocked("REPLACED_BY_NEW_CALL")
        val call = requireNotNull(calls.find(callId)) { "call session not found" }
        require(call.state == CallState.ACCEPTED || call.state == CallState.CONNECTING) {
            "call must be accepted before WebRTC starts"
        }
        require(runtimeConfig.backendBaseUrl.isNotBlank() && runtimeConfig.backendBearerToken.isNotBlank()) {
            "backend configuration is missing"
        }
        activeCallId = callId
        activeJob = scope.launch { runCall(call, runtimeConfig) }
    }

    suspend fun stop(callId: String, reason: String) = lifecycleMutex.withLock {
        if (activeCallId == callId) stopLocked(reason)
    }

    fun setLowBandwidthMode(enabled: Boolean): Boolean {
        bandwidthPolicy.update(enabled)
        return activeEngine?.let { engine -> bandwidthPolicy.apply(engine::setLowBandwidthMode) } ?: true
    }

    override fun close() {
        activeJob?.cancel()
        activeJob = null
        activeEngine?.close()
        activeEngine = null
        activeCallId = null
    }

    private suspend fun runCall(call: CallSession, runtimeConfig: RuntimeConfig) {
        val client = HttpCommunicationClient(
            runtimeConfig.backendBaseUrl,
            runtimeConfig.backendBearerToken,
            wallClock = wallClock,
        )
        try {
            val ice = client.fetchIceConfiguration(call.callId, call.deviceId)
            val engine = WebRtcCallEngine(
                appContext,
                call.callId,
                call.mediaMode,
                ice,
                Listener(call, client),
                wallClock = wallClock,
            )
            activeEngine = engine
            val offer = engine.createOffer()
            val bandwidthModeApplied = bandwidthPolicy.apply(engine::setLowBandwidthMode)
            val offerSignal = signal(
                call,
                CallSignalType.OFFER,
                JSONObject()
                    .put("sdp", offer.sdp)
                    .put("audioEnabled", offer.audioEnabled)
                    .put("videoEnabled", offer.videoEnabled)
                    .put("degradedReason", offer.degradedReason ?: JSONObject.NULL),
            )
            signalingMutex.withLock {
                client.sendCallSignal(offerSignal)
                offerSent = true
                pendingCandidates.forEach { client.sendCallSignal(it) }
                pendingCandidates.clear()
            }
            if (calls.find(call.callId)?.state == CallState.ACCEPTED) {
                val connecting = calls.transition(call.callId, CallState.CONNECTING)
                client.syncCall(connecting)
                calls.markDelivered(call.callId, wallClock())
            }
            record(
                "WEBRTC_OFFER_SENT",
                EventSeverity.INFO,
                mapOf(
                    "callId" to call.callId,
                    "audioEnabled" to offer.audioEnabled,
                    "videoEnabled" to offer.videoEnabled,
                    "degradedReason" to offer.degradedReason,
                    "bandwidthModeApplied" to bandwidthModeApplied,
                    "iceExpiresAtEpochMillis" to ice.expiresAtEpochMillis,
                ),
            )
            val answered = pollRemoteSignals(call, client, engine)
            if (!answered) failCall(call.callId, client, "WEBRTC_ANSWER_TIMEOUT")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            record(
                "WEBRTC_SESSION_FAILED",
                EventSeverity.HIGH,
                mapOf("callId" to call.callId, "error" to error.toString()),
            )
            failCall(call.callId, client, error.message ?: error.javaClass.simpleName)
        }
    }

    private suspend fun pollRemoteSignals(
        call: CallSession,
        client: HttpCommunicationClient,
        engine: WebRtcCallEngine,
    ): Boolean {
        var afterSequence = 0L
        var answerApplied = false
        val queuedCandidates = mutableListOf<CallSignal>()
        val deadline = monotonicClock() + ANSWER_TIMEOUT_MILLIS
        while (currentCoroutineContext().isActive) {
            val signals = client.fetchCallSignals(call.callId, afterSequence, 100)
            for (remote in signals) {
                afterSequence = requireNotNull(remote.serverSequence)
                if (remote.senderId == call.deviceId) continue
                val payload = JSONObject(remote.payloadJson)
                when (remote.type) {
                    CallSignalType.ANSWER -> {
                        if (!answerApplied) {
                            engine.applyRemoteAnswer(payload.getString("sdp"))
                            answerApplied = true
                            queuedCandidates.forEach { candidate -> applyRemoteCandidate(engine, candidate) }
                            queuedCandidates.clear()
                            record(
                                "WEBRTC_ANSWER_APPLIED",
                                EventSeverity.INFO,
                                mapOf("callId" to call.callId, "signalId" to remote.signalId),
                            )
                        }
                    }
                    CallSignalType.ICE_CANDIDATE -> {
                        if (answerApplied) applyRemoteCandidate(engine, remote) else queuedCandidates += remote
                    }
                    CallSignalType.ICE_COMPLETE -> Unit
                    CallSignalType.OFFER -> throw IllegalStateException("unexpected remote offer")
                }
            }
            val currentState = calls.find(call.callId)?.state
            if (currentState in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED)) return answerApplied
            if (!answerApplied && monotonicClock() >= deadline) return false
            delay(SIGNAL_POLL_INTERVAL_MILLIS)
        }
        return answerApplied
    }

    private fun applyRemoteCandidate(engine: WebRtcCallEngine, signal: CallSignal) {
        val payload = JSONObject(signal.payloadJson)
        check(
            engine.addRemoteIceCandidate(
                payload.optString("sdpMid").takeIf(String::isNotBlank),
                payload.getInt("sdpMLineIndex"),
                payload.getString("candidate"),
            ),
        ) { "remote ICE candidate was rejected" }
    }

    private suspend fun failCall(callId: String, client: HttpCommunicationClient, reason: String) {
        val current = calls.find(callId) ?: return
        if (current.state in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED)) return
        runCatching {
            val failed = calls.transition(callId, CallState.FAILED, reason.take(MAX_REASON_LENGTH))
            client.syncCall(failed)
            calls.markDelivered(callId, wallClock())
        }
    }

    private suspend fun stopLocked(reason: String) {
        val callId = activeCallId
        activeJob?.cancel()
        activeJob = null
        activeEngine?.close()
        activeEngine = null
        activeCallId = null
        signalingMutex.withLock {
            offerSent = false
            pendingCandidates.clear()
        }
        if (callId != null) {
            record("WEBRTC_SESSION_CLOSED", EventSeverity.INFO, mapOf("callId" to callId, "reason" to reason))
        }
    }

    private fun signal(call: CallSession, type: CallSignalType, payload: JSONObject): CallSignal = CallSignal(
        signalId = UUID.randomUUID().toString(),
        callId = call.callId,
        serverSequence = null,
        senderId = call.deviceId,
        type = type,
        payloadJson = payload.toString(),
        createdAtEpochMillis = wallClock(),
    )

    private suspend fun record(type: String, severity: EventSeverity, payload: Map<String, Any?>) {
        events.record(type, severity, JSONObject(payload).toString())
    }

    private inner class Listener(
        private val call: CallSession,
        private val client: HttpCommunicationClient,
    ) : WebRtcCallListener {
        override fun onStatus(status: WebRtcStatus) {
            scope.launch {
                record(
                    "WEBRTC_STATUS_${status.state.name}",
                    if (status.state == WebRtcMediaState.FAILED) EventSeverity.HIGH else EventSeverity.INFO,
                    mapOf(
                        "callId" to status.callId,
                        "audioEnabled" to status.audioEnabled,
                        "videoEnabled" to status.videoEnabled,
                        "degradedReason" to status.degradedReason,
                        "peerConnectionState" to status.peerConnectionState,
                        "iceConnectionState" to status.iceConnectionState,
                    ),
                )
                if (status.state == WebRtcMediaState.CONNECTED) {
                    val current = calls.find(call.callId)
                    if (current?.state == CallState.CONNECTING) {
                        val connected = calls.transition(call.callId, CallState.CONNECTED)
                        client.syncCall(connected)
                        calls.markDelivered(call.callId, wallClock())
                    }
                }
                if (status.state == WebRtcMediaState.FAILED) {
                    failCall(call.callId, client, "WEBRTC_PEER_CONNECTION_FAILED")
                }
            }
        }

        override fun onLocalIceCandidate(candidate: LocalIceCandidate) {
            val candidateSignal = signal(
                call,
                CallSignalType.ICE_CANDIDATE,
                JSONObject(WebRtcCallEngine.iceCandidatePayload(candidate)),
            )
            scope.launch {
                runCatching {
                    signalingMutex.withLock {
                        if (offerSent) client.sendCallSignal(candidateSignal) else pendingCandidates += candidateSignal
                    }
                }.onFailure { error ->
                    record(
                        "WEBRTC_ICE_SIGNAL_FAILED",
                        EventSeverity.HIGH,
                        mapOf("callId" to call.callId, "error" to error.toString()),
                    )
                    failCall(call.callId, client, "ICE_SIGNAL_FAILED")
                }
            }
        }

        override fun onIceGatheringComplete() {
            val complete = signal(call, CallSignalType.ICE_COMPLETE, JSONObject())
            scope.launch {
                runCatching {
                    signalingMutex.withLock {
                        if (offerSent) client.sendCallSignal(complete) else pendingCandidates += complete
                    }
                }
            }
        }
    }

    companion object {
        private const val ANSWER_TIMEOUT_MILLIS = 60_000L
        private const val SIGNAL_POLL_INTERVAL_MILLIS = 500L
        private const val MAX_REASON_LENGTH = 1_024
    }
}
