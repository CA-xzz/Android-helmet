package com.example.helmet.service.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.HelmetOperationalState
import com.example.helmet.core.model.HelmetStateMachine
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.MediaStore
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.TrackStore
import com.example.helmet.data.local.CallStore
import com.example.helmet.data.local.BroadcastStore
import com.example.helmet.data.local.SafetyStore
import com.example.helmet.feature.camera.Camera2MediaController
import com.example.helmet.feature.camera.CameraCapabilities
import com.example.helmet.feature.camera.MediaCaptureController
import com.example.helmet.feature.connectivity.AndroidNetworkMonitor
import com.example.helmet.feature.connectivity.ConnectivitySnapshot
import com.example.helmet.feature.location.AndroidLocationController
import com.example.helmet.feature.location.LocationCapabilityInspector
import com.example.helmet.feature.location.GeofenceEngine
import com.example.helmet.core.model.GeofenceTransition
import com.example.helmet.core.model.GeofenceTransitionType
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.core.model.SafetySensorTelemetry
import com.example.helmet.hardware.api.BoundHardwareGateway
import com.example.helmet.hardware.api.HardwareGateway
import com.example.helmet.hardware.api.HardwareEvent
import com.example.helmet.hardware.api.HardwareCommand
import com.example.helmet.hardware.api.HardwareAlarmOrigin
import com.example.helmet.hardware.api.SimulatedHardwareGateway
import com.example.helmet.hardware.api.SimulatedInput
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslSafetyConfigCodec
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.communication.sync.MqttConnectionState
import com.example.helmet.communication.sync.MqttConnectionStatus
import com.example.helmet.communication.sync.MqttDeviceSession
import com.example.helmet.communication.sync.MqttDeviceSessionRegistry
import com.example.helmet.data.local.DeviceCommandStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal fun statusHeartbeatFlow(intervalMillis: Long): Flow<Unit> = flow {
    require(intervalMillis > 0)
    while (currentCoroutineContext().isActive) {
        delay(intervalMillis)
        emit(Unit)
    }
}

internal fun shouldPollCommandsOverHttp(config: RuntimeConfig): Boolean =
    config.backendBaseUrl.isNotBlank() &&
        config.backendBearerToken.isNotBlank() &&
        config.mqttBrokerUri.isBlank() &&
        config.mqttClientCertificateAlias.isBlank()

internal fun deviceCommandTransportName(config: RuntimeConfig): String = when {
    config.backendBaseUrl.isBlank() || config.backendBearerToken.isBlank() -> "UNCONFIGURED"
    config.mqttBrokerUri.isBlank() && config.mqttClientCertificateAlias.isBlank() -> "HTTP_POLL"
    config.mqttBrokerUri.isNotBlank() && config.mqttClientCertificateAlias.isNotBlank() -> "MQTT"
    else -> "INVALID_MQTT_CONFIGURATION"
}

internal fun httpCommandPollFlow(intervalMillis: Long): Flow<Unit> = flow {
    require(intervalMillis > 0)
    while (currentCoroutineContext().isActive) {
        emit(Unit)
        delay(intervalMillis)
    }
}

class HelmetService : LifecycleService() {
    private lateinit var eventStore: EventStore
    private lateinit var mediaStore: MediaStore
    private lateinit var trackStore: TrackStore
    private lateinit var callStore: CallStore
    private lateinit var broadcastStore: BroadcastStore
    private lateinit var safetyStore: SafetyStore
    private lateinit var safetySampleProcessor: SafetySampleProcessor
    private lateinit var callMediaCoordinator: CallMediaCoordinator
    private lateinit var deviceStatusOutbox: DeviceStatusOutbox
    private lateinit var timeAuthority: DeviceTimeAuthority
    private lateinit var deviceId: String
    private lateinit var hardware: HardwareGateway
    private var simulatedHardware: SimulatedHardwareGateway? = null
    private lateinit var feedback: LocalFeedbackController
    private lateinit var mediaController: MediaCaptureController
    private lateinit var cameraCapabilities: CameraCapabilities
    private lateinit var locationController: AndroidLocationController
    private lateinit var rtkController: RtkCorrectionController
    private lateinit var localIntercomController: LocalIntercomController
    private lateinit var runtimeConfig: RuntimeConfig
    private lateinit var networkMonitor: AndroidNetworkMonitor
    private lateinit var geofenceEngine: GeofenceEngine
    private var activeGeofenceCount = 0
    private var lastLocationFix: com.example.helmet.core.model.LocationFix? = null
    private val stateMachine = HelmetStateMachine()
    private val mediaActionMutex = Mutex()
    private val statusPublishMutex = Mutex()
    private var eventCollector: Job? = null
    private var statusCollector: Job? = null
    private var statusHeartbeatJob: Job? = null
    private var timeAdjustmentCollector: Job? = null
    private var locationFixCollector: Job? = null
    private var locationStatusCollector: Job? = null
    private var rtkFixCollector: Job? = null
    private var rtkStatusCollector: Job? = null
    private var localIntercomStatusCollector: Job? = null
    private var networkStatusCollector: Job? = null
    private var httpCommandPollJob: Job? = null
    private var mqttSessionJob: Job? = null
    private var mqttSession: MqttDeviceSession? = null
    private var appVersion = "unknown"
    private var configRevision = 0L
    private var safetyConfigSentVersion: Int? = null
    private val safetyOutputSequence = AtomicInteger(0x8000)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Starting"))

