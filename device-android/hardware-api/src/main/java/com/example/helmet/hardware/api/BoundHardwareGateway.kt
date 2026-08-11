package com.example.helmet.hardware.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.example.helmet.core.protocol.HslAck
import com.example.helmet.core.protocol.HslDuplicateWindow
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslFrame
import com.example.helmet.core.protocol.HslFrameCodec
import com.example.helmet.core.protocol.HslHeartbeat
import com.example.helmet.core.protocol.HslHeartbeatMonitor
import com.example.helmet.core.protocol.HslLinkState
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslPayloadCodec
import com.example.helmet.core.protocol.HslReliableCommandTracker
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

internal fun reliableFrameResult(frame: HslFrame): Int = when (frame.type) {
    HslMessageType.ALARM_EVENT -> if (frame.payload.size !in ALARM_EVENT_PAYLOAD_SIZES) {
        HSL_RESULT_INVALID_LENGTH
    } else {
        runCatching { HslPayloadCodec.decodeAlarmEvent(frame.payload) }
            .fold(onSuccess = { HSL_RESULT_SUCCESS }, onFailure = { HSL_RESULT_FIELD_OUT_OF_RANGE })
    }
    else -> HSL_RESULT_SUCCESS
}

internal fun reliableAckFlags(resultCode: Int): Int =
    HslFlags.ACK or if (resultCode == HSL_RESULT_SUCCESS) 0 else HslFlags.ERROR

internal fun requiresPersistenceBeforeAck(type: Int): Boolean = type == HslMessageType.ALARM_EVENT

private val ALARM_EVENT_PAYLOAD_SIZES = setOf(20, 60)
private const val HSL_RESULT_SUCCESS = 0
private const val HSL_RESULT_INVALID_LENGTH = 3
private const val HSL_RESULT_FIELD_OUT_OF_RANGE = 4

