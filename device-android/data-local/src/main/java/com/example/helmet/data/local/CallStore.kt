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
    ): CallSession? = database.withTransaction {
        database.callSessionDao().find(callId)?.let { existing ->
            require(
                existing.deviceId == deviceId &&
                    existing.direction == CallDirection.OUTGOING_DEVICE.name &&
                    existing.mediaMode == mediaMode.name &&
                    existing.relatedEventId == relatedEventId &&
                    existing.simulated == simulated,
            ) { "call ID identity collision" }
            return@withTransaction existing.toModel()
        }
        if (database.callSessionDao().active() != null) return@withTransaction null
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
        if (database.callSessionDao().insert(entity) == -1L) return@withTransaction null
        check(database.callStateOutboxDao().insert(entity.toOutbox()) != -1L)
        entity.toModel()
    }

    suspend fun transition(
        callId: String,
        target: CallState,
        reason: String? = null,
    ): CallSession =
        database.withTransaction {
            val current = requireNotNull(database.callSessionDao().find(callId)) { "call session not found" }
            val currentState = CallState.valueOf(current.state)
            require(CallStateTransitions.canTransition(currentState, target)) {
                "invalid call transition $currentState -> $target"
            }
            if (currentState == target) return@withTransaction current.toModel()
            check(current.updatedAtEpochMillis < Long.MAX_VALUE) { "call transition time is exhausted" }
            val minimumNextTime = current.updatedAtEpochMillis + 1
            val updatedAt = wallClock().coerceAtLeast(minimumNextTime)
            check(current.stateSequence < Long.MAX_VALUE) { "call state sequence is exhausted" }
            val newSequence = current.stateSequence + 1
            val normalizedReason = reason?.take(MAX_REASON_LENGTH)
            val changed = database.callSessionDao().transition(
                callId = callId,
                expectedSequence = current.stateSequence,
                newSequence = newSequence,
                state = target.name,
                updatedAt = updatedAt,
                reason = normalizedReason,
                deliveryState = DeliveryState.PENDING.name,
                attemptCount = 0,
                lastAttemptAt = null,
                deliveredAt = null,
            )
            check(changed == 1) { "call session changed concurrently" }
            check(
                database.callStateOutboxDao().insert(
                    CallStateOutboxEntity(
                        callId = callId,
                        stateSequence = newSequence,
                        state = target.name,
                        actorId = current.deviceId,
                        occurredAtEpochMillis = updatedAt,
                        reason = normalizedReason,
                        deliveryState = DeliveryState.PENDING.name,
                        attemptCount = 0,
                        lastAttemptAtEpochMillis = null,
                        deliveredAtEpochMillis = null,
                        lastError = null,
                    ),
                ) != -1L,
            ) { "failed to enqueue call state transition" }
            requireNotNull(database.callSessionDao().find(callId)).toModel()
        }

    /** Applies a server-authored transition without rewriting its sequence or event time. */
    suspend fun applyRemoteTransition(
        callId: String,
        target: CallState,
        stateSequence: Long,
        occurredAtEpochMillis: Long,
        reason: String?,
    ): CallSession = database.withTransaction {
        require(stateSequence > 1) { "remote call transition sequence must be greater than one" }
        require(occurredAtEpochMillis > 0) { "remote call transition time must be positive" }
        val normalizedReason = reason?.also {
            require(it.length <= MAX_REASON_LENGTH) { "remote call transition reason is too long" }
        }
        val current = requireNotNull(database.callSessionDao().find(callId)) { "call session not found" }
        val currentState = CallState.valueOf(current.state)
        if (stateSequence == current.stateSequence) {
            if (currentState != target && target.isAuthoritativeRemoteTerminal()) {
                val localOutbox = requireNotNull(
                    database.callStateOutboxDao().find(callId, stateSequence),
                ) { "same-sequence remote terminal has no local optimistic transition" }
                require(localOutbox.state == current.state) {
                    "same-sequence local outbox does not match current call state"
                }
                require(localOutbox.deliveryState in setOf("PENDING", "IN_FLIGHT", "FAILED")) {
                    "delivered call state cannot be superseded at the same sequence"
                }
                check(
                    database.callStateOutboxDao().markRejected(
                        callId,
                        stateSequence,
                        REMOTE_TERMINAL_SUPERSEDED_ERROR,
                    ) == 1,
                ) { "failed to supersede local call transition" }
                check(
                    database.callSessionDao().transition(
                        callId = callId,
                        expectedSequence = current.stateSequence,
                        newSequence = stateSequence,
                        state = target.name,
                        updatedAt = maxOf(occurredAtEpochMillis, current.updatedAtEpochMillis),
                        reason = normalizedReason,
                        deliveryState = DeliveryState.DELIVERED.name,
                        attemptCount = 0,
                        lastAttemptAt = null,
                        deliveredAt = occurredAtEpochMillis,
                    ) == 1,
                ) { "call session changed concurrently" }
                return@withTransaction requireNotNull(database.callSessionDao().find(callId)).toModel()
            }
            require(
                currentState == target &&
                    current.updatedAtEpochMillis == occurredAtEpochMillis &&
                    current.lastReason == normalizedReason
            ) { "remote call transition conflicts with current sequence" }
            return@withTransaction current.toModel()
        }
        check(current.stateSequence < Long.MAX_VALUE) { "call state sequence is exhausted" }
        require(stateSequence == current.stateSequence + 1) { "remote call transition is stale or has a sequence gap" }
        require(occurredAtEpochMillis > current.updatedAtEpochMillis) { "remote call transition time is not monotonic" }
        require(CallStateTransitions.canTransition(currentState, target)) {
            "invalid remote call transition $currentState -> $target"
        }
        check(
            database.callSessionDao().transition(
                callId = callId,
                expectedSequence = current.stateSequence,
                newSequence = stateSequence,
                state = target.name,
                updatedAt = occurredAtEpochMillis,
                reason = normalizedReason,
                deliveryState = DeliveryState.DELIVERED.name,
                attemptCount = 0,
                lastAttemptAt = null,
                deliveredAt = occurredAtEpochMillis,
            ) == 1,
        ) { "call session changed concurrently" }
        requireNotNull(database.callSessionDao().find(callId)).toModel()
    }

    suspend fun find(callId: String): CallSession? = database.callSessionDao().find(callId)?.toModel()
    suspend fun latest(): CallSession? = database.callSessionDao().latest()?.toModel()
    suspend fun active(): CallSession? = database.callSessionDao().active()?.toModel()
    fun observeActive(): Flow<CallSession?> =
        database.callSessionDao().observeActive().map { row -> row?.toModel() }
    fun observeRecent(limit: Int = 20): Flow<List<CallSession>> =
        database.callSessionDao().observeRecent(limit).map { rows -> rows.map(CallSessionEntity::toModel) }
    suspend fun pending(limit: Int = 50): List<CallSession> = database.withTransaction {
        database.callStateOutboxDao().pending(limit).map { delivery ->
            val session = requireNotNull(database.callSessionDao().find(delivery.callId))
            session.toModel().copy(
                state = CallState.valueOf(delivery.state),
                stateSequence = delivery.stateSequence,
                updatedAtEpochMillis = delivery.occurredAtEpochMillis,
                lastReason = delivery.reason,
                deliveryState = DeliveryState.valueOf(delivery.deliveryState),
                attemptCount = delivery.attemptCount,
            )
        }
    }
    suspend fun deliveryHistory(callId: String): List<CallSession> = database.withTransaction {
        val session = requireNotNull(database.callSessionDao().find(callId))
        database.callStateOutboxDao().history(callId).map { delivery ->
            session.toModel().copy(
                state = CallState.valueOf(delivery.state),
                stateSequence = delivery.stateSequence,
                updatedAtEpochMillis = delivery.occurredAtEpochMillis,
                lastReason = delivery.reason,
                deliveryState = DeliveryState.valueOf(delivery.deliveryState),
                attemptCount = delivery.attemptCount,
            )
        }
    }
    suspend fun pendingCount(): Int = database.callStateOutboxDao().pendingCount()
    suspend fun requiresLegacyReconciliation(callId: String, stateSequence: Long): Boolean =
        database.callStateOutboxDao().find(callId, stateSequence)?.lastError
            ?.startsWith(MIGRATED_V9_RECONCILIATION_MARKER) == true

    suspend fun markLegacyReconciliationAttempt(callId: String, stateSequence: Long, at: Long): Boolean =
        database.withTransaction {
            val changed = database.callStateOutboxDao()
                .markLegacyReconciliationAttempt(callId, stateSequence, at) == 1
            if (changed) database.callSessionDao().markAttemptIfCurrent(callId, stateSequence, at)
            changed
        }

    suspend fun markLegacyReconciliationFailed(
        callId: String,
        stateSequence: Long,
        error: String,
    ): Boolean = database.withTransaction {
        val diagnostic = "$MIGRATED_V9_RECONCILIATION_MARKER:${error.take(MAX_REASON_LENGTH / 2)}"
        val changed = database.callStateOutboxDao().markLegacyReconciliationFailed(
            callId,
            stateSequence,
            diagnostic,
        ) == 1
        if (changed) database.callSessionDao().updateDeliveryIfCurrent(
            callId,
            stateSequence,
            DeliveryState.FAILED.name,
            null,
        )
        changed
    }

    suspend fun completeLegacyReconciliation(
        callId: String,
        expectedLocalSequence: Long,
        serverState: CallState,
        serverSequence: Long,
        serverUpdatedAtEpochMillis: Long,
        deliveredAtEpochMillis: Long,
    ): CallSession = database.withTransaction {
        require(serverSequence > 0)
        require(serverUpdatedAtEpochMillis > 0)
        val current = requireNotNull(database.callSessionDao().find(callId))
        require(current.stateSequence == expectedLocalSequence) { "legacy call changed during reconciliation" }
        val oldOutbox = requireNotNull(database.callStateOutboxDao().find(callId, expectedLocalSequence))
        require(oldOutbox.lastError?.startsWith(MIGRATED_V9_RECONCILIATION_MARKER) == true) {
            "call is not awaiting legacy reconciliation"
        }
        if (serverSequence == expectedLocalSequence && serverState.name == current.state) {
            check(database.callStateOutboxDao().markDelivered(callId, serverSequence, deliveredAtEpochMillis) == 1)
            database.callSessionDao().updateDeliveryIfCurrent(
                callId,
                serverSequence,
                DeliveryState.DELIVERED.name,
                deliveredAtEpochMillis,
            )
            return@withTransaction requireNotNull(database.callSessionDao().find(callId)).toModel()
        }
        check(
            database.callStateOutboxDao().markRejected(
                callId,
                expectedLocalSequence,
                LEGACY_RECONCILED_ERROR,
            ) == 1,
        ) { "failed to isolate legacy call snapshot" }
        check(
            database.callSessionDao().transition(
                callId = callId,
                expectedSequence = expectedLocalSequence,
                newSequence = serverSequence,
                state = serverState.name,
                updatedAt = maxOf(current.createdAtEpochMillis, serverUpdatedAtEpochMillis),
                reason = if (serverState.name == current.state) current.lastReason else LEGACY_RECONCILED_ERROR,
                deliveryState = DeliveryState.DELIVERED.name,
                attemptCount = oldOutbox.attemptCount,
                lastAttemptAt = oldOutbox.lastAttemptAtEpochMillis,
                deliveredAt = deliveredAtEpochMillis,
            ) == 1,
        ) { "legacy call changed during reconciliation" }
        database.callStateOutboxDao().replace(
            CallStateOutboxEntity(
                callId = callId,
                stateSequence = serverSequence,
                state = serverState.name,
                actorId = current.deviceId,
                occurredAtEpochMillis = maxOf(current.createdAtEpochMillis, serverUpdatedAtEpochMillis),
                reason = if (serverState.name == current.state) current.lastReason else LEGACY_RECONCILED_ERROR,
                deliveryState = DeliveryState.DELIVERED.name,
                attemptCount = oldOutbox.attemptCount,
                lastAttemptAtEpochMillis = oldOutbox.lastAttemptAtEpochMillis,
                deliveredAtEpochMillis = deliveredAtEpochMillis,
                lastError = null,
            ),
        )
        database.callStateOutboxDao().deleteLegacyFutureRows(
            callId,
            serverSequence,
            LEGACY_RECONCILED_ERROR,
        )
        requireNotNull(database.callSessionDao().find(callId)).toModel()
    }
    suspend fun markAttempt(callId: String, stateSequence: Long, at: Long): Boolean = database.withTransaction {
        val changed = database.callStateOutboxDao().markAttempt(callId, stateSequence, at) == 1
        if (changed) database.callSessionDao().markAttemptIfCurrent(callId, stateSequence, at)
        changed
    }
    suspend fun markDelivered(callId: String, stateSequence: Long, at: Long): Boolean = database.withTransaction {
        val changed = database.callStateOutboxDao().markDelivered(callId, stateSequence, at) == 1
        if (changed) database.callSessionDao().updateDeliveryIfCurrent(
            callId,
            stateSequence,
            DeliveryState.DELIVERED.name,
            at,
        )
        changed
    }
    /** Delivery errors are recorded as events; they must not overwrite the call's business reason. */
    suspend fun markFailed(callId: String, stateSequence: Long, error: String): Boolean = database.withTransaction {
        val changed = database.callStateOutboxDao().markFailed(
            callId,
            stateSequence,
            error.take(MAX_REASON_LENGTH),
        ) == 1
        if (changed) database.callSessionDao().updateDeliveryIfCurrent(
            callId,
            stateSequence,
            DeliveryState.FAILED.name,
            null,
        )
        changed
    }

    suspend fun markRejected(callId: String, stateSequence: Long, error: String): Boolean = database.withTransaction {
        val changed = database.callStateOutboxDao().markRejected(
            callId,
            stateSequence,
            error.take(MAX_REASON_LENGTH),
        ) == 1
        if (changed) database.callSessionDao().updateDeliveryIfCurrent(
            callId,
            stateSequence,
            DeliveryState.REJECTED.name,
            null,
        )
        changed
    }

    companion object {
        private const val MAX_REASON_LENGTH = 1_024
        const val REMOTE_TERMINAL_SUPERSEDED_ERROR = "SUPERSEDED_BY_REMOTE_TERMINAL"
        const val MIGRATED_V9_RECONCILIATION_MARKER = "MIGRATED_V9_RECONCILIATION_REQUIRED"
        const val LEGACY_RECONCILED_ERROR = "MIGRATED_V9_SERVER_RECONCILED"
    }
}

private fun CallState.isAuthoritativeRemoteTerminal(): Boolean =
    this in setOf(CallState.REJECTED, CallState.ENDED, CallState.FAILED)

private fun CallSessionEntity.toOutbox() = CallStateOutboxEntity(
    callId = callId,
    stateSequence = stateSequence,
    state = state,
    actorId = deviceId,
    occurredAtEpochMillis = createdAtEpochMillis,
    reason = lastReason,
    deliveryState = deliveryState,
    attemptCount = attemptCount,
    lastAttemptAtEpochMillis = lastAttemptAtEpochMillis,
    deliveredAtEpochMillis = deliveredAtEpochMillis,
    lastError = null,
)
