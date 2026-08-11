package com.example.helmet.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.helmet.core.model.CallDirection
import com.example.helmet.core.model.CallMediaMode
import com.example.helmet.core.model.CallSession
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.DeliveryState

@Entity(
    tableName = "call_sessions",
    indices = [Index(value = ["deviceId", "updatedAtEpochMillis"])],
)
data class CallSessionEntity(
    @PrimaryKey val callId: String,
    val deviceId: String,
    val direction: String,
    val mediaMode: String,
    val state: String,
    val stateSequence: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val relatedEventId: String?,
    val simulated: Boolean,
    val lastReason: String?,
    val deliveryState: String,
    val attemptCount: Int,
    val lastAttemptAtEpochMillis: Long?,
    val deliveredAtEpochMillis: Long?,
) {
    fun toModel() = CallSession(
        callId = callId,
        deviceId = deviceId,
        direction = CallDirection.valueOf(direction),
        mediaMode = CallMediaMode.valueOf(mediaMode),
        state = CallState.valueOf(state),
        stateSequence = stateSequence,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        relatedEventId = relatedEventId,
        simulated = simulated,
        lastReason = lastReason,
        deliveryState = DeliveryState.valueOf(deliveryState),
        attemptCount = attemptCount,
    )
}
