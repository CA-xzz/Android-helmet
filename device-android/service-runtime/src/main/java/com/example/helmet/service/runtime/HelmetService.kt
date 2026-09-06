package com.example.helmet.service.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.helmet.core.model.CallSession
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.HelmetOperationalState
import com.example.helmet.core.model.HelmetStateMachine
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.DurableIdentityConflictException
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.HardwareKeyAction
import com.example.helmet.data.local.HardwareKeyActionState
import com.example.helmet.data.local.HardwareKeyActionStore
import com.example.helmet.data.local.MediaStore
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.TrackStore
import com.example.helmet.data.local.CallStore
import com.example.helmet.data.local.CallMediaRecoveryStore
import com.example.helmet.data.local.BroadcastStore
import com.example.helmet.data.local.SafetyStore
import com.example.helmet.data.local.SafetyDetectionCheckpointLoadResult
import com.example.helmet.feature.camera.Camera2MediaController
import com.example.helmet.feature.camera.CameraCapabilities
import com.example.helmet.feature.camera.MediaCaptureController
import com.example.helmet.feature.camera.MediaCaptureEvent
import com.example.helmet.feature.camera.MediaCaptureTerminationReason
import com.example.helmet.feature.camera.AndroidVoiceMessageRecorder
import com.example.helmet.feature.camera.VoiceMessageCaptureController
import com.example.helmet.feature.camera.VideoStoragePolicy
import com.example.helmet.feature.connectivity.AndroidNetworkMonitor
import com.example.helmet.feature.connectivity.ConnectivitySnapshot
import com.example.helmet.feature.location.AndroidLocationController
import com.example.helmet.feature.location.LocationCapabilityInspector
import com.example.helmet.feature.location.GeofenceEngine
import com.example.helmet.core.model.GeofenceTransition
import com.example.helmet.core.model.GeofenceTransitionType
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.core.model.SafetySensorTelemetry
import com.example.helmet.core.model.canonicalFingerprint
import com.example.helmet.hardware.api.BoundHardwareGateway
import com.example.helmet.hardware.api.HardwareGateway
import com.example.helmet.hardware.api.HardwareEvent
import com.example.helmet.hardware.api.HardwareCommand
import com.example.helmet.hardware.api.HardwareCommandException
import com.example.helmet.hardware.api.HardwareCommandOutcome
import com.example.helmet.hardware.api.HardwareCommandResult
import com.example.helmet.hardware.api.HardwareAlarmOrigin
import com.example.helmet.hardware.api.HardwareAcknowledgementResult
import com.example.helmet.hardware.api.HardwareOperationalState
import com.example.helmet.hardware.api.HardwareStatus
import com.example.helmet.hardware.api.SimulatedHardwareGateway
import com.example.helmet.hardware.api.SimulatedInput
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslSafetyConfigCodec
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.core.model.RtkTransportMode
import com.example.helmet.core.protocol.HslCapability
import com.example.helmet.hardware.api.DirectRtkHardwareGateway
import com.example.helmet.communication.sync.MqttConnectionState
import com.example.helmet.communication.sync.MqttConnectionStatus
import com.example.helmet.communication.sync.MqttDeviceSession
import com.example.helmet.communication.sync.MqttDeviceSessionRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

internal fun statusHeartbeatFlow(intervalMillis: Long): Flow<Unit> = flow {
    require(intervalMillis > 0)
    while (currentCoroutineContext().isActive) {
        delay(intervalMillis)
        emit(Unit)
    }
}

private const val HTTP_COMMAND_POLL_INTERVAL_MILLIS = 2_000L
private const val MQTT_HTTP_FALLBACK_POLL_INTERVAL_MILLIS = 30_000L

internal fun httpCommandPollIntervalMillis(config: RuntimeConfig): Long? {
    if (config.backendBaseUrl.isBlank() || config.backendBearerToken.isBlank()) return null
    val brokerConfigured = config.mqttBrokerUri.isNotBlank()
    val certificateConfigured = config.mqttClientCertificateAlias.isNotBlank()
    if (brokerConfigured != certificateConfigured) return null
    return if (brokerConfigured) MQTT_HTTP_FALLBACK_POLL_INTERVAL_MILLIS else HTTP_COMMAND_POLL_INTERVAL_MILLIS
}

internal fun shouldPollCommandsOverHttp(config: RuntimeConfig): Boolean =
    httpCommandPollIntervalMillis(config) != null

internal fun mqttCommandWakeConfigurationIsComplete(config: RuntimeConfig): Boolean =
    config.mqttBrokerUri.isNotBlank() &&
        config.mqttClientCertificateAlias.isNotBlank() &&
        config.backendBaseUrl.isNotBlank() &&
        config.backendBearerToken.isNotBlank()

internal fun requiredHslCapabilityMask(config: RuntimeConfig): Long {
    var mask = HslCapability.PHYSICAL_KEYS or HslCapability.LED_OUTPUT or
        HslCapability.VIBRATION_OUTPUT or HslCapability.BUZZER_OUTPUT or
        HslCapability.MMA8452_ACCELEROMETER
    if (config.rtk.transportMode == RtkTransportMode.HSL) {
        mask = mask or HslCapability.RTK_NMEA
        if (config.rtk.enabled) mask = mask or HslCapability.RTK_CORRECTION
    }
    if (config.localIntercom.enabled) mask = mask or HslCapability.LOCAL_INTERCOM
    return mask
}

internal fun deviceCommandTransportName(config: RuntimeConfig): String = when {
    config.backendBaseUrl.isBlank() || config.backendBearerToken.isBlank() -> "UNCONFIGURED"
    config.mqttBrokerUri.isBlank() && config.mqttClientCertificateAlias.isBlank() -> "HTTP_POLL"
    config.mqttBrokerUri.isNotBlank() && config.mqttClientCertificateAlias.isNotBlank() -> "MQTT"
    else -> "INVALID_MQTT_CONFIGURATION"
}

internal fun enforceHardwareModePolicy(
    stored: RuntimeConfig,
    simulatedHardwareAllowed: Boolean,
): RuntimeConfig {
    if (simulatedHardwareAllowed || !stored.simulatorEnabled) return stored
    val correctedRevision = if (stored.revision == Long.MAX_VALUE) Long.MAX_VALUE else stored.revision + 1
    return stored.copy(revision = correctedRevision, simulatorEnabled = false)
}

internal data class HardwareDegradedStartupResult(
    val hardwareFailure: Throwable?,
    val failureReportingFailure: Throwable?,
)

internal suspend fun startRuntimeWithHardwareDegradation(
    startHardware: suspend () -> Unit,
    onHardwareUnavailable: suspend (Throwable) -> Unit,
    startIndependentRuntime: suspend (hardwareStartAccepted: Boolean) -> Unit,
): HardwareDegradedStartupResult {
    val hardwareFailure = try {
        startHardware()
        null
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        error
    }
    val failureReportingFailure = if (hardwareFailure == null) {
        null
    } else {
        try {
            onHardwareUnavailable(hardwareFailure)
            null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            error
        }
    }
    startIndependentRuntime(hardwareFailure == null)
    return HardwareDegradedStartupResult(hardwareFailure, failureReportingFailure)
}

internal fun httpCommandPollFlow(intervalMillis: Long): Flow<Unit> = flow {
    require(intervalMillis > 0)
    while (currentCoroutineContext().isActive) {
        emit(Unit)
        delay(intervalMillis)
    }
}

internal enum class VideoRecordingStopCause(val eventType: String) {
    USER("VIDEO_RECORDED"),
    DURATION_LIMIT("VIDEO_RECORDING_LIMIT_REACHED"),
    STORAGE_RESERVE("VIDEO_RECORDING_STORAGE_RESERVE_REACHED"),
    SHUTDOWN("VIDEO_RECORDING_SHUTDOWN_FINALIZED"),
}

internal data class AsyncMediaEventPolicy(
    val eventType: String,
    val feedbackKind: String,
    val severity: EventSeverity,
)

internal fun asyncMediaEventPolicy(
    kind: MediaKind,
    reason: MediaCaptureTerminationReason,
    success: Boolean,
): AsyncMediaEventPolicy? {
    if (reason in setOf(MediaCaptureTerminationReason.USER, MediaCaptureTerminationReason.CLOSED)) return null
    if (!success) {
        return AsyncMediaEventPolicy(
            eventType = "${kind.name}_RECORDING_ASYNC_FAILED",
            feedbackKind = if (kind == MediaKind.VOICE) "VOICE_STOP" else "VIDEO_STOP",
            severity = EventSeverity.MEDIUM,
        )
    }
    val eventType = when (kind) {
        MediaKind.VIDEO -> when (reason) {
            MediaCaptureTerminationReason.DURATION_LIMIT -> "VIDEO_RECORDING_LIMIT_REACHED"
            MediaCaptureTerminationReason.FILE_SIZE_LIMIT -> "VIDEO_RECORDING_FILE_SIZE_LIMIT_REACHED"
            MediaCaptureTerminationReason.STORAGE_RESERVE -> "VIDEO_RECORDING_STORAGE_RESERVE_REACHED"
            MediaCaptureTerminationReason.CAMERA_DISCONNECTED -> "VIDEO_RECORDING_CAMERA_DISCONNECTED_FINALIZED"
            MediaCaptureTerminationReason.CAMERA_ERROR -> "VIDEO_RECORDING_CAMERA_ERROR_FINALIZED"
            MediaCaptureTerminationReason.RECORDER_ERROR -> "VIDEO_RECORDING_RECORDER_ERROR_FINALIZED"
            MediaCaptureTerminationReason.USER,
            MediaCaptureTerminationReason.CLOSED,
            -> return null
        }
        MediaKind.VOICE -> when (reason) {
            MediaCaptureTerminationReason.DURATION_LIMIT -> "VOICE_MESSAGE_RECORDING_LIMIT_REACHED"
            MediaCaptureTerminationReason.FILE_SIZE_LIMIT -> "VOICE_MESSAGE_FILE_SIZE_LIMIT_REACHED"
            MediaCaptureTerminationReason.STORAGE_RESERVE -> "VOICE_MESSAGE_STORAGE_RESERVE_REACHED"
            MediaCaptureTerminationReason.RECORDER_ERROR -> "VOICE_MESSAGE_RECORDER_ERROR_FINALIZED"
            MediaCaptureTerminationReason.CAMERA_DISCONNECTED,
            MediaCaptureTerminationReason.CAMERA_ERROR,
            MediaCaptureTerminationReason.USER,
            MediaCaptureTerminationReason.CLOSED,
            -> return null
        }
        MediaKind.PHOTO -> return null
    }
    val severity = if (
        reason in setOf(
            MediaCaptureTerminationReason.CAMERA_DISCONNECTED,
            MediaCaptureTerminationReason.CAMERA_ERROR,
            MediaCaptureTerminationReason.RECORDER_ERROR,
            MediaCaptureTerminationReason.STORAGE_RESERVE,
        )
    ) EventSeverity.MEDIUM else EventSeverity.INFO
    return AsyncMediaEventPolicy(
        eventType = eventType,
        feedbackKind = if (kind == MediaKind.VOICE) "VOICE_STOP" else "VIDEO_STOP",
        severity = severity,
    )
}

internal fun mediaEventPayloadFields(asset: MediaAsset): Map<String, Any?> = mapOf(
    "assetId" to asset.assetId,
    "kind" to asset.kind.name,
    "byteSize" to asset.byteSize,
    "sha256" to asset.sha256,
    "relatedEventId" to asset.relatedEventId,
    "locationFixType" to asset.locationFixType,
    "transferState" to asset.transferState.name,
)

internal fun mediaFailurePayloadFields(error: Throwable): Map<String, Any?> {
    val rootCause = generateSequence(error) { current -> current.cause }.last()
    return mapOf(
        "errorType" to error.javaClass.name,
        "causeType" to rootCause.javaClass.name,
    )
}

internal fun safetyAlarmEventPayloadFields(
    event: HardwareEvent.Alarm,
    alertId: String,
    messageId: String,
): Map<String, Any?> = mapOf(
    "alertId" to alertId,
    "messageId" to messageId,
    "active" to event.active,
    "simulated" to event.simulated,
    "detectionOrigin" to event.origin.name,
    "monotonicMillis" to event.monotonicMillis,
    "configVersion" to event.configVersion,
    "sampleReference" to event.sampleReference,
    "localActions" to event.localActions,
    "sensorFaults" to event.sensorFaults,
    "evidenceRelatedEventId" to messageId,
    "finalHardwareEvidence" to false,
)

internal enum class InteractiveKeyAction {
    PHOTO,
    VIDEO_START,
    VIDEO_STOP,
    CALL_START,
    CALL_END,
    LOCAL_INTERCOM_COMMAND,
    VOLUME_SET,
    SOS,
    NO_OP,
    REJECT,
}

internal data class InteractiveKeyDecision(
    val action: InteractiveKeyAction,
    val reason: String? = null,
)

internal data class DurableInteractiveKeyPlan(
    val decision: InteractiveKeyDecision,
    val targetCallId: String? = null,
    val plannedArgument: String? = null,
    val businessRequestId: Long? = null,
)

internal class RetryableHardwareKeyActionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal suspend fun runDurableActionAttempt(
    performSideEffect: suspend () -> Unit,
    markApplied: suspend () -> Boolean,
    readState: suspend () -> HardwareKeyActionState?,
    afterApplied: () -> Unit,
): Throwable? {
    performSideEffect()
    val changed = try {
        markApplied()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw RetryableHardwareKeyActionException("hardware key action commit failed", error)
    }
    if (!changed) {
        val state = try {
            readState()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw RetryableHardwareKeyActionException("hardware key action state reload failed", error)
        }
        when (state) {
            HardwareKeyActionState.APPLIED -> Unit
            HardwareKeyActionState.RECEIVED -> throw RetryableHardwareKeyActionException(
                "hardware key action commit was not persisted",
            )
            HardwareKeyActionState.FAILED -> error("hardware key action is already terminally failed")
            null -> error("hardware key action disappeared before commit")
        }
    }
    return runCatching(afterApplied).exceptionOrNull()
}

internal fun hardwareKeyActionId(
    deviceId: String,
    input: SimulatedInput,
    monotonicMillis: Long,
    hardwareEventId: Long?,
): String = hardwareEventId?.let { "$deviceId:key:$it" }
    ?: "$deviceId:key:sim:${input.name}:$monotonicMillis"

internal fun requireMatchingHardwareKeyEvent(
    existing: HardwareKeyAction,
    input: SimulatedInput,
    monotonicMillis: Long,
    hardwareEventId: Long?,
    simulated: Boolean,
) {
    if (
        existing.input != input.name ||
        existing.hardwareEventId != hardwareEventId ||
        existing.simulated != simulated ||
        existing.monotonicMillis != monotonicMillis
    ) {
        throw DurableIdentityConflictException("hardware key event ID conflicts with stored content")
    }
}

internal fun sosAlarmBusinessId(hardwareEventId: Long?, stableFallback: String): Long {
    hardwareEventId?.let {
        require(it in 1..0xFFFF_FFFFL) { "SOS hardware event ID is outside u32 range" }
        return it
    }
    val checksum = CRC32().apply { update(stableFallback.toByteArray(Charsets.UTF_8)) }.value
    return checksum.coerceAtLeast(1L)
}

internal fun shouldCreateSosEmergencyCall(activeCallId: String?): Boolean = activeCallId == null

internal fun planSosFallbackCallId(idFactory: () -> String): String = idFactory().also {
    require(it.isNotBlank()) { "SOS fallback call ID must not be blank" }
}

internal fun requirePersistedSosAlarm(persisted: Boolean) {
    if (!persisted) throw RetryableHardwareKeyActionException("SOS alarm was not durably persisted")
}

internal suspend fun <T> runRetryableCallPersistence(operation: suspend () -> T): T = try {
    operation()
} catch (error: CancellationException) {
    throw error
} catch (error: RetryableHardwareKeyActionException) {
    throw error
} catch (error: IllegalArgumentException) {
    throw error
} catch (error: Throwable) {
    throw RetryableHardwareKeyActionException("call persistence is temporarily unavailable", error)
}

internal suspend fun <T> commitCallOutboxAndSchedule(
    commit: suspend () -> T,
    schedule: () -> Unit,
): T = commit().also { schedule() }

internal fun hardwareKeyActionRetryDelayMillis(failedRound: Int): Long =
    safetyOutputReplayRetryDelayMillis(failedRound)

internal fun isRetryableHardwareCommandFailure(error: HardwareCommandException): Boolean =
    error.result.outcome != HardwareCommandOutcome.REJECTED

internal fun decideInteractiveKeyAction(
    input: SimulatedInput,
    state: HelmetOperationalState,
    videoRecording: Boolean,
    voiceRecording: Boolean,
    hasActiveInternetCall: Boolean,
    useLocalIntercom: Boolean,
): InteractiveKeyDecision {
    val idle = state == HelmetOperationalState.IDLE || state == HelmetOperationalState.OFFLINE_READY
    return when (input) {
        SimulatedInput.SOS -> InteractiveKeyDecision(InteractiveKeyAction.SOS)
        SimulatedInput.CALL -> when {
            hasActiveInternetCall -> InteractiveKeyDecision(InteractiveKeyAction.CALL_END)
            videoRecording || voiceRecording -> InteractiveKeyDecision(
                InteractiveKeyAction.REJECT,
                "media capture is active",
            )
            !idle -> InteractiveKeyDecision(InteractiveKeyAction.REJECT, "runtime is not idle")
            useLocalIntercom -> InteractiveKeyDecision(InteractiveKeyAction.LOCAL_INTERCOM_COMMAND)
            else -> InteractiveKeyDecision(InteractiveKeyAction.CALL_START)
        }
        SimulatedInput.PHOTO_SHORT -> when {
            hasActiveInternetCall -> InteractiveKeyDecision(InteractiveKeyAction.CALL_END)
            videoRecording || voiceRecording -> InteractiveKeyDecision(
                InteractiveKeyAction.REJECT,
                "media capture is active",
            )
            !idle -> InteractiveKeyDecision(InteractiveKeyAction.REJECT, "runtime is not idle")
            else -> InteractiveKeyDecision(InteractiveKeyAction.PHOTO)
        }
        SimulatedInput.RECORD_LONG -> when {
            videoRecording -> InteractiveKeyDecision(InteractiveKeyAction.VIDEO_STOP)
            voiceRecording || hasActiveInternetCall -> InteractiveKeyDecision(
                InteractiveKeyAction.REJECT,
                "call or media capture is active",
            )
            !idle -> InteractiveKeyDecision(InteractiveKeyAction.REJECT, "runtime is not idle")
            else -> InteractiveKeyDecision(InteractiveKeyAction.VIDEO_START)
        }
        SimulatedInput.VOLUME_UP,
        SimulatedInput.VOLUME_DOWN,
        -> InteractiveKeyDecision(InteractiveKeyAction.VOLUME_SET)
        SimulatedInput.FALL,
        SimulatedInput.NEAR_ELECTRIC,
        SimulatedInput.HEIGHT_LIMIT,
        -> InteractiveKeyDecision(InteractiveKeyAction.NO_OP)
    }
}

internal fun HelmetOperationalState.toHardwareOperationalState(): HardwareOperationalState = when (this) {
    HelmetOperationalState.BOOTING,
    HelmetOperationalState.SELF_TEST,
    -> HardwareOperationalState.INITIALIZING
    HelmetOperationalState.OFFLINE_READY -> HardwareOperationalState.OFFLINE_READY
    HelmetOperationalState.IDLE -> HardwareOperationalState.IDLE
    HelmetOperationalState.CALLING -> HardwareOperationalState.CALLING
    HelmetOperationalState.IN_CALL -> HardwareOperationalState.IN_CALL
    HelmetOperationalState.RECORDING -> HardwareOperationalState.RECORDING
    HelmetOperationalState.SOS -> HardwareOperationalState.SOS
    HelmetOperationalState.FAULT -> HardwareOperationalState.FAULT
    HelmetOperationalState.SHUTTING_DOWN -> HardwareOperationalState.SHUTTING_DOWN
}

internal class CallStateObservationGate {
    private var initialized = false
    private var lastKey: Triple<String, CallState, Long>? = null

    fun shouldHandle(call: CallSession?): Boolean {
        val initial = !initialized
        initialized = true
        if (call == null) return false
        val key = Triple(call.callId, call.state, call.stateSequence)
        if (key == lastKey) return false
        lastKey = key
        return if (initial) {
            call.state !in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED)
        } else {
            true
        }
    }
}

