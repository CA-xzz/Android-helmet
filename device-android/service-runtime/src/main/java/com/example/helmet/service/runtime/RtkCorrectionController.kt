package com.example.helmet.service.runtime

import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.RtkRuntimeConfig
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.Rtcm3FrameCodec
import com.example.helmet.feature.location.ExternalRtkFixAssembler
import com.example.helmet.hardware.api.HardwareCommand
import com.example.helmet.hardware.api.HardwareStatus
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

enum class RtkCorrectionState {
    DISABLED,
    WAITING_HARDWARE,
    WAITING_GGA,
    CONNECTING,
    STREAMING,
    RETRY_WAIT,
    CONFIG_ERROR,
    STOPPED,
}

data class RtkCorrectionStatus(
    val state: RtkCorrectionState = RtkCorrectionState.DISABLED,
    val endpoint: String? = null,
    val receiverSentences: Long = 0,
    val correctionFrames: Long = 0,
    val correctionBytes: Long = 0,
    val correctionChunks: Long = 0,
    val lastFixQuality: FixQuality = FixQuality.NO_FIX,
    val lastFixAtEpochMillis: Long? = null,
    val lastError: String? = null,
)

internal object RtkCorrectionPacketizer {
    const val MAX_HSL_CHUNK_BYTES = 512

    fun chunks(frame: ByteArray): List<ByteArray> {
        require(Rtcm3FrameCodec.validate(frame)) { "RTCM frame failed CRC validation" }
        return frame.asList().chunked(MAX_HSL_CHUNK_BYTES).map { chunk -> chunk.toByteArray() }
    }
}

