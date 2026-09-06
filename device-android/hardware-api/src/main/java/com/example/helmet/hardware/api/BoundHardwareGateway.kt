package com.example.helmet.hardware.api

import android.content.Context
import android.os.IBinder
import com.example.helmet.core.protocol.HslAck
import com.example.helmet.core.protocol.HslDuplicateWindow
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslFrame
import com.example.helmet.core.protocol.HslFrameCodec
import com.example.helmet.core.protocol.HslHeartbeat
import com.example.helmet.core.protocol.HslHeartbeatMonitor
import com.example.helmet.core.protocol.HslCapability
import com.example.helmet.core.protocol.HslHelloCodec
import com.example.helmet.core.protocol.HslLinkState
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslPayloadCodec
import com.example.helmet.core.protocol.HslReliableCommandTracker
import com.example.helmet.core.protocol.LocalIntercomPayloadCodec
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun hardwareFailureCode(error: Throwable): String = error.javaClass.name.take(160)

internal fun reliableFrameResult(frame: HslFrame): Int {
    if (frame.version != HslFrameCodec.SUPPORTED_VERSION) return HSL_RESULT_UNSUPPORTED_VERSION
    if (frame.flags and HSL_KNOWN_FLAGS.inv() != 0) return HSL_RESULT_FIELD_OUT_OF_RANGE
    if (frame.type != HslMessageType.ACK && frame.flags and (HslFlags.ACK or HslFlags.ERROR) != 0) {
        return HSL_RESULT_FIELD_OUT_OF_RANGE
    }
    if (frame.type == HslMessageType.ACK && frame.flags and HslFlags.ACK == 0) {
        return HSL_RESULT_FIELD_OUT_OF_RANGE
    }
    if (frame.payload.size !in expectedInboundPayloadSizes(frame.type)) {
        return if (isSupportedInboundType(frame.type)) {
            HSL_RESULT_INVALID_LENGTH
        } else {
            HSL_RESULT_UNSUPPORTED_TYPE
        }
    }
    return runCatching {
        when (frame.type) {
            HslMessageType.HEARTBEAT -> HslPayloadCodec.decodeHeartbeat(frame.payload).also { heartbeat ->
                require(heartbeat.protocolVersion == HslFrameCodec.SUPPORTED_VERSION)
            }
            HslMessageType.HELLO -> HslHelloCodec.decode(frame.payload).also {
                require(frame.flags and HslFlags.ACK_REQUIRED != 0) { "HELLO requires ACK_REQUIRED" }
            }
            HslMessageType.KEY_EVENT -> HslPayloadCodec.decodeKeyEvent(frame.payload).also { event ->
                require(frame.flags and HslFlags.ACK_REQUIRED != 0) { "KEY_EVENT requires ACK_REQUIRED" }
                require(event.eventId != 0L) { "key event ID must be non-zero" }
                require(event.key in 1..5) { "unsupported key" }
                require(event.action in 3..5) { "only completed key actions are supported" }
            }
            HslMessageType.SENSOR_SAMPLE -> HslPayloadCodec.decodeSensorSample(frame.payload).also { sample ->
                require(sample.sampleReference != 0L) { "sample reference must be non-zero" }
                require(sample.validFlags and HSL_SENSOR_VALID_FLAGS.inv() == 0) {
                    "unsupported sensor valid flags"
                }
            }
            HslMessageType.ALARM_EVENT -> {
                require(frame.flags and HslFlags.ACK_REQUIRED != 0) { "ALARM_EVENT requires ACK_REQUIRED" }
                HslPayloadCodec.decodeAlarmEvent(frame.payload)
            }
            HslMessageType.RTK_NMEA -> {
                require(frame.flags and HslFlags.ACK_REQUIRED == 0) {
                    "streamed RTK_NMEA does not support frame acknowledgement"
                }
                require(frame.payload.isNotEmpty()) { "RTK_NMEA payload is empty" }
            }
            HslMessageType.LOCAL_INTERCOM_STATUS -> LocalIntercomPayloadCodec.decodeStatus(frame.payload)
            HslMessageType.ACK -> {
                require(frame.flags and HslFlags.ACK_REQUIRED == 0) { "ACK cannot require ACK" }
                HslPayloadCodec.decodeAck(frame.payload).also { ack ->
                    require(ack.resultCode in HSL_RESULT_SUCCESS..HSL_RESULT_FIELD_OUT_OF_RANGE) {
                        "unsupported ACK result code"
                    }
                    require(
                        (ack.resultCode == HSL_RESULT_SUCCESS) ==
                            (frame.flags and HslFlags.ERROR == 0),
                    ) { "ACK ERROR flag does not match result code" }
                }
            }
            else -> return HSL_RESULT_UNSUPPORTED_TYPE
        }
    }.fold(
        onSuccess = { HSL_RESULT_SUCCESS },
        onFailure = { HSL_RESULT_FIELD_OUT_OF_RANGE },
    )
}

internal fun reliableAckFlags(resultCode: Int): Int =
    HslFlags.ACK or if (resultCode == HSL_RESULT_SUCCESS) 0 else HslFlags.ERROR

internal fun validatedAcknowledgement(frame: HslFrame): HslAck? =
    if (frame.type == HslMessageType.ACK && reliableFrameResult(frame) == HSL_RESULT_SUCCESS) {
        HslPayloadCodec.decodeAck(frame.payload)
    } else {
        null
    }

internal fun commandOutcomeForAcknowledgement(ack: HslAck): HardwareCommandOutcome =
    if (ack.resultCode == HSL_RESULT_SUCCESS) {
        HardwareCommandOutcome.ACKNOWLEDGED
    } else {
        HardwareCommandOutcome.REJECTED
    }

internal fun remoteModuleRestarted(previousUptimeMillis: Long?, currentUptimeMillis: Long): Boolean =
    previousUptimeMillis != null && currentUptimeMillis < previousUptimeMillis

internal fun helloHandshakeTimedOut(
    deadlineMillis: Long?,
    nowMillis: Long,
    compatibility: HardwareCompatibility,
): Boolean = deadlineMillis != null && nowMillis >= deadlineMillis &&
    compatibility == HardwareCompatibility.AWAITING_HELLO

