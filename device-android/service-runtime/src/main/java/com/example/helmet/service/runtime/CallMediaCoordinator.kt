package com.example.helmet.service.runtime

import android.content.Context
import com.example.helmet.communication.sync.CallSyncReceipt
import com.example.helmet.communication.sync.CommunicationException
import com.example.helmet.communication.sync.HttpCommunicationClient
import com.example.helmet.core.model.CallSession
import com.example.helmet.core.model.CallSignal
import com.example.helmet.core.model.CallSignalType
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.core.model.StreamState
import com.example.helmet.data.local.CallMediaRecoveryState
import com.example.helmet.data.local.CallMediaRecoveryStore
import com.example.helmet.data.local.CallStore
import com.example.helmet.data.local.EventStore
import com.example.helmet.feature.connectivity.ConnectivitySnapshot
import com.example.helmet.webrtc.LocalIceCandidate
import com.example.helmet.webrtc.WebRtcCallEngine
import com.example.helmet.webrtc.WebRtcCallListener
import com.example.helmet.webrtc.WebRtcException
import com.example.helmet.webrtc.WebRtcMediaState
import com.example.helmet.webrtc.WebRtcOffer
import com.example.helmet.webrtc.WebRtcStatus
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

internal fun isCallMediaStartState(state: CallState): Boolean = state in setOf(
    CallState.ACCEPTED,
    CallState.CONNECTING,
    CallState.CONNECTED,
)

internal fun streamStateForWebRtcMediaState(state: WebRtcMediaState): StreamState = when (state) {
    WebRtcMediaState.NEW,
    WebRtcMediaState.OFFER_READY,
    WebRtcMediaState.CONNECTING,
    WebRtcMediaState.DISCONNECTED,
    -> StreamState.STARTING
    WebRtcMediaState.CONNECTED -> StreamState.STREAMING
    WebRtcMediaState.FAILED -> StreamState.FAILED
    WebRtcMediaState.CLOSED -> StreamState.IDLE
}

internal fun isNonRecoverableMediaFailure(status: WebRtcStatus): Boolean =
    status.state == WebRtcMediaState.FAILED &&
        status.degradedReason?.let { reason ->
            reason.startsWith("AUDIO_") ||
                reason == "CAMERA_RUNTIME_ERROR" ||
                reason == "CAMERA_DISCONNECTED" ||
                reason == "CAMERA_FROZEN" ||
                reason == "CAMERA_CLOSED"
        } == true

internal fun callMediaFailureCode(error: Throwable): String {
    val nativeCode = (error as? WebRtcException)
        ?.message
        ?.substringBefore(':')
        ?.takeIf { value -> value.isNotBlank() && value.all { it == '_' || it.isDigit() || it in 'A'..'Z' } }
    return nativeCode ?: error.javaClass.simpleName.takeIf(String::isNotBlank) ?: "UNKNOWN"
}

private fun updateStreamStatus(
    state: StreamState,
    audioEnabled: Boolean = false,
    videoEnabled: Boolean = false,
    error: String? = null,
) {
    RuntimeStatus.update {
        it.copy(
            streamState = state,
            streamAudioEnabled = audioEnabled,
            streamVideoEnabled = videoEnabled,
            streamError = error,
        )
    }
}

internal class CallBandwidthPolicy {
    @Volatile
    private var lowBandwidth = false

    fun update(enabled: Boolean) {
        lowBandwidth = enabled
    }

    fun apply(setMode: (Boolean) -> Boolean): Boolean = setMode(lowBandwidth)
}

