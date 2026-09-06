package com.example.helmet.data.local

import androidx.room.Entity

@Entity(
    tableName = "device_command_cursors",
    primaryKeys = ["commandStreamId", "deviceId"],
)
data class DeviceCommandCursorEntity(
    val commandStreamId: String,
    val deviceId: String,
    val afterSequence: Long,
    val maxObservedHighWater: Long = afterSequence,
)
