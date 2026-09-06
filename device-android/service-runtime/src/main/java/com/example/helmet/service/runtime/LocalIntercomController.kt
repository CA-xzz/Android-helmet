package com.example.helmet.service.runtime

import com.example.helmet.core.model.LocalIntercomRuntimeConfig
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslLocalIntercomCommand
import com.example.helmet.core.protocol.HslLocalIntercomStatus
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.LocalIntercomAction
import com.example.helmet.core.protocol.LocalIntercomCodec
import com.example.helmet.core.protocol.LocalIntercomModuleState
import com.example.helmet.core.protocol.LocalIntercomPayloadCodec
import com.example.helmet.hardware.api.HardwareCommand
import com.example.helmet.hardware.api.HardwareStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LocalIntercomState {
    DISABLED,
    UNAVAILABLE,
    JOINING,
    READY,
    REQUESTING_TRANSMIT,
    TRANSMITTING,
    RECEIVING,
    REQUESTING_STOP,
    FAULT,
}

data class LocalIntercomStatus(
    val state: LocalIntercomState = LocalIntercomState.DISABLED,
    val lastRequestId: Long? = null,
    val peerCount: Int = 0,
    val codec: String = "unknown",
    val sampleRateHertz: Int? = null,
    val rssiDbm: Int? = null,
    val snrTenthsDb: Int? = null,
    val packetLossPermille: Int? = null,
    val oneWayLatencyMillis: Int? = null,
    val transmittedPackets: Long = 0,
    val receivedPackets: Long = 0,
    val faultCode: Int = 0,
    val lastError: String? = null,
)

internal data class PlannedLocalIntercomCommand(
    val action: LocalIntercomAction,
    val requestId: Long,
)

internal fun plannedLocalIntercomAction(state: LocalIntercomState): LocalIntercomAction = when (state) {
    LocalIntercomState.TRANSMITTING,
    LocalIntercomState.REQUESTING_TRANSMIT,
    LocalIntercomState.REQUESTING_STOP,
    -> LocalIntercomAction.STOP_TRANSMIT
    LocalIntercomState.READY,
    LocalIntercomState.RECEIVING,
    -> LocalIntercomAction.START_TRANSMIT
    LocalIntercomState.DISABLED,
    LocalIntercomState.UNAVAILABLE,
    LocalIntercomState.JOINING,
    LocalIntercomState.FAULT,
    -> LocalIntercomAction.JOIN
}

