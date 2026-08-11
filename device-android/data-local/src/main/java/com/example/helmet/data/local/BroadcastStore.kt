package com.example.helmet.data.local

import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.TextBroadcast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BroadcastStore(private val database: HelmetDatabase) {
    suspend fun receive(message: TextBroadcast): Boolean {
        require(message.playbackState == BroadcastPlaybackState.RECEIVED)
        val entity = message.toEntity()
        val inserted = database.textBroadcastDao().insert(entity) != -1L
        if (!inserted) {
            val existing = requireNotNull(database.textBroadcastDao().find(message.broadcastId)).toModel()
            require(existing.deviceId == message.deviceId && existing.serverSequence == message.serverSequence &&
                existing.text == message.text && existing.language == message.language
            ) { "broadcast ID conflicts with stored content" }
        }
        return inserted
    }

    suspend fun updatePlayback(
        broadcastId: String,
        state: BroadcastPlaybackState,
        now: Long,
        error: String? = null,
    ): Boolean {
        require(state != BroadcastPlaybackState.RECEIVED)
        return database.textBroadcastDao().updatePlayback(
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

    suspend fun find(id: String): TextBroadcast? = database.textBroadcastDao().find(id)?.toModel()
    fun observeRecent(limit: Int = 20): Flow<List<TextBroadcast>> =
        database.textBroadcastDao().observeRecent(limit).map { rows -> rows.map(TextBroadcastEntity::toModel) }
    suspend fun pendingReceipts(limit: Int = 50): List<TextBroadcast> =
        database.textBroadcastDao().pendingReceipts(limit).map(TextBroadcastEntity::toModel)
    suspend fun pendingReceiptCount(): Int = database.textBroadcastDao().pendingReceiptCount()
    suspend fun markReceiptAttempt(id: String, at: Long) =
        database.textBroadcastDao().markReceiptAttempt(id, at) == 1
    suspend fun markReceiptDelivered(id: String, at: Long) =
        database.textBroadcastDao().markReceiptDelivered(id, at) == 1
    suspend fun markReceiptFailed(id: String) =
        database.textBroadcastDao().markReceiptFailed(id) == 1

    private fun TextBroadcast.toEntity() = TextBroadcastEntity(
        broadcastId = broadcastId,
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
        receiptDeliveryState = receiptDeliveryState.name,
        receiptAttemptCount = receiptAttemptCount,
        lastReceiptAttemptAtEpochMillis = null,
        receiptDeliveredAtEpochMillis = null,
    )

    companion object {
        private const val MAX_ERROR_LENGTH = 1_024
    }
}
