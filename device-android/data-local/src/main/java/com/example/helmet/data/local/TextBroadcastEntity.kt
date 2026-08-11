package com.example.helmet.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.TextBroadcast

@Entity(
    tableName = "text_broadcasts",
    indices = [
        Index(value = ["deviceId", "serverSequence"], unique = true),
        Index(value = ["receiptDeliveryState", "serverSequence"]),
    ],
)
data class TextBroadcastEntity(
    @PrimaryKey val broadcastId: String,
    val deviceId: String,
    val serverSequence: Long,
    val text: String,
    val language: String,
    val priority: Int,
    val expiresAtEpochMillis: Long?,
    val playbackState: String,
    val receivedAtEpochMillis: Long,
    val playingAtEpochMillis: Long?,
    val playedAtEpochMillis: Long?,
    val lastError: String?,
    val receiptDeliveryState: String,
    val receiptAttemptCount: Int,
    val lastReceiptAttemptAtEpochMillis: Long?,
    val receiptDeliveredAtEpochMillis: Long?,
) {
    fun toModel() = TextBroadcast(
        broadcastId = broadcastId,
        deviceId = deviceId,
        serverSequence = serverSequence,
        text = text,
        language = language,
        priority = priority,
        expiresAtEpochMillis = expiresAtEpochMillis,
        playbackState = BroadcastPlaybackState.valueOf(playbackState),
        receivedAtEpochMillis = receivedAtEpochMillis,
        playingAtEpochMillis = playingAtEpochMillis,
        playedAtEpochMillis = playedAtEpochMillis,
        lastError = lastError,
        receiptDeliveryState = DeliveryState.valueOf(receiptDeliveryState),
        receiptAttemptCount = receiptAttemptCount,
    )
}
