package com.example.helmet.hardware.api

import android.content.Context
import android.os.IBinder
import com.example.helmet.core.protocol.HslLinkState
import com.example.helmet.core.protocol.HslMessageType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raw NMEA/RTCM transport for the dedicated UART4 receiver. */
class DirectRtkHardwareGateway(
    context: Context,
    private val devicePath: String = "/dev/ttyAS4",
    private val baudRate: Int = 115_200,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
) : HardwareGateway {
    private data class Transport(
        val connection: RtkSerialProviderConnection,
        val binder: IBinder,
        val service: IRtkSerialService,
        val callback: IRtkSerialCallback,
        val epoch: ProviderTransportEpoch,
        val deathRecipient: IBinder.DeathRecipient,
    )

    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val sendMutex = Mutex()
    private val transportLock = Any()
    private val sequence = AtomicInteger()
    private val eventQueue = HardwareEventQueue(EVENT_QUEUE_CAPACITY)
    private val mutableStatus = MutableStateFlow(
        HardwareStatus(simulated = false, compatibility = HardwareCompatibility.NOT_APPLICABLE),
    )

    @Volatile
    private var started = false
    private var transport: Transport? = null
    private var reconnectJob: Job? = null

    init {
        require(
            devicePath == "/dev/ttyAS4" || devicePath.matches(Regex("^/dev/pts/[0-9]{1,5}$")),
        ) { "direct RTK path is not allowed" }
        require(baudRate in DIRECT_RTK_BAUD_RATES) { "unsupported direct RTK baud rate" }
    }

    override val status: StateFlow<HardwareStatus> = mutableStatus
    override val events: Flow<HardwareEvent> = eventQueue.events

    override suspend fun start() = lifecycleMutex.withLock {
        if (started) return@withLock
        started = true
        runCatching { connectAndOpen() }.onFailure { error ->
            mutableStatus.value = mutableStatus.value.copy(
                connected = false,
                linkState = "OPEN_RETRY",
                lastError = hardwareFailureCode(error),
            )
            scheduleReconnect()
            throw IllegalStateException("failed to open direct RTK transport", error)
        }
    }

    override suspend fun stop() = lifecycleMutex.withLock {
        if (!started && currentTransport() == null) return@withLock
        started = false
        val job = synchronized(transportLock) { reconnectJob.also { reconnectJob = null } }
        job?.cancelAndJoin()
        detachTransport()?.let(::closeTransport)
        mutableStatus.value = HardwareStatus(
            simulated = false,
            compatibility = HardwareCompatibility.NOT_APPLICABLE,
            moduleSessionGeneration = mutableStatus.value.moduleSessionGeneration,
            eventQueueOverflowCount = mutableStatus.value.eventQueueOverflowCount,
        )
    }

    override suspend fun send(command: HardwareCommand) {
        val result = sendForResult(command)
        if (!result.accepted) throw HardwareCommandException(result)
    }

    override suspend fun sendForResult(command: HardwareCommand): HardwareCommandResult {
        require(command.type == HslMessageType.RTK_CORRECTION) {
            "direct RTK transport only accepts RTCM correction data"
        }
        require(command.payload.isNotEmpty() && command.payload.size <= MAX_WRITE_SIZE)
        val requested = currentTransport() ?: return failed(command)
        return sendMutex.withLock {
            if (currentTransport() !== requested || !mutableStatus.value.connected) return@withLock failed(command)
            runCatching {
                check(requested.epoch.dispatch { requested.service.write(command.payload) }) {
                    "direct RTK transport changed before write"
                }
                HardwareCommandResult(
                    outcome = HardwareCommandOutcome.SENT,
                    type = command.type,
                    businessSequence = command.sequence,
                    wireSequence = command.sequence,
                )
            }.getOrElse { error ->
                mutableStatus.value = mutableStatus.value.copy(
                    connected = false,
                    linkState = "WRITE_FAILED",
                    lastError = hardwareFailureCode(error),
                )
                scheduleReconnect()
                failed(command)
            }
        }
    }

    private fun connectAndOpen() {
        check(started)
        val connection = RtkSerialProviderConnection.connect(applicationContext)
        val binder = connection.binder
        val service = connection.service
        val epoch = ProviderTransportEpoch()
        var created: Transport? = null
        val callback = object : IRtkSerialCallback.Stub() {
            override fun onBytes(bytes: ByteArray) {
                epoch.dispatch { handleBytes(bytes) }
            }

            override fun onLinkStateChanged(state: Int, detail: String) {
                epoch.dispatch { handleLinkState(state, detail) }
            }
        }
        val deathRecipient = IBinder.DeathRecipient { onBinderDied(binder) }
        var linked = false
        var callbackRegistered = false
        try {
            binder.linkToDeath(deathRecipient, 0)
            linked = true
            service.closePort()
            service.registerCallback(callback)
            callbackRegistered = true
            val candidate = Transport(connection, binder, service, callback, epoch, deathRecipient)
            created = candidate
            synchronized(transportLock) {
                check(started && transport == null && binder.isBinderAlive)
                transport = candidate
                epoch.activate()
            }
            service.openPort(devicePath, baudRate)
            mutableStatus.value = mutableStatus.value.copy(
                connected = true,
                linkState = "CONNECTED",
                lastError = null,
                compatibility = HardwareCompatibility.NOT_APPLICABLE,
                moduleSessionGeneration = mutableStatus.value.moduleSessionGeneration + 1,
            )
        } catch (error: Throwable) {
            synchronized(transportLock) {
                if (transport === created) transport = null
            }
            epoch.invalidate()
            if (callbackRegistered) runCatching { service.unregisterCallback(callback) }
            epoch.awaitDrained()
            if (linked) runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            runCatching { connection.close() }
            throw error
        }
    }

    private fun handleBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val event = HardwareEvent.ProtocolFrame(
            monotonicMillis = monotonicClock(),
            version = 0,
            flags = 0,
            type = HslMessageType.RTK_NMEA,
            sequence = sequence.getAndUpdate { current -> (current + 1) and 0xFFFF },
            payload = bytes.copyOf(),
        )
        val offer = eventQueue.offer(listOf(event))
        mutableStatus.value = if (offer.accepted) {
            mutableStatus.value.copy(
                connected = true,
                linkState = "CONNECTED",
                lastHeartbeatMillis = monotonicClock(),
                lastError = null,
            )
        } else {
            mutableStatus.value.copy(
                lastError = "direct RTK event queue full; rejected chunks=${offer.overflowCount}",
                eventQueueOverflowCount = offer.overflowCount,
            )
        }
    }

    private fun handleLinkState(state: Int, detail: String) {
        val linkState = HslLinkState.entries.getOrNull(state) ?: HslLinkState.FAULT
        mutableStatus.value = mutableStatus.value.copy(
            connected = linkState == HslLinkState.CONNECTED,
            linkState = linkState.name,
            lastError = detail.takeIf { linkState == HslLinkState.FAULT },
        )
        if (linkState == HslLinkState.FAULT) scheduleReconnect()
    }

    private fun onBinderDied(binder: IBinder) {
        val detached = synchronized(transportLock) {
            transport?.takeIf { it.binder === binder }?.also {
                it.epoch.invalidate()
                transport = null
            }
        } ?: return
        scope.launch {
            closeTransport(detached, unlinkDeath = false)
            mutableStatus.value = mutableStatus.value.copy(
                connected = false,
                linkState = "PROVIDER_DISCONNECTED",
                lastError = "direct RTK provider disconnected",
            )
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!started) return
        lateinit var scheduled: Job
        synchronized(transportLock) {
            if (!started || reconnectJob != null) return
            scheduled = scope.launch(start = CoroutineStart.LAZY) {
                detachTransport()?.let(::closeTransport)
                var delayMillis = RECONNECT_INITIAL_MILLIS
                while (isActive && started && currentTransport() == null) {
                    delay(delayMillis)
                    val opened = runCatching { connectAndOpen() }
                    if (opened.isSuccess) return@launch
                    mutableStatus.value = mutableStatus.value.copy(
                        connected = false,
                        linkState = "OPEN_RETRY",
                        lastError = opened.exceptionOrNull()?.let(::hardwareFailureCode),
                    )
                    delayMillis = (delayMillis * 2).coerceAtMost(RECONNECT_MAX_MILLIS)
                }
            }
            reconnectJob = scheduled
        }
        scheduled.invokeOnCompletion {
            synchronized(transportLock) { if (reconnectJob === scheduled) reconnectJob = null }
        }
        scheduled.start()
    }

    private fun detachTransport(): Transport? = synchronized(transportLock) {
        transport.also {
            it?.epoch?.invalidate()
            transport = null
        }
    }

    private fun closeTransport(detached: Transport, unlinkDeath: Boolean = true) {
        detached.epoch.awaitDrained()
        runCatching { detached.service.closePort() }
        runCatching { detached.service.unregisterCallback(detached.callback) }
        if (unlinkDeath) runCatching { detached.binder.unlinkToDeath(detached.deathRecipient, 0) }
        runCatching { detached.connection.close() }
    }

    private fun currentTransport(): Transport? = synchronized(transportLock) { transport }

    private fun failed(command: HardwareCommand) = HardwareCommandResult(
        outcome = HardwareCommandOutcome.SEND_FAILED,
        type = command.type,
        businessSequence = command.sequence,
        wireSequence = command.sequence,
    )

    companion object {
        private const val EVENT_QUEUE_CAPACITY = 64
        private const val MAX_WRITE_SIZE = 4_096
        private const val RECONNECT_INITIAL_MILLIS = 250L
        private const val RECONNECT_MAX_MILLIS = 30_000L
        private val DIRECT_RTK_BAUD_RATES = setOf(
            9_600, 19_200, 38_400, 57_600, 115_200, 230_400, 460_800, 921_600,
        )
    }
}
