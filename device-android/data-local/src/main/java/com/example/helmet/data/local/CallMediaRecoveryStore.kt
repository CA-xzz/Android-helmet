package com.example.helmet.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction

@Entity(tableName = "call_media_recovery")
data class CallMediaRecoveryEntity(
    @PrimaryKey val callId: String,
    val generation: Long,
    val lastRemoteSequence: Long,
    val latestOfferSequence: Long?,
    val latestAnsweredOfferSequence: Long?,
    val restartAttemptCount: Int,
    val updatedAtEpochMillis: Long,
    val lastError: String?,
)

@Dao
interface CallMediaRecoveryDao {
    @Query("SELECT * FROM call_media_recovery WHERE callId = :callId LIMIT 1")
    suspend fun find(callId: String): CallMediaRecoveryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CallMediaRecoveryEntity)

    @Query("DELETE FROM call_media_recovery WHERE callId = :callId")
    suspend fun delete(callId: String): Int
}

data class CallMediaRecoveryState(
    val callId: String,
    val generation: Long,
    val lastRemoteSequence: Long,
    val latestOfferSequence: Long?,
    val latestAnsweredOfferSequence: Long?,
    val restartAttemptCount: Int,
    val updatedAtEpochMillis: Long,
    val lastError: String?,
) {
    val recoveredFromPreviousProcess: Boolean get() = generation > 1
    val awaitingAnswer: Boolean
        get() = latestOfferSequence != null && latestAnsweredOfferSequence != latestOfferSequence
}

class CallMediaRecoveryStore(
    private val database: HelmetDatabase,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    suspend fun begin(callId: String): CallMediaRecoveryState = database.withTransaction {
        require(callId.isNotBlank())
        val previous = database.callMediaRecoveryDao().find(callId)
        val state = CallMediaRecoveryEntity(
            callId = callId,
            generation = (previous?.generation ?: 0) + 1,
            lastRemoteSequence = previous?.lastRemoteSequence ?: 0,
            latestOfferSequence = null,
            latestAnsweredOfferSequence = null,
            restartAttemptCount = 0,
            updatedAtEpochMillis = wallClock(),
            lastError = null,
        )
        database.callMediaRecoveryDao().upsert(state)
        state.toModel()
    }

    suspend fun recordOffer(callId: String, generation: Long, serverSequence: Long): CallMediaRecoveryState =
        update(callId, generation) { current ->
            require(serverSequence > current.lastRemoteSequence) { "offer sequence did not advance" }
            current.copy(
                lastRemoteSequence = serverSequence,
                latestOfferSequence = serverSequence,
                updatedAtEpochMillis = wallClock(),
                lastError = null,
            )
        }

    suspend fun advanceRemote(callId: String, generation: Long, serverSequence: Long): CallMediaRecoveryState =
        update(callId, generation) { current ->
            require(serverSequence >= current.lastRemoteSequence) { "remote signal sequence regressed" }
            current.copy(
                lastRemoteSequence = serverSequence,
                updatedAtEpochMillis = wallClock(),
            )
        }

    suspend fun recordAnswer(callId: String, generation: Long, answerSequence: Long): CallMediaRecoveryState =
        update(callId, generation) { current ->
            val offerSequence = requireNotNull(current.latestOfferSequence) { "answer has no local offer" }
            require(answerSequence > offerSequence) { "answer sequence does not follow the offer" }
            current.copy(
                lastRemoteSequence = maxOf(current.lastRemoteSequence, answerSequence),
                latestAnsweredOfferSequence = offerSequence,
                updatedAtEpochMillis = wallClock(),
                lastError = null,
            )
        }

    suspend fun recordRestart(callId: String, generation: Long): CallMediaRecoveryState =
        update(callId, generation) { current ->
            current.copy(
                restartAttemptCount = current.restartAttemptCount + 1,
                updatedAtEpochMillis = wallClock(),
            )
        }

    suspend fun recordFailure(callId: String, generation: Long, errorCode: String): CallMediaRecoveryState =
        update(callId, generation) { current ->
            current.copy(
                updatedAtEpochMillis = wallClock(),
                lastError = errorCode.take(MAX_ERROR_LENGTH),
            )
        }

    suspend fun find(callId: String): CallMediaRecoveryState? =
        database.callMediaRecoveryDao().find(callId)?.toModel()

    suspend fun clear(callId: String): Boolean = database.callMediaRecoveryDao().delete(callId) == 1

    private suspend fun update(
        callId: String,
        generation: Long,
        transform: (CallMediaRecoveryEntity) -> CallMediaRecoveryEntity,
    ): CallMediaRecoveryState = database.withTransaction {
        val current = requireNotNull(database.callMediaRecoveryDao().find(callId)) {
            "call media recovery state not found"
        }
        require(current.generation == generation) { "stale call media recovery generation" }
        val updated = transform(current)
        database.callMediaRecoveryDao().upsert(updated)
        updated.toModel()
    }

    private fun CallMediaRecoveryEntity.toModel() = CallMediaRecoveryState(
        callId = callId,
        generation = generation,
        lastRemoteSequence = lastRemoteSequence,
        latestOfferSequence = latestOfferSequence,
        latestAnsweredOfferSequence = latestAnsweredOfferSequence,
        restartAttemptCount = restartAttemptCount,
        updatedAtEpochMillis = updatedAtEpochMillis,
        lastError = lastError,
    )

    companion object {
        private const val MAX_ERROR_LENGTH = 128
    }
}
