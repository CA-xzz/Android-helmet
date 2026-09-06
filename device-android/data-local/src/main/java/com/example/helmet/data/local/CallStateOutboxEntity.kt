package com.example.helmet.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(
    tableName = "call_state_outbox",
    primaryKeys = ["callId", "stateSequence"],
    indices = [Index(value = ["deliveryState", "occurredAtEpochMillis"])],
)
data class CallStateOutboxEntity(
    val callId: String,
    val stateSequence: Long,
    val state: String,
    val actorId: String,
    val occurredAtEpochMillis: Long,
    val reason: String?,
    val deliveryState: String,
    val attemptCount: Int,
    val lastAttemptAtEpochMillis: Long?,
    val deliveredAtEpochMillis: Long?,
    val lastError: String?,
)

@Dao
interface CallStateOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CallStateOutboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entity: CallStateOutboxEntity)

    @Query(
        "SELECT * FROM call_state_outbox " +
            "WHERE deliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED') " +
            "ORDER BY callId, stateSequence LIMIT :limit",
    )
    suspend fun pending(limit: Int): List<CallStateOutboxEntity>

    @Query("SELECT * FROM call_state_outbox WHERE callId = :callId ORDER BY stateSequence")
    suspend fun history(callId: String): List<CallStateOutboxEntity>

    @Query(
        "SELECT * FROM call_state_outbox WHERE callId = :callId AND stateSequence = :stateSequence LIMIT 1",
    )
    suspend fun find(callId: String, stateSequence: Long): CallStateOutboxEntity?

    @Query(
        "SELECT COUNT(*) FROM call_state_outbox " +
            "WHERE deliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED')",
    )
    suspend fun pendingCount(): Int

    @Query(
        "UPDATE call_state_outbox SET deliveryState = 'IN_FLIGHT', " +
            "attemptCount = attemptCount + 1, lastAttemptAtEpochMillis = :attemptAt, lastError = NULL " +
            "WHERE callId = :callId AND stateSequence = :stateSequence " +
            "AND deliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED')",
    )
    suspend fun markAttempt(callId: String, stateSequence: Long, attemptAt: Long): Int

    @Query(
        "UPDATE call_state_outbox SET deliveryState = 'IN_FLIGHT', " +
            "attemptCount = attemptCount + 1, lastAttemptAtEpochMillis = :attemptAt " +
            "WHERE callId = :callId AND stateSequence = :stateSequence " +
            "AND deliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED')",
    )
    suspend fun markLegacyReconciliationAttempt(callId: String, stateSequence: Long, attemptAt: Long): Int

    @Query(
        "UPDATE call_state_outbox SET deliveryState = 'DELIVERED', " +
            "deliveredAtEpochMillis = :deliveredAt, lastError = NULL " +
            "WHERE callId = :callId AND stateSequence = :stateSequence " +
            "AND deliveryState = 'IN_FLIGHT'",
    )
    suspend fun markDelivered(callId: String, stateSequence: Long, deliveredAt: Long): Int

    @Query(
        "UPDATE call_state_outbox SET deliveryState = 'FAILED', lastError = :error " +
            "WHERE callId = :callId AND stateSequence = :stateSequence " +
            "AND deliveryState != 'DELIVERED' AND deliveryState != 'REJECTED'",
    )
    suspend fun markFailed(callId: String, stateSequence: Long, error: String): Int

    @Query(
        "UPDATE call_state_outbox SET deliveryState = 'FAILED', lastError = :error " +
            "WHERE callId = :callId AND stateSequence = :stateSequence " +
            "AND deliveryState != 'DELIVERED' AND deliveryState != 'REJECTED'",
    )
    suspend fun markLegacyReconciliationFailed(callId: String, stateSequence: Long, error: String): Int

    @Query(
        "UPDATE call_state_outbox SET deliveryState = 'REJECTED', lastError = :error " +
            "WHERE callId = :callId AND stateSequence = :stateSequence " +
            "AND deliveryState != 'DELIVERED'",
    )
    suspend fun markRejected(callId: String, stateSequence: Long, error: String): Int

    @Query(
        "DELETE FROM call_state_outbox WHERE callId = :callId AND stateSequence > :serverSequence " +
            "AND lastError = :reconciledError",
    )
    suspend fun deleteLegacyFutureRows(
        callId: String,
        serverSequence: Long,
        reconciledError: String,
    ): Int
}