internal class ActiveCallObservationTracker {
    private var previousActiveCallId: String? = null

    fun activeChanged(current: CallSession?): String? {
        val previous = previousActiveCallId
        previousActiveCallId = current?.callId
        return previous?.takeIf { it != current?.callId }
    }
}

class HelmetService : LifecycleService() {
    private lateinit var eventStore: EventStore
    private lateinit var mediaStore: MediaStore
    private lateinit var trackStore: TrackStore
    private lateinit var callStore: CallStore
    private lateinit var broadcastStore: BroadcastStore
    private lateinit var safetyStore: SafetyStore
    private lateinit var hardwareKeyActionStore: HardwareKeyActionStore
    private lateinit var safetySampleProcessor: SafetySampleProcessor
    private lateinit var safetyThresholdFingerprint: String
    private lateinit var callMediaCoordinator: CallMediaCoordinator
    private lateinit var deviceStatusOutbox: DeviceStatusOutbox
    private lateinit var timeAuthority: DeviceTimeAuthority
    private lateinit var deviceId: String
    private lateinit var hardware: HardwareGateway
    private lateinit var rtkHardware: HardwareGateway
    private var ownsRtkHardware = false
    private lateinit var hardwareMaintenanceRegistration: HardwareRuntimeMaintenance.Registration
    private var simulatedHardware: SimulatedHardwareGateway? = null
    private lateinit var feedback: LocalFeedbackController
    private lateinit var mediaController: MediaCaptureController
    private lateinit var voiceMessageRecorder: VoiceMessageCaptureController
    private lateinit var cameraCapabilities: CameraCapabilities
    private lateinit var locationController: AndroidLocationController
    private lateinit var rtkController: RtkCorrectionController
    private lateinit var localIntercomController: LocalIntercomController
    private lateinit var runtimeConfig: RuntimeConfig
    private lateinit var networkMonitor: AndroidNetworkMonitor
    private lateinit var geofenceEngine: GeofenceEngine
    private var activeGeofenceCount = 0
    @Volatile
    private var lastLocationFix: LocationFix? = null
    private val stateMachine = HelmetStateMachine()
    private val mediaActionMutex = Mutex()
    private val statusPublishMutex = Mutex()
    private val callStateHandlingMutex = Mutex()
    private val safetyConfigMutex = Mutex()
    private val safetyProcessingMutex = Mutex()
    private val locationProcessingMutex = Mutex()
    private var lastHandledCallStateKey: Triple<String, CallState, Long>? = null
    private var eventCollector: Job? = null
    private var statusCollector: Job? = null
    private var statusHeartbeatJob: Job? = null
    private var timeAdjustmentCollector: Job? = null
    private var locationFixCollector: Job? = null
    private var locationStatusCollector: Job? = null
    private var rtkFixCollector: Job? = null
    private var rtkStatusCollector: Job? = null
    private var rtkTransportEventCollector: Job? = null
    private var rtkTransportStatusCollector: Job? = null
    private var localIntercomStatusCollector: Job? = null
    private var networkStatusCollector: Job? = null
    private var callStateCollector: Job? = null
    private var mediaCaptureCollector: Job? = null
    private var voiceCaptureCollector: Job? = null
    private var httpCommandPollJob: Job? = null
    private var mqttSessionJob: Job? = null
    private var videoRecordingLimitJob: Job? = null
    private var voiceRecordingLimitJob: Job? = null
    private var safetyOutputReplayJob: Job? = null
    private var safetyOutputReplaySessionGeneration: Long? = null
    private var safetyDerivationRetryJob: Job? = null
    private var hardwareKeyActionRetryJob: Job? = null
    private var mqttSession: MqttDeviceSession? = null
    private var appVersion = "unknown"
    private var configRevision = 0L
    private var safetyConfigSentVersion: Int? = null
    private var safetyConfigSessionGeneration: Long? = null
    private var safetyConfigFailedVersion: Int? = null
    private var pendingSafetyEpisodeTerminationReason: String? = null
    private var pendingSafetyEpisodeTerminationAlertIds: Set<String> = emptySet()
    @Volatile
    private var lastHardwareKeyEventEpochMillis: Long? = null
    @Volatile
    private var lastHardwareSensorSampleEpochMillis: Long? = null
    @Volatile
    private var lastHardwareOutputConfirmedEpochMillis: Long? = null
    private var evidenceHardwareSessionGeneration: Long? = null
    private val safetyOutputSequence = AtomicInteger(0x8000)
    private lateinit var safetyOutputRecoveryStore: SafetyOutputRecoveryStore
    private lateinit var safetyOutputRequestIdStore: SafetyOutputRequestIdStore
    private val safetyOutputReplayGate = SafetyOutputReplayGate()
    private val shutdownMutex = Mutex()
    private lateinit var batteryPromptTracker: BatteryPromptTracker
    private var lastPromptedNetworkValidated: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Starting"))

        val database = HelmetDatabase.get(this)
        val databaseAudit = database.storageAudit()
        StructuredLogger.info(
            event = "database_storage_ready",
            fields = mapOf(
                "quickCheck" to databaseAudit.quickCheck,
                "userVersion" to databaseAudit.userVersion,
                "tableCounts" to databaseAudit.tableCounts,
            ),
        )
        timeAuthority = DeviceTimeAuthorityProvider.get(this)
        StructuredLogger.installClock(timeAuthority::nowEpochMillis)
        eventStore = EventStore(database, timeAuthority::nowEpochMillis)
        mediaStore = MediaStore(database)
        trackStore = TrackStore(database, timeAuthority::nowEpochMillis)
        callStore = CallStore(database, timeAuthority::nowEpochMillis)
        broadcastStore = BroadcastStore(database)
        safetyStore = SafetyStore(database)
        safetyOutputRecoveryStore = SafetyOutputRecoveryStore(this)
        safetyOutputRequestIdStore = SafetyOutputRequestIdStore(this)
        hardwareKeyActionStore = HardwareKeyActionStore(database, timeAuthority::nowEpochMillis)
        callMediaCoordinator = CallMediaCoordinator(
            this,
            lifecycleScope,
            callStore,
            eventStore,
            CallMediaRecoveryStore(database, timeAuthority::nowEpochMillis),
            timeAuthority::nowEpochMillis,
            SystemClock::elapsedRealtime,
        )
        deviceStatusOutbox = DeviceStatusOutbox(this)
        deviceId = DeviceIdentityStore(this).getOrCreateDeviceId()
        val runtimeConfigStore = RuntimeConfigStore(this)
        val runtimeConfigLoad = runtimeConfigStore.loadWithDiagnostics()
        val storedRuntimeConfig = runtimeConfigLoad.config
        if (runtimeConfigLoad.issues.isNotEmpty()) {
            StructuredLogger.warn(
                event = "runtime_configuration_recovered",
                fields = mapOf(
                    "issueCount" to runtimeConfigLoad.issues.size,
                    "issues" to runtimeConfigLoad.issues.joinToString(",") { issue ->
                        "${issue.field}:${issue.kind.name}"
                    },
                ),
            )
        }
        runtimeConfig = enforceHardwareModePolicy(
            stored = storedRuntimeConfig,
            simulatedHardwareAllowed = BuildConfig.ALLOW_SIMULATED_HARDWARE,
        )
        if (runtimeConfig !== storedRuntimeConfig) {
            StructuredLogger.warn(
                event = "release_simulated_hardware_rejected",
                fields = mapOf("configRevision" to storedRuntimeConfig.revision),
            )
            runCatching {
                runtimeConfigStore.persistRealHardwareMode(
                    expectedHardwareDevicePath = storedRuntimeConfig.hardwareDevicePath,
                    expectedHardwareBaudRate = storedRuntimeConfig.hardwareBaudRate,
                ).also { persistence ->
                    check(persistence.changed && persistence.revision == runtimeConfig.revision) {
                        "release hardware mode correction revision mismatch"
                    }
                }
            }
                .onFailure { error ->
                    StructuredLogger.warn(
                        event = "release_hardware_mode_correction_not_persisted",
                        fields = mapOf("errorType" to error.javaClass.name),
                    )
                }
        }
        DeviceStatusWorker.reconfigure(this)
        mediaController = Camera2MediaController(
            this,
            mediaStore,
            deviceId,
            personId = runtimeConfig.personId,
            wallClock = timeAuthority::nowEpochMillis,
        )
        voiceMessageRecorder = AndroidVoiceMessageRecorder(
            context = this,
            mediaStore = mediaStore,
            deviceId = deviceId,
            personId = runtimeConfig.personId,
            wallClock = timeAuthority::nowEpochMillis,
            monotonicClock = SystemClock::elapsedRealtime,
        )
        cameraCapabilities = mediaController.inspect()
        locationController = AndroidLocationController(
            this,
            deviceId,
            epochClock = timeAuthority::nowEpochMillis,
        )
        networkMonitor = AndroidNetworkMonitor(this)
        val locationCapabilities = LocationCapabilityInspector(this).inspect()
        geofenceEngine = GeofenceEngine(runtimeConfig.geofences)
        safetySampleProcessor = SafetySampleProcessor(runtimeConfig.safetyThresholds)
        safetyThresholdFingerprint = runtimeConfig.safetyThresholds.canonicalFingerprint()
        activeGeofenceCount = runtimeConfig.geofences.size
        configRevision = runtimeConfig.revision
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        appVersion = "${packageInfo.versionName ?: "unknown"} (${packageInfo.longVersionCode})"
        CrashCapture.install(eventStore)
        startMqttSession()
        stateMachine.transitionTo(HelmetOperationalState.SELF_TEST)

        feedback = LocalFeedbackController(this)
        val feedbackPreferences = getSharedPreferences(LOCAL_FEEDBACK_PREFERENCES, MODE_PRIVATE)
        batteryPromptTracker = BatteryPromptTracker(
            feedbackPreferences.getInt(LAST_BATTERY_PROMPT_THRESHOLD, 0).takeIf { it in setOf(20, 10, 5) },
        )
        feedback.announceStartup(BatteryStatusReader.read(this))
        startMediaCaptureEventCollection()
        hardware = if (runtimeConfig.simulatorEnabled) {
            SimulatedHardwareGateway(
                scope = lifecycleScope,
                durableSampleReferenceHighWater = {
                    safetyStore.latestSample(deviceId)?.sampleReference
                },
            ).also { simulatedHardware = it }
        } else {
            BoundHardwareGateway(
                context = this,
                devicePath = runtimeConfig.hardwareDevicePath,
                baudRate = runtimeConfig.hardwareBaudRate,
                requiredCapabilityMask = requiredHslCapabilityMask(runtimeConfig),
            )
        }
        hardwareMaintenanceRegistration = HardwareRuntimeMaintenance.register(hardware)
        ownsRtkHardware = !runtimeConfig.simulatorEnabled &&
            runtimeConfig.rtk.transportMode == RtkTransportMode.DIRECT_UART4
        rtkHardware = if (ownsRtkHardware) {
            DirectRtkHardwareGateway(
                context = this,
                devicePath = runtimeConfig.rtk.directDevicePath,
                baudRate = runtimeConfig.rtk.directBaudRate,
            )
        } else {
            hardware
        }
        rtkController = RtkCorrectionController(
            scope = lifecycleScope,
            deviceId = deviceId,
            config = runtimeConfig.rtk,
            hardwareStatus = { rtkHardware.status.value },
            correctionSink = rtkHardware::send,
            epochClock = timeAuthority::nowEpochMillis,
        )
        val intercomRequestIds = PersistentLocalIntercomRequestIdAllocator(this)
        intercomRequestIds.recoveryIssue?.let { issue ->
            StructuredLogger.warn("local_intercom_request_id_recovered", mapOf("issue" to issue))
        }
        localIntercomController = LocalIntercomController(
            config = runtimeConfig.localIntercom,
            hardwareStatus = { hardware.status.value },
            commandSink = hardware::send,
            requestIdSource = intercomRequestIds::allocate,
        )
        startRtkCollection()
        if (ownsRtkHardware) startDirectRtkTransportCollection()
        startLocalIntercomCollection()
        startTimeAdjustmentCollection()
        startStatusHeartbeat()
        eventCollector = lifecycleScope.launch(Dispatchers.IO) {
            hardware.events.collect { event ->
                processHardwareEventSafely(
                    event = event,
                    process = ::handleHardwareEvent,
                    onFailure = { failedEvent, error ->
                        StructuredLogger.error(
                            event = "hardware_event_processing_failed",
                            error = error,
                            fields = mapOf(
                                "eventType" to failedEvent.javaClass.simpleName,
                                "monotonicMillis" to failedEvent.monotonicMillis,
                                "errorType" to error.javaClass.name,
                            ),
                        )
                        runCatching {
                            eventStore.record(
                                eventType = "HARDWARE_EVENT_PROCESSING_FAILED",
                                severity = EventSeverity.HIGH,
                                payloadJson = JSONObject(
                                    mapOf(
                                        "eventType" to failedEvent.javaClass.simpleName,
                                        "monotonicMillis" to failedEvent.monotonicMillis,
                                        "errorType" to error.javaClass.name,
                                    ),
                                ).toString(),
                            )
                        }
                    },
                )
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            runMediaRetention("startup")
            restoreSafetyDetectionState()
            val startupResult = startRuntimeWithHardwareDegradation(
                startHardware = {
                    HardwareRuntimeMaintenance.startRegistered(hardwareMaintenanceRegistration)
                },
                onHardwareUnavailable = { startFailure ->
                    eventStore.record(
                        eventType = "HARDWARE_START_FAILED",
                        severity = EventSeverity.HIGH,
                        payloadJson = JSONObject(
                            mapOf(
                                "simulated" to hardware.status.value.simulated,
                                "errorType" to startFailure.javaClass.name,
                                "hardwareLinkState" to hardware.status.value.linkState,
                            ),
                        ).toString(),
                    )
                    publishStatus("HARDWARE_START_FAILED")
                },
                startIndependentRuntime = { hardwareStartAccepted ->
                    if (ownsRtkHardware) {
                        runCatching { rtkHardware.start() }.onFailure { error ->
                            eventStore.record(
                                eventType = "RTK_DIRECT_TRANSPORT_START_FAILED",
                                severity = EventSeverity.MEDIUM,
                                payloadJson = JSONObject(
                                    mapOf(
                                        "devicePath" to runtimeConfig.rtk.directDevicePath,
                                        "baudRate" to runtimeConfig.rtk.directBaudRate,
                                        "errorType" to error.javaClass.name,
                                    ),
                                ).toString(),
                            )
                        }
                    }
                    statusCollector = lifecycleScope.launch(Dispatchers.IO) {
                        hardware.status
                            .drop(1)
                            .distinctUntilChangedBy { status ->
                                listOf(
                                    status.connected,
                                    status.linkState,
                                    status.lastError,
                                    status.eventQueueOverflowCount,
                                    status.moduleSessionGeneration,
                                    status.compatibility,
                                    status.moduleContractVersion,
                                    status.moduleFirmwareVersion,
                                    status.moduleCapabilityMask,
                                    status.missingCapabilityMask,
                                )
                            }
                            .collect { status ->
                                if (evidenceHardwareSessionGeneration != status.moduleSessionGeneration) {
                                    lastHardwareKeyEventEpochMillis = null
                                    lastHardwareSensorSampleEpochMillis = null
                                    lastHardwareOutputConfirmedEpochMillis = null
                                    evidenceHardwareSessionGeneration = status.moduleSessionGeneration
                                }
                                if (safetyConfigSessionGeneration != status.moduleSessionGeneration) {
                                    safetyConfigSentVersion = null
                                    safetyConfigFailedVersion = null
                                    safetyConfigSessionGeneration = status.moduleSessionGeneration
                                } else if (!status.connected) {
                                    safetyConfigSentVersion = null
                                }
                                sendSafetyConfigIfConnected(runtimeConfig)
                                scheduleSafetyOutputReplay(status)
                                if (hardware.status.value.connected) {
                                    if (
                                        stateMachine.current() !in setOf(
                                            HelmetOperationalState.BOOTING,
                                            HelmetOperationalState.SELF_TEST,
                                        )
                                    ) {
                                        val intercomCommandPending = recoverPendingHardwareKeyActions()
                                        if (!intercomCommandPending) localIntercomController.ensureJoined()
                                    }
                                } else {
                                    localIntercomController.onHardwareDisconnected()
                                }
                                publishStatus("HARDWARE_STATUS_CHANGED")
                            }
                    }
                    sendSafetyConfigIfConnected(runtimeConfig)
                    scheduleSafetyOutputReplay(hardware.status.value)
                    rtkController.start()
                    networkMonitor.start()
                    startNetworkMonitoring()
                    startHttpCommandPolling()
                    // A previous process can die after Room committed a call transition but before
                    // its in-process enqueue. Always rescan the durable outbox on service startup.
                    CommunicationWorker.enqueue(this@HelmetService)
                    val networkAvailable = isNetworkAvailable()
                    stateMachine.transitionTo(
                        if (networkAvailable) HelmetOperationalState.IDLE else HelmetOperationalState.OFFLINE_READY,
                    )
                    val intercomCommandPending = recoverPendingHardwareKeyActions()
                    if (!intercomCommandPending) localIntercomController.ensureJoined()
                    startCallStateMonitoring()
                    eventStore.record(
                        eventType = "RUNTIME_STARTED",
                        severity = EventSeverity.INFO,
                        payloadJson = JSONObject(
                            mapOf(
                                "deviceId" to deviceId,
                                "networkAvailable" to networkAvailable,
                                "hardwareStartAccepted" to hardwareStartAccepted,
                                "hardwareMode" to if (runtimeConfig.simulatorEnabled) "SIMULATED" else "UART",
                                "hardwareDevicePath" to runtimeConfig.hardwareDevicePath,
                                "hardwareBaudRate" to runtimeConfig.hardwareBaudRate,
                                "appVersion" to appVersion,
                                "androidVersion" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                                "configRevision" to configRevision,
                                "cameraCount" to cameraCapabilities.cameraCount,
                                "cameraId" to cameraCapabilities.selectedCameraId,
                                "maximumJpegSize" to cameraCapabilities.maximumJpegSize?.let {
                                    "${it.width}x${it.height}"
                                },
                                "selectedVideoSize" to cameraCapabilities.selectedVideoSize?.let {
                                    "${it.width}x${it.height}"
                                },
                                "supportsThirteenMegapixelPhoto" to
                                    cameraCapabilities.supportsThirteenMegapixelPhoto,
                                "supports1080pVideo" to cameraCapabilities.supports1080pVideo,
                                "locationProviders" to locationCapabilities.providers.joinToString(","),
                                "locationEnabledProviders" to
                                    locationCapabilities.enabledProviders.joinToString(","),
                                "hasGnssProvider" to locationCapabilities.hasGnssProvider,
                                "rtkEnabled" to runtimeConfig.rtk.enabled,
                                "ntripEndpoint" to rtkController.status.value.endpoint,
                                "localIntercomEnabled" to runtimeConfig.localIntercom.enabled,
                                "localIntercomGroupId" to runtimeConfig.localIntercom.groupId,
                                "localIntercomChannel" to runtimeConfig.localIntercom.channel,
                                "localIntercomKeySlot" to runtimeConfig.localIntercom.keySlot,
                                "geofenceCount" to activeGeofenceCount,
                                "deviceCommandTransport" to deviceCommandTransportName(runtimeConfig),
                                "httpCommandPollIntervalMillis" to
                                    httpCommandPollIntervalMillis(runtimeConfig),
                            ),
                        ).toString(),
                    )
                    publishStatus("RUNTIME_STARTED")
                    MediaUploadWorker.enqueue(this@HelmetService)
                    TrackUploadWorker.enqueue(this@HelmetService)
                    SafetyAlertWorker.enqueue(this@HelmetService)
                    startLocationCollection()
                },
            )
            startupResult.failureReportingFailure?.let { reportingFailure ->
                StructuredLogger.warn(
                    event = "hardware_start_failure_not_persisted",
                    fields = mapOf(
                        "hardwareErrorType" to startupResult.hardwareFailure?.javaClass?.name,
                        "reportingErrorType" to reportingFailure.javaClass.name,
                    ),
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent == null -> recordRecoveryRequest(
                source = "android_start_sticky",
                attempt = 0,
                flags = flags,
                startId = startId,
            )
            intent.action == ACTION_RECOVERY -> recordRecoveryRequest(
                source = sanitizeRecoverySource(intent.getStringExtra(EXTRA_RECOVERY_SOURCE)),
                attempt = intent.getIntExtra(EXTRA_RECOVERY_ATTEMPT, 0).coerceIn(0, MAX_RECOVERY_ATTEMPT),
                flags = flags,
                startId = startId,
            )
        }
        if (intent?.action == ACTION_CALL_STATE_UPDATED) {
            val callId = intent.getStringExtra(EXTRA_CALL_ID)
            val state = intent.getStringExtra(EXTRA_CALL_STATE)
                ?.let { raw -> runCatching { CallState.valueOf(raw) }.getOrNull() }
            if (!callId.isNullOrBlank() && state != null) {
                lifecycleScope.launch(Dispatchers.IO) { handleCallStateSignal(callId, state) }
            }
        }
        if (intent?.action == ACTION_SAFETY_ALERT_DELIVERED) {
            intent.getStringExtra(EXTRA_SAFETY_ALERT_MESSAGE_ID)
                ?.takeIf { it.isNotBlank() }
                ?.let { messageId ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        eventStore.record(
                            eventType = "SOS_ALERT_UPLOAD_PROMPT_REQUESTED",
                            severity = EventSeverity.INFO,
                            payloadJson = JSONObject(mapOf("messageId" to messageId)).toString(),
                        )
                        feedback.announceAlarmUploaded()
                        publishStatus("SOS_ALERT_UPLOAD_PROMPT_REQUESTED")
                    }
                }
        }
        if (intent?.action == ACTION_SIMULATE) {
            intent.getStringExtra(EXTRA_SIMULATED_INPUT)
                ?.let { raw -> runCatching { SimulatedInput.valueOf(raw) }.getOrNull() }
                ?.let { input ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val simulator = simulatedHardware
                        if (simulator != null) {
                            simulator.inject(input)
                        } else {
                            eventStore.record(
                                eventType = "SIMULATION_REJECTED_REAL_HARDWARE_MODE",
                                severity = EventSeverity.INFO,
                                payloadJson = JSONObject(mapOf("input" to input.name)).toString(),
                            )
                            publishStatus("SIMULATION_REJECTED_REAL_HARDWARE_MODE")
                        }
                    }
                }
        }
        if (intent?.action == ACTION_VOICE_MESSAGE_START) {
            lifecycleScope.launch(Dispatchers.IO) {
                mediaActionMutex.withLock { startVoiceMessageRecording() }
            }
        }
        if (intent?.action == ACTION_VOICE_MESSAGE_STOP) {
            lifecycleScope.launch(Dispatchers.IO) {
                mediaActionMutex.withLock { stopVoiceMessageRecording(automatic = false) }
            }
        }
        if (intent?.action == ACTION_PREPARE_SHUTDOWN) {
            lifecycleScope.launch(Dispatchers.IO) { prepareForShutdown() }
        }
        return super.onStartCommand(intent, flags, startId).let { Service.START_STICKY }
    }

