package com.example.helmet.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallSessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CallSessionEntity): Long

    @Query("SELECT * FROM call_sessions WHERE callId = :callId LIMIT 1")
    suspend fun find(callId: String): CallSessionEntity?

    @Query("SELECT * FROM call_sessions ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CallSessionEntity>>

    @Query("SELECT * FROM call_sessions ORDER BY updatedAtEpochMillis DESC LIMIT 1")
    suspend fun latest(): CallSessionEntity?

    @Query("SELECT * FROM call_sessions WHERE deliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED') ORDER BY createdAtEpochMillis, stateSequence LIMIT :limit")
    suspend fun pending(limit: Int): List<CallSessionEntity>

    @Query("SELECT COUNT(*) FROM call_sessions WHERE deliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED')")
    suspend fun pendingCount(): Int

    @Query("UPDATE call_sessions SET state = :state, stateSequence = :newSequence, updatedAtEpochMillis = :updatedAt, lastReason = :reason, deliveryState = 'PENDING' WHERE callId = :callId AND stateSequence = :expectedSequence")
    suspend fun transition(
        callId: String,
        expectedSequence: Long,
        newSequence: Long,
        state: String,
        updatedAt: Long,
        reason: String?,
    ): Int

    @Query("UPDATE call_sessions SET deliveryState = 'IN_FLIGHT', attemptCount = attemptCount + 1, lastAttemptAtEpochMillis = :attemptAt WHERE callId = :callId AND deliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markAttempt(callId: String, attemptAt: Long): Int

    @Query("UPDATE call_sessions SET deliveryState = 'DELIVERED', deliveredAtEpochMillis = :deliveredAt WHERE callId = :callId")
    suspend fun markDelivered(callId: String, deliveredAt: Long): Int

    @Query("UPDATE call_sessions SET deliveryState = 'FAILED', lastReason = :error WHERE callId = :callId AND deliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markFailed(callId: String, error: String): Int
}