internal suspend fun <T> retryTransientCommunication(
    initialDelayMillis: Long = 500L,
    maximumDelayMillis: Long = 10_000L,
    maximumAttempts: Int? = null,
    monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
    pause: suspend (Long) -> Unit = { delay(it) },
    shouldContinue: () -> Boolean = { true },
    onRetry: suspend (
        attempt: Int,
        delayMillis: Long,
        operationElapsedMillis: Long,
        error: CommunicationException,
    ) -> Unit = { _, _, _, _ -> },
    operation: suspend () -> T,
): T {
    require(initialDelayMillis > 0 && maximumDelayMillis >= initialDelayMillis)
    require(maximumAttempts == null || maximumAttempts > 0)
    var attempt = 0
    var retryDelayMillis = initialDelayMillis
    while (true) {
        currentCoroutineContext().ensureActive()
        if (!shouldContinue()) throw CancellationException("signaling session is no longer active")
        val attemptStartedAt = monotonicClock()
        try {
            return operation()
        } catch (error: CommunicationException) {
            if (!error.retryable) throw error
            if (!shouldContinue()) throw CancellationException("signaling session is no longer active")
            attempt += 1
            if (maximumAttempts != null && attempt >= maximumAttempts) throw error
            val elapsed = (monotonicClock() - attemptStartedAt).coerceAtLeast(0L)
            onRetry(attempt, retryDelayMillis, elapsed, error)
            pause(retryDelayMillis)
            retryDelayMillis = if (retryDelayMillis >= maximumDelayMillis) {
                maximumDelayMillis
            } else if (retryDelayMillis > maximumDelayMillis / 2) {
                maximumDelayMillis
            } else {
                retryDelayMillis * 2
            }
        }
    }
}

internal fun shouldRecordSignalingRetry(attempt: Int): Boolean {
    require(attempt > 0)
    return attempt and (attempt - 1) == 0
}

internal data class CallStateSyncAttempt(
    val delivered: Boolean,
    val errorType: String?,
)

internal suspend fun syncCallStateOrDefer(
    call: CallSession,
    sync: suspend (CallSession) -> CallSyncReceipt,
    markDelivered: suspend (String, Long) -> Boolean,
    defer: () -> Unit,
): CallStateSyncAttempt {
    return try {
        val receipt = sync(call)
        val acknowledged =
            receipt.acknowledgedState == call.state.name &&
                receipt.acknowledgedStateSequence == call.stateSequence &&
                markDelivered(call.callId, call.stateSequence)
        if (!acknowledged) defer()
        CallStateSyncAttempt(delivered = acknowledged, errorType = null)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        defer()
        CallStateSyncAttempt(delivered = false, errorType = error.javaClass.name)
    }
}

internal fun requireSignalOfferSequence(payload: JSONObject): Long {
    val raw = payload.opt("offerSequence")
    require(raw is Int || raw is Long) { "offerSequence must be an integer" }
    return (raw as Number).toLong().also { require(it > 0) { "offerSequence must be positive" } }
}

internal data class CallNegotiationSnapshot(
    val serverCursor: Long,
    val latestOfferSequence: Long?,
    val latestLocalIceGeneration: Long?,
    val latestAnsweredOfferSequence: Long?,
    val answerDeadlineMonotonicMillis: Long?,
    val restartAttemptCount: Int,
) {
    val awaitingAnswer: Boolean
        get() = latestOfferSequence != null && latestAnsweredOfferSequence != latestOfferSequence
}