class RtkCorrectionController(
    private val scope: CoroutineScope,
    deviceId: String,
    private val config: RtkRuntimeConfig,
    private val hardwareStatus: () -> HardwareStatus,
    private val correctionSink: suspend (HardwareCommand) -> Unit,
    epochClock: () -> Long = System::currentTimeMillis,
) : Closeable {
    private val assembler = ExternalRtkFixAssembler(deviceId, epochClock)
    private val sequence = AtomicInteger()
    private val mutableFixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = 64)
    private val mutableStatus = MutableStateFlow(
        RtkCorrectionStatus(state = if (config.enabled) RtkCorrectionState.STOPPED else RtkCorrectionState.DISABLED),
    )
    private var runnerJob: Job? = null

    @Volatile
    private var activeClient: NtripCorrectionClient? = null

    val fixes: SharedFlow<LocationFix> = mutableFixes
    val status: StateFlow<RtkCorrectionStatus> = mutableStatus

    fun acceptReceiverBytes(bytes: ByteArray) {
        val hardware = hardwareStatus()
        if (!hardware.connected || hardware.simulated) {
            if (config.enabled) {
                updateState(
                    RtkCorrectionState.WAITING_HARDWARE,
                    error = if (hardware.simulated) {
                        "RTK receiver input is rejected in simulated hardware mode"
                    } else {
                        "hardware link is unavailable"
                    },
                )
            }
            return
        }
        val updates = assembler.feed(bytes)
        if (updates.isEmpty()) return
        val fixes = updates.mapNotNull { it.fix }
        val latestFix = fixes.lastOrNull()
        mutableStatus.value = mutableStatus.value.copy(
            receiverSentences = mutableStatus.value.receiverSentences + updates.size,
            lastFixQuality = latestFix?.quality ?: mutableStatus.value.lastFixQuality,
            lastFixAtEpochMillis = latestFix?.occurredAtEpochMillis ?: mutableStatus.value.lastFixAtEpochMillis,
        )
        fixes.forEach(mutableFixes::tryEmit)
    }

    fun start() {
        if (runnerJob != null || !config.enabled) return
        val endpoint = runCatching {
            require(config.username.isNotBlank()) { "NTRIP username is missing" }
            require(config.password.isNotBlank()) { "NTRIP password is missing" }
            NtripEndpoint.parse(config.ntripUrl)
        }.getOrElse { error ->
            updateState(RtkCorrectionState.CONFIG_ERROR, error = safeError(error))
            return
        }
        mutableStatus.value = mutableStatus.value.copy(endpoint = endpoint.redactedDescription, lastError = null)
        runnerJob = scope.launch(Dispatchers.IO) { runLoop(endpoint) }
    }

    fun stop() {
        activeClient?.close()
        activeClient = null
        runnerJob?.cancel()
        runnerJob = null
        updateState(if (config.enabled) RtkCorrectionState.STOPPED else RtkCorrectionState.DISABLED)
    }

    override fun close() = stop()

    private suspend fun runLoop(endpoint: NtripEndpoint) {
        var retryDelayMillis = INITIAL_RETRY_MILLIS
        while (true) {
            val hardware = hardwareStatus()
            if (!hardware.connected || hardware.simulated) {
                updateState(
                    RtkCorrectionState.WAITING_HARDWARE,
                    error = if (hardware.simulated) "RTK correction is disabled in simulated hardware mode" else null,
                )
                delay(HARDWARE_POLL_MILLIS)
                continue
            }
            if (assembler.latestValidGga == null) {
                updateState(RtkCorrectionState.WAITING_GGA)
                delay(HARDWARE_POLL_MILLIS)
                continue
            }
            val client = NtripCorrectionClient(endpoint, config.username, config.password)
            activeClient = client
            try {
                updateState(RtkCorrectionState.CONNECTING)
                val result = client.stream(
                    ggaProvider = { assembler.latestValidGga },
                    onConnected = { updateState(RtkCorrectionState.STREAMING) },
                    onCorrectionFrame = ::forwardCorrectionFrame,
                )
                retryDelayMillis = if (result.correctionFrames > 0) INITIAL_RETRY_MILLIS else {
                    (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
                }
                updateState(RtkCorrectionState.RETRY_WAIT, error = "NTRIP stream ended")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: NtripProtocolException) {
                if (!error.retryable) {
                    updateState(RtkCorrectionState.CONFIG_ERROR, error = safeError(error))
                    return
                }
                updateState(RtkCorrectionState.RETRY_WAIT, error = safeError(error))
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
            } catch (error: Throwable) {
                updateState(RtkCorrectionState.RETRY_WAIT, error = safeError(error))
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
            } finally {
                client.close()
                if (activeClient === client) activeClient = null
            }
            delay(retryDelayMillis)
        }
    }

    private fun forwardCorrectionFrame(frame: ByteArray) {
        val currentHardware = hardwareStatus()
        if (!currentHardware.connected || currentHardware.simulated) {
            throw IOException("hardware link is unavailable during RTK correction forwarding")
        }
        val chunks = RtkCorrectionPacketizer.chunks(frame)
        runBlocking {
            chunks.forEach { chunk ->
                val nextSequence = sequence.getAndUpdate { current -> (current + 1) and 0xFFFF }
                correctionSink(
                    HardwareCommand(
                        type = HslMessageType.RTK_CORRECTION,
                        flags = 0,
                        sequence = nextSequence,
                        payload = chunk,
                    ),
                )
            }
        }
        mutableStatus.value = mutableStatus.value.copy(
            correctionFrames = mutableStatus.value.correctionFrames + 1,
            correctionBytes = mutableStatus.value.correctionBytes + frame.size,
            correctionChunks = mutableStatus.value.correctionChunks + chunks.size,
            lastError = null,
        )
    }

    private fun updateState(state: RtkCorrectionState, error: String? = null) {
        mutableStatus.value = mutableStatus.value.copy(state = state, lastError = error)
    }

    private fun safeError(error: Throwable): String = buildString {
        append(error.javaClass.simpleName)
        error.message?.takeIf(String::isNotBlank)?.let { message -> append(": ").append(message.take(240)) }
    }

    companion object {
        private const val HARDWARE_POLL_MILLIS = 1_000L
        private const val INITIAL_RETRY_MILLIS = 1_000L
        private const val MAX_RETRY_MILLIS = 30_000L
    }
}
