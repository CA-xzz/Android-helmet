package com.example.helmet.data.local

import androidx.room.withTransaction
import com.example.helmet.core.model.CallDirection
import com.example.helmet.core.model.CallMediaMode
import com.example.helmet.core.model.CallSession
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.CallStateTransitions
import com.example.helmet.core.model.DeliveryState
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CallStore(
    private val database: HelmetDatabase,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    suspend fun createOutgoing(
        deviceId: String,
        relatedEventId: String?,
        simulated: Boolean,
        mediaMode: CallMediaMode = CallMediaMode.VIDEO_UPLINK,
        callId: String = UUID.randomUUID().toString(),
    ): CallSession? {
        val now = wallClock()
        val entity = CallSessionEntity(
            callId = callId,
            deviceId = deviceId,
            direction = CallDirection.OUTGOING_DEVICE.name,
            mediaMode = mediaMode.name,
            state = CallState.REQUESTED.name,
            stateSequence = 1,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            relatedEventId = relatedEventId,
            simulated = simulated,
            lastReason = null,
            deliveryState = DeliveryState.PENDING.name,
            attemptCount = 0,
            lastAttemptAtEpochMillis = null,
            deliveredAtEpochMillis = null,
        )
        return if (database.callSessionDao().insert(entity) == -1L) null else entity.toModel()
    }

    suspend fun transition(callId: String, target: CallState, reason: String? = null): CallSession =
        database.withTransaction {
            val current = requireNotNull(database.callSessionDao().find(callId)) { "call session not found" }
            val currentState = CallState.valueOf(current.state)
            require(CallStateTransitions.canTransition(currentState, target)) {
                "invalid call transition $currentState -> $target"
            }
            if (currentState == target) return@withTransaction current.toModel()
            val changed = database.callSessionDao().transition(
                callId = callId,
                expectedSequence = current.stateSequence,
                newSequence = current.stateSequence + 1,
                state = target.name,
                updatedAt = wallClock().coerceAtLeast(current.createdAtEpochMillis),
                reason = reason?.take(MAX_REASON_LENGTH),
            )
            check(changed == 1) { "call session changed concurrently" }
            requireNotNull(database.callSessionDao().find(callId)).toModel()
        }

    suspend fun find(callId: String): CallSession? = database.callSessionDao().find(callId)?.toModel()
    suspend fun latest(): CallSession? = database.callSessionDao().latest()?.toModel()
    fun observeRecent(limit: Int = 20): Flow<List<CallSession>> =
        database.callSessionDao().observeRecent(limit).map { rows -> rows.map(CallSessionEntity::toModel) }
    suspend fun pending(limit: Int = 50): List<CallSession> = database.callSessionDao().pending(limit).map(CallSessionEntity::toModel)
    suspend fun pendingCount(): Int = database.callSessionDao().pendingCount()
    suspend fun markAttempt(callId: String, at: Long) = database.callSessionDao().markAttempt(callId, at) == 1
    suspend fun markDelivered(callId: String, at: Long) = database.callSessionDao().markDelivered(callId, at) == 1
    suspend fun markFailed(callId: String, error: String) =
        database.callSessionDao().markFailed(callId, error.take(MAX_REASON_LENGTH)) == 1

    companion object {
        private const val MAX_REASON_LENGTH = 1_024
    }
}
