package com.example.helmet.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackPointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: TrackPointEntity): Long

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM track_points WHERE deviceId = :deviceId")
    suspend fun maxSequence(deviceId: String): Long

    @Query("SELECT * FROM track_points WHERE messageId = :messageId LIMIT 1")
    suspend fun find(messageId: String): TrackPointEntity?

    @Query("SELECT * FROM track_points ORDER BY occurredAtEpochMillis DESC, sequence DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE deliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED') ORDER BY deviceId, sequence LIMIT :limit")
    suspend fun pending(limit: Int): List<TrackPointEntity>

    @Query("SELECT COUNT(*) FROM track_points WHERE deliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED')")
    suspend fun pendingCount(): Int

    @Query("UPDATE track_points SET deliveryState = 'IN_FLIGHT', attemptCount = attemptCount + 1, lastAttemptAtEpochMillis = :attemptAt, lastError = NULL WHERE messageId = :messageId AND deliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markAttempt(messageId: String, attemptAt: Long): Int

    @Query("UPDATE track_points SET deliveryState = 'DELIVERED', deliveredAtEpochMillis = :deliveredAt, lastError = NULL WHERE messageId = :messageId AND deliveryState != 'REJECTED'")
    suspend fun markDelivered(messageId: String, deliveredAt: Long): Int

    @Query("UPDATE track_points SET deliveryState = 'FAILED', lastError = :error WHERE messageId = :messageId AND deliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markFailed(messageId: String, error: String): Int

    @Query("UPDATE track_points SET deliveryState = 'REJECTED', lastError = :error WHERE messageId = :messageId AND deliveryState != 'DELIVERED'")
    suspend fun markRejected(messageId: String, error: String): Int

    @Query(
        "DELETE FROM track_points WHERE deviceId = :deviceId " +
            "AND deliveryState IN ('DELIVERED', 'REJECTED') " +
            "AND messageId NOT IN (" +
            "SELECT messageId FROM track_points WHERE deviceId = :deviceId " +
            "AND deliveryState IN ('DELIVERED', 'REJECTED') " +
            "ORDER BY sequence DESC LIMIT :keepPerDevice" +
            ")",
    )
    suspend fun pruneTerminal(deviceId: String, keepPerDevice: Int): Int
}
