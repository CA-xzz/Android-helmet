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
    val deduplicated: Boolean,
)

class CommunicationException(
    message: String,
    val retryable: Boolean,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

interface DeviceMessageTransport {
    val deviceId: String?
        get() = null

    suspend fun acknowledgeCommand(command: DeviceCommand, status: String, error: String? = null)
    suspend fun sendBroadcastReceipt(
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
    suspend fun fetchCommands(deviceId: String, afterSequence: Long, limit: Int = 100): List<DeviceCommand>
}
