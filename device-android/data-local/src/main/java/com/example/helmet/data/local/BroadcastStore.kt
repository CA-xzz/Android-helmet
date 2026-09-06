package com.example.helmet.data.local

import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.TextBroadcast
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BroadcastStore(private val database: HelmetDatabase) {
    suspend fun receive(commandStreamId: String, message: TextBroadcast): Boolean =
        database.withTransaction {
        DeviceCommandStore.requireValidCommandStreamId(commandStreamId)
        require(message.playbackState == BroadcastPlaybackState.RECEIVED)
        val dao = database.textBroadcastDao()
        val existing = dao.find(commandStreamId, message.broadcastId)
        if (existing != null) {
            if (
                existing.deviceId != message.deviceId ||
                existing.serverSequence != message.serverSequence ||
                existing.text != message.text ||
                existing.language != message.language ||
                existing.priority != message.priority ||
                existing.expiresAtEpochMillis != message.expiresAtEpochMillis
            ) {
                throw DurableIdentityConflictException("broadcast ID conflicts with stored content")
            }
            return@withTransaction false
        }
        val lineageMatches = dao.findInSourceLineage(
            commandStreamId,
            message.deviceId,
            message.broadcastId,
        )
        val immutableMatches = lineageMatches.filter { candidate ->
            candidate.immutableContentMatches(message)
        }
        if (lineageMatches.any { candidate -> !candidate.immutableContentMatches(message) }) {
            throw DurableIdentityConflictException(
                "broadcast ID conflicts with verified stream lineage",
            )
        }
        val inherited = immutableMatches
            .maxWithOrNull(
                compareBy<TextBroadcastEntity> { candidate -> candidate.playbackProgress() }
                    .thenBy { candidate -> candidate.receivedAtEpochMillis }
                    .thenBy { candidate -> candidate.commandStreamId },
            )
        if (inherited != null) {
            if (
                dao.insert(
                    inherited.forRecoveredCommandStream(
                        commandStreamId,
                        message.serverSequence,
                        message.receivedAtEpochMillis,
                    ),
                ) == -1L
            ) {
                throw DurableIdentityConflictException(
                    "broadcast sequence conflicts with stored content",
                )
            }
            return@withTransaction false
        }
        requireReceivedBroadcastTimelineCapacity(message.receivedAtEpochMillis)
        if (dao.insert(message.toEntity(commandStreamId)) == -1L) {
            throw DurableIdentityConflictException("broadcast sequence conflicts with stored content")
        }
        true
    }

    suspend fun updatePlayback(
        commandStreamId: String,
        deviceId: String,
        broadcastId: String,
        state: BroadcastPlaybackState,
        now: Long,
        error: String? = null,
    ): Boolean {
        require(state != BroadcastPlaybackState.RECEIVED)
        require(now > 0)
        require(
            (state == BroadcastPlaybackState.FAILED && !error.isNullOrBlank()) ||
                (state != BroadcastPlaybackState.FAILED && error == null),
        ) { "broadcast playback error does not match its state" }
        DeviceCommandStore.requireValidCommandStreamId(commandStreamId)
        return database.textBroadcastDao().updatePlayback(
            commandStreamId = commandStreamId,
            deviceId = deviceId,
            broadcastId = broadcastId,
            state = state.name,
            playingAt = now.takeIf { state == BroadcastPlaybackState.PLAYING },
            playedAt = now.takeIf {
                state == BroadcastPlaybackState.PLAYED ||
                    state == BroadcastPlaybackState.FAILED ||
                    state == BroadcastPlaybackState.EXPIRED
            },
            error = error?.take(MAX_ERROR_LENGTH),
        ) == 1
    }

    suspend fun find(commandStreamId: String, id: String): TextBroadcast? {
        DeviceCommandStore.requireValidCommandStreamId(commandStreamId)
        return database.textBroadcastDao().find(commandStreamId, id)?.toModel()
    }
    fun observeRecent(limit: Int = 20): Flow<List<TextBroadcast>> =
        database.textBroadcastDao().observeRecent(limit).map { rows -> rows.map(TextBroadcastEntity::toModel) }
    suspend fun pendingReceipts(
        commandStreamId: String,
        deviceId: String,
        limit: Int = 50,
    ): List<TextBroadcast> {
        DeviceCommandStore.requireValidCommandStreamId(commandStreamId)
        return database.textBroadcastDao().pendingReceipts(commandStreamId, deviceId, limit)
            .map(TextBroadcastEntity::toModel)
    }
    suspend fun pendingReceiptCount(commandStreamId: String, deviceId: String): Int {
        DeviceCommandStore.requireValidCommandStreamId(commandStreamId)
        return database.textBroadcastDao().pendingReceiptCount(commandStreamId, deviceId)
    }
    suspend fun totalPendingReceiptCount(): Int = database.textBroadcastDao().totalPendingReceiptCount()
    suspend fun markReceiptAttempt(commandStreamId: String, deviceId: String, id: String, at: Long) =
        database.textBroadcastDao().markReceiptAttempt(commandStreamId, deviceId, id, at) == 1
    suspend fun markReceiptDelivered(commandStreamId: String, deviceId: String, id: String, at: Long) =
        database.textBroadcastDao().markReceiptDelivered(commandStreamId, deviceId, id, at) == 1
    suspend fun markReceiptFailed(
        commandStreamId: String,
        deviceId: String,
        id: String,
        error: String? = null,
    ) = database.textBroadcastDao().markReceiptFailed(
        commandStreamId,
        deviceId,
        id,
        (error ?: RECEIPT_DELIVERY_FAILED).take(MAX_ERROR_LENGTH),
    ) == 1
    suspend fun markReceiptRejected(commandStreamId: String, deviceId: String, id: String, error: String) =
        database.textBroadcastDao().markReceiptRejected(
            commandStreamId,
            deviceId,
            id,
            error.take(MAX_ERROR_LENGTH),
        ) == 1

    private fun TextBroadcast.toEntity(commandStreamId: String) = TextBroadcastEntity(
        broadcastId = broadcastId,
        commandStreamId = commandStreamId,
        deviceId = deviceId,
        serverSequence = serverSequence,
        text = text,
        language = language,
        priority = priority,
        expiresAtEpochMillis = expiresAtEpochMillis,
        playbackState = playbackState.name,
        receivedAtEpochMillis = receivedAtEpochMillis,
        playingAtEpochMillis = playingAtEpochMillis,
        playedAtEpochMillis = playedAtEpochMillis,
        lastError = lastError,
        receiptLastError = null,
        receiptDeliveryState = receiptDeliveryState.name,
        receiptAttemptCount = receiptAttemptCount,
        lastReceiptAttemptAtEpochMillis = null,
        receiptDeliveredAtEpochMillis = null,
    )

    companion object {
        private const val MAX_ERROR_LENGTH = 1_024
        private const val RECEIPT_DELIVERY_FAILED = "RECEIPT_DELIVERY_FAILED"

        private fun TextBroadcastEntity.immutableContentMatches(message: TextBroadcast): Boolean =
            deviceId == message.deviceId &&
                text == message.text &&
                language == message.language &&
                priority == message.priority &&
                expiresAtEpochMillis == message.expiresAtEpochMillis

        private fun TextBroadcastEntity.playbackProgress(): Int = when (
            BroadcastPlaybackState.valueOf(playbackState)
        ) {
            BroadcastPlaybackState.RECEIVED -> 0
            BroadcastPlaybackState.PLAYING -> 1
            BroadcastPlaybackState.PLAYED,
            BroadcastPlaybackState.FAILED,
            BroadcastPlaybackState.EXPIRED,
            -> 2
        }

    }
}

