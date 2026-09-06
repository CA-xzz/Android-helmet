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

    @Query("SELECT * FROM call_sessions WHERE state NOT IN ('REJECTED', 'ENDED', 'FAILED') ORDER BY updatedAtEpochMillis DESC LIMIT 1")
    suspend fun active(): CallSessionEntity?

    @Query("SELECT * FROM call_sessions WHERE state NOT IN ('REJECTED', 'ENDED', 'FAILED') ORDER BY updatedAtEpochMillis DESC LIMIT 1")
    fun observeActive(): Flow<CallSessionEntity?>

    @Query(
        "UPDATE call_sessions SET state = :state, stateSequence = :newSequence, " +
            "updatedAtEpochMillis = :updatedAt, lastReason = :reason, deliveryState = :deliveryState, " +
            "attemptCount = :attemptCount, lastAttemptAtEpochMillis = :lastAttemptAt, " +
            "deliveredAtEpochMillis = :deliveredAt " +
            "WHERE callId = :callId AND stateSequence = :expectedSequence",
    )
    suspend fun transition(
        callId: String,
        expectedSequence: Long,
        newSequence: Long,
        state: String,
        updatedAt: Long,
        reason: String?,
        deliveryState: String,
        attemptCount: Int,
        lastAttemptAt: Long?,
        deliveredAt: Long?,
    ): Int

    @Query(
        "UPDATE call_sessions SET deliveryState = 'IN_FLIGHT', " +
            "attemptCount = attemptCount + 1, lastAttemptAtEpochMillis = :attemptAt, " +
            "deliveredAtEpochMillis = NULL " +
            "WHERE callId = :callId AND stateSequence = :stateSequence",
    )
    suspend fun markAttemptIfCurrent(callId: String, stateSequence: Long, attemptAt: Long): Int

    @Query("UPDATE call_sessions SET deliveryState = :deliveryState, deliveredAtEpochMillis = :deliveredAt WHERE callId = :callId AND stateSequence = :stateSequence")
    suspend fun updateDeliveryIfCurrent(
        callId: String,
        stateSequence: Long,
        deliveryState: String,
        deliveredAt: Long?,
    ): Int
}
