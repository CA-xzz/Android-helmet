package com.example.helmet.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeviceCommandDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: DeviceCommandEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<DeviceCommandEntity>): List<Long>

    @Query("SELECT * FROM device_commands WHERE commandStreamId = :commandStreamId AND commandId = :commandId LIMIT 1")
    suspend fun find(commandStreamId: String, commandId: String): DeviceCommandEntity?

    @Query("SELECT * FROM device_commands WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId ORDER BY serverSequence")
    suspend fun commandsForStream(commandStreamId: String, deviceId: String): List<DeviceCommandEntity>

    @Query("SELECT * FROM device_commands WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND serverSequence > :afterSequence ORDER BY serverSequence LIMIT :limit")
    suspend fun commandsAfter(
        commandStreamId: String,
        deviceId: String,
        afterSequence: Long,
        limit: Int,
    ): List<DeviceCommandEntity>

    @Query("SELECT * FROM device_commands WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND serverSequence = :serverSequence LIMIT 1")
    suspend fun findBySequence(
        commandStreamId: String,
        deviceId: String,
        serverSequence: Long,
    ): DeviceCommandEntity?

    @Query("SELECT DISTINCT commandStreamId FROM device_commands WHERE deviceId = :deviceId AND serverSequence = 1 AND commandId = :commandId")
    suspend fun streamsWithFirstCommand(deviceId: String, commandId: String): List<String>

    @Query("SELECT COUNT(*) FROM device_commands WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId")
    suspend fun commandCountForStream(commandStreamId: String, deviceId: String): Long

    @Query("UPDATE device_commands SET state = CASE WHEN state = 'RECEIVED' THEN 'ACKNOWLEDGED' ELSE state END, ackDeliveryState = 'DELIVERED', ackDeliveredAtEpochMillis = COALESCE(ackDeliveredAtEpochMillis, receivedAtEpochMillis), ackLastError = NULL WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND commandId = :commandId")
    suspend fun markServerAcknowledged(
        commandStreamId: String,
        deviceId: String,
        commandId: String,
    ): Int

    @Query("UPDATE device_commands SET ackDeliveryState = 'PENDING', ackDeliveredAtEpochMillis = NULL, ackLastError = NULL WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND commandId = :commandId AND state IN ('APPLIED', 'FAILED') AND ackDeliveryState != 'PENDING'")
    suspend fun resetUnacknowledgedDeliveredResult(
        commandStreamId: String,
        deviceId: String,
        commandId: String,
    ): Int

    @Query("SELECT * FROM device_command_cursors WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId LIMIT 1")
    suspend fun findCursor(commandStreamId: String, deviceId: String): DeviceCommandCursorEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCursor(entity: DeviceCommandCursorEntity)

    @Query("DELETE FROM device_command_cursors WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId")
    suspend fun deleteCursor(commandStreamId: String, deviceId: String): Int

    @Query(
        "UPDATE device_command_cursors SET afterSequence = :nextSequence, " +
            "maxObservedHighWater = CASE WHEN maxObservedHighWater < :nextSequence " +
            "THEN :nextSequence ELSE maxObservedHighWater END " +
            "WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId " +
            "AND afterSequence = :expectedSequence",
    )
    suspend fun advanceCursor(
        commandStreamId: String,
        deviceId: String,
        expectedSequence: Long,
        nextSequence: Long,
    ): Int

    @Query(
        "UPDATE device_command_cursors SET maxObservedHighWater = :observedHighWater " +
            "WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId " +
            "AND maxObservedHighWater < :observedHighWater",
    )
    suspend fun raiseCursorHighWater(
        commandStreamId: String,
        deviceId: String,
        observedHighWater: Long,
    ): Int

    @Query("SELECT * FROM device_commands WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND state = 'RECEIVED' ORDER BY serverSequence LIMIT :limit")
    suspend fun pendingApplication(
        commandStreamId: String,
        deviceId: String,
        limit: Int,
    ): List<DeviceCommandEntity>

    @Query("SELECT COUNT(*) FROM device_commands WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND state = 'RECEIVED'")
    suspend fun pendingApplicationCount(commandStreamId: String, deviceId: String): Int

    @Query("SELECT * FROM device_commands WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND state IN ('APPLIED', 'FAILED') AND ackDeliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED') ORDER BY serverSequence LIMIT :limit")
    suspend fun pendingAcks(
        commandStreamId: String,
        deviceId: String,
        limit: Int,
    ): List<DeviceCommandEntity>

    @Query("SELECT COUNT(*) FROM device_commands WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND state IN ('APPLIED', 'FAILED') AND ackDeliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED')")
    suspend fun pendingAckCount(commandStreamId: String, deviceId: String): Int

    @Query("UPDATE device_commands SET state = :state, appliedAtEpochMillis = :appliedAt, lastError = :error, ackDeliveryState = 'PENDING', ackLastError = NULL WHERE commandStreamId = :commandStreamId AND commandId = :commandId AND state = 'RECEIVED'")
    suspend fun markApplied(
        commandStreamId: String,
        commandId: String,
        state: String,
        appliedAt: Long,
        error: String?,
    ): Int

    @Query("UPDATE device_commands SET ackDeliveryState = 'IN_FLIGHT', ackAttemptCount = ackAttemptCount + 1, lastAckAttemptAtEpochMillis = :attemptAt, ackLastError = NULL WHERE commandStreamId = :commandStreamId AND commandId = :commandId AND ackDeliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markAckAttempt(commandStreamId: String, commandId: String, attemptAt: Long): Int

    @Query("UPDATE device_commands SET ackDeliveryState = 'DELIVERED', ackDeliveredAtEpochMillis = :deliveredAt, ackLastError = NULL WHERE commandStreamId = :commandStreamId AND commandId = :commandId AND ackDeliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markAckDelivered(commandStreamId: String, commandId: String, deliveredAt: Long): Int

    @Query("UPDATE device_commands SET ackDeliveryState = 'FAILED', ackLastError = :error WHERE commandStreamId = :commandStreamId AND commandId = :commandId AND ackDeliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markAckFailed(commandStreamId: String, commandId: String, error: String): Int

    @Query("UPDATE device_commands SET ackDeliveryState = 'REJECTED', ackLastError = :error WHERE commandStreamId = :commandStreamId AND commandId = :commandId AND ackDeliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markAckRejected(commandStreamId: String, commandId: String, error: String): Int
}