internal fun TextBroadcastEntity.forRecoveredCommandStream(
    targetStreamId: String,
    targetSequence: Long,
    targetReceivedAtEpochMillis: Long,
): TextBroadcastEntity {
    val state = runCatching { BroadcastPlaybackState.valueOf(playbackState) }
        .getOrElse {
            throw DurableIdentityConflictException("broadcast lineage has an invalid playback state")
        }
    validateDurableTimeline(state)
    val playingAt = when (state) {
        BroadcastPlaybackState.PLAYING -> checkedBroadcastEventSuccessor(targetReceivedAtEpochMillis).also {
            checkedBroadcastEventSuccessor(it)
        }
        BroadcastPlaybackState.PLAYED,
        BroadcastPlaybackState.FAILED,
        BroadcastPlaybackState.EXPIRED,
        -> playingAtEpochMillis?.let { checkedBroadcastEventSuccessor(targetReceivedAtEpochMillis) }
        BroadcastPlaybackState.RECEIVED -> {
            requireReceivedBroadcastTimelineCapacity(targetReceivedAtEpochMillis)
            null
        }
    }
    val playedAt = when (state) {
        BroadcastPlaybackState.PLAYED,
        BroadcastPlaybackState.FAILED,
        BroadcastPlaybackState.EXPIRED,
        -> checkedBroadcastEventSuccessor(playingAt ?: targetReceivedAtEpochMillis)
        BroadcastPlaybackState.RECEIVED,
        BroadcastPlaybackState.PLAYING,
        -> null
    }
    val playbackError = when (state) {
        BroadcastPlaybackState.FAILED -> lastError?.takeIf(String::isNotBlank)
            ?: throw DurableIdentityConflictException(
                "failed broadcast lineage is missing its playback error",
            )
        else -> null
    }
    return copy(
        commandStreamId = targetStreamId,
        serverSequence = targetSequence,
        receivedAtEpochMillis = targetReceivedAtEpochMillis,
        playingAtEpochMillis = playingAt,
        playedAtEpochMillis = playedAt,
        lastError = playbackError,
        receiptDeliveryState = DeliveryState.PENDING.name,
        receiptAttemptCount = 0,
        lastReceiptAttemptAtEpochMillis = null,
        receiptDeliveredAtEpochMillis = null,
        receiptLastError = null,
    )
}

private fun TextBroadcastEntity.validateDurableTimeline(state: BroadcastPlaybackState) {
    val valid = when (state) {
        BroadcastPlaybackState.RECEIVED -> playingAtEpochMillis == null && playedAtEpochMillis == null
        BroadcastPlaybackState.PLAYING ->
            playingAtEpochMillis != null &&
                playingAtEpochMillis > receivedAtEpochMillis &&
                playedAtEpochMillis == null
        BroadcastPlaybackState.PLAYED,
        BroadcastPlaybackState.FAILED,
        BroadcastPlaybackState.EXPIRED,
        -> playedAtEpochMillis != null &&
            playedAtEpochMillis > receivedAtEpochMillis &&
            (playingAtEpochMillis == null ||
                (playingAtEpochMillis > receivedAtEpochMillis &&
                    playedAtEpochMillis > playingAtEpochMillis))
    }
    if (!valid) {
        throw DurableIdentityConflictException("broadcast lineage has an invalid event timeline")
    }
}

internal fun checkedBroadcastEventSuccessor(value: Long): Long {
    if (value == Long.MAX_VALUE) {
        throw DurableIdentityConflictException("broadcast event timeline is exhausted")
    }
    return value + 1L
}

private fun requireReceivedBroadcastTimelineCapacity(receivedAtEpochMillis: Long) {
    checkedBroadcastEventSuccessor(
        checkedBroadcastEventSuccessor(receivedAtEpochMillis),
    )
}
