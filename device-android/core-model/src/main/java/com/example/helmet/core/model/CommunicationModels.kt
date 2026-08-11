package com.example.helmet.core.model

enum class CallDirection {
    OUTGOING_DEVICE,
    INCOMING_BACKEND,
}

enum class CallMediaMode {
    AUDIO,
    VIDEO_UPLINK,
}

enum class CallState {
    REQUESTED,
    RINGING,
    ACCEPTED,
    CONNECTING,
    CONNECTED,
    REJECTED,
    ENDED,
    FAILED,
}

data class CallSession(
    val callId: String,
    val deviceId: String,
    val direction: CallDirection,
    val mediaMode: CallMediaMode,
    val state: CallState,
    val stateSequence: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val relatedEventId: String?,
    val simulated: Boolean,
    val lastReason: String?,
    val deliveryState: DeliveryState,
    val attemptCount: Int,
) {
    init {
        require(callId.isNotBlank())
        require(deviceId.isNotBlank())
        require(stateSequence > 0)
        require(createdAtEpochMillis > 0)
        require(updatedAtEpochMillis >= createdAtEpochMillis)
        require(attemptCount >= 0)
    }
}

object CallStateTransitions {
    private val allowed = mapOf(
        CallState.REQUESTED to setOf(
            CallState.RINGING,
            CallState.ACCEPTED,
            CallState.REJECTED,
            CallState.ENDED,
            CallState.FAILED,
        ),
        CallState.RINGING to setOf(
            CallState.ACCEPTED,
            CallState.REJECTED,
            CallState.ENDED,
            CallState.FAILED,
        ),
        CallState.ACCEPTED to setOf(
            CallState.CONNECTING,
            CallState.CONNECTED,
            CallState.ENDED,
            CallState.FAILED,
        ),
        CallState.CONNECTING to setOf(
            CallState.CONNECTED,
            CallState.ENDED,
            CallState.FAILED,
        ),
        CallState.CONNECTED to setOf(CallState.ENDED, CallState.FAILED),
        CallState.REJECTED to emptySet(),
        CallState.ENDED to emptySet(),
        CallState.FAILED to emptySet(),
    )

    fun canTransition(from: CallState, to: CallState): Boolean = from == to || to in allowed.getValue(from)
}

data class IceServerConfig(
    val urls: List<String>,
    val username: String?,
    val credential: String?,
) {
    init {
        require(urls.isNotEmpty() && urls.all(String::isNotBlank))
        require((username == null) == (credential == null))
    }
}

data class IceConfiguration(
    val callId: String,
    val requesterId: String,
    val servers: List<IceServerConfig>,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(callId.isNotBlank())
        require(requesterId.isNotBlank())
        require(servers.isNotEmpty())
        require(issuedAtEpochMillis > 0)
        require(expiresAtEpochMillis > issuedAtEpochMillis)
    }

    fun isUsableAt(nowEpochMillis: Long, minimumRemainingMillis: Long = 30_000): Boolean =
        expiresAtEpochMillis - maxOf(nowEpochMillis, issuedAtEpochMillis) >= minimumRemainingMillis
}

enum class CallSignalType {
    OFFER,
    ANSWER,
    ICE_CANDIDATE,
    ICE_COMPLETE,
}

data class CallSignal(
    val signalId: String,
    val callId: String,
    val serverSequence: Long?,
    val senderId: String,
    val type: CallSignalType,
    val payloadJson: String,
    val createdAtEpochMillis: Long,
) {
    init {
        require(signalId.isNotBlank())
        require(callId.isNotBlank())
        require(serverSequence == null || serverSequence > 0)
        require(senderId.isNotBlank())
        require(payloadJson.isNotBlank())
        require(createdAtEpochMillis > 0)
    }
}

enum class BroadcastPlaybackState {
    RECEIVED,
    PLAYING,
    PLAYED,
    FAILED,
    EXPIRED,
}

data class TextBroadcast(
    val broadcastId: String,
    val deviceId: String,
    val serverSequence: Long,
    val text: String,
    val language: String,
    val priority: Int,
    val expiresAtEpochMillis: Long?,
    val playbackState: BroadcastPlaybackState,
    val receivedAtEpochMillis: Long,
    val playingAtEpochMillis: Long?,
    val playedAtEpochMillis: Long?,
    val lastError: String?,
    val receiptDeliveryState: DeliveryState,
    val receiptAttemptCount: Int,
) {
    init {
        require(broadcastId.isNotBlank())
        require(deviceId.isNotBlank())
        require(serverSequence > 0)
        require(text.isNotBlank() && text.length <= 2_000)
        require(language.isNotBlank())
        require(priority in 0..10)
        require(receivedAtEpochMillis > 0)
        require(receiptAttemptCount >= 0)
    }
}

enum class DeviceCommandType {
    CALL_STATE,
    TEXT_BROADCAST,
}

enum class DeviceCommandState {
    RECEIVED,
    APPLIED,
    FAILED,
}

data class DeviceCommand(
    val commandId: String,
    val deviceId: String,
    val serverSequence: Long,
    val type: DeviceCommandType,
    val payloadJson: String,
    val createdAtEpochMillis: Long,
    val state: DeviceCommandState,
    val receivedAtEpochMillis: Long,
    val appliedAtEpochMillis: Long?,
    val lastError: String?,
    val ackDeliveryState: DeliveryState,
    val ackAttemptCount: Int,
) {
    init {
        require(commandId.isNotBlank())
        require(deviceId.isNotBlank())
        require(serverSequence > 0)
        require(payloadJson.isNotBlank())
        require(createdAtEpochMillis > 0)
        require(receivedAtEpochMillis > 0)
        require(ackAttemptCount >= 0)
    }
}
