package com.example.helmet.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction

enum class HardwareKeyActionState {
    RECEIVED,
    APPLIED,
    FAILED,
}

data class HardwareKeyAction(
    val actionId: String,
    val input: String,
    val plannedAction: String,
    val plannedArgument: String? = null,
    val businessRequestId: Long? = null,
    val rejectionReason: String?,
    val targetCallId: String?,
    val simulated: Boolean,
    val monotonicMillis: Long,
    val hardwareEventId: Long?,
    val sequence: Int?,
    val receivedAtEpochMillis: Long,
    val state: HardwareKeyActionState = HardwareKeyActionState.RECEIVED,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "hardware_key_actions")
data class HardwareKeyActionEntity(
    @PrimaryKey val actionId: String,
    val input: String,
    val plannedAction: String,
    val plannedArgument: String?,
    val businessRequestId: Long?,
    val rejectionReason: String?,
    val targetCallId: String?,
    val simulated: Boolean,
    val monotonicMillis: Long,
    val hardwareEventId: Long?,
    val sequence: Int?,
    val state: String,
    val attemptCount: Int,
    val lastError: String?,
    val receivedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Dao
interface HardwareKeyActionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: HardwareKeyActionEntity): Long

    @Query("SELECT * FROM hardware_key_actions WHERE actionId = :actionId LIMIT 1")
    suspend fun find(actionId: String): HardwareKeyActionEntity?

    @Query(
        "SELECT * FROM hardware_key_actions WHERE state = 'RECEIVED' " +
            "ORDER BY receivedAtEpochMillis, actionId LIMIT :limit",
    )
    suspend fun pending(limit: Int): List<HardwareKeyActionEntity>

    @Query(
        "UPDATE hardware_key_actions SET state = 'APPLIED', attemptCount = attemptCount + 1, " +
            "lastError = NULL, updatedAtEpochMillis = :updatedAt " +
            "WHERE actionId = :actionId AND state = 'RECEIVED'",
    )
    suspend fun markApplied(actionId: String, updatedAt: Long): Int

    @Query(
        "UPDATE hardware_key_actions SET state = 'FAILED', attemptCount = attemptCount + 1, " +
            "lastError = :error, updatedAtEpochMillis = :updatedAt " +
            "WHERE actionId = :actionId AND state = 'RECEIVED'",
    )
    suspend fun markFailed(actionId: String, error: String, updatedAt: Long): Int

    @Query(
        "UPDATE hardware_key_actions SET attemptCount = attemptCount + 1, " +
            "lastError = :error, updatedAtEpochMillis = :updatedAt " +
            "WHERE actionId = :actionId AND state = 'RECEIVED'",
    )
    suspend fun recordRetryableFailure(actionId: String, error: String, updatedAt: Long): Int

}

class HardwareKeyActionStore(
    private val database: HelmetDatabase,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    suspend fun receive(action: HardwareKeyAction): HardwareKeyAction = database.withTransaction {
        database.hardwareKeyActionDao().insert(
            action.toEntity(updatedAtEpochMillis = action.receivedAtEpochMillis),
        )
        requireNotNull(database.hardwareKeyActionDao().find(action.actionId)).toModel().also { stored ->
            require(stored.hasSameIdentity(action)) { "hardware key action identity collision" }
        }
    }

    suspend fun pending(limit: Int = 100): List<HardwareKeyAction> {
        require(limit in 1..1_000)
        return database.hardwareKeyActionDao().pending(limit).map(HardwareKeyActionEntity::toModel)
    }

    suspend fun find(actionId: String): HardwareKeyAction? =
        database.hardwareKeyActionDao().find(actionId)?.toModel()

    suspend fun markApplied(actionId: String): Boolean =
        database.hardwareKeyActionDao().markApplied(actionId, wallClock()) == 1

    suspend fun markFailed(actionId: String, errorType: String): Boolean =
        database.hardwareKeyActionDao().markFailed(
            actionId,
            errorType.take(MAX_ERROR_TYPE_LENGTH),
            wallClock(),
        ) == 1

    suspend fun recordRetryableFailure(actionId: String, errorType: String): Boolean =
        database.hardwareKeyActionDao().recordRetryableFailure(
            actionId,
            errorType.take(MAX_ERROR_TYPE_LENGTH),
            wallClock(),
        ) == 1

    companion object {
        private const val MAX_ERROR_TYPE_LENGTH = 256
    }
}

private fun HardwareKeyAction.hasSameIdentity(other: HardwareKeyAction): Boolean =
    actionId == other.actionId &&
        input == other.input &&
        plannedAction == other.plannedAction &&
        plannedArgument == other.plannedArgument &&
        businessRequestId == other.businessRequestId &&
        rejectionReason == other.rejectionReason &&
        targetCallId == other.targetCallId &&
        simulated == other.simulated &&
        monotonicMillis == other.monotonicMillis &&
        hardwareEventId == other.hardwareEventId

private fun HardwareKeyAction.toEntity(updatedAtEpochMillis: Long) = HardwareKeyActionEntity(
    actionId = actionId,
    input = input,
    plannedAction = plannedAction,
    plannedArgument = plannedArgument,
    businessRequestId = businessRequestId,
    rejectionReason = rejectionReason,
    targetCallId = targetCallId,
    simulated = simulated,
    monotonicMillis = monotonicMillis,
    hardwareEventId = hardwareEventId,
    sequence = sequence,
    state = state.name,
    attemptCount = attemptCount,
    lastError = lastError,
    receivedAtEpochMillis = receivedAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun HardwareKeyActionEntity.toModel() = HardwareKeyAction(
    actionId = actionId,
    input = input,
    plannedAction = plannedAction,
    plannedArgument = plannedArgument,
    businessRequestId = businessRequestId,
    rejectionReason = rejectionReason,
    targetCallId = targetCallId,
    simulated = simulated,
    monotonicMillis = monotonicMillis,
    hardwareEventId = hardwareEventId,
    sequence = sequence,
    receivedAtEpochMillis = receivedAtEpochMillis,
    state = HardwareKeyActionState.valueOf(state),
    attemptCount = attemptCount,
    lastError = lastError,
)