internal fun nextPortReopenDelayMillis(
    currentDelayMillis: Long,
    initialDelayMillis: Long = 250L,
    maximumDelayMillis: Long = 30_000L,
): Long {
    require(currentDelayMillis >= 0 && initialDelayMillis > 0 && maximumDelayMillis >= initialDelayMillis)
    return if (currentDelayMillis == 0L) {
        initialDelayMillis
    } else if (currentDelayMillis >= maximumDelayMillis) {
        maximumDelayMillis
    } else {
        (currentDelayMillis * 2).coerceAtMost(maximumDelayMillis)
    }
}

internal fun requiresPersistenceBeforeAck(type: Int): Boolean =
    type == HslMessageType.ALARM_EVENT ||
        type == HslMessageType.KEY_EVENT ||
        type == HslMessageType.SENSOR_SAMPLE

internal fun heartbeatPayload(
    uptimeMillis: Long,
    state: HardwareOperationalState,
    errorCount: Int = 0,
): ByteArray = HslPayloadCodec.encodeHeartbeat(
    HslHeartbeat(
        uptimeMillis = uptimeMillis and 0xFFFF_FFFFL,
        operationalState = state.wireValue,
        protocolVersion = HslFrameCodec.SUPPORTED_VERSION,
        errorCount = errorCount,
    ),
)

private val ALARM_EVENT_PAYLOAD_SIZES = setOf(20, 60)
private val VARIABLE_PAYLOAD_SIZES = 1..HslFrameCodec.MAX_PAYLOAD_SIZE
private val ACK_PAYLOAD_SIZES = 3..HslFrameCodec.MAX_PAYLOAD_SIZE
internal val HSL_RESULT_SUCCESS = HardwareAcknowledgementResult.SUCCESS.wireValue
internal val HSL_RESULT_UNSUPPORTED_VERSION = HardwareAcknowledgementResult.UNSUPPORTED_VERSION.wireValue
internal val HSL_RESULT_UNSUPPORTED_TYPE = HardwareAcknowledgementResult.UNSUPPORTED_TYPE.wireValue
internal val HSL_RESULT_INVALID_LENGTH = HardwareAcknowledgementResult.INVALID_LENGTH.wireValue
internal val HSL_RESULT_FIELD_OUT_OF_RANGE = HardwareAcknowledgementResult.FIELD_OUT_OF_RANGE.wireValue
private const val HSL_KNOWN_FLAGS = HslFlags.ACK_REQUIRED or HslFlags.ACK or HslFlags.ERROR
private const val HSL_SENSOR_VALID_FLAGS = 0x001F

private fun isSupportedInboundType(type: Int): Boolean = type in setOf(
    HslMessageType.HEARTBEAT,
    HslMessageType.HELLO,
    HslMessageType.KEY_EVENT,
    HslMessageType.SENSOR_SAMPLE,
    HslMessageType.ALARM_EVENT,
    HslMessageType.RTK_NMEA,
    HslMessageType.LOCAL_INTERCOM_STATUS,
    HslMessageType.ACK,
)

private fun expectedInboundPayloadSizes(type: Int): Iterable<Int> = when (type) {
    HslMessageType.HEARTBEAT -> setOf(8)
    HslMessageType.HELLO -> setOf(HslHelloCodec.ENCODED_SIZE)
    HslMessageType.KEY_EVENT -> setOf(12)
    HslMessageType.SENSOR_SAMPLE -> setOf(40)
    HslMessageType.ALARM_EVENT -> ALARM_EVENT_PAYLOAD_SIZES
    HslMessageType.RTK_NMEA -> VARIABLE_PAYLOAD_SIZES
    HslMessageType.LOCAL_INTERCOM_STATUS -> setOf(LocalIntercomPayloadCodec.STATUS_SIZE)
    HslMessageType.ACK -> ACK_PAYLOAD_SIZES
    else -> emptySet()
}

private fun requiredInboundCapability(type: Int): Long = when (type) {
    HslMessageType.KEY_EVENT -> HslCapability.PHYSICAL_KEYS
    HslMessageType.SENSOR_SAMPLE -> HslCapability.MMA8452_ACCELEROMETER
    HslMessageType.RTK_NMEA -> HslCapability.RTK_NMEA
    HslMessageType.LOCAL_INTERCOM_STATUS -> HslCapability.LOCAL_INTERCOM
    else -> 0L
}

private fun requiredCommandCapability(type: Int): Long = when (type) {
    HslMessageType.SET_CONFIG -> HslCapability.MMA8452_ACCELEROMETER
    HslMessageType.SET_OUTPUT -> HslCapability.LED_OUTPUT
    HslMessageType.RTK_CORRECTION -> HslCapability.RTK_CORRECTION
    HslMessageType.LOCAL_INTERCOM_COMMAND -> HslCapability.LOCAL_INTERCOM
    else -> 0L
}

/** Admission and drain barrier for work owned by one provider transport generation. */
internal class ProviderTransportEpoch {
    private val lock = ReentrantLock()
    private val drained = lock.newCondition()
    private var active = false
    private var invalidated = false
    private var inFlight = 0

    fun activate() = lock.withLock {
        check(!invalidated) { "provider transport epoch is invalidated" }
        active = true
    }

    fun dispatch(action: () -> Unit): Boolean {
        lock.withLock {
            if (!active || invalidated) return false
            inFlight += 1
        }
        try {
            action()
            return true
        } finally {
            lock.withLock {
                inFlight -= 1
                check(inFlight >= 0) { "provider transport epoch underflow" }
                if (inFlight == 0) drained.signalAll()
            }
        }
    }

    fun invalidate() = lock.withLock {
        active = false
        invalidated = true
    }

    fun awaitDrained() = lock.withLock {
        while (inFlight != 0) drained.awaitUninterruptibly()
    }
}

internal fun <T : Any> isCurrentProviderTransportRequest(requested: T, current: T?): Boolean =
    requested === current

internal fun claimProviderPortOpenAttempt(attempted: AtomicBoolean): Boolean =
    attempted.compareAndSet(false, true)

/** Defers a reconnect until the owner of a detached transport has completed cleanup. */
internal class ProviderReconnectAfterCleanupGate {
    private val requested = AtomicBoolean()

    fun request() {
        requested.set(true)
    }

    fun reset() {
        requested.set(false)
    }

    fun consume(canReconnect: Boolean): Boolean = requested.getAndSet(false) && canReconnect
}

