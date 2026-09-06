package com.example.helmet.communication.sync

import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.CallSession
import com.example.helmet.core.model.CallSignal
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.IceConfiguration

data class CallSyncReceipt(
    val callId: String,
    val state: String,
    val stateSequence: Long,
    val acknowledgedState: String,
    val acknowledgedStateSequence: Long,
    val deduplicated: Boolean,
    val updatedAtEpochMillis: Long? = null,
)

data class DeviceCommandPage(
    val commandStreamId: String,
    val commandHighWaterSequence: Long,
    val commands: List<DeviceCommand>,
    val hasMore: Boolean,
    val nextAfterSequence: Long?,
)

open class CommunicationException(
    message: String,
    val retryable: Boolean,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

class CommandStreamHighWaterRegressionException(
    val observedCommandStreamId: String,
    val observedHighWater: Long,
    val requestedAfterSequence: Long,
) : CommunicationException(
    "command high-water sequence regressed below the requested cursor",
    retryable = false,
)

interface DeviceMessageTransport {
    val deviceId: String?
        get() = null

    suspend fun sendBroadcastReceipt(
        commandStreamId: String,
        broadcastId: String,
        state: BroadcastPlaybackState,
        occurredAtEpochMillis: Long,
        error: String? = null,
    )
}

interface CommunicationTransport : DeviceMessageTransport {
    suspend fun syncCall(call: CallSession): CallSyncReceipt
    suspend fun fetchIceConfiguration(callId: String, requesterId: String): IceConfiguration
    suspend fun sendCallSignal(signal: CallSignal): CallSignal
    suspend fun fetchCallSignals(callId: String, afterSequence: Long, limit: Int = 100): List<CallSignal>
    suspend fun fetchCommandPage(
        deviceId: String,
        afterSequence: Long,
        limit: Int = 100,
    ): DeviceCommandPage

    suspend fun fetchCommands(
        deviceId: String,
        afterSequence: Long,
        limit: Int = 100,
    ): List<DeviceCommand> = fetchCommandPage(deviceId, afterSequence, limit).commands

    suspend fun acknowledgeCommand(
        commandStreamId: String,
        command: DeviceCommand,
        status: String,
        error: String? = null,
    )
}
