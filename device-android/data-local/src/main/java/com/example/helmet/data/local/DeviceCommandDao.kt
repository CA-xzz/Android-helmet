package com.example.helmet.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeviceCommandDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: DeviceCommandEntity): Long

    @Query("SELECT * FROM device_commands WHERE commandId = :commandId LIMIT 1")
    suspend fun find(commandId: String): DeviceCommandEntity?

    @Query("SELECT COALESCE(MAX(serverSequence), 0) FROM device_commands WHERE deviceId = :deviceId")
    suspend fun maxSequence(deviceId: String): Long

    @Query("SELECT * FROM device_commands WHERE state = 'RECEIVED' ORDER BY deviceId, serverSequence LIMIT :limit")
    suspend fun pendingApplication(limit: Int): List<DeviceCommandEntity>

    @Query("SELECT COUNT(*) FROM device_commands WHERE state = 'RECEIVED'")
    suspend fun pendingApplicationCount(): Int

    @Query("SELECT * FROM device_commands WHERE state IN ('APPLIED', 'FAILED') AND ackDeliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED') ORDER BY deviceId, serverSequence LIMIT :limit")
    suspend fun pendingAcks(limit: Int): List<DeviceCommandEntity>

    @Query("SELECT COUNT(*) FROM device_commands WHERE state IN ('APPLIED', 'FAILED') AND ackDeliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED')")
    suspend fun pendingAckCount(): Int

    @Query("UPDATE device_commands SET state = :state, appliedAtEpochMillis = :appliedAt, lastError = :error, ackDeliveryState = 'PENDING' WHERE commandId = :commandId")
    suspend fun markApplied(commandId: String, state: String, appliedAt: Long, error: String?): Int

    @Query("UPDATE device_commands SET ackDeliveryState = 'IN_FLIGHT', ackAttemptCount = ackAttemptCount + 1, lastAckAttemptAtEpochMillis = :attemptAt WHERE commandId = :commandId AND ackDeliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markAckAttempt(commandId: String, attemptAt: Long): Int

    @Query("UPDATE device_commands SET ackDeliveryState = 'DELIVERED', ackDeliveredAtEpochMillis = :deliveredAt WHERE commandId = :commandId")
    suspend fun markAckDelivered(commandId: String, deliveredAt: Long): Int

    @Query("UPDATE device_commands SET ackDeliveryState = 'FAILED', lastError = :error WHERE commandId = :commandId AND ackDeliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markAckFailed(commandId: String, error: String): Int
}