class LocalIntercomController(
    private val config: LocalIntercomRuntimeConfig,
    private val hardwareStatus: () -> HardwareStatus,
    private val commandSink: suspend (HardwareCommand) -> Unit,
    private val requestIdSource: () -> Long = run {
        var next = 1L
        {
            val value = next
            next = if (next == LocalIntercomRequestIdAllocator.MAX_REQUEST_ID) 1L else next + 1L
            value
        }
    },
) {
    private val mutableStatus = MutableStateFlow(
        LocalIntercomStatus(
            state = if (config.enabled) LocalIntercomState.UNAVAILABLE else LocalIntercomState.DISABLED,
        ),
    )

    val status: StateFlow<LocalIntercomStatus> = mutableStatus

    suspend fun ensureJoined(): Boolean {
        if (!config.enabled) return false
        val hardware = hardwareStatus()
        if (!hardware.connected || hardware.simulated) {
            mutableStatus.value = mutableStatus.value.copy(
                state = LocalIntercomState.UNAVAILABLE,
                lastError = if (hardware.simulated) {
                    "local intercom requires the dedicated hardware module"
                } else {
                    "hardware link is unavailable"
                },
            )
            return false
        }
        if (mutableStatus.value.state in setOf(
                LocalIntercomState.JOINING,
                LocalIntercomState.READY,
                LocalIntercomState.REQUESTING_TRANSMIT,
                LocalIntercomState.TRANSMITTING,
                LocalIntercomState.RECEIVING,
                LocalIntercomState.REQUESTING_STOP,
            )
        ) {
            return true
        }
        return sendAction(LocalIntercomAction.JOIN, LocalIntercomState.JOINING)
    }

    internal fun planToggleCommand(): PlannedLocalIntercomCommand {
        require(config.enabled) { "local intercom is disabled" }
        return PlannedLocalIntercomCommand(
            action = plannedLocalIntercomAction(mutableStatus.value.state),
            requestId = requestIdSource().also {
                require(it in 1..LocalIntercomRequestIdAllocator.MAX_REQUEST_ID)
            },
        )
    }

    internal suspend fun executePlanned(command: PlannedLocalIntercomCommand): Boolean = sendAction(
        action = command.action,
        pendingState = when (command.action) {
            LocalIntercomAction.JOIN -> LocalIntercomState.JOINING
            LocalIntercomAction.START_TRANSMIT -> LocalIntercomState.REQUESTING_TRANSMIT
            LocalIntercomAction.STOP_TRANSMIT -> LocalIntercomState.REQUESTING_STOP
            else -> throw IllegalArgumentException("unsupported planned local intercom action")
        },
        requestId = command.requestId,
        propagateCommandFailure = true,
    )

    suspend fun toggleTransmit(): Boolean = executePlanned(planToggleCommand())

    suspend fun leave(): Boolean {
        if (!config.enabled) return false
        return sendAction(LocalIntercomAction.LEAVE, LocalIntercomState.UNAVAILABLE)
    }

    fun acceptModuleStatus(payload: ByteArray): HslLocalIntercomStatus {
        require(config.enabled) { "local intercom is disabled" }
        val hardware = hardwareStatus()
        require(hardware.connected && !hardware.simulated) {
            "local intercom status requires the dedicated hardware module"
        }
        val decoded = LocalIntercomPayloadCodec.decodeStatus(payload)
        val nextState = when (decoded.state) {
            LocalIntercomModuleState.UNAVAILABLE -> LocalIntercomState.UNAVAILABLE
            LocalIntercomModuleState.READY -> LocalIntercomState.READY
            LocalIntercomModuleState.RECEIVING -> LocalIntercomState.RECEIVING
            LocalIntercomModuleState.TRANSMITTING -> LocalIntercomState.TRANSMITTING
            LocalIntercomModuleState.FAULT -> LocalIntercomState.FAULT
        }
        mutableStatus.value = LocalIntercomStatus(
            state = nextState,
            lastRequestId = decoded.requestId,
            peerCount = decoded.peerCount,
            codec = decoded.codec.name,
            sampleRateHertz = decoded.sampleRateHertz.takeIf { it > 0 },
            rssiDbm = decoded.rssiDbm,
            snrTenthsDb = decoded.snrTenthsDb,
            packetLossPermille = decoded.packetLossPermille,
            oneWayLatencyMillis = decoded.oneWayLatencyMillis,
            transmittedPackets = decoded.transmittedPackets,
            receivedPackets = decoded.receivedPackets,
            faultCode = decoded.faultCode,
            lastError = if (decoded.state == LocalIntercomModuleState.FAULT) {
                "local intercom module fault ${decoded.faultCode}"
            } else {
                null
            },
        )
        return decoded
    }

    fun onHardwareDisconnected() {
        if (config.enabled) {
            mutableStatus.value = mutableStatus.value.copy(
                state = LocalIntercomState.UNAVAILABLE,
                lastError = "hardware link is unavailable",
            )
        }
    }

    private suspend fun sendAction(
        action: LocalIntercomAction,
        pendingState: LocalIntercomState,
        requestId: Long = requestIdSource(),
        propagateCommandFailure: Boolean = false,
    ): Boolean {
        val hardware = hardwareStatus()
        if (!hardware.connected || hardware.simulated) {
            mutableStatus.value = mutableStatus.value.copy(
                state = LocalIntercomState.UNAVAILABLE,
                lastError = if (hardware.simulated) {
                    "local intercom requires the dedicated hardware module"
                } else {
                    "hardware link is unavailable"
                },
            )
            return false
        }
        require(requestId in 1..LocalIntercomRequestIdAllocator.MAX_REQUEST_ID)
        val command = HslLocalIntercomCommand(
            action = action,
            requestId = requestId,
            groupId = config.groupId,
            channel = config.channel,
            keySlot = config.keySlot,
            codec = LocalIntercomCodec.MODULE_NEGOTIATED,
        )
        return try {
            commandSink(
                HardwareCommand(
                    type = HslMessageType.LOCAL_INTERCOM_COMMAND,
                    flags = HslFlags.ACK_REQUIRED,
                    sequence = (requestId and 0xFFFF).toInt(),
                    payload = LocalIntercomPayloadCodec.encodeCommand(command),
                ),
            )
            mutableStatus.value = mutableStatus.value.copy(
                state = pendingState,
                lastRequestId = requestId,
                lastError = null,
            )
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            mutableStatus.value = mutableStatus.value.copy(
                state = LocalIntercomState.FAULT,
                lastRequestId = requestId,
                lastError = error.javaClass.name.take(256),
            )
            if (propagateCommandFailure) throw error
            false
        }
    }
}
