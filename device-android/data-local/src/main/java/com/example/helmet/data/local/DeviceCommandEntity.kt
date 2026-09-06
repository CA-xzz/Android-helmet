package com.example.helmet.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState
import com.example.helmet.core.model.DeviceCommandType

@Entity(
    tableName = "device_commands",
    primaryKeys = ["commandStreamId", "commandId"],
    indices = [
        Index(value = ["commandStreamId", "deviceId", "serverSequence"], unique = true),
        Index(value = ["commandStreamId", "deviceId", "state", "serverSequence"]),
        Index(value = ["commandStreamId", "deviceId", "ackDeliveryState", "serverSequence"]),
    ],
)
data class DeviceCommandEntity(
    val commandId: String,
    @ColumnInfo(defaultValue = "'legacy-v13-unscoped'")
    val commandStreamId: String,
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
    val ackLastError: String? = null,
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