        val database = HelmetDatabase.get(this)
        timeAuthority = DeviceTimeAuthorityProvider.get(this)
        StructuredLogger.installClock(timeAuthority::nowEpochMillis)
        eventStore = EventStore(database, timeAuthority::nowEpochMillis)
        mediaStore = MediaStore(database)
        trackStore = TrackStore(database, timeAuthority::nowEpochMillis)
        callStore = CallStore(database, timeAuthority::nowEpochMillis)
        broadcastStore = BroadcastStore(database)
        safetyStore = SafetyStore(database)
        callMediaCoordinator = CallMediaCoordinator(
            this,
            lifecycleScope,
            callStore,
            eventStore,
            timeAuthority::nowEpochMillis,
            SystemClock::elapsedRealtime,
        )
        deviceStatusOutbox = DeviceStatusOutbox(this)
        deviceId = DeviceIdentityStore(this).getOrCreateDeviceId()
        val storedRuntimeConfig = RuntimeConfigStore(this).load()
        runtimeConfig = if (!BuildConfig.ALLOW_SIMULATED_HARDWARE && storedRuntimeConfig.simulatorEnabled) {
            StructuredLogger.warn(
                event = "release_simulated_hardware_rejected",
                fields = mapOf("configRevision" to storedRuntimeConfig.revision),
            )
            storedRuntimeConfig.copy(simulatorEnabled = false)
        } else {
            storedRuntimeConfig
        }
        mediaController = Camera2MediaController(
            this,
            mediaStore,
            deviceId,
            personId = runtimeConfig.personId,
            wallClock = timeAuthority::nowEpochMillis,
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
        activeGeofenceCount = runtimeConfig.geofences.size
        configRevision = runtimeConfig.revision
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        appVersion = "${packageInfo.versionName ?: "unknown"} (${packageInfo.longVersionCode})"
        CrashCapture.install(eventStore)
        startMqttSession(database)
        stateMachine.transitionTo(HelmetOperationalState.SELF_TEST)

        feedback = LocalFeedbackController(this)
        hardware = if (runtimeConfig.simulatorEnabled) {
            SimulatedHardwareGateway(lifecycleScope).also { simulatedHardware = it }
        } else {
            BoundHardwareGateway(
                context = this,
                devicePath = runtimeConfig.hardwareDevicePath,
                baudRate = runtimeConfig.hardwareBaudRate,
            )
        }
        rtkController = RtkCorrectionController(
            scope = lifecycleScope,
            deviceId = deviceId,
            config = runtimeConfig.rtk,
            hardwareStatus = { hardware.status.value },
            correctionSink = hardware::send,
            epochClock = timeAuthority::nowEpochMillis,
        )
        localIntercomController = LocalIntercomController(
            config = runtimeConfig.localIntercom,
            hardwareStatus = { hardware.status.value },
            commandSink = hardware::send,
        )
        startRtkCollection()
        startLocalIntercomCollection()
        startTimeAdjustmentCollection()
        startStatusHeartbeat()
        eventCollector = lifecycleScope.launch(Dispatchers.IO) {
            hardware.events.collect(::handleHardwareEvent)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val startFailure = runCatching { hardware.start() }.exceptionOrNull()
            if (startFailure != null) {
                stateMachine.transitionTo(HelmetOperationalState.FAULT)
                eventStore.record(
                    eventType = "HARDWARE_START_FAILED",
                    severity = EventSeverity.HIGH,
                    payloadJson = JSONObject(
                        mapOf(
                            "simulated" to hardware.status.value.simulated,
                            "error" to startFailure.toString(),
                        ),
                    ).toString(),
                )
                publishStatus("HARDWARE_START_FAILED")
                return@launch
            }
            statusCollector = lifecycleScope.launch(Dispatchers.IO) {
                hardware.status
                    .drop(1)
                    .distinctUntilChangedBy { status ->
                        listOf(
                            status.connected,
                            status.linkState,
                            status.lastError,
                        )
                    }
                    .collect {
                        sendSafetyConfigIfConnected(runtimeConfig)
                        if (hardware.status.value.connected) {
                            localIntercomController.ensureJoined()
                        } else {
                            localIntercomController.onHardwareDisconnected()
                        }
                        publishStatus("HARDWARE_STATUS_CHANGED")
                    }
            }
            sendSafetyConfigIfConnected(runtimeConfig)
            rtkController.start()
            localIntercomController.ensureJoined()
            networkMonitor.start()
            startNetworkMonitoring()
            startHttpCommandPolling()
            val networkAvailable = isNetworkAvailable()
            stateMachine.transitionTo(
                if (networkAvailable) HelmetOperationalState.IDLE else HelmetOperationalState.OFFLINE_READY,
            )
            eventStore.record(
                eventType = "RUNTIME_STARTED",
                severity = EventSeverity.INFO,
                payloadJson = JSONObject(
                    mapOf(
                        "deviceId" to deviceId,
                        "networkAvailable" to networkAvailable,
                        "hardwareMode" to if (runtimeConfig.simulatorEnabled) "SIMULATED" else "UART",
                        "hardwareDevicePath" to runtimeConfig.hardwareDevicePath,
                        "hardwareBaudRate" to runtimeConfig.hardwareBaudRate,
                        "appVersion" to appVersion,
                        "androidVersion" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        "configRevision" to configRevision,
                        "cameraCount" to cameraCapabilities.cameraCount,
                        "cameraId" to cameraCapabilities.selectedCameraId,
                        "maximumJpegSize" to cameraCapabilities.maximumJpegSize?.let { "${it.width}x${it.height}" },
                        "selectedVideoSize" to cameraCapabilities.selectedVideoSize?.let { "${it.width}x${it.height}" },
                        "supportsThirteenMegapixelPhoto" to cameraCapabilities.supportsThirteenMegapixelPhoto,
                        "supports1080pVideo" to cameraCapabilities.supports1080pVideo,
                        "locationProviders" to locationCapabilities.providers.joinToString(","),
                        "locationEnabledProviders" to locationCapabilities.enabledProviders.joinToString(","),
                        "hasGnssProvider" to locationCapabilities.hasGnssProvider,
                        "rtkEnabled" to runtimeConfig.rtk.enabled,
                        "ntripEndpoint" to rtkController.status.value.endpoint,
                        "localIntercomEnabled" to runtimeConfig.localIntercom.enabled,
                        "localIntercomGroupId" to runtimeConfig.localIntercom.groupId,
                        "localIntercomChannel" to runtimeConfig.localIntercom.channel,
                        "localIntercomKeySlot" to runtimeConfig.localIntercom.keySlot,
                        "geofenceCount" to activeGeofenceCount,
                        "deviceCommandTransport" to deviceCommandTransportName(runtimeConfig),
                        "httpCommandPollIntervalMillis" to if (shouldPollCommandsOverHttp(runtimeConfig)) {
                            HTTP_COMMAND_POLL_INTERVAL_MILLIS
                        } else {
                            null
                        },
                    ),
                ).toString(),
            )
            publishStatus("RUNTIME_STARTED")
            MediaUploadWorker.enqueue(this@HelmetService)
            TrackUploadWorker.enqueue(this@HelmetService)
            CommunicationWorker.enqueue(this@HelmetService)
            SafetyAlertWorker.enqueue(this@HelmetService)
            startLocationCollection()
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
        return super.onStartCommand(intent, flags, startId).let { Service.START_STICKY }
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
        localIntercomStatusCollector?.cancel()
        networkStatusCollector?.cancel()
        httpCommandPollJob?.cancel()
        mqttSessionJob?.cancel()
        mqttSession?.let { session ->
            MqttDeviceSessionRegistry.unregister(session)
            session.close()
        }
        mqttSession = null
        callMediaCoordinator.close()
        locationController.close()
        rtkController.close()
        networkMonitor.close()
        mediaController.close()
        feedback.close()
        lifecycleScope.launch { hardware.stop() }
        super.onDestroy()
    }

    private fun startMqttSession(database: HelmetDatabase) {
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
        val commandStore = DeviceCommandStore(database)
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
                        onCommand = { command ->
                            val maximumSequence = commandStore.maxSequence(deviceId)
                            require(command.serverSequence <= maximumSequence + 1) {
                                "MQTT command sequence contains a gap"
                            }
                            commandStore.receive(command)
                            CommunicationWorker.enqueue(this@HelmetService)
                        },
                        onReady = { CommunicationWorker.enqueue(this@HelmetService) },
                        onStatus = ::recordMqttStatus,
                        wallClock = timeAuthority::nowEpochMillis,
                    )
                    mqttSession = candidate
                    MqttDeviceSessionRegistry.register(candidate)
                    candidate.start()
                    return@launch
                } catch (error: Throwable) {
                    candidate?.let { failed ->
                        MqttDeviceSessionRegistry.unregister(failed)
                        failed.close()
                        if (mqttSession === failed) mqttSession = null
                    }
                    eventStore.record(
                        eventType = "MQTT_INITIAL_CONNECTION_FAILED",
                        severity = EventSeverity.HIGH,
                        payloadJson = JSONObject(
                            mapOf(
                                "retryDelayMillis" to retryDelayMillis,
                                "error" to error.toString().take(MAX_MQTT_ERROR_LENGTH),
                            ),
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
            is HardwareEvent.Key -> {
                val simulated = hardware.status.value.simulated
                val useLocalIntercom = event.input == SimulatedInput.CALL &&
                    runtimeConfig.localIntercom.enabled &&
                    runtimeConfig.localIntercom.fallbackWhenInternetUnavailable &&
                    !networkMonitor.snapshot.value.hasValidatedInternet
                if (!useLocalIntercom) applyKeyStateChange(event.input)
                feedback.handleKey(event.input, simulated)
                val eventPrefix = if (simulated) "SIMULATED_KEY" else "HARDWARE_KEY"
                val keyEventId = UUID.randomUUID().toString()
                eventStore.record(
                    eventType = "${eventPrefix}_${event.input.name}",
                    severity = EventSeverity.INFO,
                    payloadJson = JSONObject(
                        mapOf("simulated" to simulated, "monotonicMillis" to event.monotonicMillis),
                    ).toString(),
                    messageId = keyEventId,
                )
                publishStatus("${eventPrefix}_${event.input.name}")
                if (event.input == SimulatedInput.PHOTO_SHORT || event.input == SimulatedInput.RECORD_LONG) {
                    lifecycleScope.launch(Dispatchers.IO) { handleMediaAction(event.input, keyEventId) }
                }
                if (event.input == SimulatedInput.CALL) {
                    if (useLocalIntercom) {
                        handleLocalIntercomToggle(keyEventId, simulated)
                    } else {
                        handleCallRequest(keyEventId, simulated)
                    }
                }
            }
            is HardwareEvent.Alarm -> {
                handleSafetyAlarm(event)
            }
            is HardwareEvent.SensorSample -> {
                handleSafetySample(event)
            }
            is HardwareEvent.ProtocolFrame -> {
                if (event.type == HslMessageType.RTK_NMEA) {
                    rtkController.acceptReceiverBytes(event.payload)
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

    private suspend fun sendSafetyConfigIfConnected(runtimeConfig: RuntimeConfig) {
        val config = runtimeConfig.safetyThresholds
        if (!hardware.status.value.connected || safetyConfigSentVersion == config.version) return
        runCatching {
            hardware.send(
                HardwareCommand(
                    type = HslMessageType.SET_CONFIG,
                    flags = HslFlags.ACK_REQUIRED,
                    sequence = config.version,
                    payload = HslSafetyConfigCodec.encode(config),
                ),
            )
        }.onSuccess {
            safetyConfigSentVersion = config.version
            eventStore.record(
                eventType = "SAFETY_CONFIG_SENT",
                severity = EventSeverity.INFO,
                payloadJson = JSONObject(
                    mapOf(
                        "configVersion" to config.version,
                        "payloadBytes" to HslSafetyConfigCodec.ENCODED_SIZE,
                        "simulated" to hardware.status.value.simulated,
                        "ackRequired" to true,
                        "finalHardwareEvidence" to false,
                    ),
                ).toString(),
            )
        }.onFailure { error ->
            eventStore.record(
                eventType = "SAFETY_CONFIG_SEND_FAILED",
                severity = EventSeverity.MEDIUM,
                payloadJson = JSONObject(
                    mapOf(
                        "configVersion" to config.version,
                        "error" to error.toString(),
                    ),
                ).toString(),
            )
        }
    }

    private suspend fun handleSafetySample(event: HardwareEvent.SensorSample) {
        val persisted = persistSafetySample(event)
        if (!persisted) return
        val processing = runCatching { safetySampleProcessor.process(event) }
            .getOrElse { error ->
                eventStore.record(
                    eventType = "ANDROID_SAFETY_SAMPLE_REJECTED",
                    severity = EventSeverity.HIGH,
                    payloadJson = JSONObject(
                        mapOf(
                            "sampleReference" to event.sampleReference,
                            "monotonicMillis" to event.monotonicMillis,
                            "simulated" to event.simulated,
                            "error" to error.toString(),
                        ),
                    ).toString(),
                )
                return
            }
        if (processing.engineReset) {
            eventStore.record(
                eventType = "ANDROID_SAFETY_DETECTION_RESET",
                severity = EventSeverity.INFO,
                payloadJson = JSONObject(
                    mapOf(
                        "sampleReference" to event.sampleReference,
                        "monotonicMillis" to event.monotonicMillis,
                        "reason" to "module_monotonic_clock_reset_or_wrap",
                    ),
                ).toString(),
            )
        }
        processing.alarms.forEach { alarm ->
            handleSafetyAlarm(alarm.event, alarm.toAnalysisJson())
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
            ),
        )

    private suspend fun handleSafetyAlarm(event: HardwareEvent.Alarm, analysis: JSONObject? = null) {
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
        val fix = lastLocationFix?.takeIf { it.hasPosition && !it.isMock }
        val severity = runCatching { EventSeverity.valueOf(event.severity) }.getOrDefault(EventSeverity.HIGH)
        val occurredAt = timeAuthority.nowEpochMillis()
        val persistence = persistAndAcknowledgeSafetyAlarm(
            sequence = event.sequence,
            persist = {
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
                    payloadJson = JSONObject(
                        mapOf(
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
                            "locationFixType" to alert.locationFixType,
                            "latitude" to alert.latitude,
                            "longitude" to alert.longitude,
                            "evidenceRelatedEventId" to messageId,
                            "finalHardwareEvidence" to false,
                        ),
                    ).toString(),
                    messageId = messageId,
                    occurredAtEpochMillis = occurredAt,
                )
                inserted
            },
            acknowledge = hardware::acknowledge,
        )
        if (!persistence.persisted) return
        val inserted = persistence.value == true
        if (event.active && event.alarmType in setOf("FALL", "IMPACT")) {
            stateMachine.transitionTo(HelmetOperationalState.SOS)
        }
        if (inserted && event.active) feedback.announceAlarm(event.alarmType, event.simulated)
        if (inserted) {
            requestSafetyOutput(event)
            SafetyAlertWorker.enqueue(this)
            if (event.active && !event.simulated &&
                event.alarmType in setOf("FALL", "IMPACT", "VIOLENT_SHAKE")
            ) {
                lifecycleScope.launch(Dispatchers.IO) { captureSafetyEvidence(messageId) }
            }
        }
        publishStatus("${eventPrefix}_${event.alarmType}_$state")
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
            "reasonCode" to decision.reasonCode,
            "measuredValue" to decision.measuredValue,
            "thresholdValue" to decision.thresholdValue,
            "accumulatedValue" to decision.accumulatedValue,
        ),
    )

    private suspend fun requestSafetyOutput(event: HardwareEvent.Alarm) {
        val sequence = safetyOutputSequence.getAndUpdate { current ->
            if (current >= 0xFFFF) 0x8000 else current + 1
        }
        val command = safetyOutputCommand(event, sequence) ?: return
        val result = runCatching {
            check(hardware.status.value.connected && !hardware.status.value.simulated) {
                "external output module is unavailable"
            }
            hardware.send(command)
        }
        eventStore.record(
            eventType = if (result.isSuccess) "LOCAL_SAFETY_OUTPUT_REQUESTED" else "LOCAL_SAFETY_OUTPUT_FAILED",
            severity = if (result.isSuccess) EventSeverity.INFO else EventSeverity.HIGH,
            payloadJson = JSONObject(
                mapOf(
                    "alarmId" to event.alarmId,
                    "alarmType" to event.alarmType,
                    "active" to event.active,
                    "localActions" to event.localActions,
                    "sequence" to sequence,
                    "ackRequired" to true,
                    "error" to result.exceptionOrNull()?.toString(),
                ),
            ).toString(),
        )
    }

    private suspend fun captureSafetyEvidence(relatedEventId: String) = mediaActionMutex.withLock {
        runCatching { mediaController.capturePhoto(relatedEventId, locationForMedia()) }
            .onSuccess { asset ->
                eventStore.record(
                    eventType = "SAFETY_EVIDENCE_PHOTO_CAPTURED",
                    severity = EventSeverity.INFO,
                    payloadJson = mediaPayload(asset).toString(),
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
                            "error" to error.toString(),
                            "cameraCount" to cameraCapabilities.cameraCount,
                        ),
                    ).toString(),
                )
            }
    }

    private suspend fun publishStatus(lastEventType: String) = statusPublishMutex.withLock {
        cameraCapabilities = mediaController.inspect()
        val persistedEventCount = eventStore.totalCount()
        val pendingMediaCount = mediaStore.pendingCount()
        val pendingTrackCount = trackStore.pendingCount()
        val pendingCallCount = callStore.pendingCount()
        val pendingBroadcastReceiptCount = broadcastStore.pendingReceiptCount()
        val pendingSafetyAlertCount = safetyStore.pendingAlertCount()
        val retainedSafetySampleCount = safetyStore.sampleCount(deviceId)
        val latestCall = callStore.latest()
        val locationStatus = locationController.status.value
        val rtkStatus = rtkController.status.value
        val localIntercomStatus = localIntercomController.status.value
        val networkStatus = networkMonitor.snapshot.value
        val networkAvailable = isNetworkAvailable()
        val batteryStatus = BatteryStatusReader.read(this)
        val timeReading = timeAuthority.read()
        val activeCall = latestCall?.takeUnless {
            it.state in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED)
        }
        RuntimeStatus.update {
            it.copy(
                deviceId = deviceId,
                state = stateMachine.current(),
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
                cameraCount = cameraCapabilities.cameraCount,
                pendingMediaCount = pendingMediaCount,
                locationState = if (lastLocationFix?.source == com.example.helmet.core.model.LocationSource.EXTERNAL_NMEA) {
                    "TRACKING"
                } else {
                    locationStatus.state.name
                },
                locationProvider = lastLocationFix?.provider ?: locationStatus.selectedProvider ?: "none",
                locationFixQuality = lastLocationFix?.quality?.name ?: locationStatus.lastFixQuality.name,
                locationHasPosition = lastLocationFix?.hasPosition == true,
                rtkState = rtkStatus.state.name,
                rtkCorrectionFrames = rtkStatus.correctionFrames,
                rtkCorrectionBytes = rtkStatus.correctionBytes,
                rtkLastError = rtkStatus.lastError,
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
            val trustedFix = lastLocationFix?.takeIf { it.hasPosition && !it.isMock }
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

    private fun applyKeyStateChange(input: SimulatedInput) {
        val target = when (input) {
            SimulatedInput.CALL -> HelmetOperationalState.CALLING
            SimulatedInput.SOS -> HelmetOperationalState.SOS
            else -> null
        } ?: return

        val previous = stateMachine.current()
        val accepted = stateMachine.transitionTo(target)
        StructuredLogger.info(
            event = "key_state_transition",
            fields = mapOf(
                "input" to input.name,
                "from" to previous.name,
                "to" to target.name,
                "accepted" to accepted,
            ),
        )
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

    private suspend fun handleLocalIntercomToggle(relatedEventId: String, simulated: Boolean) {
        val before = localIntercomController.status.value.state
        val accepted = localIntercomController.toggleTransmit()
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
                    "groupId" to runtimeConfig.localIntercom.groupId,
                    "channel" to runtimeConfig.localIntercom.channel,
                    "keySlot" to runtimeConfig.localIntercom.keySlot,
                    "error" to after.lastError,
                    "finalHardwareEvidence" to false,
                ),
            ).toString(),
        )
        publishStatus(if (accepted) "LOCAL_INTERCOM_COMMAND_SENT" else "LOCAL_INTERCOM_COMMAND_FAILED")
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
                            "error" to error.toString(),
                        ),
                    ).toString(),
                )
            }
    }

    private fun startNetworkMonitoring() {
        networkStatusCollector = lifecycleScope.launch(Dispatchers.IO) {
            networkMonitor.snapshot
                .drop(1)
                .collect { snapshot -> handleNetworkStatus(snapshot) }
        }
    }

    private fun startHttpCommandPolling() {
        if (!shouldPollCommandsOverHttp(runtimeConfig)) return
        httpCommandPollJob = lifecycleScope.launch(Dispatchers.IO) {
            httpCommandPollFlow(HTTP_COMMAND_POLL_INTERVAL_MILLIS).collect {
                if (networkMonitor.snapshot.value.hasValidatedInternet ||
                    isLoopbackBackendUrl(runtimeConfig.backendBaseUrl)
                ) {
                    CommunicationWorker.enqueue(this@HelmetService)
                }
            }
        }
    }

    private suspend fun handleNetworkStatus(snapshot: ConnectivitySnapshot) {
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
        val lowBandwidth = snapshot.metered ||
            snapshot.downstreamKbps?.let { it in 1 until 1_000 } == true ||
            snapshot.upstreamKbps?.let { it in 1 until 500 } == true
        callMediaCoordinator.setLowBandwidthMode(lowBandwidth)
        publishStatus("NETWORK_STATUS_CHANGED")
    }

    private suspend fun handleLocationFix(fix: LocationFix) {
        if (fix.isMock) {
            eventStore.record(
                eventType = "MOCK_LOCATION_REJECTED",
                severity = EventSeverity.MEDIUM,
                payloadJson = JSONObject(mapOf("provider" to fix.provider, "fixId" to fix.fixId)).toString(),
            )
            publishStatus("MOCK_LOCATION_REJECTED")
            return
        }
        val point = trackStore.record(fix) ?: return
        lastLocationFix = fix
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
                    "latitude" to fix.latitude,
                    "longitude" to fix.longitude,
                    "horizontalAccuracyMeters" to fix.horizontalAccuracyMeters,
                    "satellitesUsed" to fix.gnss.satellitesUsed,
                    "satellitesVisible" to fix.gnss.satellitesVisible,
                    "isMock" to false,
                ),
            ).toString(),
        )
        val transitions = geofenceEngine.evaluate(fix)
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
                    "latitude" to fix.latitude,
                    "longitude" to fix.longitude,
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

    private suspend fun handleCallRequest(relatedEventId: String, simulated: Boolean) {
        val call = callStore.createOutgoing(
            deviceId = deviceId,
            relatedEventId = relatedEventId,
            simulated = simulated,
        ) ?: return
        recordCallStatePromptRequest(call.callId, CallState.REQUESTED)
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
        publishStatus(if (simulated) "SIMULATED_CALL_REQUESTED" else "CALL_REQUESTED")
        CommunicationWorker.enqueue(this)
    }

    private suspend fun handleCallStateSignal(callId: String, state: CallState) {
        val target = when (state) {
            CallState.REQUESTED, CallState.RINGING -> HelmetOperationalState.CALLING
            CallState.ACCEPTED, CallState.CONNECTING, CallState.CONNECTED -> HelmetOperationalState.IN_CALL
            CallState.REJECTED, CallState.ENDED, CallState.FAILED -> idleState()
        }
        val current = stateMachine.current()
        if (state !in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED) ||
            current == HelmetOperationalState.CALLING || current == HelmetOperationalState.IN_CALL
        ) {
            stateMachine.transitionTo(target)
        }
        recordCallStatePromptRequest(callId, state)
        when (state) {
            CallState.ACCEPTED -> runCatching {
                callMediaCoordinator.start(callId, RuntimeConfigStore(this).load())
            }.onFailure { error ->
                eventStore.record(
                    eventType = "WEBRTC_START_REJECTED",
                    severity = EventSeverity.HIGH,
                    payloadJson = JSONObject(
                        mapOf("callId" to callId, "error" to error.toString()),
                    ).toString(),
                )
            }
            CallState.REJECTED, CallState.ENDED, CallState.FAILED ->
                callMediaCoordinator.stop(callId, "CALL_${state.name}")
            else -> Unit
        }
        publishStatus("CALL_${state.name}")
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

    private suspend fun handleMediaAction(input: SimulatedInput, relatedEventId: String) = mediaActionMutex.withLock {
        when (input) {
            SimulatedInput.PHOTO_SHORT -> capturePhoto(relatedEventId)
            SimulatedInput.RECORD_LONG -> toggleVideoRecording(relatedEventId)
            else -> Unit
        }
    }

    private suspend fun capturePhoto(relatedEventId: String) {
        runCatching { mediaController.capturePhoto(relatedEventId, locationForMedia()) }
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

    private suspend fun toggleVideoRecording(relatedEventId: String) {
        if (!mediaController.isRecording) {
            runCatching { mediaController.startRecording(relatedEventId, locationForMedia()) }
                .onSuccess { assetId ->
                    stateMachine.transitionTo(HelmetOperationalState.RECORDING)
                    feedback.announceMediaResult("VIDEO_START", success = true)
                    eventStore.record(
                        eventType = "VIDEO_RECORDING_STARTED",
                        severity = EventSeverity.INFO,
                        payloadJson = JSONObject(mapOf("assetId" to assetId, "relatedEventId" to relatedEventId)).toString(),
                    )
                    publishStatus("VIDEO_RECORDING_STARTED")
                }
                .onFailure { error ->
                    feedback.announceMediaResult("VIDEO_START", success = false)
                    recordMediaFailure("VIDEO_RECORDING_START_FAILED", error)
                }
        } else {
            runCatching { mediaController.stopRecording() }
                .onSuccess { asset ->
                    stateMachine.transitionTo(idleState())
                    feedback.announceMediaResult("VIDEO_STOP", success = true)
                    eventStore.record(
                        eventType = "VIDEO_RECORDED",
                        severity = EventSeverity.INFO,
                        payloadJson = mediaPayload(asset).toString(),
                    )
                    publishStatus("VIDEO_RECORDED")
                    MediaUploadWorker.enqueue(this)
                }
                .onFailure { error ->
                    stateMachine.transitionTo(idleState())
                    feedback.announceMediaResult("VIDEO_STOP", success = false)
                    recordMediaFailure("VIDEO_RECORDING_STOP_FAILED", error)
                }
        }
    }

    private suspend fun recordMediaFailure(eventType: String, error: Throwable) {
        eventStore.record(
            eventType = eventType,
            severity = EventSeverity.MEDIUM,
            payloadJson = JSONObject(
                mapOf(
                    "error" to error.toString(),
                    "cause" to generateSequence(error) { current -> current.cause }.last().toString(),
                    "cameraCount" to cameraCapabilities.cameraCount,
                    "cameraId" to cameraCapabilities.selectedCameraId,
                ),
            ).toString(),
        )
        publishStatus(eventType)
    }

    private fun mediaPayload(asset: com.example.helmet.core.model.MediaAsset): JSONObject = JSONObject(
        mapOf(
            "assetId" to asset.assetId,
            "kind" to asset.kind.name,
            "filePath" to asset.filePath,
            "mimeType" to asset.mimeType,
            "byteSize" to asset.byteSize,
            "sha256" to asset.sha256,
            "width" to asset.width,
            "height" to asset.height,
            "durationMillis" to asset.durationMillis,
            "deviceId" to asset.deviceId,
            "relatedEventId" to asset.relatedEventId,
            "personId" to asset.personId,
            "latitude" to asset.latitude,
            "longitude" to asset.longitude,
            "horizontalAccuracyMeters" to asset.horizontalAccuracyMeters,
            "locationFixType" to asset.locationFixType,
            "transferState" to asset.transferState.name,
        ),
    )

    private fun idleState(): HelmetOperationalState =
        if (isNetworkAvailable()) HelmetOperationalState.IDLE else HelmetOperationalState.OFFLINE_READY

    private fun locationForMedia(): LocationFix? {
        val fix = lastLocationFix ?: return null
        val ageMillis = timeAuthority.nowEpochMillis() - fix.occurredAtEpochMillis
        return fix.takeIf { it.hasPosition && !it.isMock && ageMillis in 0..MAX_MEDIA_LOCATION_AGE_MILLIS }
    }

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
        private const val MAX_MEDIA_LOCATION_AGE_MILLIS = 120_000L
        private const val STATUS_HEARTBEAT_INTERVAL_MILLIS = 60_000L
        private const val MQTT_INITIAL_RETRY_MILLIS = 1_000L
        private const val MQTT_MAX_RETRY_MILLIS = 60_000L
        private const val MAX_MQTT_ERROR_LENGTH = 1_024
        private const val MAX_RECOVERY_ATTEMPT = 1_000_000
        private const val MAX_RECOVERY_SOURCE_LENGTH = 64
        private const val HTTP_COMMAND_POLL_INTERVAL_MILLIS = 2_000L
        private val RECOVERY_SOURCE_PATTERN = Regex("[A-Za-z0-9._-]+")
        const val ACTION_SIMULATE = "com.example.helmet.action.SIMULATE"
        const val ACTION_CALL_STATE_UPDATED = "com.example.helmet.action.CALL_STATE_UPDATED"
        const val ACTION_RECOVERY = "com.example.helmet.action.RECOVERY"
        const val EXTRA_SIMULATED_INPUT = "simulated_input"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALL_STATE = "call_state"
        const val EXTRA_RECOVERY_SOURCE = "recovery_source"
        const val EXTRA_RECOVERY_ATTEMPT = "recovery_attempt"

        fun startIntent(context: Context): Intent = Intent(context, HelmetService::class.java)

        fun recoveryIntent(context: Context, source: String, attempt: Int = 0): Intent =
            startIntent(context)
                .setAction(ACTION_RECOVERY)
                .putExtra(EXTRA_RECOVERY_SOURCE, source)
                .putExtra(EXTRA_RECOVERY_ATTEMPT, attempt)

        fun simulateIntent(context: Context, input: SimulatedInput): Intent =
            startIntent(context).setAction(ACTION_SIMULATE).putExtra(EXTRA_SIMULATED_INPUT, input.name)

        fun callStateIntent(context: Context, callId: String, state: CallState): Intent =
            startIntent(context)
                .setAction(ACTION_CALL_STATE_UPDATED)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_CALL_STATE, state.name)
    }
}