class BoundHardwareGateway(
    context: Context,
    private val devicePath: String,
    private val baudRate: Int = 115_200,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
) : HardwareGateway {
    private val applicationContext = context.applicationContext
    private val mutableStatus = MutableStateFlow(HardwareStatus(simulated = false))
    private val mutableEvents = MutableSharedFlow<HardwareEvent>(extraBufferCapacity = 64)
    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reliabilityLock = Any()
    private val tracker = HslReliableCommandTracker()
    private val heartbeatMonitor = HslHeartbeatMonitor()
    private val duplicateWindow = HslDuplicateWindow()
    private val transmitSequence = AtomicInteger()
    private var remote: IHelmetHardwareService? = null
    private var bound = false
    private var monitorJob: Job? = null
    private var lastHeartbeatTransmitMillis: Long? = null

    override val status: StateFlow<HardwareStatus> = mutableStatus
    override val events: SharedFlow<HardwareEvent> = mutableEvents

    private val callback = object : IHelmetHardwareCallback.Stub() {
        override fun onFrame(version: Int, flags: Int, type: Int, sequence: Int, payload: ByteArray) {
            val now = monotonicClock()
            val frame = HslFrame(
                version = version,
                flags = flags,
                type = type,
                sequence = sequence,
                payload = payload.copyOf(),
            )
            if (type == HslMessageType.ACK) {
                runCatching { HslPayloadCodec.decodeAck(payload) }
                    .onSuccess { ack -> synchronized(reliabilityLock) { tracker.acknowledge(ack.acknowledgedSequence) } }
            }
            if (flags and HslFlags.ACK_REQUIRED != 0) {
                val resultCode = reliableFrameResult(frame)
                if (resultCode != HSL_RESULT_SUCCESS) {
                    sendAcknowledgement(sequence, resultCode)
                    mutableStatus.value = mutableStatus.value.copy(
                        lastError = "rejected external module frame type=$type result=$resultCode",
                    )
                    return
                }
                if (!requiresPersistenceBeforeAck(type)) {
                    sendAcknowledgement(sequence, resultCode)
                    val accepted = synchronized(reliabilityLock) { duplicateWindow.accept(type, sequence) }
                    if (!accepted) return
                }
            }
            if (type == HslMessageType.HEARTBEAT) {
                runCatching { HslPayloadCodec.decodeHeartbeat(payload) }
                    .onSuccess {
                        synchronized(reliabilityLock) { heartbeatMonitor.onHeartbeat(now) }
                        mutableStatus.value = mutableStatus.value.afterHeartbeat(now)
                        mutableEvents.tryEmit(
                            HardwareEvent.Heartbeat(monotonicMillis = now),
                        )
                    }
                    .onFailure { error ->
                        mutableStatus.value = mutableStatus.value.copy(
                            lastError = "invalid external module heartbeat: ${error.message ?: error::class.java.simpleName}",
                        )
                    }
            }
            if (!requiresPersistenceBeforeAck(type)) {
                mutableEvents.tryEmit(
                    HardwareEvent.ProtocolFrame(
                        monotonicMillis = now,
                        version = version,
                        flags = flags,
                        type = type,
                        sequence = sequence,
                        payload = payload.copyOf(),
                    ),
                )
            }
            HslEventMapper.map(frame)?.let(mutableEvents::tryEmit)
        }

        override fun onLinkStateChanged(state: Int, detail: String) {
            val linkState = HslLinkState.entries.getOrNull(state) ?: HslLinkState.FAULT
            if (linkState == HslLinkState.CONNECTED) {
                synchronized(reliabilityLock) { heartbeatMonitor.onLinkStarted(monotonicClock()) }
            }
            mutableStatus.value = mutableStatus.value.copy(
                connected = if (linkState == HslLinkState.CONNECTED) {
                    mutableStatus.value.connected
                } else {
                    false
                },
                linkState = if (linkState == HslLinkState.CONNECTED) "AWAITING_HEARTBEAT" else linkState.name,
                lastError = detail.takeIf { linkState == HslLinkState.FAULT },
            )
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val hardwareService = IHelmetHardwareService.Stub.asInterface(service)
            remote = hardwareService
            runCatching {
                hardwareService.registerCallback(callback)
                hardwareService.openPort(devicePath, baudRate)
            }.onFailure {
                mutableStatus.value = HardwareStatus(
                    connected = false,
                    simulated = false,
                    linkState = "OPEN_FAILED",
                    lastError = it.toString(),
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            synchronized(reliabilityLock) { heartbeatMonitor.reset() }
            mutableStatus.value = HardwareStatus(
                connected = false,
                simulated = false,
                linkState = "SERVICE_DISCONNECTED",
            )
        }
    }

    override suspend fun start() {
        if (bound) return
        val intent = Intent().addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).setComponent(
            ComponentName(
                applicationContext.packageName,
                HARDWARE_SERVICE_CLASS,
            ),
        )
        bound = applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        check(bound) { "failed to bind hardware service" }
        monitorJob = gatewayScope.launch {
            while (isActive) {
                delay(RELIABILITY_POLL_MILLIS)
                pollReliability()
            }
        }
    }

    override suspend fun stop() {
        if (!bound) return
        monitorJob?.cancel()
        monitorJob = null
        runCatching { remote?.unregisterCallback(callback) }
        runCatching { remote?.closePort() }
        applicationContext.unbindService(connection)
        bound = false
        remote = null
        lastHeartbeatTransmitMillis = null
        synchronized(reliabilityLock) { heartbeatMonitor.reset() }
        mutableStatus.value = HardwareStatus(connected = false, simulated = false)
    }

    override suspend fun send(command: HardwareCommand) {
        val service = checkNotNull(remote) { "hardware service is not connected" }
        val frame = HslFrame(
            flags = command.flags,
            type = command.type,
            sequence = command.sequence,
            payload = command.payload,
        )
        if (command.flags and HslFlags.ACK_REQUIRED != 0) {
            synchronized(reliabilityLock) { tracker.track(frame, monotonicClock()) }
        }
        service.sendFrame(command.type, command.flags, command.sequence, command.payload)
    }

    override suspend fun acknowledge(acknowledgedSequence: Int, resultCode: Int) {
        require(acknowledgedSequence in 0..0xFFFF)
        require(resultCode in 0..0xFF)
        checkNotNull(remote) { "hardware service is not connected" }
        sendAcknowledgement(acknowledgedSequence, resultCode)
    }

    private fun sendAcknowledgement(acknowledgedSequence: Int, resultCode: Int) {
        val sequence = transmitSequence.getAndUpdate { current -> (current + 1) and 0xFFFF }
        val payload = HslPayloadCodec.encodeAck(HslAck(acknowledgedSequence, resultCode = resultCode))
        runCatching {
            remote?.sendFrame(HslMessageType.ACK, reliableAckFlags(resultCode), sequence, payload)
        }
    }

    private fun pollReliability() {
        val now = monotonicClock()
        val lastTransmit = lastHeartbeatTransmitMillis
        if (lastTransmit == null || now - lastTransmit >= HEARTBEAT_INTERVAL_MILLIS) {
            sendHeartbeat(now)
            lastHeartbeatTransmitMillis = now
        }
        val batch = synchronized(reliabilityLock) { tracker.poll(now) }
        batch.retryFrames.forEach { frame ->
            runCatching { remote?.sendFrame(frame.type, frame.flags, frame.sequence, frame.payload) }
        }
        val linkState = synchronized(reliabilityLock) { heartbeatMonitor.state(now) }
        if (batch.timedOutSequences.isNotEmpty()) {
            mutableStatus.value = mutableStatus.value.copy(
                connected = false,
                linkState = "COMMAND_TIMEOUT",
                lastError = batch.timedOutSequences.joinToString(prefix = "sequences "),
            )
        } else if (linkState == HslLinkState.DEGRADED || linkState == HslLinkState.FAULT) {
            mutableStatus.value = mutableStatus.value.copy(
                connected = false,
                linkState = linkState.name,
                lastError = if (linkState == HslLinkState.FAULT) "external module heartbeat timeout" else null,
            )
        }
    }

    private fun sendHeartbeat(now: Long) {
        val sequence = transmitSequence.getAndUpdate { current -> (current + 1) and 0xFFFF }
        val payload = HslPayloadCodec.encodeHeartbeat(
            HslHeartbeat(
                uptimeMillis = now and 0xFFFF_FFFFL,
                operationalState = 0,
                protocolVersion = HslFrameCodec.SUPPORTED_VERSION,
                errorCount = 0,
            ),
        )
        runCatching { remote?.sendFrame(HslMessageType.HEARTBEAT, 0, sequence, payload) }
            .onFailure {
                mutableStatus.value = mutableStatus.value.copy(
                    connected = false,
                    linkState = "HEARTBEAT_SEND_FAILED",
                    lastError = it.toString(),
                )
            }
    }

    companion object {
        const val HARDWARE_SERVICE_CLASS = "com.example.helmet.hardware.service.HelmetHardwareService"
        private const val RELIABILITY_POLL_MILLIS = 50L
        private const val HEARTBEAT_INTERVAL_MILLIS = 1_000L
    }
}