    private suspend fun prepareForShutdown() = shutdownMutex.withLock {
        if (stateMachine.current() == HelmetOperationalState.SHUTTING_DOWN) return@withLock
        mediaActionMutex.withLock {
            if (mediaController.isRecording) stopVideoRecording(VideoRecordingStopCause.SHUTDOWN)
            if (voiceMessageRecorder.isRecording) stopVoiceMessageRecording(automatic = true)
        }
        callStore.active()?.let { activeCall ->
            runCatching { handleDeviceCallHangup(activeCall, "system-shutdown") }
                .onFailure { error ->
                    StructuredLogger.error(event = "shutdown_call_finalize_failed", error = error)
                }
        }
        check(stateMachine.transitionTo(HelmetOperationalState.SHUTTING_DOWN)) {
            "runtime rejected shutdown transition"
        }
        hardware.updateOperationalState(HardwareOperationalState.SHUTTING_DOWN)
        eventStore.record(
            eventType = "RUNTIME_SHUTDOWN_PREPARED",
            severity = EventSeverity.INFO,
            payloadJson = JSONObject(
                mapOf(
                    "pendingMediaCount" to mediaStore.pendingCount(),
                    "pendingTrackCount" to trackStore.pendingCount(),
                    "pendingCallCount" to callStore.pendingCount(),
                    "pendingSafetyAlertCount" to safetyStore.pendingAlertCount(),
                ),
            ).toString(),
        )
        MediaUploadWorker.enqueue(this)
        TrackUploadWorker.enqueue(this)
        CommunicationWorker.enqueue(this, CommunicationWorkTrigger.KICK)
        SafetyAlertWorker.enqueue(this)
        feedback.announceShutdown()
        publishStatus("RUNTIME_SHUTDOWN_PREPARED")
    }

    private fun recordRecoveryRequest(source: String, attempt: Int, flags: Int, startId: Int) {
        StructuredLogger.warn(
            event = "runtime_recovery_requested",
            fields = mapOf(
                "source" to source,
                "attempt" to attempt,
                "startId" to startId,
                "startFlags" to flags,
            ),
        )
        lifecycleScope.launch(Dispatchers.IO) {
            eventStore.record(
                eventType = "RUNTIME_RECOVERY_REQUESTED",
                severity = EventSeverity.MEDIUM,
                payloadJson = JSONObject(
                    mapOf(
                        "source" to source,
                        "attempt" to attempt,
                        "startId" to startId,
                        "startFlags" to flags,
                        "retry" to (flags and Service.START_FLAG_RETRY != 0),
                        "redelivery" to (flags and Service.START_FLAG_REDELIVERY != 0),
                        "processUptimeMillis" to SystemClock.elapsedRealtime(),
                    ),
                ).toString(),
            )
            publishStatus("RUNTIME_RECOVERY_REQUESTED")
        }
    }

    private fun sanitizeRecoverySource(source: String?): String = source
        ?.take(MAX_RECOVERY_SOURCE_LENGTH)
        ?.takeIf { it.matches(RECOVERY_SOURCE_PATTERN) }
        ?: "unknown"

    override fun onDestroy() {
        eventCollector?.cancel()
        statusCollector?.cancel()
        statusHeartbeatJob?.cancel()
        timeAdjustmentCollector?.cancel()
        locationFixCollector?.cancel()
        locationStatusCollector?.cancel()
        rtkFixCollector?.cancel()
        rtkStatusCollector?.cancel()
        rtkTransportEventCollector?.cancel()
        rtkTransportStatusCollector?.cancel()
        localIntercomStatusCollector?.cancel()
        networkStatusCollector?.cancel()
        callStateCollector?.cancel()
        mediaCaptureCollector?.cancel()
        voiceCaptureCollector?.cancel()
        httpCommandPollJob?.cancel()
        mqttSessionJob?.cancel()
        videoRecordingLimitJob?.cancel()
        voiceRecordingLimitJob?.cancel()
        safetyOutputReplayJob?.cancel()
        safetyDerivationRetryJob?.cancel()
        hardwareKeyActionRetryJob?.cancel()
        safetyOutputReplayGate.reset()
        mqttSession?.let { session ->
            MqttDeviceSessionRegistry.unregister(session)
            session.close()
        }
        mqttSession = null
        callMediaCoordinator.close()
        locationController.close()
        rtkController.close()
        networkMonitor.close()
        voiceMessageRecorder.close()
        mediaController.close()
        feedback.close()
        runBlocking {
            if (ownsRtkHardware) runCatching { rtkHardware.stop() }
            HardwareRuntimeMaintenance.unregisterAndStop(hardwareMaintenanceRegistration)
        }
        super.onDestroy()
    }

