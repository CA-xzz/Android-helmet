package com.example.helmet.data.local

import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.DeviceCommand
import com.example.helmet.core.model.DeviceCommandState

class DeviceCommandStore(private val database: HelmetDatabase) {
    suspend fun receive(command: DeviceCommand): Boolean {
        require(command.state == DeviceCommandState.RECEIVED)
        val entity = command.toEntity()
        val inserted = database.deviceCommandDao().insert(entity) != -1L
        if (!inserted) {
            val existing = requireNotNull(database.deviceCommandDao().find(command.commandId)).toModel()
            require(
                existing.deviceId == command.deviceId &&
                    existing.serverSequence == command.serverSequence &&
                    existing.type == command.type &&
                    existing.payloadJson == command.payloadJson
            ) { "command ID conflicts with stored content" }
        }
        return inserted
    }

    suspend fun find(id: String): DeviceCommand? = database.deviceCommandDao().find(id)?.toModel()
    suspend fun maxSequence(deviceId: String): Long = database.deviceCommandDao().maxSequence(deviceId)
    suspend fun pendingApplication(limit: Int = 100): List<DeviceCommand> =
        database.deviceCommandDao().pendingApplication(limit).map(DeviceCommandEntity::toModel)
    suspend fun pendingApplicationCount(): Int = database.deviceCommandDao().pendingApplicationCount()
    suspend fun pendingAcks(limit: Int = 100): List<DeviceCommand> =
        database.deviceCommandDao().pendingAcks(limit).map(DeviceCommandEntity::toModel)
    suspend fun pendingAckCount(): Int = database.deviceCommandDao().pendingAckCount()
    suspend fun markApplied(id: String, at: Long): Boolean =
        database.deviceCommandDao().markApplied(id, DeviceCommandState.APPLIED.name, at, null) == 1
    suspend fun markFailed(id: String, at: Long, error: String): Boolean =
        database.deviceCommandDao().markApplied(
            id,
            DeviceCommandState.FAILED.name,
            at,
            error.take(MAX_ERROR_LENGTH),
        ) == 1
    suspend fun markAckAttempt(id: String, at: Long): Boolean =
        database.deviceCommandDao().markAckAttempt(id, at) == 1
    suspend fun markAckDelivered(id: String, at: Long): Boolean =
        database.deviceCommandDao().markAckDelivered(id, at) == 1
    suspend fun markAckFailed(id: String, error: String): Boolean =
        database.deviceCommandDao().markAckFailed(id, error.take(MAX_ERROR_LENGTH)) == 1

    private fun DeviceCommand.toEntity() = DeviceCommandEntity(
        commandId = commandId,
        deviceId = deviceId,
        serverSequence = serverSequence,
        type = type.name,
        payloadJson = payloadJson,
        createdAtEpochMillis = createdAtEpochMillis,
        state = state.name,
        receivedAtEpochMillis = receivedAtEpochMillis,
        appliedAtEpochMillis = appliedAtEpochMillis,
        lastError = lastError,
        ackDeliveryState = DeliveryState.PENDING.name,
        ackAttemptCount = ackAttemptCount,
        lastAckAttemptAtEpochMillis = null,
        ackDeliveredAtEpochMillis = null,
    )

    companion object {
        private const val MAX_ERROR_LENGTH = 1_024
    }
}