class BoundHardwareGateway(
    context: Context,
    private val devicePath: String,
    private val baudRate: Int = 115_200,
    private val requiredCapabilityMask: Long = HslModuleContract.REQUIRED_BASE_CAPABILITIES,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
) : HardwareGateway {
    private data class PendingCommand(
        val type: Int,
        val businessSequence: Int,
        val completion: CompletableDeferred<HardwareCommandResult>,
    )

    private data class ProviderTransport(
        val connection: HardwareProviderConnection,
        val binder: IBinder,
        val service: IHelmetHardwareService,
        val callback: IHelmetHardwareCallback,
        val epoch: ProviderTransportEpoch,
        val portOpenAttempted: AtomicBoolean,
        val portReopenRequested: AtomicBoolean,
        val deathRecipient: IBinder.DeathRecipient,
    )

    private data class ProviderStopSnapshot(
        val cancellableJobs: List<Job>,
        val disconnectJob: Job?,
        val transport: ProviderTransport?,
    )

    private val applicationContext = context.applicationContext
    private val mutableStatus = MutableStateFlow(HardwareStatus(simulated = false))
    private val eventQueue = HardwareEventQueue(EVENT_QUEUE_CAPACITY)
    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reliabilityLock = Any()
    private val tracker = HslReliableCommandTracker()
    private val pendingCommands = mutableMapOf<Int, PendingCommand>()
    private val lifecycleMutex = Mutex()
    private val sendMutex = Mutex()
    private val reliableSequenceAllocator = PersistentReliableSequenceAllocator(applicationContext)
    private val heartbeatMonitor = HslHeartbeatMonitor()
    private val duplicateWindow = HslDuplicateWindow()
    private val reliableInboundAdmission = ReliableInboundAdmission(
        alreadyAccepted = duplicateWindow::contains,
        rememberAccepted = { type, sequence -> check(duplicateWindow.accept(type, sequence)) },
    )
    private val transmitSequence = AtomicInteger()
    private val operationalState = AtomicInteger(HardwareOperationalState.INITIALIZING.wireValue)
    private val providerLock = Any()
    @Volatile
    private var remote: IHelmetHardwareService? = null
    @Volatile
    private var bound = false
    private var providerTransport: ProviderTransport? = null
    private var monitorJob: Job? = null
    private var reopenJob: Job? = null
    private var providerReconnectJob: Job? = null
    private val providerReconnectAfterCleanupGate = ProviderReconnectAfterCleanupGate()
    private var providerDisconnectJob: Job? = null
    private var lastHeartbeatTransmitMillis: Long? = null
    private var lastRemoteUptimeMillis: Long? = null
    private var lastRemoteBootSessionId: Long? = null
    private var helloDeadlineMillis: Long? = null

    init {
        reliableSequenceAllocator.recoveryIssue?.let { issue ->
            mutableStatus.value = mutableStatus.value.copy(lastError = issue)
        }
    }

    override val status: StateFlow<HardwareStatus> = mutableStatus
    override val events: Flow<HardwareEvent> = eventQueue.events

    private fun createProviderCallback(
        service: IHelmetHardwareService,
        epoch: ProviderTransportEpoch,
    ): IHelmetHardwareCallback = object : IHelmetHardwareCallback.Stub() {
        override fun onFrame(version: Int, flags: Int, type: Int, sequence: Int, payload: ByteArray) {
            epoch.dispatch {
                handleProviderFrame(service, epoch, version, flags, type, sequence, payload)
            }
        }

        override fun onLinkStateChanged(state: Int, detail: String) {
            epoch.dispatch { handleProviderLinkState(state) }
        }
    }

    private fun handleProviderFrame(
        service: IHelmetHardwareService,
        epoch: ProviderTransportEpoch,
        version: Int,
        flags: Int,
        type: Int,
        sequence: Int,
        payload: ByteArray,
    ) {
        val now = monotonicClock()
        val frame = HslFrame(
            version = version,
            flags = flags,
            type = type,
            sequence = sequence,
            payload = payload.copyOf(),
        )
        val resultCode = reliableFrameResult(frame)
        if (resultCode != HSL_RESULT_SUCCESS) {
            if (flags and HslFlags.ACK_REQUIRED != 0) {
                sendAcknowledgement(service, sequence, resultCode)
            }
            mutableStatus.value = mutableStatus.value.copy(
                lastError = "rejected external module frame type=$type result=$resultCode",
            )
            return
        }
        if (type == HslMessageType.ACK) {
            val ack = requireNotNull(validatedAcknowledgement(frame))
            val pending = synchronized(reliabilityLock) {
                val tracked = tracker.acknowledge(ack.acknowledgedSequence)
                val command = pendingCommands.remove(ack.acknowledgedSequence)
                command.takeIf { tracked }
            }
            if (pending == null) {
                mutableStatus.value = mutableStatus.value.copy(
                    lastError = "unexpected ACK sequence=${ack.acknowledgedSequence}",
                )
                return
            }
            val outcome = commandOutcomeForAcknowledgement(ack)
            pending.completion.complete(
                HardwareCommandResult(
                    outcome = outcome,
                    type = pending.type,
                    businessSequence = pending.businessSequence,
                    wireSequence = ack.acknowledgedSequence,
                    resultCode = ack.resultCode,
                    detailSize = ack.detail.size,
                ),
            )
            if (outcome == HardwareCommandOutcome.REJECTED) {
                mutableStatus.value = mutableStatus.value.copy(
                    lastError = "external module rejected command type=${pending.type} " +
                        "sequence=${ack.acknowledgedSequence} result=${ack.resultCode}",
                )
            }
            return
        }
        if (type == HslMessageType.HELLO) {
            handleModuleHello(service, frame, now)
            return
        }
        val requiredInboundCapability = requiredInboundCapability(type)
        val currentStatus = mutableStatus.value
        if (
            currentStatus.compatibility != HardwareCompatibility.COMPATIBLE ||
            requiredInboundCapability != 0L && currentStatus.moduleCapabilityMask and requiredInboundCapability == 0L
        ) {
            if (flags and HslFlags.ACK_REQUIRED != 0) {
                sendAcknowledgement(service, sequence, HSL_RESULT_FIELD_OUT_OF_RANGE)
            }
            mutableStatus.value = currentStatus.copy(
                connected = false,
                linkState = "AWAITING_COMPATIBILITY",
                lastError = "external module frame received before compatible HELLO type=$type",
            )
            return
        }
        val acknowledgement = if (
            flags and HslFlags.ACK_REQUIRED != 0 && requiresPersistenceBeforeAck(type)
        ) {
            HardwareAcknowledgement(sequence, epoch)
        } else {
            null
        }
        val mappedEvent = try {
            HslEventMapper.map(frame, acknowledgement)
        } catch (error: RuntimeException) {
            if (flags and HslFlags.ACK_REQUIRED != 0) {
                sendAcknowledgement(service, sequence, HSL_RESULT_FIELD_OUT_OF_RANGE)
            }
            mutableStatus.value = mutableStatus.value.copy(
                lastError = "rejected external module frame type=$type error=${error.javaClass.simpleName}",
            )
            return
        }
        val events = mutableListOf<HardwareEvent>()
        if (type == HslMessageType.HEARTBEAT) {
            runCatching { HslPayloadCodec.decodeHeartbeat(payload) }
                .onSuccess { heartbeat ->
                    synchronized(reliabilityLock) { heartbeatMonitor.onHeartbeat(now) }
                    val previousUptime = lastRemoteUptimeMillis
                    val restarted = remoteModuleRestarted(previousUptime, heartbeat.uptimeMillis)
                    lastRemoteUptimeMillis = heartbeat.uptimeMillis
                    if (restarted) {
                        synchronized(reliabilityLock) { duplicateWindow.reset() }
                    }
                    mutableStatus.value = mutableStatus.value.afterHeartbeat(now).let { status ->
                        if (restarted) {
                            status.copy(moduleSessionGeneration = status.moduleSessionGeneration + 1)
                        } else {
                            status
                        }
                    }
                    events += HardwareEvent.Heartbeat(monotonicMillis = now)
                }
                .onFailure { error ->
                    mutableStatus.value = mutableStatus.value.copy(
                        lastError = "invalid_external_module_heartbeat:${hardwareFailureCode(error)}",
                    )
                }
        }
        if (type == HslMessageType.RTK_NMEA || type == HslMessageType.LOCAL_INTERCOM_STATUS) {
            events += HardwareEvent.ProtocolFrame(
                monotonicMillis = now,
                version = version,
                flags = flags,
                type = type,
                sequence = sequence,
                payload = payload.copyOf(),
            )
        }
        mappedEvent?.let(events::add)
        val acknowledgementRequired = flags and HslFlags.ACK_REQUIRED != 0
        if (acknowledgementRequired && !requiresPersistenceBeforeAck(type)) {
            val admission = synchronized(reliabilityLock) {
                reliableInboundAdmission.admit(type, sequence) { offerEventBatch(events) }
            }
            if (admission.sendSuccessAcknowledgement) {
                sendAcknowledgement(service, sequence, HSL_RESULT_SUCCESS)
            }
        } else {
            offerEventBatch(events)
        }
    }

    private fun handleModuleHello(
        service: IHelmetHardwareService,
        frame: HslFrame,
        now: Long,
    ) {
        val hello = HslHelloCodec.decode(frame.payload)
        val evaluation = HslModuleContract.evaluate(hello, requiredCapabilityMask)
        val previousBootSessionId = lastRemoteBootSessionId
        val restarted = previousBootSessionId != null && previousBootSessionId != hello.bootSessionId
        lastRemoteBootSessionId = hello.bootSessionId
        helloDeadlineMillis = null
        if (restarted) {
            synchronized(reliabilityLock) { duplicateWindow.reset() }
        }
        val firmwareVersion = "${hello.firmwareMajor}.${hello.firmwareMinor}.${hello.firmwarePatch}"
        val contractVersion = "${hello.contractMajor}.${hello.contractMinor}.${hello.contractPatch}"
        mutableStatus.value = mutableStatus.value.copy(
            connected = false,
            compatibility = evaluation.compatibility,
            moduleContractVersion = contractVersion,
            moduleFirmwareVersion = firmwareVersion,
            moduleCapabilityMask = hello.capabilityMask,
            missingCapabilityMask = evaluation.missingCapabilityMask,
            moduleHardwareRevision = hello.hardwareRevision,
            moduleBootSessionId = hello.bootSessionId,
            linkState = if (evaluation.compatibility == HardwareCompatibility.COMPATIBLE) {
                "AWAITING_HEARTBEAT"
            } else {
                "INCOMPATIBLE"
            },
            lastError = when (evaluation.compatibility) {
                HardwareCompatibility.COMPATIBLE -> null
                HardwareCompatibility.CONTRACT_VERSION_MISMATCH ->
                    "HSL contract mismatch expected=${HslModuleContract.version} actual=$contractVersion"
                HardwareCompatibility.REQUIRED_CAPABILITIES_MISSING ->
                    "HSL required capabilities missing mask=0x${evaluation.missingCapabilityMask.toString(16)}"
                else -> "HSL compatibility handshake incomplete"
            },
            moduleSessionGeneration = mutableStatus.value.moduleSessionGeneration + if (restarted) 1 else 0,
        )
        val event = HardwareEvent.ModuleHello(
            monotonicMillis = now,
            contractVersion = HardwareContractVersion(
                hello.contractMajor,
                hello.contractMinor,
                hello.contractPatch,
            ),
            capabilityMask = hello.capabilityMask,
            firmwareVersion = firmwareVersion,
            hardwareRevision = hello.hardwareRevision,
            bootSessionId = hello.bootSessionId,
            compatible = evaluation.compatibility == HardwareCompatibility.COMPATIBLE,
            missingCapabilityMask = evaluation.missingCapabilityMask,
        )
        val admission = synchronized(reliabilityLock) {
            reliableInboundAdmission.admit(frame.type, frame.sequence) { offerEventBatch(listOf(event)) }
        }
        if (admission.sendSuccessAcknowledgement) {
            sendAcknowledgement(
                service,
                frame.sequence,
                if (evaluation.compatibility == HardwareCompatibility.COMPATIBLE) {
                    HSL_RESULT_SUCCESS
                } else {
                    HSL_RESULT_FIELD_OUT_OF_RANGE
                },
            )
        }
    }

    private fun handleProviderLinkState(state: Int) {
        val linkState = HslLinkState.entries.getOrNull(state) ?: HslLinkState.FAULT
        if (linkState == HslLinkState.CONNECTED) {
            val now = monotonicClock()
            synchronized(reliabilityLock) { heartbeatMonitor.onLinkStarted(now) }
            helloDeadlineMillis = now + HELLO_TIMEOUT_MILLIS
        }
        mutableStatus.value = mutableStatus.value.copy(
            connected = if (linkState == HslLinkState.CONNECTED) {
                mutableStatus.value.connected
            } else {
                false
            },
            linkState = if (linkState == HslLinkState.CONNECTED) "AWAITING_HELLO" else linkState.name,
            lastError = "native_hardware_fault".takeIf { linkState == HslLinkState.FAULT },
            compatibility = if (linkState == HslLinkState.CONNECTED) {
                HardwareCompatibility.AWAITING_HELLO
            } else {
                mutableStatus.value.compatibility
            },
        )
        if (linkState == HslLinkState.FAULT) schedulePortReopen("native_link_fault")
    }

    private fun connectProviderTransport() {
        val connection = HardwareProviderConnection.connect(applicationContext)
        val binder = connection.binder
        val service = connection.service
        val transportEpoch = ProviderTransportEpoch()
        val callback = createProviderCallback(service, transportEpoch)
        val deathObserved = AtomicBoolean()
        val deathRecipient = IBinder.DeathRecipient {
            deathObserved.set(true)
            onProviderBinderDied(binder)
        }
        var linked = false
        var callbackRegistered = false
        try {
            binder.linkToDeath(deathRecipient, 0)
            linked = true
            // A provider process may outlive a previous main-process callback. Close and drain that
            // session before registering this generation so it can never observe frames from a
            // UART session it did not open.
            service.closePort()
            service.registerCallback(callback)
            callbackRegistered = true
            val transport = ProviderTransport(
                connection,
                binder,
                service,
                callback,
                transportEpoch,
                AtomicBoolean(),
                AtomicBoolean(),
                deathRecipient,
            )
            synchronized(providerLock) {
                check(bound) { "hardware gateway stopped while provider was connecting" }
                check(providerTransport == null) { "hardware provider is already connected" }
                check(!deathObserved.get() && binder.isBinderAlive) {
                    "hardware provider died while connecting"
                }
                providerTransport = transport
                remote = service
                transportEpoch.activate()
            }
            check(!deathObserved.get() && binder.isBinderAlive) {
                "hardware provider died after connecting"
            }
            synchronized(reliabilityLock) { duplicateWindow.reset() }
            schedulePortReopen("provider_connected", immediate = true)
        } catch (error: Throwable) {
            synchronized(providerLock) {
                if (providerTransport?.binder === binder) {
                    providerTransport?.epoch?.invalidate()
                    providerTransport = null
                    remote = null
                } else {
                    transportEpoch.invalidate()
                }
            }
            if (callbackRegistered) runCatching { service.unregisterCallback(callback) }
            transportEpoch.awaitDrained()
            if (linked) runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            runCatching { connection.close() }
            throw error
        }
    }

    private fun onProviderBinderDied(deadBinder: IBinder) {
        lateinit var disconnectJob: Job
        synchronized(providerLock) {
            val current = providerTransport
            if (current?.binder !== deadBinder) return
            current.epoch.invalidate()
            providerTransport = null
            remote = null
            disconnectJob = gatewayScope.launch(start = CoroutineStart.LAZY) {
                withContext(NonCancellable) {
                    current.epoch.awaitDrained()
                    runCatching { current.connection.close() }
                    handleProviderDisconnected()
                    scheduleProviderReconnect()
                }
            }
            providerDisconnectJob = disconnectJob
        }
        disconnectJob.invokeOnCompletion {
            synchronized(providerLock) {
                if (providerDisconnectJob === disconnectJob) providerDisconnectJob = null
            }
        }
        disconnectJob.start()
    }

    private fun handleProviderDisconnected() {
        val staleReopenJob = synchronized(providerLock) {
            reopenJob.also { reopenJob = null }
        }
        staleReopenJob?.cancel()
        completePendingCommands(HardwareCommandOutcome.SEND_FAILED)
        synchronized(reliabilityLock) {
            heartbeatMonitor.reset()
            duplicateWindow.reset()
        }
        lastRemoteUptimeMillis = null
        lastRemoteBootSessionId = null
        helloDeadlineMillis = null
        mutableStatus.value = HardwareStatus(
            connected = false,
            simulated = false,
            linkState = "PROVIDER_DISCONNECTED",
            eventQueueOverflowCount = mutableStatus.value.eventQueueOverflowCount,
            moduleSessionGeneration = mutableStatus.value.moduleSessionGeneration + 1,
        )
    }

    private fun scheduleProviderReconnect() {
        if (!bound) return
        lateinit var reconnectJob: Job
        synchronized(providerLock) {
            if (!bound) return
            if (providerReconnectJob != null) {
                // A successful connect may be detached by death or port rotation before the job
                // observes its result. Only the cleanup owner may request the replacement; the
                // current job must not publish it before that cleanup has drained the old epoch.
                providerReconnectAfterCleanupGate.request()
                return
            }
            providerReconnectAfterCleanupGate.reset()
            reconnectJob = gatewayScope.launch(start = CoroutineStart.LAZY) {
                var delayMillis = PROVIDER_RECONNECT_INITIAL_DELAY_MILLIS
                while (isActive && bound && remote == null) {
                    delay(delayMillis)
                    val reconnected = runCatching { connectProviderTransport() }
                    // connectProviderTransport published one transport. If another owner detached
                    // it immediately, that owner schedules a replacement only after cleanup.
                    if (reconnected.isSuccess) return@launch
                    mutableStatus.value = mutableStatus.value.copy(
                        connected = false,
                        linkState = "PROVIDER_RECONNECT",
                        lastError = reconnected.exceptionOrNull()?.let(::hardwareFailureCode),
                    )
                    delayMillis = nextPortReopenDelayMillis(
                        currentDelayMillis = delayMillis,
                        initialDelayMillis = PROVIDER_RECONNECT_INITIAL_DELAY_MILLIS,
                        maximumDelayMillis = PROVIDER_RECONNECT_MAX_DELAY_MILLIS,
                    )
                }
            }
            providerReconnectJob = reconnectJob
        }
        reconnectJob.invokeOnCompletion {
            val reconnectAfterCleanup = synchronized(providerLock) {
                if (providerReconnectJob !== reconnectJob) return@synchronized false
                providerReconnectJob = null
                providerReconnectAfterCleanupGate.consume(bound && remote == null)
            }
            if (reconnectAfterCleanup) scheduleProviderReconnect()
        }
        reconnectJob.start()
    }

    private fun schedulePortReopen(reason: String, immediate: Boolean = false) {
        if (!bound || remote == null) return
        lateinit var scheduledJob: Job
        synchronized(providerLock) {
            if (!bound || remote == null) return
            if (reopenJob != null) {
                providerTransport?.takeIf { it.portOpenAttempted.get() }
                    ?.portReopenRequested
                    ?.set(true)
                return
            }
            scheduledJob = gatewayScope.launch(start = CoroutineStart.LAZY) {
                var delayMillis = if (immediate) 0L else REOPEN_INITIAL_DELAY_MILLIS
                while (isActive && bound) {
                    if (delayMillis > 0) delay(delayMillis)
                    val transport = currentProviderTransport() ?: return@launch
                    if (!claimProviderPortOpenAttempt(transport.portOpenAttempted)) {
                        rotateProviderTransportForPortReopen(transport, reason)
                        return@launch
                    }
                    val opened = runCatching {
                        check(
                            transport.epoch.dispatch {
                                runCatching { transport.service.closePort() }
                                transport.service.openPort(devicePath, baudRate)
                            },
                        ) { "hardware provider transport changed before port open" }
                    }
                    if (!isActive || !bound || currentProviderTransport() !== transport) {
                        return@launch
                    }
                    if (opened.isSuccess) {
                        if (transport.portReopenRequested.getAndSet(false)) {
                            rotateProviderTransportForPortReopen(transport, reason)
                            return@launch
                        }
                        lastRemoteUptimeMillis = null
                        lastRemoteBootSessionId = null
                        helloDeadlineMillis = monotonicClock() + HELLO_TIMEOUT_MILLIS
                        synchronized(reliabilityLock) {
                            heartbeatMonitor.onLinkStarted(monotonicClock())
                            duplicateWindow.reset()
                        }
                        mutableStatus.value = mutableStatus.value.copy(
                            connected = false,
                            linkState = "AWAITING_HELLO",
                            lastError = null,
                            compatibility = HardwareCompatibility.AWAITING_HELLO,
                            moduleContractVersion = null,
                            moduleFirmwareVersion = null,
                            moduleCapabilityMask = 0,
                            missingCapabilityMask = 0,
                            moduleHardwareRevision = null,
                            moduleBootSessionId = null,
                            moduleSessionGeneration = mutableStatus.value.moduleSessionGeneration + 1,
                        )
                        return@launch
                    }
                    mutableStatus.value = mutableStatus.value.copy(
                        connected = false,
                        linkState = "OPEN_RETRY",
                        lastError = "port reopen failed reason=$reason " +
                            "error=${opened.exceptionOrNull()?.javaClass?.simpleName}",
                    )
                    delayMillis = nextPortReopenDelayMillis(
                        currentDelayMillis = delayMillis,
                        initialDelayMillis = REOPEN_INITIAL_DELAY_MILLIS,
                        maximumDelayMillis = REOPEN_MAX_DELAY_MILLIS,
                    )
                }
            }
            reopenJob = scheduledJob
        }
        scheduledJob.invokeOnCompletion {
            synchronized(providerLock) {
                if (reopenJob === scheduledJob) reopenJob = null
            }
            val current = currentProviderTransport()
            if (
                bound && current != null &&
                (!current.portOpenAttempted.get() || current.portReopenRequested.getAndSet(false))
            ) {
                schedulePortReopen("provider_transport_rotated", immediate = true)
            }
        }
        scheduledJob.start()
    }

    private suspend fun rotateProviderTransportForPortReopen(
        expected: ProviderTransport,
        reason: String,
    ) {
        val detached = synchronized(providerLock) {
            if (!bound || providerTransport !== expected) return
            expected.epoch.invalidate()
            providerTransport = null
            remote = null
            expected
        }
        withContext(NonCancellable) {
            // The reopen job owns this detached transport. Stop may cancel and join this job, but
            // cleanup must finish before any replacement callback or UART session can be created.
            sendMutex.withLock { Unit }
            detached.epoch.awaitDrained()
            runCatching { detached.service.closePort() }
            runCatching { detached.service.unregisterCallback(detached.callback) }
            runCatching { detached.binder.unlinkToDeath(detached.deathRecipient, 0) }
            runCatching { detached.connection.close() }
            lastHeartbeatTransmitMillis = null
            lastRemoteUptimeMillis = null
            lastRemoteBootSessionId = null
            helloDeadlineMillis = null
            completePendingCommands(HardwareCommandOutcome.SEND_FAILED)
            synchronized(reliabilityLock) {
                heartbeatMonitor.reset()
                duplicateWindow.reset()
            }
            mutableStatus.value = HardwareStatus(
                connected = false,
                simulated = false,
                linkState = "PROVIDER_RECONNECT",
                lastError = "provider transport rotated reason=$reason",
                eventQueueOverflowCount = mutableStatus.value.eventQueueOverflowCount,
                moduleSessionGeneration = mutableStatus.value.moduleSessionGeneration + 1,
            )
            if (bound) scheduleProviderReconnect()
        }
    }

    override suspend fun start() = lifecycleMutex.withLock {
        if (bound) return@withLock
        bound = true
        val initialFailure = try {
            connectProviderTransport()
            null
        } catch (error: Throwable) {
            mutableStatus.value = mutableStatus.value.copy(
                connected = false,
                linkState = "PROVIDER_RECONNECT",
                lastError = hardwareFailureCode(error),
            )
            scheduleProviderReconnect()
            IllegalStateException("failed to connect hardware provider", error)
        }
        val scheduledMonitor = gatewayScope.launch(start = CoroutineStart.LAZY) {
            while (isActive) {
                delay(RELIABILITY_POLL_MILLIS)
                pollReliability()
            }
        }
        synchronized(providerLock) { monitorJob = scheduledMonitor }
        scheduledMonitor.start()
        initialFailure?.let { throw it }
        Unit
    }

    override suspend fun stop() {
        lifecycleMutex.lock()
        try {
            withContext(NonCancellable) {
                val snapshot = synchronized(providerLock) {
                    if (!bound && providerTransport == null && providerDisconnectJob == null) {
                        return@withContext
                    }
                    bound = false
                    val detachedTransport = providerTransport
                    detachedTransport?.epoch?.invalidate()
                    providerTransport = null
                    remote = null
                    ProviderStopSnapshot(
                        cancellableJobs = listOfNotNull(
                            monitorJob,
                            reopenJob,
                            providerReconnectJob,
                        ),
                        disconnectJob = providerDisconnectJob,
                        transport = detachedTransport,
                    ).also {
                        monitorJob = null
                        reopenJob = null
                        providerReconnectJob = null
                        providerReconnectAfterCleanupGate.reset()
                        providerDisconnectJob = null
                    }
                }
                snapshot.cancellableJobs.forEach { it.cancel() }
                snapshot.disconnectJob?.start()
                (snapshot.cancellableJobs + listOfNotNull(snapshot.disconnectJob)).joinAll()
                // Requests bind to a transport before entering sendMutex. This barrier drains all
                // old requests that already hold or are queued for that mutex before resume.
                sendMutex.withLock { Unit }
                val transport = snapshot.transport
                if (transport != null) {
                    transport.epoch.awaitDrained()
                    runCatching { transport.service.closePort() }
                    runCatching { transport.service.unregisterCallback(transport.callback) }
                    runCatching { transport.binder.unlinkToDeath(transport.deathRecipient, 0) }
                    runCatching { transport.connection.close() }
                }
                lastHeartbeatTransmitMillis = null
                lastRemoteUptimeMillis = null
                lastRemoteBootSessionId = null
                helloDeadlineMillis = null
                completePendingCommands(HardwareCommandOutcome.SEND_FAILED)
                synchronized(reliabilityLock) {
                    heartbeatMonitor.reset()
                    duplicateWindow.reset()
                }
                mutableStatus.value = HardwareStatus(
                    connected = false,
                    simulated = false,
                    eventQueueOverflowCount = mutableStatus.value.eventQueueOverflowCount,
                    moduleSessionGeneration = mutableStatus.value.moduleSessionGeneration,
                )
            }
        } finally {
            lifecycleMutex.unlock()
        }
    }

    override suspend fun send(command: HardwareCommand) {
        val result = sendForResult(command)
        if (!result.accepted) throw HardwareCommandException(result)
    }

    override suspend fun sendForResult(command: HardwareCommand): HardwareCommandResult {
        require(command.type in 0..0xFF)
        require(command.sequence in 0..0xFFFF)
        require(command.payload.size <= HslFrameCodec.MAX_PAYLOAD_SIZE)
        val requiredCommandCapability = requiredCommandCapability(command.type)
        if (
            requiredCommandCapability != 0L &&
            (mutableStatus.value.compatibility != HardwareCompatibility.COMPATIBLE ||
                mutableStatus.value.moduleCapabilityMask and requiredCommandCapability == 0L)
        ) {
            mutableStatus.value = mutableStatus.value.copy(
                lastError = "hardware command blocked before compatible HELLO type=${command.type}",
            )
            return HardwareCommandResult(
                HardwareCommandOutcome.REJECTED,
                command.type,
                command.sequence,
                command.sequence,
                resultCode = HSL_RESULT_FIELD_OUT_OF_RANGE,
            )
        }
        val requestedTransport = currentProviderTransport() ?: return HardwareCommandResult(
            HardwareCommandOutcome.SEND_FAILED,
            command.type,
            command.sequence,
            command.sequence,
        )
        val reliable = command.flags and HslFlags.ACK_REQUIRED != 0
        if (!reliable) {
            return sendMutex.withLock {
                if (!isCurrentProviderTransportRequest(requestedTransport, currentProviderTransport())) {
                    return@withLock HardwareCommandResult(
                        HardwareCommandOutcome.SEND_FAILED,
                        command.type,
                        command.sequence,
                        command.sequence,
                    )
                }
                val transport = requestedTransport
                runCatching {
                    check(
                        transport.epoch.dispatch {
                            transport.service.sendFrame(
                                command.type,
                                command.flags,
                                command.sequence,
                                command.payload,
                            )
                        },
                    ) { "hardware provider transport changed before send" }
                    HardwareCommandResult(
                        HardwareCommandOutcome.SENT,
                        command.type,
                        command.sequence,
                        command.sequence,
                    )
                }.getOrElse {
                    HardwareCommandResult(
                        HardwareCommandOutcome.SEND_FAILED,
                        command.type,
                        command.sequence,
                        command.sequence,
                    )
                }
            }
        }

        val completion = sendMutex.withLock {
            if (!isCurrentProviderTransportRequest(requestedTransport, currentProviderTransport())) {
                return@withLock null
            }
            val transport = requestedTransport
            val wireSequence = synchronized(reliabilityLock) {
                reliableSequenceAllocator.allocate(tracker::isPending)
            }
            val deferred = CompletableDeferred<HardwareCommandResult>()
            val frame = HslFrame(
                flags = command.flags,
                type = command.type,
                sequence = wireSequence,
                payload = command.payload,
            )
            synchronized(reliabilityLock) {
                tracker.track(frame, monotonicClock())
                check(
                    pendingCommands.put(
                        wireSequence,
                        PendingCommand(command.type, command.sequence, deferred),
                    ) == null,
                )
            }
            runCatching {
                check(
                    transport.epoch.dispatch {
                        transport.service.sendFrame(
                            command.type,
                            command.flags,
                            wireSequence,
                            command.payload,
                        )
                    },
                ) { "hardware provider transport changed before reliable send" }
            }.onFailure {
                synchronized(reliabilityLock) {
                    tracker.acknowledge(wireSequence)
                    pendingCommands.remove(wireSequence)
                }
                deferred.complete(
                    HardwareCommandResult(
                        HardwareCommandOutcome.SEND_FAILED,
                        command.type,
                        command.sequence,
                        wireSequence,
                    ),
                )
            }
            deferred
        }
        return completion?.await() ?: HardwareCommandResult(
            HardwareCommandOutcome.SEND_FAILED,
            command.type,
            command.sequence,
            command.sequence,
        )
    }

    override suspend fun acknowledge(
        acknowledgement: HardwareAcknowledgement,
        resultCode: Int,
    ) {
        require(resultCode in HSL_RESULT_SUCCESS..HSL_RESULT_FIELD_OUT_OF_RANGE)
        val requestedTransport = checkNotNull(currentProviderTransport()) {
            "hardware service is not connected"
        }
        check(acknowledgement.transportEpoch === requestedTransport.epoch) {
            "hardware acknowledgement belongs to an inactive provider transport"
        }
        sendMutex.withLock {
            check(isCurrentProviderTransportRequest(requestedTransport, currentProviderTransport())) {
                "hardware provider transport changed before acknowledgement"
            }
            check(acknowledgement.transportEpoch === requestedTransport.epoch) {
                "hardware acknowledgement belongs to an inactive provider transport"
            }
            check(
                requestedTransport.epoch.dispatch {
                    sendAcknowledgement(
                        requestedTransport.service,
                        acknowledgement.sequence,
                        resultCode,
                    )
                },
            ) { "hardware provider transport changed before acknowledgement" }
        }
    }

    override fun updateOperationalState(state: HardwareOperationalState) {
        operationalState.set(state.wireValue)
    }

    private fun sendAcknowledgement(
        service: IHelmetHardwareService,
        acknowledgedSequence: Int,
        resultCode: Int,
    ) {
        val sequence = transmitSequence.getAndUpdate { current -> (current + 1) and 0xFFFF }
        val payload = HslPayloadCodec.encodeAck(HslAck(acknowledgedSequence, resultCode = resultCode))
        runCatching {
            service.sendFrame(HslMessageType.ACK, reliableAckFlags(resultCode), sequence, payload)
        }
    }

    private fun currentProviderTransport(): ProviderTransport? = synchronized(providerLock) {
        providerTransport
    }

    private fun offerEventBatch(events: List<HardwareEvent>): Boolean {
        if (events.isEmpty()) return true
        val result = eventQueue.offer(events)
        if (!result.accepted) {
            mutableStatus.value = mutableStatus.value.copy(
                lastError = "hardware event queue full; rejected frames=${result.overflowCount}",
                eventQueueOverflowCount = result.overflowCount,
            )
        }
        return result.accepted
    }

    private fun completePendingCommands(outcome: HardwareCommandOutcome) {
        val pending = synchronized(reliabilityLock) {
            tracker.clear()
            pendingCommands.toMap().also { pendingCommands.clear() }
        }
        pending.forEach { (wireSequence, command) ->
            command.completion.complete(
                HardwareCommandResult(
                    outcome = outcome,
                    type = command.type,
                    businessSequence = command.businessSequence,
                    wireSequence = wireSequence,
                ),
            )
        }
    }

    private fun pollReliability() {
        val now = monotonicClock()
        val helloDeadline = helloDeadlineMillis
        if (helloHandshakeTimedOut(helloDeadline, now, mutableStatus.value.compatibility)) {
            mutableStatus.value = mutableStatus.value.copy(
                connected = false,
                linkState = "HELLO_TIMEOUT",
                lastError = "external module compatibility HELLO timeout",
            )
            helloDeadlineMillis = null
            schedulePortReopen("hello_timeout")
        }
        val lastTransmit = lastHeartbeatTransmitMillis
        if (lastTransmit == null || now - lastTransmit >= HEARTBEAT_INTERVAL_MILLIS) {
            sendHeartbeat(now)
            lastHeartbeatTransmitMillis = now
        }
        val batch = synchronized(reliabilityLock) { tracker.poll(now) }
        batch.retryFrames.forEach { frame ->
            val transport = currentProviderTransport()
            if (transport != null) {
                runCatching {
                    transport.epoch.dispatch {
                        transport.service.sendFrame(
                            frame.type,
                            frame.flags,
                            frame.sequence,
                            frame.payload,
                        )
                    }
                }
            }
        }
        val linkState = synchronized(reliabilityLock) { heartbeatMonitor.state(now) }
        if (batch.timedOutSequences.isNotEmpty()) {
            val timedOut = synchronized(reliabilityLock) {
                batch.timedOutSequences.mapNotNull { sequence ->
                    pendingCommands.remove(sequence)?.let { sequence to it }
                }
            }
            timedOut.forEach { (wireSequence, command) ->
                command.completion.complete(
                    HardwareCommandResult(
                        outcome = HardwareCommandOutcome.TIMED_OUT,
                        type = command.type,
                        businessSequence = command.businessSequence,
                        wireSequence = wireSequence,
                    ),
                )
            }
            mutableStatus.value = mutableStatus.value.copy(
                connected = false,
                linkState = "COMMAND_TIMEOUT",
                lastError = batch.timedOutSequences.joinToString(prefix = "sequences "),
            )
            schedulePortReopen("reliable_command_timeout")
        } else if (linkState == HslLinkState.DEGRADED || linkState == HslLinkState.FAULT) {
            mutableStatus.value = mutableStatus.value.copy(
                connected = false,
                linkState = linkState.name,
                lastError = if (linkState == HslLinkState.FAULT) "external module heartbeat timeout" else null,
            )
            if (linkState == HslLinkState.FAULT) schedulePortReopen("heartbeat_timeout")
        }
    }

    private fun sendHeartbeat(now: Long) {
        val sequence = transmitSequence.getAndUpdate { current -> (current + 1) and 0xFFFF }
        val state = HardwareOperationalState.entries.firstOrNull {
            it.wireValue == operationalState.get()
        } ?: HardwareOperationalState.FAULT
        val payload = heartbeatPayload(
            uptimeMillis = now,
            state = state,
        )
        val transport = currentProviderTransport()
        runCatching {
            transport?.epoch?.dispatch {
                transport.service.sendFrame(HslMessageType.HEARTBEAT, 0, sequence, payload)
            }
        }
            .onFailure {
                mutableStatus.value = mutableStatus.value.copy(
                    connected = false,
                    linkState = "HEARTBEAT_SEND_FAILED",
                    lastError = hardwareFailureCode(it),
                )
            }
    }

    companion object {
        const val HARDWARE_PROVIDER_AUTHORITY =
            HardwareProviderConnection.HARDWARE_PROVIDER_AUTHORITY
        const val HARDWARE_PROVIDER_CLASS = HardwareProviderConnection.HARDWARE_PROVIDER_CLASS
        private const val RELIABILITY_POLL_MILLIS = 50L
        private const val HEARTBEAT_INTERVAL_MILLIS = 1_000L
        private const val HELLO_TIMEOUT_MILLIS = 5_000L
        private const val REOPEN_INITIAL_DELAY_MILLIS = 250L
        private const val REOPEN_MAX_DELAY_MILLIS = 30_000L
        private const val PROVIDER_RECONNECT_INITIAL_DELAY_MILLIS = 250L
        private const val PROVIDER_RECONNECT_MAX_DELAY_MILLIS = 30_000L
        private const val EVENT_QUEUE_CAPACITY = 64
    }
}