    private fun startMqttSession() {
        if (runtimeConfig.mqttBrokerUri.isBlank() && runtimeConfig.mqttClientCertificateAlias.isBlank()) return
        if (runtimeConfig.mqttBrokerUri.isBlank() || runtimeConfig.mqttClientCertificateAlias.isBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                eventStore.record(
                    eventType = "MQTT_CONFIGURATION_REJECTED",
                    severity = EventSeverity.HIGH,
                    payloadJson = JSONObject(
                        mapOf("reason" to "broker URI and certificate alias must both be configured"),
                    ).toString(),
                )
            }
            return
        }
        if (!mqttCommandWakeConfigurationIsComplete(runtimeConfig)) {
            lifecycleScope.launch(Dispatchers.IO) {
                eventStore.record(
                    eventType = "MQTT_CONFIGURATION_REJECTED",
                    severity = EventSeverity.HIGH,
                    payloadJson = JSONObject(
                        mapOf("reason" to "HTTP backend URL and bearer token are required for MQTT command wake"),
                    ).toString(),
                )
            }
            return
        }
        mqttSessionJob = lifecycleScope.launch(Dispatchers.IO) {
            var retryDelayMillis = MQTT_INITIAL_RETRY_MILLIS
            while (currentCoroutineContext().isActive) {
                var candidate: MqttDeviceSession? = null
                try {
                    candidate = MqttDeviceSession(
                        context = this@HelmetService,
                        brokerUri = runtimeConfig.mqttBrokerUri,
                        deviceId = deviceId,
                        certificateAlias = runtimeConfig.mqttClientCertificateAlias,
                        parentScope = lifecycleScope,
                        onCommandWake = {
                            // MQTT only wakes the durable worker. HTTP provides the authoritative
                            // stream identity, command page, and acknowledgement endpoint.
                            CommunicationWorker.enqueue(this@HelmetService, CommunicationWorkTrigger.KICK)
                        },
                        onReady = {
                            CommunicationWorker.enqueue(this@HelmetService, CommunicationWorkTrigger.KICK)
                        },
                        onStatus = ::recordMqttStatus,
                        wallClock = timeAuthority::nowEpochMillis,
                    )
                    mqttSession = candidate
                    MqttDeviceSessionRegistry.register(candidate)
                    candidate.start()
                    candidate.awaitTermination()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    candidate?.let { failed ->
                        MqttDeviceSessionRegistry.unregister(failed)
                        failed.close()
                        if (mqttSession === failed) mqttSession = null
                    }
                    eventStore.record(
                        eventType = "MQTT_SESSION_FAILED",
                        severity = EventSeverity.HIGH,
                        payloadJson = JSONObject(
                            mapOf(
                                "retryDelayMillis" to retryDelayMillis,
                            ) + mediaFailurePayloadFields(error),
                        ).toString(),
                    )
                    delay(retryDelayMillis)
                    retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MQTT_MAX_RETRY_MILLIS)
                }
            }
        }
    }

    private fun recordMqttStatus(status: MqttConnectionStatus) {
        lifecycleScope.launch(Dispatchers.IO) {
            eventStore.record(
                eventType = "MQTT_${status.state.name}",
                severity = when (status.state) {
                    MqttConnectionState.ERROR,
                    MqttConnectionState.PROCESSING_ERROR -> EventSeverity.HIGH
                    MqttConnectionState.DISCONNECTED,
                    MqttConnectionState.MESSAGE_REJECTED -> EventSeverity.MEDIUM
                    else -> EventSeverity.INFO
                },
                payloadJson = JSONObject(
                    mapOf(
                        "state" to status.state.name,
                        "detail" to status.detail?.take(MAX_MQTT_ERROR_LENGTH),
                    ),
                ).toString(),
            )
        }
    }

    private suspend fun handleHardwareEvent(event: HardwareEvent) {
        when (event) {
            is HardwareEvent.Heartbeat -> Unit
            is HardwareEvent.ModuleHello -> {
                eventStore.record(
                    eventType = if (event.compatible) "HSL_MODULE_COMPATIBLE" else "HSL_MODULE_INCOMPATIBLE",
                    severity = if (event.compatible) EventSeverity.INFO else EventSeverity.HIGH,
                    payloadJson = JSONObject(
                        mapOf(
                            "contractVersion" to event.contractVersion.toString(),
                            "firmwareVersion" to event.firmwareVersion,
                            "hardwareRevision" to event.hardwareRevision,
                            "capabilityMask" to event.capabilityMask.toString(16),
                            "missingCapabilityMask" to event.missingCapabilityMask.toString(16),
                            "compatible" to event.compatible,
                        ),
                    ).toString(),
                )
                publishStatus(if (event.compatible) "HSL_MODULE_COMPATIBLE" else "HSL_MODULE_INCOMPATIBLE")
            }
            is HardwareEvent.Key -> {
                val simulated = hardware.status.value.simulated
                val keyEventId = hardwareKeyActionId(
                    deviceId = deviceId,
                    input = event.input,
                    monotonicMillis = event.monotonicMillis,
                    hardwareEventId = event.eventId,
                )
                mediaActionMutex.withLock {
                    val stored = runCatching {
                        hardwareKeyActionStore.find(keyEventId)?.also { existing ->
                            requireMatchingHardwareKeyEvent(
                                existing = existing,
                                input = event.input,
                                monotonicMillis = event.monotonicMillis,
                                hardwareEventId = event.eventId,
                                simulated = simulated,
                            )
                        } ?: run {
                            val plan = planInteractiveKeyAction(event.input)
                            hardwareKeyActionStore.receive(
                                HardwareKeyAction(
                                    actionId = keyEventId,
                                    input = event.input.name,
                                    plannedAction = plan.decision.action.name,
                                    plannedArgument = plan.plannedArgument,
                                    businessRequestId = plan.businessRequestId,
                                    rejectionReason = plan.decision.reason,
                                    targetCallId = plan.targetCallId,
                                    simulated = simulated,
                                    monotonicMillis = event.monotonicMillis,
                                    hardwareEventId = event.eventId,
                                    sequence = event.sequence,
                                    receivedAtEpochMillis = timeAuthority.nowEpochMillis(),
                                ),
                            )
                        }
                    }
                    event.acknowledgement?.takeIf { stored.isSuccess }?.let { acknowledgement ->
                        runCatching {
                            hardware.acknowledge(acknowledgement, 0)
                        }
                    }
                    if (stored.isFailure) {
                        val error = requireNotNull(stored.exceptionOrNull())
                        if (error is DurableIdentityConflictException) {
                            event.acknowledgement?.let { acknowledgement ->
                                runCatching {
                                    hardware.acknowledge(
                                        acknowledgement,
                                        HardwareAcknowledgementResult.FIELD_OUT_OF_RANGE.wireValue,
                                    )
                                }
                            }
                        }
                        StructuredLogger.error(
                            event = "hardware_key_persistence_failed",
                            error = error,
                            fields = mapOf(
                                "input" to event.input.name,
                                "sequence" to event.sequence,
                                "errorType" to error.javaClass.name,
                            ),
                        )
                        return@withLock
                    }
                    val action = stored.getOrThrow()
                    if (!simulated) lastHardwareKeyEventEpochMillis = timeAuthority.nowEpochMillis()
                    if (action.state == HardwareKeyActionState.RECEIVED) {
                        executeDurableHardwareKeyAction(action)
                    }
                }
                publishStatus(if (simulated) "SIMULATED_KEY_RECEIVED" else "HARDWARE_KEY_RECEIVED")
            }
            is HardwareEvent.Alarm -> {
                handleSafetyAlarm(event)
            }
            is HardwareEvent.SensorSample -> {
                handleSafetySample(event)
                if (!event.simulated) lastHardwareSensorSampleEpochMillis = timeAuthority.nowEpochMillis()
            }
            is HardwareEvent.ProtocolFrame -> {
                if (event.type == HslMessageType.RTK_NMEA) {
                    if (runtimeConfig.rtk.transportMode == RtkTransportMode.HSL) {
                        rtkController.acceptReceiverBytes(event.payload)
                    }
                    return
                }
                if (event.type == HslMessageType.LOCAL_INTERCOM_STATUS) {
                    handleLocalIntercomStatus(event)
                    return
                }
                eventStore.record(
                    eventType = "HARDWARE_FRAME_${event.type.toString(16).uppercase().padStart(2, '0')}",
                    severity = EventSeverity.INFO,
                    payloadJson = JSONObject(
                        mapOf(
                            "simulated" to false,
                            "protocolVersion" to event.version,
                            "flags" to event.flags,
                            "sequence" to event.sequence,
                            "payloadSize" to event.payload.size,
                            "monotonicMillis" to event.monotonicMillis,
                        ),
                    ).toString(),
                )
                publishStatus("HARDWARE_FRAME_${event.type.toString(16).uppercase().padStart(2, '0')}")
            }
        }
    }

    private suspend fun recoverPendingHardwareKeyActions(): Boolean {
        val pending = hardwareKeyActionStore.pending(HARDWARE_KEY_RECOVERY_BATCH_SIZE)
        pending.forEach { action ->
            mediaActionMutex.withLock { executeDurableHardwareKeyAction(action) }
        }
        if (pending.isNotEmpty()) {
            eventStore.record(
                eventType = "HARDWARE_KEY_ACTIONS_RECOVERED",
                severity = EventSeverity.INFO,
                payloadJson = JSONObject(mapOf("count" to pending.size)).toString(),
            )
        }
        return pending.any { action ->
            action.plannedAction == InteractiveKeyAction.LOCAL_INTERCOM_COMMAND.name &&
                hardwareKeyActionStore.find(action.actionId)?.state == HardwareKeyActionState.RECEIVED
        }
    }

    private suspend fun executeDurableHardwareKeyAction(action: HardwareKeyAction) {
        if (hardwareKeyActionStore.find(action.actionId)?.state != HardwareKeyActionState.RECEIVED) return
        val input = runCatching { SimulatedInput.valueOf(action.input) }.getOrElse { error ->
            hardwareKeyActionStore.markFailed(action.actionId, error.javaClass.name)
            return
        }
        try {
            val plannedAction = InteractiveKeyAction.valueOf(action.plannedAction)
            val feedbackFailure = runDurableActionAttempt(
                performSideEffect = {
                    executePlannedInteractiveKeyAction(
                        action = plannedAction,
                        input = input,
                        relatedEventId = action.actionId,
                        simulated = action.simulated,
                        monotonicMillis = action.monotonicMillis,
                        rejectionReason = action.rejectionReason,
                        targetCallId = action.targetCallId,
                        plannedArgument = action.plannedArgument,
                        businessRequestId = action.businessRequestId,
                        hardwareEventId = action.hardwareEventId,
                    )
                },
                markApplied = { hardwareKeyActionStore.markApplied(action.actionId) },
                readState = { hardwareKeyActionStore.find(action.actionId)?.state },
                afterApplied = { feedback.handleKey(input) },
            )
            feedbackFailure?.let { error ->
                StructuredLogger.warn(
                    event = "hardware_key_post_commit_feedback_failed",
                    fields = mapOf(
                        "actionId" to action.actionId,
                        "errorType" to error.javaClass.name,
                    ),
                )
            }
            eventStore.record(
                eventType = "HARDWARE_KEY_ACTION_APPLIED",
                severity = EventSeverity.INFO,
                payloadJson = JSONObject(
                    mapOf("actionId" to action.actionId, "action" to action.plannedAction),
                ).toString(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: RetryableHardwareKeyActionException) {
            hardwareKeyActionStore.recordRetryableFailure(action.actionId, error.javaClass.name)
            eventStore.record(
                eventType = "HARDWARE_KEY_ACTION_RETRY_PENDING",
                severity = EventSeverity.MEDIUM,
                payloadJson = JSONObject(
                    mapOf(
                        "actionId" to action.actionId,
                        "action" to action.plannedAction,
                        "errorType" to error.javaClass.name,
                    ),
                ).toString(),
            )
            scheduleHardwareKeyActionRetry()
        } catch (error: Throwable) {
            hardwareKeyActionStore.markFailed(action.actionId, error.javaClass.name)
            eventStore.record(
                eventType = "HARDWARE_KEY_ACTION_FAILED",
                severity = EventSeverity.HIGH,
                payloadJson = JSONObject(
                    mapOf(
                        "actionId" to action.actionId,
                        "action" to action.plannedAction,
                        "errorType" to error.javaClass.name,
                    ),
                ).toString(),
            )
            StructuredLogger.error(
                event = "hardware_key_action_failed",
                error = error,
                fields = mapOf("actionId" to action.actionId, "input" to action.input),
            )
        }
    }

    private fun scheduleHardwareKeyActionRetry() {
        if (hardwareKeyActionRetryJob?.isActive == true) return
        hardwareKeyActionRetryJob = lifecycleScope.launch(Dispatchers.IO) {
            var failedRound = 0
            while (currentCoroutineContext().isActive) {
                delay(hardwareKeyActionRetryDelayMillis(failedRound))
                val pendingBefore = runCatching { hardwareKeyActionStore.pending(1).isNotEmpty() }
                    .getOrDefault(true)
                if (!pendingBefore) return@launch
                runCatching { recoverPendingHardwareKeyActions() }
                    .onFailure { error ->
                        StructuredLogger.warn(
                            event = "hardware_key_retry_round_failed",
                            fields = mapOf(
                                "failedRound" to failedRound,
                                "errorType" to error.javaClass.name,
                            ),
                        )
                    }
                if (runCatching { hardwareKeyActionStore.pending(1).isEmpty() }.getOrDefault(false)) {
                    return@launch
                }
                failedRound += 1
            }
        }
    }

    private suspend fun sendSafetyConfigIfConnected(runtimeConfig: RuntimeConfig) {
        safetyConfigMutex.withLock {
            val config = runtimeConfig.safetyThresholds
            val status = hardware.status.value
            if (
                !status.connected || safetyConfigSentVersion == config.version ||
                safetyConfigFailedVersion == config.version
            ) return@withLock
            val result = runCatching {
                hardware.sendForResult(
                    HardwareCommand(
                        type = HslMessageType.SET_CONFIG,
                        flags = HslFlags.ACK_REQUIRED,
                        sequence = config.version,
                        payload = HslSafetyConfigCodec.encode(config),
                    ),
                )
            }
            val receipt = result.getOrNull()
            if (receipt?.accepted == true) {
                safetyConfigSentVersion = config.version
                safetyConfigFailedVersion = null
                safetyConfigSessionGeneration = hardware.status.value.moduleSessionGeneration
            } else {
                safetyConfigFailedVersion = config.version
            }
            eventStore.record(
                eventType = if (receipt?.accepted == true) {
                    "SAFETY_CONFIG_CONFIRMED"
                } else {
                    "SAFETY_CONFIG_SEND_FAILED"
                },
                severity = if (receipt?.accepted == true) EventSeverity.INFO else EventSeverity.HIGH,
                payloadJson = JSONObject(
                    mapOf(
                        "configVersion" to config.version,
                        "payloadBytes" to HslSafetyConfigCodec.ENCODED_SIZE,
                        "simulated" to hardware.status.value.simulated,
                        "ackRequired" to true,
                        "outcome" to receipt?.outcome?.name,
                        "wireSequence" to receipt?.wireSequence,
                        "resultCode" to receipt?.resultCode,
                        "errorType" to result.exceptionOrNull()?.javaClass?.name,
                        "finalHardwareEvidence" to
                            (
                                receipt?.outcome == HardwareCommandOutcome.ACKNOWLEDGED &&
                                    !hardware.status.value.simulated
                                ),
                    ),
                ).toString(),
            )
        }
    }

    private suspend fun handleSafetySample(event: HardwareEvent.SensorSample) =
        safetyProcessingMutex.withLock { handleSafetySampleLocked(event) }

    private suspend fun handleSafetySampleLocked(event: HardwareEvent.SensorSample) {
        val persistence = persistAndAcknowledgeSafetySample(
            acknowledgement = event.acknowledgement,
            persist = {
                persistSafetySample(event)
                terminatePendingAndroidDetectionEpisodes(event)
                // Always rebuild from the last durable checkpoint before advancing. A failed
                // alarm must not leave mutable detector state that a later live sample can
                // carry past the uncommitted derivation waterline.
                replayPersistedSafetyDecisions()
                true
            },
            acknowledge = hardware::acknowledge,
        )
        if (!persistence.persisted) {
            StructuredLogger.warn(
                event = "safety_sample_persistence_failed",
                fields = mapOf(
                    "sampleReference" to event.sampleReference,
                    "sequence" to event.sequence,
                    "errorType" to persistence.error?.javaClass?.name,
                ),
            )
            if (persistence.error !is DurableIdentityConflictException) {
                scheduleSafetyDerivationRetry()
            }
            return
        }
        scheduleSafetyOutputReplay(hardware.status.value)
    }

    private suspend fun replayPersistedSafetyDecisions(): SafetyStateRestoreResult {
        val loaded = safetyStore.detectionCheckpoint(deviceId)
        if (loaded is SafetyDetectionCheckpointLoadResult.Missing) {
            var retiredForeignSamples = 0
            return recoverSafetyDerivationsDurably(
                processor = safetySampleProcessor,
                rebuildProcessor = safetySampleProcessor::reset,
                prepareDurableSamples = {
                    retiredForeignSamples = safetyStore.isolateSamplesOutsideConfig(
                        deviceId = deviceId,
                        thresholdConfigVersion = runtimeConfig.safetyThresholds.version,
                        thresholdConfigFingerprint = safetyThresholdFingerprint,
                    )
                },
                // No checkpoint means no pruning has occurred in schema 13. Replaying every
                // pending sample explicitly bound to the current config closes the sample /
                // alarm / checkpoint crash window. Legacy null-identity samples stay isolated.
                loadPendingSamples = {
                    safetyStore.samplesForConfig(
                        deviceId,
                        runtimeConfig.safetyThresholds.version,
                        safetyThresholdFingerprint,
                    )
                },
                beforeReplay = { samples ->
                    armPendingSafetyEpisodeTerminations(
                        reason = "CHECKPOINT_MISSING",
                        alertIds = stageStaleAndroidDetectionOutputClears(
                            // A retired row proves that an old identity existed even when its
                            // integer version equals the current version.
                            includeCurrentConfig = samples.isEmpty() || retiredForeignSamples > 0,
                            protectedCurrentPendingReferences = samples
                                .mapTo(mutableSetOf(), SafetySensorTelemetry::sampleReference),
                        ),
                    )
                    terminatePendingSafetyEpisodesBeforeReplay(samples)
                },
                persistAlarm = ::persistReplayedSafetyAlarm,
                commitCheckpoint = ::commitReplayedSafetyCheckpoint,
            )
        }
        if (loaded is SafetyDetectionCheckpointLoadResult.Invalid) {
            return invalidateSafetyDetectionCheckpoint(
                reason = loaded.reason,
                thresholdConfigVersion = null,
                thresholdConfigFingerprint = null,
            )
        }

        val validCheckpoint = (loaded as SafetyDetectionCheckpointLoadResult.Valid).checkpoint
        if (
            validCheckpoint.thresholdConfigVersion != runtimeConfig.safetyThresholds.version ||
            validCheckpoint.thresholdConfigFingerprint != safetyThresholdFingerprint
        ) {
            return invalidateSafetyDetectionCheckpoint(
                reason = "THRESHOLD_CONFIG_IDENTITY_CHANGED",
                thresholdConfigVersion = validCheckpoint.thresholdConfigVersion,
                thresholdConfigFingerprint = validCheckpoint.thresholdConfigFingerprint,
            )
        }
        val decodeFailure = runCatching {
            SafetyProcessorCheckpoint.decode(validCheckpoint)
        }.exceptionOrNull()
        if (decodeFailure != null) {
            return invalidateSafetyDetectionCheckpoint(
                reason = "CHECKPOINT_DECODE_OR_VERSION_INVALID:${decodeFailure.javaClass.simpleName}",
                thresholdConfigVersion = null,
                thresholdConfigFingerprint = null,
            )
        }
        return recoverSafetyDerivationsDurably(
            processor = safetySampleProcessor,
            rebuildProcessor = { safetySampleProcessor.restoreCheckpoint(validCheckpoint) },
            loadPendingSamples = {
                safetyStore.samplesAfterCheckpoint(
                    deviceId = deviceId,
                    afterSampleReference = validCheckpoint.lastSampleReference,
                    thresholdConfigVersion = runtimeConfig.safetyThresholds.version,
                    thresholdConfigFingerprint = safetyThresholdFingerprint,
                )
            },
            persistAlarm = ::persistReplayedSafetyAlarm,
            commitCheckpoint = ::commitReplayedSafetyCheckpoint,
        )
    }

    private suspend fun replaySafetySamples(
        samples: List<SafetySensorTelemetry>,
    ): SafetyStateRestoreResult = replaySafetySamplesDurably(
        samples = samples,
        processor = safetySampleProcessor,
        persistAlarm = { alarm ->
            handleSafetyAlarm(alarm.event, alarm.toAnalysisJson(), recoveryReplay = true)
        },
        commitCheckpoint = { checkpoint ->
            safetyStore.commitDetectionCheckpoint(
                checkpoint.toRecord(
                    deviceId = deviceId,
                    updatedAtEpochMillis = timeAuthority.nowEpochMillis(),
                ),
            )
        },
    )

    private suspend fun persistReplayedSafetyAlarm(alarm: EvaluatedSafetyAlarm): Boolean =
        handleSafetyAlarm(alarm.event, alarm.toAnalysisJson(), recoveryReplay = true)

    private suspend fun commitReplayedSafetyCheckpoint(checkpoint: SafetyProcessorCheckpoint) {
        safetyStore.commitDetectionCheckpoint(
            checkpoint.toRecord(
                deviceId = deviceId,
                updatedAtEpochMillis = timeAuthority.nowEpochMillis(),
            ),
        )
    }

    private suspend fun invalidateSafetyDetectionCheckpoint(
        reason: String,
        thresholdConfigVersion: Int?,
        thresholdConfigFingerprint: String?,
    ): SafetyStateRestoreResult {
        val retiredIdentity = if (thresholdConfigVersion != null && thresholdConfigFingerprint != null) {
            SafetyThresholdIdentity(thresholdConfigVersion, thresholdConfigFingerprint)
        } else {
            null
        }
        return recoverUnusableSafetyCheckpointDurably(
            processor = safetySampleProcessor,
            retiredIdentity = retiredIdentity,
            stageRetiredEpisodes = { _ ->
                armPendingSafetyEpisodeTerminations(
                    reason = reason,
                    alertIds = stageStaleAndroidDetectionOutputClears(
                        includeCurrentConfig = true,
                    ),
                )
            },
            isolateAllDerivedSamples = {
                // Integrity/decode failure makes the old detector state unusable. Only
                // samples covered by a committed checkpoint are retired. Pending samples
                // remain eligible for a fresh-baseline replay under their exact identity.
                safetyStore.isolateAllDerivedSamples(deviceId)
            },
            isolateRetiredIdentity = { identity ->
                safetyStore.isolateSamplesForConfig(
                    deviceId,
                    identity.version,
                    identity.fingerprint,
                )
            },
            isolateOutsideCurrentIdentity = {
                safetyStore.isolateSamplesOutsideConfig(
                    deviceId = deviceId,
                    thresholdConfigVersion = runtimeConfig.safetyThresholds.version,
                    thresholdConfigFingerprint = safetyThresholdFingerprint,
                )
            },
            loadPendingSamples = {
                safetyStore.samplesForConfig(
                    deviceId,
                    runtimeConfig.safetyThresholds.version,
                    safetyThresholdFingerprint,
                )
            },
            terminateRetiredEpisodes = { pending ->
                // The old checkpoint remains present until all old active episodes are
                // durably cleared. A crash at any earlier point re-enters this invalidation.
                terminatePendingSafetyEpisodesBeforeReplay(
                    pending = pending,
                    quarantineSamplesBeforeTermination = true,
                )
            },
            deleteCheckpoint = { safetyStore.deleteDetectionCheckpoint(deviceId) },
            persistAlarm = ::persistReplayedSafetyAlarm,
            commitCheckpoint = ::commitReplayedSafetyCheckpoint,
        )
    }

    private suspend fun terminatePendingSafetyEpisodesBeforeReplay(
        pending: List<SafetySensorTelemetry>,
        quarantineSamplesBeforeTermination: Boolean = false,
    ): Boolean {
        if (pendingSafetyEpisodeTerminationReason == null) return true
        val active = safetyStore.latestActiveAlerts(deviceId)
            .filter { it.alertId in pendingSafetyEpisodeTerminationAlertIds }
        if (active.isEmpty()) {
            pendingSafetyEpisodeTerminationReason = null
            pendingSafetyEpisodeTerminationAlertIds = emptySet()
            return true
        }
        val references = active.mapNotNull(SafetyAlertRecord::sampleReference)
        // An episode without a source reference predates the strict sequencing contract.
        // It may use the first durable sample; versioned episodes must strictly advance.
        val sequencingFloor = references + if (references.size == active.size) emptyList() else listOf(-1L)
        return terminateSafetyEpisodesUsingDurableSample(
            pending = pending,
            latestPublishedReferences = sequencingFloor,
            quarantineBeforeTermination = { candidateReference ->
                if (quarantineSamplesBeforeTermination) {
                    safetyStore.isolatePendingSamplesBefore(
                        deviceId = deviceId,
                        beforeSampleReference = candidateReference,
                        thresholdConfigVersion = runtimeConfig.safetyThresholds.version,
                        thresholdConfigFingerprint = safetyThresholdFingerprint,
                    )
                }
            },
            terminateAt = { candidate ->
                terminatePendingAndroidDetectionEpisodes(
                    sampleReference = candidate.sampleReference,
                    monotonicMillis = candidate.monotonicMillis,
                )
                pendingSafetyEpisodeTerminationAlertIds.isEmpty()
            },
        )
    }

    private fun armPendingSafetyEpisodeTerminations(reason: String, alertIds: Set<String>) {
        pendingSafetyEpisodeTerminationReason = reason.takeIf { alertIds.isNotEmpty() }
        pendingSafetyEpisodeTerminationAlertIds = alertIds
    }

    private suspend fun stageStaleAndroidDetectionOutputClears(
        includeCurrentConfig: Boolean,
        protectedCurrentPendingReferences: Set<Long> = emptySet(),
    ): Set<String> {
        val pending = mutableSetOf<String>()
        // Desired output state is persisted before the Room alert. If the process dies in
        // that small window and the checkpoint is later unusable, there may be no alert row
        // from which to derive a clear. Clear those orphaned outputs directly so corruption
        // or a retired configuration cannot leave a physical output latched indefinitely.
        safetyOutputRecoveryStore.load().outputs.forEach { output ->
            val state = output.toRecoverableSafetyOutputState() ?: return@forEach
            val alertId = "$deviceId:${state.alarmId}"
            val latest = safetyStore.latestAlert(alertId)
            if (
                latest != null &&
                isProtectedCurrentPendingSafetyAlert(
                    latest,
                    protectedCurrentPendingReferences,
                    safetyThresholdFingerprint,
                )
            ) {
                return@forEach
            }
            if (!state.active) {
                // An inactive desired output is also the durable termination marker for a
                // previous invalidation that had no strictly newer real sample. Re-arm its
                // Room/backend clear once such a sample is available after restart.
                if (latest?.active == true && latest.detectionOrigin() == HardwareAlarmOrigin.ANDROID_DETECTION.name) {
                    pending += alertId
                }
                return@forEach
            }
            if (!includeCurrentConfig && state.configVersion == runtimeConfig.safetyThresholds.version) {
                return@forEach
            }
            pending += "$deviceId:${state.alarmId}"
            check(
                safetyOutputRecoveryStore.remember(
                    state.toFailSafeClear(runtimeConfig.safetyThresholds.version).toHardwareEvent(),
                ),
            ) { "orphaned safety output clear could not be staged" }
        }
        safetyStore.latestActiveAlerts(deviceId).forEach { alert ->
            if (
                isProtectedCurrentPendingSafetyAlert(
                    alert,
                    protectedCurrentPendingReferences,
                    safetyThresholdFingerprint,
                )
            ) {
                return@forEach
            }
            if (!includeCurrentConfig && alert.configVersion == runtimeConfig.safetyThresholds.version) {
                return@forEach
            }
            val clear = alert.toDetectionEpisodeTermination(
                detectionOrigin = alert.detectionOrigin(),
                replacementConfigVersion = runtimeConfig.safetyThresholds.version,
            ) ?: return@forEach
            pending += alert.alertId
            if (clear.toRecoverableSafetyOutputState() != null) {
                check(safetyOutputRecoveryStore.remember(clear)) {
                    "stale safety output clear could not be staged"
                }
            }
        }
        return pending
    }

    private suspend fun terminatePendingAndroidDetectionEpisodes(event: HardwareEvent.SensorSample) =
        terminatePendingAndroidDetectionEpisodes(event.sampleReference, event.monotonicMillis)

    private suspend fun terminatePendingAndroidDetectionEpisodes(
        sampleReference: Long,
        monotonicMillis: Long,
    ) {
        val reason = pendingSafetyEpisodeTerminationReason ?: return
        safetyStore.latestActiveAlerts(deviceId).forEach { alert ->
            if (alert.alertId !in pendingSafetyEpisodeTerminationAlertIds) return@forEach
            val previousReference = alert.sampleReference
            check(safetyEpisodeTerminationReferenceAdvances(previousReference, sampleReference)) {
                "safety episode termination sample reference did not advance"
            }
            val clear = alert.toDetectionEpisodeTermination(
                detectionOrigin = alert.detectionOrigin(),
                replacementConfigVersion = runtimeConfig.safetyThresholds.version,
                replacementSampleReference = sampleReference,
                replacementMonotonicMillis = monotonicMillis,
            ) ?: return@forEach
            check(
                handleSafetyAlarm(
                    event = clear,
                    analysis = JSONObject(
                        mapOf(
                            "reasonCode" to reason,
                            "terminatedConfigVersion" to alert.configVersion,
                            "replacementConfigVersion" to runtimeConfig.safetyThresholds.version,
                        ),
                    ),
                ),
            ) { "stale Android safety episode was not durably cleared" }
        }
        pendingSafetyEpisodeTerminationReason = null
        pendingSafetyEpisodeTerminationAlertIds = emptySet()
    }

    private suspend fun restoreSafetyDetectionState() {
        runCatching {
            safetyProcessingMutex.withLock { replayPersistedSafetyDecisions() }
        }.onSuccess { result ->
            runCatching {
                eventStore.record(
                    eventType = "ANDROID_SAFETY_DETECTION_RESTORED",
                    severity = if (result.rejectedSamples == 0) EventSeverity.INFO else EventSeverity.MEDIUM,
                    payloadJson = JSONObject(
                        mapOf(
                            "restoredSamples" to result.restoredSamples,
                            "rejectedSamples" to result.rejectedSamples,
                            "clockResets" to result.clockResets,
                            "replayedAlarmDecisions" to result.alarms.size,
                            "retentionLimit" to SafetyStore.MAX_RETAINED_SAMPLES,
                        ),
                    ).toString(),
                )
            }.onFailure { error ->
                StructuredLogger.warn(
                    event = "safety_detection_restore_result_not_persisted",
                    fields = mapOf("errorType" to error.javaClass.name),
                )
            }
        }.onFailure { error ->
            StructuredLogger.warn(
                event = "safety_detection_restore_failed",
                fields = mapOf("errorType" to error.javaClass.name),
            )
            scheduleSafetyDerivationRetry()
            runCatching {
                eventStore.record(
                    eventType = "ANDROID_SAFETY_DETECTION_RESTORE_FAILED",
                    severity = EventSeverity.HIGH,
                    payloadJson = JSONObject(
                        mapOf("errorType" to error.javaClass.name),
                    ).toString(),
                )
            }
        }
        restoreSafetyOutputState()
    }

    private fun scheduleSafetyDerivationRetry() {
        if (safetyDerivationRetryJob?.isActive == true) return
        safetyDerivationRetryJob = lifecycleScope.launch(Dispatchers.IO) {
            var failedRound = 0
            while (currentCoroutineContext().isActive) {
                delay(safetyOutputReplayRetryDelayMillis(failedRound))
                val replay = runCatching {
                    safetyProcessingMutex.withLock { replayPersistedSafetyDecisions() }
                }
                if (replay.isSuccess) {
                    runCatching {
                        eventStore.record(
                            eventType = "ANDROID_SAFETY_DERIVATION_RETRY_RECOVERED",
                            severity = EventSeverity.INFO,
                            payloadJson = JSONObject(
                                mapOf(
                                    "failedRounds" to (failedRound + 1),
                                    "replayedAlarmDecisions" to replay.getOrThrow().alarms.size,
                                ),
                            ).toString(),
                        )
                    }
                    scheduleSafetyOutputReplay(hardware.status.value)
                    return@launch
                }
                val error = requireNotNull(replay.exceptionOrNull())
                StructuredLogger.warn(
                    event = "safety_derivation_retry_failed",
                    fields = mapOf(
                        "failedRound" to failedRound,
                        "errorType" to error.javaClass.name,
                    ),
                )
                failedRound += 1
            }
        }
    }

    private suspend fun restoreSafetyOutputState() {
        val restored = runCatching {
            val persisted = safetyOutputRecoveryStore.load()
            val desiredByAlarmId = persisted.outputs
                .mapNotNull(HardwareEvent.Alarm::toRecoverableSafetyOutputState)
                .associateByTo(mutableMapOf(), SafetyOutputDesiredState::alarmId)
            safetyStore.latestActiveAlerts(deviceId).forEach { alert ->
                val state = alert.toRecoverableSafetyOutputState(alert.detectionOrigin()) ?: return@forEach
                if (state.alarmId !in desiredByAlarmId) {
                    if (safetyOutputRecoveryStore.remember(state.toHardwareEvent())) {
                        desiredByAlarmId[state.alarmId] = state
                    } else {
                        StructuredLogger.warn(
                            event = "safety_output_recovery_seed_failed",
                            fields = mapOf("alarmId" to state.alarmId, "alarmType" to state.alarmType),
                        )
                    }
                }
            }
            var reconciledRoomClears = 0
            desiredByAlarmId.values.toList().forEach { desired ->
                if (!desired.active) return@forEach
                val latest = safetyStore.latestAlert("$deviceId:${desired.alarmId}") ?: return@forEach
                val latestState = latest.toRecoverableSafetyOutputState(latest.detectionOrigin()) ?: return@forEach
                val reconciled = reconcileSafetyOutputDesiredState(desired, latestState)
                if (reconciled != desired) {
                    check(safetyOutputRecoveryStore.remember(reconciled.toHardwareEvent())) {
                        "failed to reconcile cleared safety output state"
                    }
                    desiredByAlarmId[desired.alarmId] = reconciled
                    reconciledRoomClears += 1
                }
            }
            Triple(
                desiredByAlarmId.values.sortedBy(SafetyOutputDesiredState::alarmId)
                    .map(SafetyOutputDesiredState::toHardwareEvent),
                persisted.discardedEntries,
                reconciledRoomClears,
            )
        }.getOrElse { error ->
            StructuredLogger.warn(
                event = "safety_output_recovery_restore_failed",
                fields = mapOf("errorType" to error.javaClass.name),
            )
            runCatching {
                eventStore.record(
                    eventType = "SAFETY_OUTPUT_RECOVERY_RESTORE_FAILED",
                    severity = EventSeverity.HIGH,
                    payloadJson = JSONObject(mapOf("errorType" to error.javaClass.name)).toString(),
                )
            }
            return
        }
        restored.first.filter(HardwareEvent.Alarm::active).forEach { event ->
            runCatching { feedback.announceAlarm(event.alarmType, simulated = false) }
        }
        runCatching {
            eventStore.record(
                eventType = "SAFETY_OUTPUT_RECOVERY_RESTORED",
                severity = if (restored.second == 0 && restored.third == 0) {
                    EventSeverity.INFO
                } else {
                    EventSeverity.MEDIUM
                },
                payloadJson = JSONObject(
                    mapOf(
                        "desiredOutputCount" to restored.first.size,
                        "activeOutputCount" to restored.first.count(HardwareEvent.Alarm::active),
                        "discardedCorruptEntries" to restored.second,
                        "reconciledRoomClearEntries" to restored.third,
                        "localPromptReplayed" to restored.first.any(HardwareEvent.Alarm::active),
                    ),
                ).toString(),
            )
        }
    }

    private fun SafetyAlertRecord.detectionOrigin(): String? = runCatching {
        JSONObject(sensorSnapshotJson).optString("detectionOrigin").takeIf(String::isNotBlank)
    }.getOrNull()

    private fun scheduleSafetyOutputReplay(status: HardwareStatus) {
        if (!status.connected || status.simulated) {
            safetyOutputReplayJob?.cancel()
            safetyOutputReplayJob = null
            safetyOutputReplaySessionGeneration = null
            safetyOutputReplayGate.reset()
            return
        }
        if (
            safetyOutputReplayJob?.isActive == true &&
            safetyOutputReplaySessionGeneration == status.moduleSessionGeneration
        ) {
            return
        }
        safetyOutputReplayJob?.cancel()
        safetyOutputReplaySessionGeneration = status.moduleSessionGeneration
        safetyOutputReplayJob = lifecycleScope.launch(Dispatchers.IO) {
            replaySafetyOutputsUntilConfirmed(status.moduleSessionGeneration)
        }
    }

    private suspend fun replaySafetyOutputsUntilConfirmed(expectedSessionGeneration: Long) {
        var failedRound = 0
        while (currentCoroutineContext().isActive) {
            val currentStatus = hardware.status.value
            if (
                !currentStatus.connected || currentStatus.simulated ||
                currentStatus.moduleSessionGeneration != expectedSessionGeneration
            ) {
                return
            }
            val loadResult = runCatching { safetyOutputRecoveryStore.load() }
            if (loadResult.isFailure) {
                val error = requireNotNull(loadResult.exceptionOrNull())
                StructuredLogger.warn(
                    event = "safety_output_recovery_load_failed",
                    fields = mapOf("errorType" to error.javaClass.name),
                )
                delay(safetyOutputReplayRetryDelayMillis(failedRound++))
                continue
            }
            val load = loadResult.getOrThrow()
            val pending = safetyOutputReplayGate.pending(
                connected = currentStatus.connected,
                simulated = currentStatus.simulated,
                sessionGeneration = expectedSessionGeneration,
                outputs = load.outputs,
            )
            if (pending.isEmpty()) return

            var anyFailed = false
            pending.forEach { event ->
                if (!currentCoroutineContext().isActive) return
                runCatching {
                    eventStore.record(
                        eventType = "RECOVERED_SAFETY_OUTPUT_REQUESTED",
                        severity = EventSeverity.INFO,
                        payloadJson = JSONObject(
                            mapOf(
                                "alarmId" to event.alarmId,
                                "alarmType" to event.alarmType,
                                "active" to event.active,
                                "moduleSessionGeneration" to expectedSessionGeneration,
                                "failedRound" to failedRound,
                            ),
                        ).toString(),
                    )
                }
                val confirmed = runCatching { requestSafetyOutput(event, recovered = true) }
                    .onFailure { error ->
                        StructuredLogger.warn(
                            event = "recovered_safety_output_failed",
                            fields = mapOf(
                                "alarmId" to event.alarmId,
                                "alarmType" to event.alarmType,
                                "errorType" to error.javaClass.name,
                            ),
                        )
                    }
                    .getOrDefault(false)
                if (confirmed) {
                    safetyOutputReplayGate.recordConfirmed(expectedSessionGeneration, event)
                    if (!event.active) {
                        if (!safetyOutputRecoveryStore.removeAfterConfirmedClear(event)) {
                            runCatching {
                                eventStore.record(
                                    eventType = "SAFETY_OUTPUT_RECOVERY_CLEAR_FAILED",
                                    severity = EventSeverity.HIGH,
                                    payloadJson = JSONObject(
                                        mapOf(
                                            "alarmId" to event.alarmId,
                                            "errorType" to "SharedPreferencesCommitFailed",
                                        ),
                                    ).toString(),
                                )
                            }
                        } else {
                            releaseSafetyOutputRequestId(event)
                        }
                    }
                } else {
                    anyFailed = true
                }
            }
            if (!anyFailed) return
            delay(safetyOutputReplayRetryDelayMillis(failedRound++))
        }
    }

    private suspend fun runMediaRetention(source: String) {
        runCatching {
            reconcileMediaStorage(this, mediaStore, timeAuthority::nowEpochMillis)
        }.onSuccess { result ->
            if (
                result.recoveredAssets > 0 ||
                result.clearedCaptureJournals > 0 ||
                result.quarantinedCaptureJournals > 0 ||
                result.failedCaptureRecoveries > 0 ||
                result.deletedIncompleteFiles > 0 ||
                result.rejectedMissingAssets > 0 ||
                result.deletedTerminalAssets > 0 ||
                result.deletedOrphanFiles > 0 ||
                result.failedFileDeletes > 0 ||
                result.encryptedMediaFiles > 0 ||
                result.failedMediaEncryptions > 0
            ) {
                eventStore.record(
                    eventType = "MEDIA_RETENTION_RECONCILED",
                    severity = if (
                        result.failedFileDeletes == 0 &&
                        result.failedCaptureRecoveries == 0 &&
                        result.quarantinedCaptureJournals == 0 &&
                        result.failedMediaEncryptions == 0
                    ) EventSeverity.INFO else EventSeverity.MEDIUM,
                    payloadJson = JSONObject(
                        mapOf(
                            "source" to source,
                            "recoveredAssets" to result.recoveredAssets,
                            "clearedCaptureJournals" to result.clearedCaptureJournals,
                            "quarantinedCaptureJournals" to result.quarantinedCaptureJournals,
                            "failedCaptureRecoveries" to result.failedCaptureRecoveries,
                            "deletedIncompleteFiles" to result.deletedIncompleteFiles,
                            "rejectedMissingAssets" to result.rejectedMissingAssets,
                            "deletedTerminalAssets" to result.deletedTerminalAssets,
                            "deletedOrphanFiles" to result.deletedOrphanFiles,
                            "failedFileDeletes" to result.failedFileDeletes,
                            "encryptedMediaFiles" to result.encryptedMediaFiles,
                            "failedMediaEncryptions" to result.failedMediaEncryptions,
                        ),
                    ).toString(),
                )
            }
        }.onFailure { error ->
            StructuredLogger.warn(
                event = "media_retention_failed",
                fields = mapOf("source" to source, "errorType" to error.javaClass.name),
            )
        }
    }

    private suspend fun persistSafetySample(event: HardwareEvent.SensorSample): Boolean =
        safetyStore.recordSample(
            SafetySensorTelemetry(
                sampleId = "$deviceId:${event.sampleReference}",
                deviceId = deviceId,
                sampleReference = event.sampleReference,
                monotonicMillis = event.monotonicMillis,
                validFlags = event.validFlags,
                accelerationXMilliG = event.accelerationXMilliG,
                accelerationYMilliG = event.accelerationYMilliG,
                accelerationZMilliG = event.accelerationZMilliG,
                gyroXMilliDegreesPerSecond = event.gyroXMilliDegreesPerSecond,
                gyroYMilliDegreesPerSecond = event.gyroYMilliDegreesPerSecond,
                gyroZMilliDegreesPerSecond = event.gyroZMilliDegreesPerSecond,
                electricFieldMilliVolts = event.electricFieldMilliVolts,
                pressurePascals = event.pressurePascals,
                temperatureCentiCelsius = event.temperatureCentiCelsius,
                altitudeMillimetres = event.altitudeMillimetres,
                simulated = event.simulated,
                recordedAtEpochMillis = timeAuthority.nowEpochMillis(),
                thresholdConfigVersion = runtimeConfig.safetyThresholds.version,
                thresholdConfigFingerprint = safetyThresholdFingerprint,
            ),
        )

    private suspend fun handleSafetyAlarm(
        event: HardwareEvent.Alarm,
        analysis: JSONObject? = null,
        recoveryReplay: Boolean = false,
    ): Boolean {
        val eventPrefix = when (event.origin) {
            HardwareAlarmOrigin.EXTERNAL_MODULE -> "HARDWARE"
            HardwareAlarmOrigin.ANDROID_DETECTION -> if (event.simulated) "SIMULATED" else "ANDROID"
            HardwareAlarmOrigin.SIMULATOR -> "SIMULATED"
        }
        val state = if (event.active) "ACTIVE" else "CLEARED"
        val alertId = event.alarmId?.let { "$deviceId:$it" }
            ?: "$deviceId:sim:${UUID.randomUUID()}"
        val referenceComponent = event.sampleReference ?: event.monotonicMillis
        val messageId = "$alertId:$state:$referenceComponent"
        val fix = effectiveLocationFix(
            rtkFixQuality = rtkController.status.value.lastFixQuality,
            maximumAgeMillis = MAX_REALTIME_LOCATION_AGE_MILLIS,
        )
        val severity = runCatching { EventSeverity.valueOf(event.severity) }.getOrDefault(EventSeverity.HIGH)
        val occurredAt = timeAuthority.nowEpochMillis()
        val recoverableOutput = event.toRecoverableSafetyOutputState()
        val persistence = persistAndAcknowledgeSafetyAlarm(
            acknowledgement = event.acknowledgement,
            persist = {
                recoverableOutput?.let {
                    check(safetyOutputRecoveryStore.remember(it.toHardwareEvent())) {
                        "failed to persist desired safety output state"
                    }
                }
                event.embeddedSample?.let { persistSafetySample(it) }
                val sample = event.sampleReference?.let { safetyStore.findSample(deviceId, it) }
                val sensorSnapshot = sample?.toSnapshotJson() ?: JSONObject()
                sensorSnapshot.put("detectionOrigin", event.origin.name)
                analysis?.keys()?.forEach { key -> sensorSnapshot.put(key, analysis.get(key)) }
                val alert = SafetyAlertRecord(
                    messageId = messageId,
                    alertId = alertId,
                    deviceId = deviceId,
                    alarmType = event.alarmType,
                    severity = severity,
                    active = event.active,
                    configVersion = event.configVersion,
                    sampleReference = event.sampleReference,
                    monotonicMillis = event.monotonicMillis,
                    occurredAtEpochMillis = occurredAt,
                    localActions = event.localActions,
                    sensorFaults = event.sensorFaults,
                    simulated = event.simulated,
                    sensorSnapshotJson = sensorSnapshot.toString(),
                    latitude = fix?.latitude,
                    longitude = fix?.longitude,
                    horizontalAccuracyMeters = fix?.horizontalAccuracyMeters,
                    locationFixType = fix?.quality?.name ?: "NO_FIX",
                    evidenceAssetId = null,
                    deliveryState = DeliveryState.PENDING,
                    attemptCount = 0,
                )
                val inserted = safetyStore.recordAlert(alert)
                eventStore.record(
                    eventType = "${eventPrefix}_${event.alarmType}_$state",
                    severity = severity,
                    payloadJson = JSONObject(safetyAlarmEventPayloadFields(event, alertId, messageId)).toString(),
                    messageId = messageId,
                    occurredAtEpochMillis = occurredAt,
                )
                inserted
            },
            acknowledge = hardware::acknowledge,
        )
        if (!persistence.persisted) {
            StructuredLogger.warn(
                event = "safety_alarm_persistence_failed",
                fields = mapOf(
                    "alarmType" to event.alarmType,
                    "sequence" to event.sequence,
                    "errorType" to persistence.error?.javaClass?.name,
                ),
            )
            return false
        }
        val inserted = persistence.value == true
        if (!recoveryReplay && event.active && event.alarmType in setOf("FALL", "IMPACT", "INACTIVITY", "SOS")) {
            stateMachine.transitionTo(HelmetOperationalState.SOS)
        }
        if (inserted && event.active && !recoveryReplay) feedback.announceAlarm(event.alarmType, event.simulated)
        if (inserted) {
            if (!recoveryReplay) {
                val confirmed = requestSafetyOutput(event)
                if (!confirmed && recoverableOutput != null) {
                    scheduleSafetyOutputReplay(hardware.status.value)
                }
                if (confirmed && recoverableOutput != null && !event.active) {
                    if (!safetyOutputRecoveryStore.removeAfterConfirmedClear(event)) {
                        eventStore.record(
                            eventType = "SAFETY_OUTPUT_RECOVERY_CLEAR_FAILED",
                            severity = EventSeverity.HIGH,
                            payloadJson = JSONObject(
                                mapOf("alarmId" to event.alarmId, "errorType" to "SharedPreferencesCommitFailed"),
                            ).toString(),
                        )
                    } else {
                        releaseSafetyOutputRequestId(event)
                    }
                }
            }
            SafetyAlertWorker.enqueue(this)
            if (shouldCaptureSafetyEvidenceVideo(
                    alarmType = event.alarmType,
                    active = event.active,
                    simulated = event.simulated,
                    recoveryReplay = recoveryReplay,
                )
            ) {
                lifecycleScope.launch(Dispatchers.IO) { captureSafetyEvidence(messageId) }
            }
        }
        publishStatus("${eventPrefix}_${event.alarmType}_$state")
        return true
    }

    private fun SafetySensorTelemetry.toSnapshotJson(): JSONObject = JSONObject(
        mapOf(
            "sampleReference" to sampleReference,
            "monotonicMillis" to monotonicMillis,
            "validFlags" to validFlags,
            "accelerationXMilliG" to accelerationXMilliG,
            "accelerationYMilliG" to accelerationYMilliG,
            "accelerationZMilliG" to accelerationZMilliG,
            "gyroXMilliDegreesPerSecond" to gyroXMilliDegreesPerSecond,
            "gyroYMilliDegreesPerSecond" to gyroYMilliDegreesPerSecond,
            "gyroZMilliDegreesPerSecond" to gyroZMilliDegreesPerSecond,
            "electricFieldMilliVolts" to electricFieldMilliVolts,
            "pressurePascals" to pressurePascals,
            "temperatureCentiCelsius" to temperatureCentiCelsius,
            "altitudeMillimetres" to altitudeMillimetres,
            "simulated" to simulated,
            "thresholdConfigVersion" to thresholdConfigVersion,
            "thresholdConfigFingerprint" to thresholdConfigFingerprint,
        ),
    )

    private fun EvaluatedSafetyAlarm.toAnalysisJson(): JSONObject = JSONObject(
        mapOf(
            "accelerationMagnitudeMilliG" to evaluation.accelerationMagnitudeMilliG,
            "electricFieldLevel" to evaluation.electricFieldLevel.name,
            "electricFieldBaselineMilliVolts" to evaluation.electricFieldBaselineMilliVolts,
            "electricFieldExcessMilliVolts" to evaluation.electricFieldExcessMilliVolts,
            "electricFieldExposureMilliVoltMillis" to evaluation.electricFieldExposureMilliVoltMillis,
            "relativeHeightMillimetres" to evaluation.relativeHeightMillimetres,
            "heightInputSource" to evaluation.heightInputSource?.name,
            "heightBaselineMillimetres" to evaluation.heightBaselineMillimetres,
            "heightChangeRateMillimetresPerSecond" to evaluation.heightChangeRateMillimetresPerSecond,
            "reasonCode" to decision.reasonCode,
            "measuredValue" to decision.measuredValue,
            "thresholdValue" to decision.thresholdValue,
            "accumulatedValue" to decision.accumulatedValue,
        ),
    )

    private suspend fun requestSafetyOutput(
        event: HardwareEvent.Alarm,
        recovered: Boolean = false,
    ): Boolean {
        if (
            event.origin != HardwareAlarmOrigin.ANDROID_DETECTION ||
            event.simulated ||
            event.localActions == 0
        ) {
            return false
        }
        val sequence = safetyOutputSequence.getAndUpdate { current ->
            if (current >= 0xFFFF) 0x8000 else current + 1
        }
        val alarmId = event.alarmId ?: return false
        val requestId = runCatching { safetyOutputRequestIdStore.getOrAllocate(alarmId) }
            .getOrElse { error ->
                StructuredLogger.warn(
                    event = "safety_output_request_id_failed",
                    fields = mapOf(
                        "alarmId" to alarmId,
                        "alarmType" to event.alarmType,
                        "errorType" to error.javaClass.name,
                    ),
                )
                return false
            }
        val command = safetyOutputCommand(event, sequence, requestId) ?: return false
        val result = runCatching<HardwareCommandResult> {
            check(hardware.status.value.connected && !hardware.status.value.simulated) {
                "external output module is unavailable"
            }
            hardware.sendForResult(command)
        }
        val receipt = result.getOrNull()
        val confirmed = receipt?.accepted == true
        if (confirmed) lastHardwareOutputConfirmedEpochMillis = timeAuthority.nowEpochMillis()
        if (confirmed && event.toRecoverableSafetyOutputState() != null) {
            safetyOutputReplayGate.recordConfirmed(hardware.status.value.moduleSessionGeneration, event)
        }
        runCatching {
            eventStore.record(
                eventType = when {
                    recovered && confirmed -> "RECOVERED_SAFETY_OUTPUT_CONFIRMED"
                    recovered -> "RECOVERED_SAFETY_OUTPUT_FAILED"
                    confirmed -> "LOCAL_SAFETY_OUTPUT_CONFIRMED"
                    else -> "LOCAL_SAFETY_OUTPUT_FAILED"
                },
                severity = if (confirmed) EventSeverity.INFO else EventSeverity.HIGH,
                payloadJson = JSONObject(
                    mapOf(
                        "alarmId" to event.alarmId,
                        "alarmType" to event.alarmType,
                        "active" to event.active,
                        "localActions" to event.localActions,
                        "requestId" to requestId,
                        "sequence" to sequence,
                        "ackRequired" to true,
                        "outcome" to receipt?.outcome?.name,
                        "wireSequence" to receipt?.wireSequence,
                        "resultCode" to receipt?.resultCode,
                        "errorType" to result.exceptionOrNull()?.javaClass?.name,
                        "recovered" to recovered,
                        "finalHardwareEvidence" to (confirmed && !hardware.status.value.simulated),
                    ),
                ).toString(),
            )
        }.onFailure { error ->
            StructuredLogger.warn(
                event = "safety_output_result_not_persisted",
                fields = mapOf("alarmId" to alarmId, "errorType" to error.javaClass.name),
            )
        }
        if (confirmed && event.toRecoverableSafetyOutputState() == null) {
            safetyOutputRequestIdStore.release(alarmId, requestId)
        }
        return confirmed
    }

    private fun releaseSafetyOutputRequestId(event: HardwareEvent.Alarm) {
        val alarmId = event.alarmId ?: return
        runCatching {
            val requestId = safetyOutputRequestIdStore.getOrAllocate(alarmId)
            check(safetyOutputRequestIdStore.release(alarmId, requestId))
        }.onFailure { error ->
            StructuredLogger.warn(
                event = "safety_output_request_id_release_failed",
                fields = mapOf("alarmId" to alarmId, "errorType" to error.javaClass.name),
            )
        }
    }

    private suspend fun captureSafetyEvidence(relatedEventId: String) = mediaActionMutex.withLock {
        val videoResult = if (voiceMessageRecorder.isRecording) {
            SafetyEvidenceVideoResult.Unavailable("VOICE_MESSAGE_RECORDING_ACTIVE")
        } else {
            recordSafetyEvidenceVideo(
                controller = mediaController,
                relatedEventId = relatedEventId,
                locationFix = locationForMedia(),
            )
        }
        when (videoResult) {
            is SafetyEvidenceVideoResult.Captured -> {
                eventStore.record(
                    eventType = "SAFETY_EVIDENCE_VIDEO_CAPTURED",
                    severity = EventSeverity.INFO,
                    payloadJson = mediaPayload(videoResult.asset)
                        .put("shortened", videoResult.shortened)
                        .put("requestedDurationMillis", SAFETY_EVIDENCE_VIDEO_DURATION_MILLIS)
                        .toString(),
                )
                MediaUploadWorker.enqueue(this)
            }
            is SafetyEvidenceVideoResult.Unavailable -> {
                recordSafetyEvidenceVideoFailure(
                    relatedEventId = relatedEventId,
                    reason = videoResult.reason,
                    recordingStarted = false,
                )
                captureSafetyEvidencePhotoFallback(relatedEventId)
            }
            is SafetyEvidenceVideoResult.Failed -> {
                recordSafetyEvidenceVideoFailure(
                    relatedEventId = relatedEventId,
                    reason = videoResult.errorType,
                    recordingStarted = videoResult.recordingStarted,
                )
                captureSafetyEvidencePhotoFallback(relatedEventId)
            }
        }
    }

    private suspend fun recordSafetyEvidenceVideoFailure(
        relatedEventId: String,
        reason: String,
        recordingStarted: Boolean,
    ) {
        eventStore.record(
            eventType = "SAFETY_EVIDENCE_VIDEO_FAILED",
            severity = EventSeverity.MEDIUM,
            payloadJson = JSONObject(
                mapOf(
                    "relatedEventId" to relatedEventId,
                    "cameraCount" to cameraCapabilities.cameraCount,
                    "recordingStarted" to recordingStarted,
                    "reason" to reason,
                ),
            ).toString(),
        )
    }

    private suspend fun captureSafetyEvidencePhotoFallback(relatedEventId: String) {
        runCatching { mediaController.capturePhoto(relatedEventId, locationForMedia()) }
            .onSuccess { asset ->
                eventStore.record(
                    eventType = "SAFETY_EVIDENCE_PHOTO_CAPTURED",
                    severity = EventSeverity.INFO,
                    payloadJson = mediaPayload(asset).put("videoFallback", true).toString(),
                )
                MediaUploadWorker.enqueue(this)
            }
            .onFailure { error ->
                eventStore.record(
                    eventType = "SAFETY_EVIDENCE_PHOTO_FAILED",
                    severity = EventSeverity.MEDIUM,
                    payloadJson = JSONObject(
                        mapOf(
                            "relatedEventId" to relatedEventId,
                            "cameraCount" to cameraCapabilities.cameraCount,
                            "videoFallback" to true,
                        ) + mediaFailurePayloadFields(error),
                    ).toString(),
                )
            }
    }

    private suspend fun publishStatus(lastEventType: String) = statusPublishMutex.withLock {
        hardware.updateOperationalState(stateMachine.current().toHardwareOperationalState())
        cameraCapabilities = mediaController.inspect()
        val persistedEventCount = eventStore.totalCount()
        val pendingMediaCount = mediaStore.pendingCount()
        val pendingTrackCount = trackStore.pendingCount()
        val pendingCallCount = callStore.pendingCount()
        val pendingBroadcastReceiptCount = broadcastStore.totalPendingReceiptCount()
        val pendingSafetyAlertCount = safetyStore.pendingAlertCount()
        val retainedSafetySampleCount = safetyStore.sampleCount(deviceId)
        val latestCall = callStore.latest()
        val locationStatus = locationController.status.value
        val rtkStatus = rtkController.status.value
        val currentLocationFix = effectiveLocationFix(
            rtkFixQuality = rtkStatus.lastFixQuality,
            maximumAgeMillis = MAX_REALTIME_LOCATION_AGE_MILLIS,
        )
        val localIntercomStatus = localIntercomController.status.value
        val networkStatus = networkMonitor.snapshot.value
        val networkAvailable = isNetworkAvailable()
        val batteryStatus = BatteryStatusReader.read(this)
        val previousBatteryThreshold = batteryPromptTracker.promptedThreshold
        batteryPromptTracker.update(batteryStatus.present, batteryStatus.percent)?.let(feedback::announceLowBattery)
        if (previousBatteryThreshold != batteryPromptTracker.promptedThreshold) {
            getSharedPreferences(LOCAL_FEEDBACK_PREFERENCES, MODE_PRIVATE).edit().apply {
                batteryPromptTracker.promptedThreshold?.let {
                    putInt(LAST_BATTERY_PROMPT_THRESHOLD, it)
                } ?: remove(LAST_BATTERY_PROMPT_THRESHOLD)
            }.apply()
        }
        val timeReading = timeAuthority.read()
        val activeCall = latestCall?.takeUnless {
            it.state in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED)
        }
        RuntimeStatus.update {
            it.copy(
                deviceId = deviceId,
                state = stateMachine.current(),
                statusUpdatedAtEpochMillis = timeReading.epochMillis,
                networkAvailable = networkAvailable,
                networkState = networkStatus.state.name,
                networkTransports = networkStatus.transports.joinToString(",").ifBlank { "none" },
                networkMetered = networkStatus.metered,
                networkInterface = networkStatus.interfaceName ?: "none",
                persistedEventCount = persistedEventCount,
                lastEventType = lastEventType,
                appVersion = appVersion,
                androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                configRevision = configRevision,
                hardwareMode = if (hardware.status.value.simulated) "SIMULATED" else "UART",
                hardwareConnected = hardware.status.value.connected,
                hardwareLinkState = hardware.status.value.linkState,
                hardwareLastError = hardware.status.value.lastError,
                hardwareEventQueueOverflowCount = hardware.status.value.eventQueueOverflowCount,
                hardwareCompatibility = hardware.status.value.compatibility.name,
                hardwareContractVersion = hardware.status.value.moduleContractVersion,
                hardwareFirmwareVersion = hardware.status.value.moduleFirmwareVersion,
                hardwareCapabilityMask = hardware.status.value.moduleCapabilityMask,
                hardwareMissingCapabilityMask = hardware.status.value.missingCapabilityMask,
                hardwareRevision = hardware.status.value.moduleHardwareRevision,
                hardwareLastKeyEventEpochMillis = lastHardwareKeyEventEpochMillis,
                hardwareLastSensorSampleEpochMillis = lastHardwareSensorSampleEpochMillis,
                hardwareLastOutputConfirmedEpochMillis = lastHardwareOutputConfirmedEpochMillis,
                cameraCount = cameraCapabilities.cameraCount,
                videoRecording = mediaController.isRecording,
                voiceMessageRecording = voiceMessageRecorder.isRecording,
                pendingMediaCount = pendingMediaCount,
                locationState = when {
                    currentLocationFix?.source == LocationSource.EXTERNAL_NMEA -> "TRACKING"
                    lastLocationFix?.source == LocationSource.EXTERNAL_NMEA -> "WAITING_FOR_FIX"
                    else -> locationStatus.state.name
                },
                locationProvider = currentLocationFix?.provider ?: locationStatus.selectedProvider ?: "none",
                locationFixQuality = currentLocationFix?.quality?.name
                    ?: if (lastLocationFix?.source == LocationSource.EXTERNAL_NMEA) {
                        rtkStatus.lastFixQuality.name
                    } else {
                        locationStatus.lastFixQuality.name
                    },
                locationHasPosition = currentLocationFix?.hasPosition == true,
                locationLatitude = currentLocationFix?.latitude,
                locationLongitude = currentLocationFix?.longitude,
                locationHorizontalAccuracyMeters = currentLocationFix?.horizontalAccuracyMeters,
                locationOccurredAtEpochMillis = currentLocationFix?.occurredAtEpochMillis,
                locationFixQueueOverflowCount = locationStatus.fixQueueOverflowCount,
                rtkState = rtkStatus.state.name,
                rtkCorrectionFrames = rtkStatus.correctionFrames,
                rtkCorrectionBytes = rtkStatus.correctionBytes,
                rtkLastError = rtkStatus.lastError,
                rtkTransportMode = runtimeConfig.rtk.transportMode.name,
                rtkTransportConnected = rtkHardware.status.value.connected,
                rtkTransportLinkState = rtkHardware.status.value.linkState,
                rtkTransportLastError = rtkHardware.status.value.lastError,
                localIntercomState = localIntercomStatus.state.name,
                localIntercomPeers = localIntercomStatus.peerCount,
                localIntercomRssiDbm = localIntercomStatus.rssiDbm,
                localIntercomPacketLossPermille = localIntercomStatus.packetLossPermille,
                localIntercomLatencyMillis = localIntercomStatus.oneWayLatencyMillis,
                localIntercomLastError = localIntercomStatus.lastError,
                pendingTrackCount = pendingTrackCount,
                activeGeofenceCount = activeGeofenceCount,
                activeCallId = activeCall?.callId ?: "none",
                callState = latestCall?.state?.name ?: "none",
                streamState = it.streamState,
                streamAudioEnabled = it.streamAudioEnabled,
                streamVideoEnabled = it.streamVideoEnabled,
                streamError = it.streamError,
                pendingCallSyncCount = pendingCallCount,
                pendingBroadcastReceiptCount = pendingBroadcastReceiptCount,
                pendingSafetyAlertCount = pendingSafetyAlertCount,
                retainedSafetySampleCount = retainedSafetySampleCount,
                batteryPresent = batteryStatus.present,
                batteryPercent = batteryStatus.percent,
                batteryVoltageMillivolts = batteryStatus.voltageMillivolts,
                timeSource = timeReading.source.name,
                timeSynchronized = timeReading.synchronized,
                timeUncertaintyMillis = timeReading.uncertaintyMillis,
                timeCalibrationAgeMillis = timeReading.calibrationAgeMillis,
                cameraSummary = cameraCapabilities.selectedCameraId?.let { cameraId ->
                    val photo = cameraCapabilities.maximumJpegSize?.let { "${it.width}x${it.height}" } ?: "no-jpeg"
                    val video = cameraCapabilities.selectedVideoSize?.let { "${it.width}x${it.height}" } ?: "no-video"
                    "$cameraId photo=$photo video=$video"
                } ?: "unavailable",
            )
        }
        val backendConfig = RuntimeConfigStore(this).load()
        if (backendConfig.backendBaseUrl.isNotBlank() && backendConfig.backendBearerToken.isNotBlank()) {
            val trustedFix = currentLocationFix
            val statusTime = deviceStatusOutbox.nextOccurredAt(
                maxOf(timeReading.epochMillis, trustedFix?.occurredAtEpochMillis ?: 1L),
                timeReading.calibrationSequence,
            )
            DeviceStatusWorker.replace(
                this,
                DeviceStatusPayload(
                    messageId = UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    personId = backendConfig.personId,
                    statusSequence = deviceStatusOutbox.nextSequence(),
                    occurredAtEpochMillis = statusTime,
                    operationalState = stateMachine.current().name,
                    networkState = networkStatus.state.name,
                    hardwareMode = if (hardware.status.value.simulated) "SIMULATED" else "UART",
                    appVersion = appVersion,
                    cameraAvailable = cameraCapabilities.cameraCount > 0,
                    simulated = hardware.status.value.simulated,
                    activeCallId = activeCall?.callId,
                    battery = batteryStatus,
                    location = DeviceLocationStatus(
                        latitude = trustedFix?.latitude,
                        longitude = trustedFix?.longitude,
                        horizontalAccuracyMeters = trustedFix?.horizontalAccuracyMeters,
                        fixType = trustedFix?.quality?.name ?: "NO_FIX",
                        occurredAtEpochMillis = trustedFix?.occurredAtEpochMillis,
                    ),
                    rtk = DeviceRtkStatus(
                        state = rtkStatus.state.name,
                        correctionFrames = rtkStatus.correctionFrames,
                        correctionBytes = rtkStatus.correctionBytes,
                        lastFixQuality = rtkStatus.lastFixQuality.name,
                        lastError = rtkStatus.lastError,
                    ),
                    localIntercom = DeviceLocalIntercomStatus(
                        state = localIntercomStatus.state.name,
                        peerCount = localIntercomStatus.peerCount,
                        codec = localIntercomStatus.codec,
                        rssiDbm = localIntercomStatus.rssiDbm,
                        packetLossPermille = localIntercomStatus.packetLossPermille,
                        oneWayLatencyMillis = localIntercomStatus.oneWayLatencyMillis,
                        faultCode = localIntercomStatus.faultCode,
                        lastError = localIntercomStatus.lastError,
                    ),
                    time = DeviceTimeStatus(
                        source = timeReading.source,
                        synchronized = timeReading.synchronized,
                        uncertaintyMillis = timeReading.uncertaintyMillis,
                        calibrationAgeMillis = timeReading.calibrationAgeMillis,
                        calibrationSequence = timeReading.calibrationSequence,
                    ),
                ),
            )
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(stateMachine.current().name))
        StructuredLogger.info(
            event = "runtime_status",
            fields = mapOf(
                "state" to stateMachine.current().name,
                "persistedEventCount" to persistedEventCount,
                "pendingMediaCount" to pendingMediaCount,
                "pendingTrackCount" to pendingTrackCount,
                "networkState" to networkStatus.state.name,
                "networkTransports" to networkStatus.transports.joinToString(","),
                "pendingCallSyncCount" to pendingCallCount,
                "pendingBroadcastReceiptCount" to pendingBroadcastReceiptCount,
                "pendingSafetyAlertCount" to pendingSafetyAlertCount,
                "retainedSafetySampleCount" to retainedSafetySampleCount,
                "locationState" to locationStatus.state.name,
                "rtkState" to rtkStatus.state.name,
                "rtkCorrectionFrames" to rtkStatus.correctionFrames,
                "localIntercomState" to localIntercomStatus.state.name,
                "localIntercomPeers" to localIntercomStatus.peerCount,
                "lastEventType" to lastEventType,
                "timeSource" to timeReading.source.name,
                "timeSynchronized" to timeReading.synchronized,
                "timeUncertaintyMillis" to timeReading.uncertaintyMillis,
                "timeCalibrationAgeMillis" to timeReading.calibrationAgeMillis,
            ),
        )
    }

    private fun startStatusHeartbeat() {
        statusHeartbeatJob?.cancel()
        statusHeartbeatJob = lifecycleScope.launch(Dispatchers.IO) {
            statusHeartbeatFlow(STATUS_HEARTBEAT_INTERVAL_MILLIS)
                .collect { publishStatus("STATUS_HEARTBEAT") }
        }
    }

    private fun startTimeAdjustmentCollection() {
        timeAdjustmentCollector?.cancel()
        timeAdjustmentCollector = lifecycleScope.launch(Dispatchers.IO) {
            timeAuthority.significantAdjustments.collect { adjustment ->
                eventStore.record(
                    eventType = "DEVICE_TIME_ADJUSTED",
                    severity = EventSeverity.INFO,
                    payloadJson = JSONObject(
                        mapOf(
                            "previousSource" to adjustment.previous.source.name,
                            "currentSource" to adjustment.current.source.name,
                            "correctionMillis" to adjustment.correctionMillis,
                            "uncertaintyMillis" to adjustment.current.uncertaintyMillis,
                            "calibrationSequence" to adjustment.current.calibrationSequence,
                        ),
                    ).toString(),
                )
                publishStatus("DEVICE_TIME_ADJUSTED")
            }
        }
    }

    private suspend fun planInteractiveKeyAction(
        input: SimulatedInput,
    ): DurableInteractiveKeyPlan {
        val activeCall = callStore.active()
        val useLocalIntercom = input == SimulatedInput.CALL &&
            runtimeConfig.localIntercom.enabled &&
            runtimeConfig.localIntercom.fallbackWhenInternetUnavailable &&
            !networkMonitor.snapshot.value.hasValidatedInternet
        val decision = decideInteractiveKeyAction(
            input = input,
            state = stateMachine.current(),
            videoRecording = mediaController.isRecording,
            voiceRecording = voiceMessageRecorder.isRecording,
            hasActiveInternetCall = activeCall != null,
            useLocalIntercom = useLocalIntercom,
        )
        return when (decision.action) {
            InteractiveKeyAction.CALL_START -> DurableInteractiveKeyPlan(
                decision = decision,
                targetCallId = UUID.randomUUID().toString(),
            )
            InteractiveKeyAction.CALL_END -> DurableInteractiveKeyPlan(
                decision = decision,
                targetCallId = activeCall?.callId,
            )
            InteractiveKeyAction.SOS -> DurableInteractiveKeyPlan(
                decision = decision,
                targetCallId = planSosFallbackCallId { UUID.randomUUID().toString() },
            )
            InteractiveKeyAction.LOCAL_INTERCOM_COMMAND -> {
                val command = localIntercomController.planToggleCommand()
                DurableInteractiveKeyPlan(
                    decision = decision,
                    plannedArgument = command.action.name,
                    businessRequestId = command.requestId,
                )
            }
            InteractiveKeyAction.VOLUME_SET -> DurableInteractiveKeyPlan(
                decision = decision,
                plannedArgument = feedback.planCallVolumeTarget(input).toString(),
            )
            else -> DurableInteractiveKeyPlan(decision = decision)
        }
    }

    private suspend fun executePlannedInteractiveKeyAction(
        action: InteractiveKeyAction,
        input: SimulatedInput,
        relatedEventId: String,
        simulated: Boolean,
        monotonicMillis: Long,
        rejectionReason: String?,
        targetCallId: String?,
        plannedArgument: String?,
        businessRequestId: Long?,
        hardwareEventId: Long?,
    ) {
        when (action) {
            InteractiveKeyAction.PHOTO -> capturePhoto(relatedEventId)
            InteractiveKeyAction.VIDEO_START -> {
                if (!mediaController.isRecording) toggleVideoRecording(relatedEventId)
            }
            InteractiveKeyAction.VIDEO_STOP -> {
                if (mediaController.isRecording) toggleVideoRecording(relatedEventId)
            }
            InteractiveKeyAction.CALL_START -> {
                runRetryableCallPersistence {
                    val plannedCallId = requireNotNull(targetCallId) { "missing planned call ID" }
                    val existing = callStore.find(plannedCallId)
                    if (existing == null && !stateMachine.transitionTo(HelmetOperationalState.CALLING)) {
                        recordInteractiveActionRejection(input, relatedEventId, "state transition rejected")
                    } else if (!handleCallRequest(relatedEventId, simulated, plannedCallId)) {
                        stateMachine.transitionTo(idleState())
                    }
                }
            }
            InteractiveKeyAction.CALL_END -> {
                runRetryableCallPersistence {
                    val current = targetCallId?.let { callStore.find(it) }
                    if (current != null) handleDeviceCallHangup(current, relatedEventId)
                }
            }
            InteractiveKeyAction.LOCAL_INTERCOM_COMMAND -> {
                val command = PlannedLocalIntercomCommand(
                    action = requireNotNull(plannedArgument).let { value ->
                        com.example.helmet.core.protocol.LocalIntercomAction.valueOf(value)
                    },
                    requestId = requireNotNull(businessRequestId),
                )
                val accepted = try {
                    handleLocalIntercomCommand(relatedEventId, simulated, command)
                } catch (error: HardwareCommandException) {
                    if (!isRetryableHardwareCommandFailure(error)) throw error
                    throw RetryableHardwareKeyActionException("local intercom command delivery failed", error)
                }
                if (!accepted) {
                    throw RetryableHardwareKeyActionException("local intercom command was not accepted")
                }
            }
            InteractiveKeyAction.VOLUME_SET -> feedback.setCallVolume(
                requireNotNull(plannedArgument?.toIntOrNull()) { "missing planned volume target" },
            )
            InteractiveKeyAction.SOS -> runRetryableCallPersistence {
                handleSosKey(
                    relatedEventId,
                    simulated,
                    monotonicMillis,
                    requireNotNull(targetCallId) { "missing planned SOS call ID" },
                    hardwareEventId,
                )
            }
            InteractiveKeyAction.REJECT -> recordInteractiveActionRejection(
                input,
                relatedEventId,
                rejectionReason ?: "action rejected",
            )
            InteractiveKeyAction.NO_OP -> Unit
        }
    }

    private suspend fun recordInteractiveActionRejection(
        input: SimulatedInput,
        relatedEventId: String,
        reason: String,
    ) {
        eventStore.record(
            eventType = "INTERACTIVE_ACTION_REJECTED",
            severity = EventSeverity.MEDIUM,
            payloadJson = JSONObject(
                mapOf(
                    "input" to input.name,
                    "relatedEventId" to relatedEventId,
                    "operationalState" to stateMachine.current().name,
                    "videoRecording" to mediaController.isRecording,
                    "voiceRecording" to voiceMessageRecorder.isRecording,
                    "activeCallId" to callStore.active()?.callId,
                    "reason" to reason,
                ),
            ).toString(),
        )
        publishStatus("INTERACTIVE_ACTION_REJECTED")
    }

    private suspend fun handleDeviceCallHangup(call: CallSession, relatedEventId: String) {
        val current = callStore.find(call.callId) ?: return
        if (current.state in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED)) {
            CommunicationWorker.enqueue(this, CommunicationWorkTrigger.DURABLE_COMMIT)
            return
        }
        val ended = commitCallOutboxAndSchedule(
            commit = { callStore.transition(call.callId, CallState.ENDED, "DEVICE_KEY_HANGUP") },
            schedule = { CommunicationWorker.enqueue(this, CommunicationWorkTrigger.DURABLE_COMMIT) },
        )
        runCatching {
            eventStore.record(
                eventType = "CALL_ENDED_BY_DEVICE_KEY",
                severity = EventSeverity.INFO,
                payloadJson = JSONObject(
                    mapOf(
                        "callId" to ended.callId,
                        "previousState" to current.state.name,
                        "stateSequence" to ended.stateSequence,
                        "relatedEventId" to relatedEventId,
                    ),
                ).toString(),
            )
        }.onFailure { error ->
            StructuredLogger.warn(
                event = "call_hangup_audit_not_persisted",
                fields = mapOf("callId" to ended.callId, "errorType" to error.javaClass.name),
            )
        }
        runCatching { publishStatus("CALL_ENDED_BY_DEVICE_KEY") }
    }

    private suspend fun handleSosKey(
        relatedEventId: String,
        simulated: Boolean,
        monotonicMillis: Long,
        plannedCallId: String,
        hardwareEventId: Long?,
    ) {
        if (mediaController.isRecording) stopVideoRecording(VideoRecordingStopCause.USER)
        if (voiceMessageRecorder.isRecording) stopVoiceMessageRecording(automatic = false)
        val normalizedMonotonic = monotonicMillis and 0xFFFF_FFFFL
        stateMachine.transitionTo(HelmetOperationalState.SOS)
        val alarmPersisted = handleSafetyAlarm(
            HardwareEvent.Alarm(
                monotonicMillis = normalizedMonotonic,
                alarmType = "SOS",
                severity = "CRITICAL",
                simulated = simulated,
                active = true,
                alarmId = sosAlarmBusinessId(hardwareEventId, relatedEventId),
                localActions = 0,
                sensorFaults = 0,
                origin = if (simulated) HardwareAlarmOrigin.SIMULATOR else HardwareAlarmOrigin.EXTERNAL_MODULE,
            ),
        )
        requirePersistedSosAlarm(alarmPersisted)
        val activeCall = callStore.active()
        if (shouldCreateSosEmergencyCall(activeCall?.callId)) {
            handleCallRequest(relatedEventId, simulated, plannedCallId)
        } else {
            eventStore.record(
                eventType = "SOS_EMERGENCY_CALL_REUSED_ACTIVE_CALL",
                severity = EventSeverity.INFO,
                payloadJson = JSONObject(
                    mapOf(
                        "relatedEventId" to relatedEventId,
                        "activeCallId" to requireNotNull(activeCall).callId,
                        "plannedCallId" to plannedCallId,
                    ),
                ).toString(),
            )
        }
        publishStatus("SOS_KEY_ALARM_PERSISTED")
    }

    private suspend fun startLocationCollection() {
        locationFixCollector = lifecycleScope.launch(Dispatchers.IO) {
            locationController.fixes.collect { fix -> handleLocationFix(fix) }
        }
        locationStatusCollector = lifecycleScope.launch(Dispatchers.IO) {
            locationController.status
                .drop(1)
                .distinctUntilChangedBy { status ->
                    listOf(
                        status.state,
                        status.selectedProvider,
                        status.lastFixQuality,
                        status.lastError,
                        status.fixQueueOverflowCount,
                    )
                }
                .collect { publishStatus("LOCATION_STATUS_CHANGED") }
        }
        locationController.start()
    }

    private fun startRtkCollection() {
        rtkFixCollector = lifecycleScope.launch(Dispatchers.IO) {
            rtkController.fixes.collect(::handleLocationFix)
        }
        rtkStatusCollector = lifecycleScope.launch(Dispatchers.IO) {
            rtkController.status
                .drop(1)
                .distinctUntilChangedBy { status ->
                    listOf(status.state, status.lastFixQuality, status.lastError)
                }
                .collect { status ->
                    eventStore.record(
                        eventType = "RTK_STATUS_CHANGED",
                        severity = if (status.state == RtkCorrectionState.CONFIG_ERROR) {
                            EventSeverity.MEDIUM
                        } else {
                            EventSeverity.INFO
                        },
                        payloadJson = JSONObject(
                            mapOf(
                                "state" to status.state.name,
                                "endpoint" to status.endpoint,
                                "receiverSentences" to status.receiverSentences,
                                "correctionFrames" to status.correctionFrames,
                                "correctionBytes" to status.correctionBytes,
                                "correctionChunks" to status.correctionChunks,
                                "lastFixQuality" to status.lastFixQuality.name,
                                "lastFixAtEpochMillis" to status.lastFixAtEpochMillis,
                                "error" to status.lastError,
                                "finalHardwareEvidence" to false,
                            ),
                        ).toString(),
                    )
                    publishStatus("RTK_STATUS_CHANGED")
                }
        }
    }

    private fun startDirectRtkTransportCollection() {
        rtkTransportEventCollector = lifecycleScope.launch(Dispatchers.IO) {
            rtkHardware.events.collect { event ->
                if (event is HardwareEvent.ProtocolFrame && event.type == HslMessageType.RTK_NMEA) {
                    rtkController.acceptReceiverBytes(event.payload)
                }
            }
        }
        rtkTransportStatusCollector = lifecycleScope.launch(Dispatchers.IO) {
            rtkHardware.status
                .drop(1)
                .distinctUntilChangedBy { status ->
                    listOf(status.connected, status.linkState, status.lastError, status.eventQueueOverflowCount)
                }
                .collect { status ->
                    eventStore.record(
                        eventType = "RTK_DIRECT_TRANSPORT_STATUS",
                        severity = if (status.linkState in setOf("WRITE_FAILED", "FAULT")) {
                            EventSeverity.MEDIUM
                        } else {
                            EventSeverity.INFO
                        },
                        payloadJson = JSONObject(
                            mapOf(
                                "connected" to status.connected,
                                "linkState" to status.linkState,
                                "eventQueueOverflowCount" to status.eventQueueOverflowCount,
                                "error" to status.lastError,
                            ),
                        ).toString(),
                    )
                    publishStatus("RTK_DIRECT_TRANSPORT_STATUS")
                }
        }
    }

    private fun startLocalIntercomCollection() {
        localIntercomStatusCollector = lifecycleScope.launch(Dispatchers.IO) {
            var lastAnnouncedState: LocalIntercomState? = null
            localIntercomController.status
                .drop(1)
                .distinctUntilChangedBy { status ->
                    listOf(status.state, status.peerCount, status.faultCode, status.lastError)
                }
                .collect { status ->
                    if (status.state != lastAnnouncedState) {
                        feedback.announceLocalIntercomState(status.state)
                        lastAnnouncedState = status.state
                    }
                    publishStatus("LOCAL_INTERCOM_STATUS_CHANGED")
                }
        }
    }

    private suspend fun handleLocalIntercomCommand(
        relatedEventId: String,
        simulated: Boolean,
        command: PlannedLocalIntercomCommand,
    ): Boolean {
        val before = localIntercomController.status.value.state
        val accepted = localIntercomController.executePlanned(command)
        val after = localIntercomController.status.value
        eventStore.record(
            eventType = if (accepted) "LOCAL_INTERCOM_COMMAND_SENT" else "LOCAL_INTERCOM_COMMAND_FAILED",
            severity = if (accepted) EventSeverity.INFO else EventSeverity.MEDIUM,
            payloadJson = JSONObject(
                mapOf(
                    "relatedEventId" to relatedEventId,
                    "simulated" to simulated,
                    "fromState" to before.name,
                    "toState" to after.state.name,
                    "requestId" to after.lastRequestId,
                    "plannedRequestId" to command.requestId,
                    "plannedAction" to command.action.name,
                    "groupId" to runtimeConfig.localIntercom.groupId,
                    "channel" to runtimeConfig.localIntercom.channel,
                    "keySlot" to runtimeConfig.localIntercom.keySlot,
                    "error" to after.lastError,
                    "finalHardwareEvidence" to false,
                ),
            ).toString(),
        )
        publishStatus(if (accepted) "LOCAL_INTERCOM_COMMAND_SENT" else "LOCAL_INTERCOM_COMMAND_FAILED")
        return accepted
    }

    private suspend fun handleLocalIntercomStatus(event: HardwareEvent.ProtocolFrame) {
        runCatching { localIntercomController.acceptModuleStatus(event.payload) }
            .onSuccess { status ->
                eventStore.record(
                    eventType = "LOCAL_INTERCOM_MODULE_STATUS",
                    severity = if (status.state == com.example.helmet.core.protocol.LocalIntercomModuleState.FAULT) {
                        EventSeverity.MEDIUM
                    } else {
                        EventSeverity.INFO
                    },
                    payloadJson = JSONObject(
                        mapOf(
                            "requestId" to status.requestId,
                            "state" to status.state.name,
                            "peerCount" to status.peerCount,
                            "codec" to status.codec.name,
                            "sampleRateHertz" to status.sampleRateHertz,
                            "rssiDbm" to status.rssiDbm,
                            "snrTenthsDb" to status.snrTenthsDb,
                            "packetLossPermille" to status.packetLossPermille,
                            "oneWayLatencyMillis" to status.oneWayLatencyMillis,
                            "transmittedPackets" to status.transmittedPackets,
                            "receivedPackets" to status.receivedPackets,
                            "faultCode" to status.faultCode,
                            "finalHardwareEvidence" to false,
                        ),
                    ).toString(),
                )
            }
            .onFailure { error ->
                eventStore.record(
                    eventType = "LOCAL_INTERCOM_STATUS_REJECTED",
                    severity = EventSeverity.MEDIUM,
                    payloadJson = JSONObject(
                        mapOf(
                            "payloadSize" to event.payload.size,
                            "sequence" to event.sequence,
                        ) + mediaFailurePayloadFields(error),
                    ).toString(),
                )
            }
    }

    private fun startNetworkMonitoring() {
        networkStatusCollector = lifecycleScope.launch(Dispatchers.IO) {
            networkMonitor.snapshot
                .collect { snapshot -> handleNetworkStatus(snapshot) }
        }
    }

    private fun startHttpCommandPolling() {
        val intervalMillis = httpCommandPollIntervalMillis(runtimeConfig) ?: return
        httpCommandPollJob = lifecycleScope.launch(Dispatchers.IO) {
            httpCommandPollFlow(intervalMillis).collect {
                if (networkMonitor.snapshot.value.hasValidatedInternet ||
                    isLoopbackBackendUrl(runtimeConfig.backendBaseUrl)
                ) {
                    CommunicationWorker.enqueue(this@HelmetService)
                }
            }
        }
    }

    private suspend fun handleNetworkStatus(snapshot: ConnectivitySnapshot) {
        networkPrompt(lastPromptedNetworkValidated, snapshot.hasValidatedInternet)?.let {
            lastPromptedNetworkValidated = snapshot.hasValidatedInternet
            feedback.announceNetwork(snapshot.hasValidatedInternet)
        }
        if (stateMachine.current() == HelmetOperationalState.IDLE ||
            stateMachine.current() == HelmetOperationalState.OFFLINE_READY
        ) {
            stateMachine.transitionTo(
                if (snapshot.hasValidatedInternet) HelmetOperationalState.IDLE
                else HelmetOperationalState.OFFLINE_READY,
            )
        }
        eventStore.record(
            eventType = "NETWORK_STATUS_CHANGED",
            severity = EventSeverity.INFO,
            payloadJson = JSONObject(
                mapOf(
                    "state" to snapshot.state.name,
                    "transports" to snapshot.transports.map { it.name },
                    "metered" to snapshot.metered,
                    "roaming" to snapshot.roaming,
                    "interfaceName" to snapshot.interfaceName,
                    "downstreamKbps" to snapshot.downstreamKbps,
                    "upstreamKbps" to snapshot.upstreamKbps,
                    "dnsServers" to snapshot.dnsServers,
                ),
            ).toString(),
        )
        if (snapshot.hasValidatedInternet) {
            MediaUploadWorker.enqueue(this)
            TrackUploadWorker.enqueue(this)
            CommunicationWorker.enqueue(this)
            SafetyAlertWorker.enqueue(this)
        }
        callMediaCoordinator.setLowBandwidthMode(shouldUseCallLowBandwidthMode(snapshot))
        publishStatus("NETWORK_STATUS_CHANGED")
    }

    private suspend fun handleLocationFix(fix: LocationFix) = locationProcessingMutex.withLock {
        if (fix.isMock) {
            eventStore.record(
                eventType = "MOCK_LOCATION_REJECTED",
                severity = EventSeverity.MEDIUM,
                payloadJson = JSONObject(mapOf("provider" to fix.provider, "fixId" to fix.fixId)).toString(),
            )
            publishStatus("MOCK_LOCATION_REJECTED")
            return@withLock
        }
        val point = trackStore.record(fix) ?: return@withLock
        val currentForSelection = lastLocationFix?.takeIf {
            isLocationFixValidForReceiverState(it, rtkController.status.value.lastFixQuality)
        }
        val selectedForRealtime = shouldSelectRealtimeLocationFix(
            current = currentForSelection,
            candidate = fix,
            observedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            maximumCandidateAgeNanos = MAX_REALTIME_LOCATION_AGE_MILLIS * 1_000_000L,
            preferredSourceHoldNanos = PREFERRED_LOCATION_SOURCE_HOLD_MILLIS * 1_000_000L,
        )
        if (selectedForRealtime) lastLocationFix = fix
        eventStore.record(
            eventType = "LOCATION_FIX_RECORDED",
            severity = EventSeverity.INFO,
            payloadJson = JSONObject(
                mapOf(
                    "messageId" to point.messageId,
                    "sequence" to point.sequence,
                    "fixId" to fix.fixId,
                    "source" to fix.source.name,
                    "quality" to fix.quality.name,
                    "horizontalAccuracyMeters" to fix.horizontalAccuracyMeters,
                    "satellitesUsed" to fix.gnss.satellitesUsed,
                    "satellitesVisible" to fix.gnss.satellitesVisible,
                    "selectedForRealtime" to selectedForRealtime,
                    "isMock" to false,
                ),
            ).toString(),
        )
        val transitions = if (selectedForRealtime) geofenceEngine.evaluate(fix) else emptyList()
        transitions.forEach { transition -> handleGeofenceTransition(transition, fix) }
        publishStatus(transitions.lastOrNull()?.let { "GEOFENCE_${it.type.name}" } ?: "LOCATION_FIX_RECORDED")
        TrackUploadWorker.enqueue(this)
    }

    private suspend fun handleGeofenceTransition(transition: GeofenceTransition, fix: LocationFix) {
        val isViolation = transition.type == GeofenceTransitionType.EXIT
        if (isViolation) feedback.announceAlarm("GEOFENCE_EXIT", simulated = false)
        val alertId = GeofenceAlertFactory.alertId(deviceId, transition.geofenceId)
        val previousAlertActive = safetyStore.latestAlert(alertId)?.active == true
        val alert = GeofenceAlertFactory.create(deviceId, transition, fix, previousAlertActive)
        val messageId = alert?.messageId ?: "geofence-transition-${UUID.randomUUID()}"
        eventStore.record(
            eventType = "GEOFENCE_${transition.type.name}",
            severity = if (isViolation) EventSeverity.HIGH else EventSeverity.INFO,
            payloadJson = JSONObject(
                mapOf(
                    "geofenceId" to transition.geofenceId,
                    "fixId" to transition.fixId,
                    "occurredAtEpochMillis" to transition.occurredAtEpochMillis,
                    "distanceMeters" to transition.distanceMeters,
                    "quality" to fix.quality.name,
                    "isMock" to false,
                    "alertId" to alert?.alertId,
                    "messageId" to alert?.messageId,
                    "finalHardwareEvidence" to false,
                ),
            ).toString(),
            messageId = messageId,
            occurredAtEpochMillis = transition.occurredAtEpochMillis,
        )
        if (alert != null && safetyStore.recordAlert(alert)) SafetyAlertWorker.enqueue(this)
    }

    private suspend fun handleCallRequest(
        relatedEventId: String,
        simulated: Boolean,
        plannedCallId: String,
    ): Boolean {
        val existedBeforeAttempt = callStore.find(plannedCallId) != null
        val call = commitCallOutboxAndSchedule(
            commit = {
                callStore.createOutgoing(
                    deviceId = deviceId,
                    relatedEventId = relatedEventId,
                    simulated = simulated,
                    callId = plannedCallId,
                )
            },
            schedule = { CommunicationWorker.enqueue(this, CommunicationWorkTrigger.DURABLE_COMMIT) },
        ) ?: run {
            val active = callStore.active()
            runCatching {
                eventStore.record(
                    eventType = "CALL_REQUEST_REJECTED_ACTIVE_CALL",
                    severity = EventSeverity.MEDIUM,
                    payloadJson = JSONObject(
                        mapOf(
                            "activeCallId" to active?.callId,
                            "activeState" to active?.state?.name,
                            "simulated" to simulated,
                        ),
                    ).toString(),
                )
            }
            runCatching { publishStatus("CALL_REQUEST_REJECTED_ACTIVE_CALL") }
            return false
        }
        if (existedBeforeAttempt) {
            return true
        }
        runCatching { recordCallStatePromptRequest(call.callId, CallState.REQUESTED) }
        runCatching {
            eventStore.record(
                eventType = if (simulated) "SIMULATED_CALL_REQUESTED" else "CALL_REQUESTED",
                severity = EventSeverity.INFO,
                payloadJson = JSONObject(
                    mapOf(
                        "callId" to call.callId,
                        "state" to call.state.name,
                        "mediaMode" to call.mediaMode.name,
                        "simulated" to simulated,
                        "finalHardwareEvidence" to false,
                    ),
                ).toString(),
            )
        }.onFailure { error ->
            StructuredLogger.warn(
                event = "call_request_audit_not_persisted",
                fields = mapOf("callId" to call.callId, "errorType" to error.javaClass.name),
            )
        }
        runCatching { publishStatus(if (simulated) "SIMULATED_CALL_REQUESTED" else "CALL_REQUESTED") }
        return true
    }

    private fun startCallStateMonitoring() {
        val gate = CallStateObservationGate()
        val activeTracker = ActiveCallObservationTracker()
        callStateCollector = lifecycleScope.launch(Dispatchers.IO) {
            callStore.observeActive().collect { activeCall ->
                activeTracker.activeChanged(activeCall)?.let { previousCallId ->
                    val previousCall = callStore.find(previousCallId)
                    if (
                        previousCall != null &&
                        previousCall.state in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED) &&
                        gate.shouldHandle(previousCall)
                    ) {
                        handleCallStateSignal(previousCall.callId, previousCall.state)
                    }
                }
                if (activeCall != null && gate.shouldHandle(activeCall)) {
                    handleCallStateSignal(activeCall.callId, activeCall.state)
                }
            }
        }
    }

    private suspend fun handleCallStateSignal(callId: String, state: CallState) =
        callStateHandlingMutex.withLock {
            val call = callStore.find(callId) ?: return@withLock
            if (call.state != state) return@withLock
            val key = Triple(call.callId, call.state, call.stateSequence)
            if (key == lastHandledCallStateKey) return@withLock
            lastHandledCallStateKey = key
            val target = when (state) {
                CallState.REQUESTED, CallState.RINGING -> HelmetOperationalState.CALLING
                CallState.ACCEPTED, CallState.CONNECTING, CallState.CONNECTED -> HelmetOperationalState.IN_CALL
                CallState.REJECTED, CallState.ENDED, CallState.FAILED -> idleState()
            }
            val current = stateMachine.current()
            val shouldTransition = state !in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED) ||
                current == HelmetOperationalState.CALLING || current == HelmetOperationalState.IN_CALL
            val transitionAccepted = !shouldTransition || stateMachine.transitionTo(target)
            if (!transitionAccepted) {
                eventStore.record(
                    eventType = "CALL_STATE_RUNTIME_REJECTED",
                    severity = EventSeverity.HIGH,
                    payloadJson = JSONObject(
                        mapOf(
                            "callId" to callId,
                            "callState" to state.name,
                            "operationalState" to current.name,
                            "targetOperationalState" to target.name,
                        ),
                    ).toString(),
                )
                publishStatus("CALL_STATE_RUNTIME_REJECTED")
                return@withLock
            }
            recordCallStatePromptRequest(callId, state)
            when (state) {
                CallState.ACCEPTED, CallState.CONNECTING, CallState.CONNECTED -> try {
                    callMediaCoordinator.start(callId, RuntimeConfigStore(this).load())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    handleCallMediaStartFailure(callId, error)
                }
                CallState.REJECTED, CallState.ENDED, CallState.FAILED ->
                    callMediaCoordinator.stop(
                        callId = callId,
                        reason = "CALL_${state.name}",
                        terminalFailure = state == CallState.FAILED,
                        terminalError = call.lastReason.takeIf { state == CallState.FAILED },
                    )
                else -> Unit
            }
            publishStatus("CALL_${state.name}")
        }

    private suspend fun handleCallMediaStartFailure(callId: String, error: Throwable) {
        val errorType = error.javaClass.simpleName
        eventStore.record(
            eventType = "WEBRTC_START_REJECTED",
            severity = EventSeverity.HIGH,
            payloadJson = JSONObject(
                mapOf("callId" to callId) + mediaFailurePayloadFields(error),
            ).toString(),
        )
        val current = callStore.find(callId)
        if (current != null && current.state !in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED)) {
            runCatching {
                commitCallOutboxAndSchedule(
                    commit = {
                        callStore.transition(
                            callId,
                            CallState.FAILED,
                            "WEBRTC_START_$errorType",
                        )
                    },
                    schedule = { CommunicationWorker.enqueue(this, CommunicationWorkTrigger.DURABLE_COMMIT) },
                )
            }.onFailure { persistenceError ->
                StructuredLogger.error(
                    event = "webrtc_start_failure_not_persisted",
                    error = persistenceError,
                    fields = mapOf("callId" to callId, "errorType" to persistenceError.javaClass.name),
                )
            }
        }
        callMediaCoordinator.stop(
            callId = callId,
            reason = "START_REJECTED",
            terminalFailure = true,
            terminalError = errorType,
        )
        publishStatus("WEBRTC_START_REJECTED")
    }

    private fun recordCallStatePromptRequest(callId: String, state: CallState) {
        lifecycleScope.launch(Dispatchers.IO) {
            eventStore.record(
                eventType = "CALL_STATUS_PROMPT_REQUESTED",
                severity = EventSeverity.INFO,
                payloadJson = JSONObject(mapOf("callId" to callId, "state" to state.name)).toString(),
            )
            val result = feedback.announceCallState(state)
            eventStore.record(
                eventType = if (result.ttsCompleted) {
                    "CALL_STATUS_PROMPT_COMPLETED"
                } else {
                    "CALL_STATUS_PROMPT_FAILED"
                },
                severity = if (result.ttsCompleted) EventSeverity.INFO else EventSeverity.MEDIUM,
                payloadJson = JSONObject(
                    mapOf(
                        "callId" to callId,
                        "state" to state.name,
                        "message" to result.message,
                        "ttsCompleted" to result.ttsCompleted,
                        "fallbackToneStarted" to result.fallbackToneStarted,
                        "error" to result.error,
                    ),
                ).toString(),
            )
        }
    }

    private suspend fun capturePhoto(relatedEventId: String) {
        runCatching {
            check(!voiceMessageRecorder.isRecording) { "voice message recording is active" }
            mediaController.capturePhoto(relatedEventId, locationForMedia())
        }
            .onSuccess { asset ->
                feedback.announceMediaResult("PHOTO", success = true)
                eventStore.record(
                    eventType = "PHOTO_CAPTURED",
                    severity = EventSeverity.INFO,
                    payloadJson = mediaPayload(asset).toString(),
                )
                publishStatus("PHOTO_CAPTURED")
                MediaUploadWorker.enqueue(this)
            }
            .onFailure { error ->
                feedback.announceMediaResult("PHOTO", success = false)
                recordMediaFailure("PHOTO_CAPTURE_FAILED", error)
            }
    }

    private fun startMediaCaptureEventCollection() {
        mediaCaptureCollector = lifecycleScope.launch(Dispatchers.IO) {
            mediaController.events.collect(::handleAsyncMediaCaptureEvent)
        }
        voiceCaptureCollector = lifecycleScope.launch(Dispatchers.IO) {
            voiceMessageRecorder.events.collect(::handleAsyncMediaCaptureEvent)
        }
    }

    private suspend fun handleAsyncMediaCaptureEvent(event: MediaCaptureEvent) {
        val success = event is MediaCaptureEvent.Finalized
        val reason = when (event) {
            is MediaCaptureEvent.Finalized -> event.reason
            is MediaCaptureEvent.Failed -> event.reason
        }
        val policy = asyncMediaEventPolicy(event.kind, reason, success) ?: return
        mediaActionMutex.withLock {
            when (event.kind) {
                MediaKind.VIDEO -> {
                    videoRecordingLimitJob?.cancel()
                    videoRecordingLimitJob = null
                }
                MediaKind.VOICE -> {
                    voiceRecordingLimitJob?.cancel()
                    voiceRecordingLimitJob = null
                }
                MediaKind.PHOTO -> return@withLock
            }
            stateMachine.transitionTo(idleState())
            feedback.announceMediaResult(policy.feedbackKind, success)
            when (event) {
                is MediaCaptureEvent.Finalized -> {
                    eventStore.record(
                        eventType = policy.eventType,
                        severity = policy.severity,
                        payloadJson = mediaPayload(event.asset)
                            .put("automaticStop", true)
                            .put("terminationReason", reason.name)
                            .toString(),
                    )
                    MediaUploadWorker.enqueue(this)
                }
                is MediaCaptureEvent.Failed -> {
                    eventStore.record(
                        eventType = policy.eventType,
                        severity = policy.severity,
                        payloadJson = JSONObject(
                            mapOf(
                                "assetId" to event.assetId,
                                "kind" to event.kind.name,
                                "terminationReason" to reason.name,
                                "errorType" to event.errorType,
                            ),
                        ).toString(),
                    )
                }
            }
            publishStatus(policy.eventType)
        }
    }

    private suspend fun toggleVideoRecording(relatedEventId: String) {
        if (!mediaController.isRecording) {
            val previousState = stateMachine.current()
            runCatching {
                check(!voiceMessageRecorder.isRecording) { "voice message recording is active" }
                check(stateMachine.transitionTo(HelmetOperationalState.RECORDING)) {
                    "runtime rejected video recording transition"
                }
                mediaController.startRecording(relatedEventId, locationForMedia())
            }
                .onSuccess { assetId ->
                    scheduleVideoRecordingLimit()
                    feedback.announceMediaResult("VIDEO_START", success = true)
                    eventStore.record(
                        eventType = "VIDEO_RECORDING_STARTED",
                        severity = EventSeverity.INFO,
                        payloadJson = JSONObject(mapOf("assetId" to assetId, "relatedEventId" to relatedEventId)).toString(),
                    )
                    publishStatus("VIDEO_RECORDING_STARTED")
                }
                .onFailure { error ->
                    stateMachine.transitionTo(previousState)
                    feedback.announceMediaResult("VIDEO_START", success = false)
                    recordMediaFailure("VIDEO_RECORDING_START_FAILED", error)
                }
        } else {
            stopVideoRecording(VideoRecordingStopCause.USER)
        }
    }

    private suspend fun stopVideoRecording(cause: VideoRecordingStopCause) {
        if (cause == VideoRecordingStopCause.USER) videoRecordingLimitJob?.cancel()
        videoRecordingLimitJob = null
        runCatching { mediaController.stopRecording() }
            .onSuccess { asset ->
                stateMachine.transitionTo(idleState())
                feedback.announceMediaResult("VIDEO_STOP", success = true)
                eventStore.record(
                    eventType = cause.eventType,
                    severity = if (cause == VideoRecordingStopCause.STORAGE_RESERVE) {
                        EventSeverity.MEDIUM
                    } else {
                        EventSeverity.INFO
                    },
                    payloadJson = mediaPayload(asset)
                        .put("automaticStop", cause != VideoRecordingStopCause.USER)
                        .put("stopCause", cause.name)
                        .put("maximumDurationMillis", VideoStoragePolicy.MAX_RECORDING_DURATION_MILLIS)
                        .toString(),
                )
                publishStatus(cause.eventType)
                MediaUploadWorker.enqueue(this)
            }
            .onFailure { error ->
                stateMachine.transitionTo(idleState())
                feedback.announceMediaResult("VIDEO_STOP", success = false)
                recordMediaFailure("VIDEO_RECORDING_STOP_FAILED", error)
            }
    }

    private fun scheduleVideoRecordingLimit() {
        videoRecordingLimitJob?.cancel()
        videoRecordingLimitJob = lifecycleScope.launch(Dispatchers.IO) {
            val deadline = SystemClock.elapsedRealtime() + VideoStoragePolicy.MAX_RECORDING_DURATION_MILLIS
            while (currentCoroutineContext().isActive) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                delay(minOf(VideoStoragePolicy.STORAGE_CHECK_INTERVAL_MILLIS, remaining.coerceAtLeast(1)))
                val cause = when {
                    SystemClock.elapsedRealtime() >= deadline -> VideoRecordingStopCause.DURATION_LIMIT
                    !VideoStoragePolicy.hasRuntimeReserve(StatFs(filesDir.absolutePath).availableBytes) ->
                        VideoRecordingStopCause.STORAGE_RESERVE
                    else -> null
                }
                if (cause != null) {
                    videoRecordingLimitJob = null
                    mediaActionMutex.withLock {
                        if (mediaController.isRecording) stopVideoRecording(cause)
                    }
                    return@launch
                }
            }
        }
    }

    private suspend fun startVoiceMessageRecording() {
        val relatedEventId = UUID.randomUUID().toString()
        val previousState = stateMachine.current()
        runCatching {
            check(!mediaController.isRecording) { "video recording is active" }
            check(stateMachine.current() in setOf(HelmetOperationalState.IDLE, HelmetOperationalState.OFFLINE_READY)) {
                "runtime is not idle"
            }
            check(stateMachine.transitionTo(HelmetOperationalState.RECORDING)) {
                "runtime rejected voice recording transition"
            }
            voiceMessageRecorder.start(
                relatedEventId = relatedEventId,
                locationFix = locationForMedia(),
                callId = null,
            )
        }.onSuccess { assetId ->
            scheduleVoiceRecordingLimit()
            feedback.announceMediaResult("VOICE_START", success = true)
            eventStore.record(
                eventType = "VOICE_MESSAGE_RECORDING_STARTED",
                severity = EventSeverity.INFO,
                payloadJson = JSONObject(
                    mapOf(
                        "assetId" to assetId,
                        "relatedEventId" to relatedEventId,
                        "maximumDurationMillis" to AndroidVoiceMessageRecorder.MAX_DURATION_MILLIS,
                    ),
                ).toString(),
            )
            publishStatus("VOICE_MESSAGE_RECORDING_STARTED")
        }.onFailure { error ->
            stateMachine.transitionTo(previousState)
            feedback.announceMediaResult("VOICE_START", success = false)
            recordMediaFailure("VOICE_MESSAGE_RECORDING_START_FAILED", error)
        }
    }

    private suspend fun stopVoiceMessageRecording(automatic: Boolean) {
        if (!automatic) voiceRecordingLimitJob?.cancel()
        voiceRecordingLimitJob = null
        runCatching { voiceMessageRecorder.stop() }
            .onSuccess { asset ->
                stateMachine.transitionTo(idleState())
                feedback.announceMediaResult("VOICE_STOP", success = true)
                eventStore.record(
                    eventType = if (automatic) "VOICE_MESSAGE_RECORDING_LIMIT_REACHED" else "VOICE_MESSAGE_RECORDED",
                    severity = EventSeverity.INFO,
                    payloadJson = mediaPayload(asset)
                        .put("automaticStop", automatic)
                        .put("maximumDurationMillis", AndroidVoiceMessageRecorder.MAX_DURATION_MILLIS)
                        .toString(),
                )
                publishStatus(
                    if (automatic) "VOICE_MESSAGE_RECORDING_LIMIT_REACHED" else "VOICE_MESSAGE_RECORDED",
                )
                MediaUploadWorker.enqueue(this)
            }
            .onFailure { error ->
                stateMachine.transitionTo(idleState())
                feedback.announceMediaResult("VOICE_STOP", success = false)
                recordMediaFailure("VOICE_MESSAGE_RECORDING_STOP_FAILED", error)
            }
    }

    private fun scheduleVoiceRecordingLimit() {
        voiceRecordingLimitJob?.cancel()
        voiceRecordingLimitJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(AndroidVoiceMessageRecorder.MAX_DURATION_MILLIS)
            voiceRecordingLimitJob = null
            mediaActionMutex.withLock {
                if (voiceMessageRecorder.isRecording) stopVoiceMessageRecording(automatic = true)
            }
        }
    }

    private suspend fun recordMediaFailure(eventType: String, error: Throwable) {
        eventStore.record(
            eventType = eventType,
            severity = EventSeverity.MEDIUM,
            payloadJson = JSONObject(mediaFailurePayloadFields(error)).toString(),
        )
        publishStatus(eventType)
    }

    private fun mediaPayload(asset: MediaAsset): JSONObject = JSONObject(mediaEventPayloadFields(asset))

    private fun idleState(): HelmetOperationalState =
        if (isNetworkAvailable()) HelmetOperationalState.IDLE else HelmetOperationalState.OFFLINE_READY

    private fun locationForMedia(): LocationFix? {
        return effectiveLocationFix(
            rtkFixQuality = rtkController.status.value.lastFixQuality,
            maximumAgeMillis = MAX_MEDIA_LOCATION_AGE_MILLIS,
        )
    }

    private fun effectiveLocationFix(
        rtkFixQuality: com.example.helmet.core.model.FixQuality,
        maximumAgeMillis: Long,
    ): LocationFix? = usableLocationFix(
        fix = lastLocationFix,
        externalReceiverQuality = rtkFixQuality,
        observedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
        observedAtEpochMillis = timeAuthority.nowEpochMillis(),
        maximumAgeMillis = maximumAgeMillis,
    )

    private fun isNetworkAvailable(): Boolean {
        return networkMonitor.snapshot.value.hasValidatedInternet
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "Helmet runtime",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Smart helmet service")
            .setContentText(text)
            .setOngoing(true)
            .build()

    companion object {
        private const val NOTIFICATION_CHANNEL = "helmet-runtime"
        private const val NOTIFICATION_ID = 1001
        private const val LOCAL_FEEDBACK_PREFERENCES = "local_feedback"
        private const val LAST_BATTERY_PROMPT_THRESHOLD = "last_battery_prompt_threshold"
        private const val MAX_MEDIA_LOCATION_AGE_MILLIS = 120_000L
        private const val MAX_REALTIME_LOCATION_AGE_MILLIS = 15_000L
        private const val PREFERRED_LOCATION_SOURCE_HOLD_MILLIS = 5_000L
        private const val STATUS_HEARTBEAT_INTERVAL_MILLIS = 60_000L
        private const val MQTT_INITIAL_RETRY_MILLIS = 1_000L
        private const val MQTT_MAX_RETRY_MILLIS = 60_000L
        private const val MAX_MQTT_ERROR_LENGTH = 1_024
        private const val MAX_RECOVERY_ATTEMPT = 1_000_000
        private const val MAX_RECOVERY_SOURCE_LENGTH = 64
        private const val HARDWARE_KEY_RECOVERY_BATCH_SIZE = 1_000
        private val RECOVERY_SOURCE_PATTERN = Regex("[A-Za-z0-9._-]+")
        const val ACTION_SIMULATE = "com.example.helmet.action.SIMULATE"
        const val ACTION_CALL_STATE_UPDATED = "com.example.helmet.action.CALL_STATE_UPDATED"
        const val ACTION_RECOVERY = "com.example.helmet.action.RECOVERY"
        const val ACTION_VOICE_MESSAGE_START = "com.example.helmet.action.VOICE_MESSAGE_START"
        const val ACTION_VOICE_MESSAGE_STOP = "com.example.helmet.action.VOICE_MESSAGE_STOP"
        const val ACTION_PREPARE_SHUTDOWN = "com.example.helmet.action.PREPARE_SHUTDOWN"
        const val ACTION_SAFETY_ALERT_DELIVERED = "com.example.helmet.action.SAFETY_ALERT_DELIVERED"
        const val EXTRA_SIMULATED_INPUT = "simulated_input"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALL_STATE = "call_state"
        const val EXTRA_RECOVERY_SOURCE = "recovery_source"
        const val EXTRA_RECOVERY_ATTEMPT = "recovery_attempt"
        const val EXTRA_SAFETY_ALERT_MESSAGE_ID = "safety_alert_message_id"

        fun startIntent(context: Context): Intent = Intent(context, HelmetService::class.java)

        fun recoveryIntent(context: Context, source: String, attempt: Int = 0): Intent =
            startIntent(context)
                .setAction(ACTION_RECOVERY)
                .putExtra(EXTRA_RECOVERY_SOURCE, source)
                .putExtra(EXTRA_RECOVERY_ATTEMPT, attempt)

        fun simulateIntent(context: Context, input: SimulatedInput): Intent =
            startIntent(context).setAction(ACTION_SIMULATE).putExtra(EXTRA_SIMULATED_INPUT, input.name)

        fun voiceMessageStartIntent(context: Context): Intent =
            startIntent(context).setAction(ACTION_VOICE_MESSAGE_START)

        fun voiceMessageStopIntent(context: Context): Intent =
            startIntent(context).setAction(ACTION_VOICE_MESSAGE_STOP)

        fun shutdownIntent(context: Context): Intent =
            startIntent(context).setAction(ACTION_PREPARE_SHUTDOWN)

        fun safetyAlertDeliveredIntent(context: Context, messageId: String): Intent {
            require(messageId.isNotBlank())
            return startIntent(context)
                .setAction(ACTION_SAFETY_ALERT_DELIVERED)
                .putExtra(EXTRA_SAFETY_ALERT_MESSAGE_ID, messageId)
        }

        fun callStateIntent(context: Context, callId: String, state: CallState): Intent =
            startIntent(context)
                .setAction(ACTION_CALL_STATE_UPDATED)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_CALL_STATE, state.name)
    }
}