/** Pure sequencing rules shared by live signaling and recovery tests. */
internal class CallNegotiationTracker(
    initialServerCursor: Long,
    initialRestartAttemptCount: Int = 0,
) {
    init {
        require(initialServerCursor >= 0)
        require(initialRestartAttemptCount >= 0)
    }

    private var serverCursor = initialServerCursor
    private var latestOfferSequence: Long? = null
    private var latestLocalIceGeneration: Long? = null
    private var offerLocalIceGeneration: Long? = null
    private var latestAnsweredOfferSequence: Long? = null
    private var answerDeadlineMonotonicMillis: Long? = null
    private var restartAttemptCount = initialRestartAttemptCount

    fun snapshot() = CallNegotiationSnapshot(
        serverCursor = serverCursor,
        latestOfferSequence = latestOfferSequence,
        latestLocalIceGeneration = latestLocalIceGeneration,
        latestAnsweredOfferSequence = latestAnsweredOfferSequence,
        answerDeadlineMonotonicMillis = answerDeadlineMonotonicMillis,
        restartAttemptCount = restartAttemptCount,
    )

    fun beginLocalIceGeneration(): Long {
        val current = latestLocalIceGeneration ?: 0L
        check(current < Long.MAX_VALUE) { "local ICE generation exhausted" }
        return (current + 1).also { latestLocalIceGeneration = it }
    }

    fun recordOffer(
        sequence: Long,
        localIceGeneration: Long,
        nowMonotonicMillis: Long,
        answerTimeoutMillis: Long,
    ) {
        require(sequence > serverCursor) { "offer sequence did not advance" }
        require(localIceGeneration == latestLocalIceGeneration) { "stale local ICE generation" }
        require(answerTimeoutMillis > 0)
        serverCursor = sequence
        latestOfferSequence = sequence
        offerLocalIceGeneration = localIceGeneration
        latestAnsweredOfferSequence = null
        answerDeadlineMonotonicMillis = safeDurationSum(nowMonotonicMillis, answerTimeoutMillis)
    }

    fun offerSequenceForLocalIceGeneration(localIceGeneration: Long): Long? =
        latestOfferSequence.takeIf {
            localIceGeneration == latestLocalIceGeneration &&
                localIceGeneration == offerLocalIceGeneration
        }

    fun recordRemote(sequence: Long) {
        require(sequence > serverCursor) { "remote signal sequence did not advance" }
        serverCursor = sequence
    }

    fun shouldApplyAnswer(answerSequence: Long): Boolean {
        val offer = latestOfferSequence ?: return false
        return answerSequence > offer && latestAnsweredOfferSequence != offer
    }

    fun recordAnswer(answerSequence: Long) {
        val offer = requireNotNull(latestOfferSequence) { "answer has no local offer" }
        require(answerSequence >= serverCursor && answerSequence > offer)
        serverCursor = maxOf(serverCursor, answerSequence)
        latestAnsweredOfferSequence = offer
        answerDeadlineMonotonicMillis = null
    }

    fun answerTimedOut(nowMonotonicMillis: Long): Boolean =
        snapshot().awaitingAnswer &&
            answerDeadlineMonotonicMillis?.let { nowMonotonicMillis >= it } == true

    fun pauseAnswerTimeout(durationMillis: Long) {
        require(durationMillis >= 0)
        val deadline = answerDeadlineMonotonicMillis ?: return
        answerDeadlineMonotonicMillis = if (Long.MAX_VALUE - deadline < durationMillis) {
            Long.MAX_VALUE
        } else {
            deadline + durationMillis
        }
    }

    fun recordRestartAttempt(): Int {
        restartAttemptCount += 1
        return restartAttemptCount
    }
}

class CallMediaCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val calls: CallStore,
    private val events: EventStore,
    private val recoveryStore: CallMediaRecoveryStore,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
) : Closeable {
    private data class ActiveNegotiation(
        val call: CallSession,
        val client: HttpCommunicationClient,
        val engine: WebRtcCallEngine,
        val recoveryGeneration: Long,
        val tracker: CallNegotiationTracker,
        val queuedRemoteCandidates: MutableList<Pair<Long, CallSignal>> = mutableListOf(),
    )

    private val appContext = context.applicationContext
    private val lifecycleMutex = Mutex()
    private val signalingMutex = Mutex()
    private val negotiationMutex = Mutex()
    private val iceRecoveryMutex = Mutex()
    @Volatile
    private var activeCallId: String? = null
    private var activeEngine: WebRtcCallEngine? = null
    private var activeNegotiation: ActiveNegotiation? = null
    private var activeJob: Job? = null
    private var iceRecoveryJob: Job? = null
    private val bandwidthPolicy = CallBandwidthPolicy()

    suspend fun start(callId: String, runtimeConfig: RuntimeConfig) = lifecycleMutex.withLock {
        try {
            if (activeCallId == callId && activeJob?.isActive == true) return
            stopLocked("REPLACED_BY_NEW_CALL")
            val call = requireNotNull(calls.find(callId)) { "call session not found" }
            require(isCallMediaStartState(call.state)) { "call must be accepted before WebRTC starts" }
            require(runtimeConfig.backendBaseUrl.isNotBlank() && runtimeConfig.backendBearerToken.isNotBlank()) {
                "backend configuration is missing"
            }
            updateStreamStatus(StreamState.STARTING)
            activeCallId = callId
            activeJob = scope.launch { runCall(call, runtimeConfig) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            updateStreamStatus(StreamState.FAILED, error = error.javaClass.simpleName)
            throw error
        }
    }

    suspend fun stop(
        callId: String,
        reason: String,
        terminalFailure: Boolean = false,
        terminalError: String? = null,
    ) = lifecycleMutex.withLock {
        if (activeCallId == callId) {
            stopLocked(
                reason = reason,
                terminalState = if (terminalFailure) StreamState.FAILED else StreamState.IDLE,
                terminalError = terminalError,
            )
        }
    }

    fun setLowBandwidthMode(enabled: Boolean): Boolean {
        bandwidthPolicy.update(enabled)
        return activeEngine?.let { engine -> bandwidthPolicy.apply(engine::setLowBandwidthMode) } ?: true
    }

    override fun close() {
        updateStreamStatus(StreamState.STOPPING)
        iceRecoveryJob?.cancel()
        iceRecoveryJob = null
        activeJob?.cancel()
        activeJob = null
        activeEngine?.close()
        activeEngine = null
        activeNegotiation = null
        activeCallId = null
        updateStreamStatus(StreamState.IDLE)
        // Keep the Room checkpoint so a restarted foreground service can identify process recovery.
    }

    private suspend fun runCall(call: CallSession, runtimeConfig: RuntimeConfig) {
        val client = HttpCommunicationClient(
            runtimeConfig.backendBaseUrl,
            runtimeConfig.backendBearerToken,
            wallClock = wallClock,
        )
        var checkpoint: CallMediaRecoveryState? = null
        try {
            checkpoint = recoveryStore.begin(call.callId)
            val ice = retrySignalingOperation(call.callId, "FETCH_ICE_CONFIGURATION") {
                client.fetchIceConfiguration(call.callId, call.deviceId)
            }
            val listener = Listener(call, client, checkpoint.generation)
            val engine = WebRtcCallEngine(
                appContext,
                call.callId,
                call.mediaMode,
                ice,
                listener,
                wallClock = wallClock,
            )
            activeEngine = engine
            val negotiation = ActiveNegotiation(
                call = call,
                client = client,
                engine = engine,
                recoveryGeneration = checkpoint.generation,
                tracker = CallNegotiationTracker(checkpoint.lastRemoteSequence),
            )
            negotiationMutex.withLock { activeNegotiation = negotiation }
            val offer = sendOffer(negotiation, restart = false)
            val bandwidthModeApplied = bandwidthPolicy.apply(engine::setLowBandwidthMode)
            if (calls.find(call.callId)?.state == CallState.ACCEPTED) {
                val connecting = calls.transition(call.callId, CallState.CONNECTING)
                syncCallWithoutFailingMedia(client, connecting)
            }
            record(
                "WEBRTC_OFFER_SENT",
                EventSeverity.INFO,
                mapOf(
                    "callId" to call.callId,
                    "audioEnabled" to offer.audioEnabled,
                    "videoEnabled" to offer.videoEnabled,
                    "degradedReason" to offer.degradedReason,
                    "processRecovery" to checkpoint.recoveredFromPreviousProcess,
                    "callStateRecovery" to (call.state == CallState.CONNECTED),
                    "recoveryGeneration" to checkpoint.generation,
                    "bandwidthModeApplied" to bandwidthModeApplied,
                    "iceExpiresAtEpochMillis" to ice.expiresAtEpochMillis,
                ),
            )
            pollRemoteSignals(negotiation)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val errorCode = callMediaFailureCode(error)
            updateStreamStatus(StreamState.FAILED, error = errorCode)
            checkpoint?.let {
                runCatching {
                    recoveryStore.recordFailure(call.callId, it.generation, errorCode)
                }
            }
            record(
                "WEBRTC_SESSION_FAILED",
                EventSeverity.HIGH,
                mapOf(
                    "callId" to call.callId,
                    "errorType" to error.javaClass.name,
                    "errorCode" to errorCode,
                ),
            )
            runCatching {
                failCall(call.callId, client, "WEBRTC_$errorCode".take(MAX_REASON_LENGTH))
            }.onFailure { persistenceError ->
                record(
                    "WEBRTC_FAILURE_STATE_NOT_PERSISTED",
                    EventSeverity.HIGH,
                    mapOf(
                        "callId" to call.callId,
                        "errorType" to persistenceError.javaClass.name,
                    ),
                )
            }
            cleanupFailedRun(call.callId, errorCode)
        }
    }

    private suspend fun cleanupFailedRun(callId: String, errorCode: String) = lifecycleMutex.withLock {
        if (activeCallId == callId) {
            stopLocked(
                reason = "SESSION_FAILED",
                terminalState = StreamState.FAILED,
                terminalError = errorCode,
                cancelActiveJob = false,
            )
        }
    }

    private suspend fun sendOffer(negotiation: ActiveNegotiation, restart: Boolean): WebRtcOffer =
        negotiationMutex.withLock {
            check(activeNegotiation === negotiation) { "stale WebRTC negotiation" }
            negotiation.queuedRemoteCandidates.clear()
            val localIceGeneration = negotiation.tracker.beginLocalIceGeneration()
            val (offer, storedSignal) = signalingMutex.withLock {
                val generated = if (restart) {
                    negotiation.engine.createIceRestartOffer(localIceGeneration)
                } else {
                    negotiation.engine.createOffer(localIceGeneration)
                }
                val stored = sendSignalWithRetry(
                    negotiation.client,
                    signal(
                        negotiation.call,
                        CallSignalType.OFFER,
                        JSONObject()
                            .put("sdp", generated.sdp)
                            .put("audioEnabled", generated.audioEnabled)
                            .put("videoEnabled", generated.videoEnabled)
                            .put("degradedReason", generated.degradedReason ?: JSONObject.NULL),
                    ),
                )
                generated to stored
            }
            val sequence = requireNotNull(storedSignal.serverSequence) { "stored WebRTC offer has no sequence" }
            negotiation.tracker.recordOffer(
                sequence = sequence,
                localIceGeneration = localIceGeneration,
                nowMonotonicMillis = monotonicClock(),
                answerTimeoutMillis = ANSWER_TIMEOUT_MILLIS,
            )
            recoveryStore.recordOffer(
                negotiation.call.callId,
                negotiation.recoveryGeneration,
                sequence,
            )
            offer
        }

    private suspend fun pollRemoteSignals(negotiation: ActiveNegotiation) {
        while (currentCoroutineContext().isActive) {
            val afterSequence = negotiationMutex.withLock { negotiation.tracker.snapshot().serverCursor }
            val signals = retryTransientCommunication(
                maximumAttempts = SIGNALING_MAX_ATTEMPTS,
                onRetry = { attempt, retryDelayMillis, operationElapsedMillis, error ->
                    negotiationMutex.withLock {
                        negotiation.tracker.pauseAnswerTimeout(
                            safeDurationSum(retryDelayMillis, operationElapsedMillis),
                        )
                    }
                    if (shouldRecordSignalingRetry(attempt)) {
                        record(
                            "WEBRTC_SIGNAL_POLL_RETRY",
                            EventSeverity.MEDIUM,
                            mapOf(
                                "callId" to negotiation.call.callId,
                                "attempt" to attempt,
                                "retryDelayMillis" to retryDelayMillis,
                                "errorType" to error.javaClass.name,
                                "statusCode" to error.statusCode,
                            ),
                        )
                    }
                },
            ) {
                negotiation.client.fetchCallSignals(negotiation.call.callId, afterSequence, 100)
            }
            for (remote in signals) processRemoteSignal(negotiation, remote)
            val currentState = calls.find(negotiation.call.callId)?.state
            if (currentState in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED)) return
            val timedOut = negotiationMutex.withLock {
                negotiation.tracker.answerTimedOut(monotonicClock())
            }
            if (timedOut) {
                failCall(negotiation.call.callId, negotiation.client, "WEBRTC_ANSWER_TIMEOUT")
                return
            }
            delay(SIGNAL_POLL_INTERVAL_MILLIS)
        }
    }

    private suspend fun processRemoteSignal(negotiation: ActiveNegotiation, remote: CallSignal) =
        negotiationMutex.withLock {
            if (activeNegotiation !== negotiation) return@withLock
            val sequence = requireNotNull(remote.serverSequence)
            if (sequence <= negotiation.tracker.snapshot().serverCursor) return@withLock
            negotiation.tracker.recordRemote(sequence)
            recoveryStore.advanceRemote(
                negotiation.call.callId,
                negotiation.recoveryGeneration,
                sequence,
            )
            if (remote.senderId == negotiation.call.deviceId) return@withLock
            when (remote.type) {
                CallSignalType.ANSWER -> {
                    val payload = JSONObject(remote.payloadJson)
                    val offerSequence = requireSignalOfferSequence(payload)
                    if (
                        offerSequence == negotiation.tracker.snapshot().latestOfferSequence &&
                        negotiation.tracker.shouldApplyAnswer(sequence)
                    ) {
                        negotiation.engine.applyRemoteAnswer(payload.getString("sdp"))
                        negotiation.tracker.recordAnswer(sequence)
                        recoveryStore.recordAnswer(
                            negotiation.call.callId,
                            negotiation.recoveryGeneration,
                            sequence,
                        )
                        negotiation.queuedRemoteCandidates
                            .filter { (candidateOffer, _) -> candidateOffer == offerSequence }
                            .forEach { (_, candidate) -> applyRemoteCandidate(negotiation.engine, candidate) }
                        negotiation.queuedRemoteCandidates.clear()
                        record(
                            "WEBRTC_ANSWER_APPLIED",
                            EventSeverity.INFO,
                            mapOf(
                                "callId" to negotiation.call.callId,
                                "signalId" to remote.signalId,
                                "offerSequence" to offerSequence,
                            ),
                        )
                    }
                }
                CallSignalType.ICE_CANDIDATE -> {
                    val snapshot = negotiation.tracker.snapshot()
                    val candidateOfferSequence = requireSignalOfferSequence(JSONObject(remote.payloadJson))
                    if (candidateOfferSequence != snapshot.latestOfferSequence) return@withLock
                    if (!snapshot.awaitingAnswer) {
                        applyRemoteCandidate(negotiation.engine, remote)
                    } else {
                        negotiation.queuedRemoteCandidates += requireNotNull(snapshot.latestOfferSequence) to remote
                    }
                }
                CallSignalType.ICE_COMPLETE -> {
                    val completeOfferSequence = requireSignalOfferSequence(JSONObject(remote.payloadJson))
                    if (completeOfferSequence != negotiation.tracker.snapshot().latestOfferSequence) {
                        return@withLock
                    }
                }
                CallSignalType.OFFER -> throw IllegalStateException("unexpected remote offer")
            }
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

    private fun scheduleIceRecovery(
        call: CallSession,
        client: HttpCommunicationClient,
        recoveryGeneration: Long,
        trigger: String,
        delayMillis: Long,
    ) {
        if (activeCallId != call.callId || iceRecoveryJob?.isActive == true) return
        iceRecoveryJob = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            iceRecoveryJob = null
            attemptIceRecovery(call, client, recoveryGeneration, trigger)
        }
    }

    private suspend fun attemptIceRecovery(
        call: CallSession,
        client: HttpCommunicationClient,
        recoveryGeneration: Long,
        trigger: String,
    ) = iceRecoveryMutex.withLock {
        if (activeCallId != call.callId) return@withLock
        val negotiation = negotiationMutex.withLock {
            activeNegotiation?.takeIf {
                it.call.callId == call.callId && it.recoveryGeneration == recoveryGeneration
            }
        } ?: return@withLock
        val attempt = negotiationMutex.withLock {
            negotiation.tracker.recordRestartAttempt()
        }
        if (attempt > MAX_ICE_RESTART_ATTEMPTS) {
            failCall(call.callId, client, "WEBRTC_ICE_RECOVERY_EXHAUSTED")
            return@withLock
        }
        recoveryStore.recordRestart(call.callId, recoveryGeneration)
        runCatching {
            val freshIce = retrySignalingOperation(call.callId, "ICE_RESTART_FETCH_CONFIGURATION") {
                client.fetchIceConfiguration(call.callId, call.deviceId)
            }
            check(negotiation.engine.replaceIceConfiguration(freshIce)) {
                "replacement ICE configuration was rejected"
            }
            val offer = sendOffer(negotiation, restart = true)
            record(
                "WEBRTC_ICE_RESTART_OFFER_SENT",
                EventSeverity.MEDIUM,
                mapOf(
                    "callId" to call.callId,
                    "trigger" to trigger,
                    "attempt" to attempt,
                    "audioEnabled" to offer.audioEnabled,
                    "videoEnabled" to offer.videoEnabled,
                    "iceExpiresAtEpochMillis" to freshIce.expiresAtEpochMillis,
                ),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            recoveryStore.recordFailure(call.callId, recoveryGeneration, error.javaClass.simpleName)
            record(
                "WEBRTC_ICE_RESTART_FAILED",
                EventSeverity.HIGH,
                mapOf(
                    "callId" to call.callId,
                    "trigger" to trigger,
                    "attempt" to attempt,
                    "errorType" to error.javaClass.name,
                ),
            )
            if (attempt >= MAX_ICE_RESTART_ATTEMPTS) {
                failCall(call.callId, client, "WEBRTC_ICE_RECOVERY_EXHAUSTED")
            } else {
                scheduleIceRecovery(
                    call,
                    client,
                    recoveryGeneration,
                    "RETRY_AFTER_${error.javaClass.simpleName}",
                    ICE_RESTART_RETRY_MILLIS,
                )
            }
        }
    }

    private suspend fun sendSignalWithRetry(
        client: HttpCommunicationClient,
        signal: CallSignal,
    ): CallSignal = retrySignalingOperation(signal.callId, "SEND_${signal.type.name}") {
        client.sendCallSignal(signal)
    }

    private suspend fun <T> retrySignalingOperation(
        callId: String,
        operationName: String,
        operation: suspend () -> T,
    ): T = retryTransientCommunication(
        maximumAttempts = SIGNALING_MAX_ATTEMPTS,
        shouldContinue = { activeCallId == callId },
        onRetry = { attempt, retryDelayMillis, _, error ->
            if (shouldRecordSignalingRetry(attempt)) {
                record(
                    "WEBRTC_SIGNAL_RETRY",
                    EventSeverity.MEDIUM,
                    mapOf(
                        "callId" to callId,
                        "operation" to operationName,
                        "attempt" to attempt,
                        "retryDelayMillis" to retryDelayMillis,
                        "errorType" to error.javaClass.name,
                        "statusCode" to error.statusCode,
                    ),
                )
            }
        },
        operation = operation,
    )

    private suspend fun syncCallWithoutFailingMedia(client: HttpCommunicationClient, call: CallSession) {
        if (!calls.markAttempt(call.callId, call.stateSequence, wallClock())) {
            CommunicationWorker.enqueue(appContext, CommunicationWorkTrigger.DURABLE_COMMIT)
            return
        }
        val attempt = syncCallStateOrDefer(
            call = call,
            sync = client::syncCall,
            markDelivered = { callId, stateSequence ->
                calls.markDelivered(callId, stateSequence, wallClock())
            },
            defer = {
                CommunicationWorker.enqueue(appContext, CommunicationWorkTrigger.DURABLE_COMMIT)
            },
        )
        if (!attempt.delivered) {
            record(
                "CALL_STATE_SYNC_DEFERRED",
                EventSeverity.MEDIUM,
                mapOf(
                    "callId" to call.callId,
                    "state" to call.state.name,
                    "stateSequence" to call.stateSequence,
                    "errorType" to attempt.errorType,
                ),
            )
        }
    }

    private suspend fun failCall(callId: String, client: HttpCommunicationClient, reason: String) {
        val current = calls.find(callId) ?: return
        if (current.state in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED)) return
        val failed = calls.transition(callId, CallState.FAILED, reason.take(MAX_REASON_LENGTH))
        syncCallWithoutFailingMedia(client, failed)
    }

    private suspend fun stopLocked(
        reason: String,
        terminalState: StreamState = StreamState.IDLE,
        terminalError: String? = null,
        cancelActiveJob: Boolean = true,
    ) {
        val callId = activeCallId
        if (callId != null || activeEngine != null) updateStreamStatus(StreamState.STOPPING)
        iceRecoveryJob?.cancel()
        iceRecoveryJob = null
        if (cancelActiveJob) activeJob?.cancel()
        activeJob = null
        activeEngine?.close()
        activeEngine = null
        activeCallId = null
        negotiationMutex.withLock { activeNegotiation = null }
        if (callId != null) {
            runCatching { recoveryStore.clear(callId) }
            record("WEBRTC_SESSION_CLOSED", EventSeverity.INFO, mapOf("callId" to callId, "reason" to reason))
        }
        updateStreamStatus(terminalState, error = terminalError)
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
        private val recoveryGeneration: Long,
    ) : WebRtcCallListener {
        override fun onStatus(status: WebRtcStatus) {
            updateStreamStatus(
                state = streamStateForWebRtcMediaState(status.state),
                audioEnabled = status.audioEnabled,
                videoEnabled = status.videoEnabled,
                error = status.degradedReason.takeIf { status.state == WebRtcMediaState.FAILED },
            )
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
                when (status.state) {
                    WebRtcMediaState.CONNECTED -> {
                        iceRecoveryJob?.cancel()
                        iceRecoveryJob = null
                        val current = calls.find(call.callId)
                        if (current?.state == CallState.CONNECTING) {
                            val connected = calls.transition(call.callId, CallState.CONNECTED)
                            syncCallWithoutFailingMedia(client, connected)
                        }
                    }
                    WebRtcMediaState.DISCONNECTED -> scheduleIceRecovery(
                        call,
                        client,
                        recoveryGeneration,
                        "PEER_DISCONNECTED",
                        DISCONNECTED_GRACE_MILLIS,
                    )
                    WebRtcMediaState.FAILED -> if (isNonRecoverableMediaFailure(status)) {
                        val reason = status.degradedReason ?: "MEDIA_CAPTURE_FAILED"
                        runCatching { failCall(call.callId, client, reason.take(MAX_REASON_LENGTH)) }
                        stop(
                            callId = call.callId,
                            reason = "NON_RECOVERABLE_MEDIA_FAILURE",
                            terminalFailure = true,
                            terminalError = reason,
                        )
                    } else {
                        scheduleIceRecovery(
                            call,
                            client,
                            recoveryGeneration,
                            "PEER_FAILED",
                            0,
                        )
                    }
                    else -> Unit
                }
            }
        }

        override fun onLocalIceCandidate(localIceGeneration: Long, candidate: LocalIceCandidate) {
            scope.launch {
                try {
                    val offerSequence = negotiationMutex.withLock {
                        activeNegotiation
                            ?.takeIf {
                                it.call.callId == call.callId &&
                                    it.recoveryGeneration == recoveryGeneration
                            }
                            ?.tracker
                            ?.offerSequenceForLocalIceGeneration(localIceGeneration)
                    } ?: return@launch
                    val candidateSignal = signal(
                        call,
                        CallSignalType.ICE_CANDIDATE,
                        JSONObject(WebRtcCallEngine.iceCandidatePayload(candidate))
                            .put("offerSequence", offerSequence),
                    )
                    signalingMutex.withLock {
                        if (activeCallId == call.callId) sendSignalWithRetry(client, candidateSignal)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    record(
                        "WEBRTC_ICE_SIGNAL_FAILED",
                        EventSeverity.MEDIUM,
                        mapOf(
                            "callId" to call.callId,
                            "errorType" to error.javaClass.name,
                        ),
                    )
                }
            }
        }

        override fun onIceGatheringComplete(localIceGeneration: Long) {
            scope.launch {
                try {
                    val offerSequence = negotiationMutex.withLock {
                        activeNegotiation
                            ?.takeIf {
                                it.call.callId == call.callId &&
                                    it.recoveryGeneration == recoveryGeneration
                            }
                            ?.tracker
                            ?.offerSequenceForLocalIceGeneration(localIceGeneration)
                    } ?: return@launch
                    val complete = signal(
                        call,
                        CallSignalType.ICE_COMPLETE,
                        JSONObject().put("offerSequence", offerSequence),
                    )
                    signalingMutex.withLock {
                        if (activeCallId == call.callId) sendSignalWithRetry(client, complete)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    record(
                        "WEBRTC_ICE_COMPLETE_SIGNAL_FAILED",
                        EventSeverity.MEDIUM,
                        mapOf("callId" to call.callId, "errorType" to error.javaClass.name),
                    )
                }
            }
        }
    }

    companion object {
        private const val ANSWER_TIMEOUT_MILLIS = 60_000L
        private const val SIGNAL_POLL_INTERVAL_MILLIS = 500L
        private const val DISCONNECTED_GRACE_MILLIS = 5_000L
        private const val ICE_RESTART_RETRY_MILLIS = 2_000L
        private const val MAX_ICE_RESTART_ATTEMPTS = 2
        private const val SIGNALING_MAX_ATTEMPTS = 5
        private const val MAX_REASON_LENGTH = 1_024
    }
}

private fun safeDurationSum(first: Long, second: Long): Long =
    if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second
