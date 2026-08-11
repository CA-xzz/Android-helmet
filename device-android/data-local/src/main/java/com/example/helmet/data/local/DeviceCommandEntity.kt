package com.example.helmet.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType

@Entity(
    tableName = "device_commands",
    indices = [
        Index(value = ["deviceId", "serverSequence"], unique = true),
        Index(value = ["state", "serverSequence"]),
        Index(value = ["ackDeliveryState", "serverSequence"]),
    ],
)
data class DeviceCommandEntity(
    @PrimaryKey val commandId: String,
    val deviceId: String,
    val serverSequence: Long,
    val type: String,
    val payloadJson: String,
    val createdAtEpochMillis: Long,
    val state: String,
    val receivedAtEpochMillis: Long,
    val appliedAtEpochMillis: Long?,
    val lastError: String?,
    val ackDeliveryState: String,
    val ackAttemptCount: Int,
    val lastAckAttemptAtEpochMillis: Long?,
    val ackDeliveredAtEpochMillis: Long?,
) {
    fun toModel() = DeviceCommand(
        commandId = commandId,
        deviceId = deviceId,
        serverSequence = serverSequence,
        type = DeviceCommandType.valueOf(type),
        payloadJson = payloadJson,
        createdAtEpochMillis = createdAtEpochMillis,
        state = DeviceCommandState.valueOf(state),
        receivedAtEpochMillis = receivedAtEpochMillis,
        appliedAtEpochMillis = appliedAtEpochMillis,
        lastError = lastError,
        ackDeliveryState = DeliveryState.valueOf(ackDeliveryState),
        ackAttemptCount = ackAttemptCount,
    )
}
