package com.example.helmet.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TextBroadcastDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TextBroadcastEntity): Long

    @Query("SELECT * FROM text_broadcasts WHERE broadcastId = :broadcastId LIMIT 1")
    suspend fun find(broadcastId: String): TextBroadcastEntity?

    @Query("SELECT * FROM text_broadcasts ORDER BY serverSequence DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TextBroadcastEntity>>

    @Query("SELECT * FROM text_broadcasts WHERE receiptDeliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED') ORDER BY deviceId, serverSequence LIMIT :limit")
    suspend fun pendingReceipts(limit: Int): List<TextBroadcastEntity>

    @Query("SELECT COUNT(*) FROM text_broadcasts WHERE receiptDeliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED')")
    suspend fun pendingReceiptCount(): Int

    @Query("UPDATE text_broadcasts SET playbackState = :state, playingAtEpochMillis = COALESCE(:playingAt, playingAtEpochMillis), playedAtEpochMillis = COALESCE(:playedAt, playedAtEpochMillis), lastError = :error, receiptDeliveryState = 'PENDING' WHERE broadcastId = :broadcastId")
    suspend fun updatePlayback(
        broadcastId: String,
        state: String,
        playingAt: Long?,
        playedAt: Long?,
        error: String?,
    ): Int

    @Query("UPDATE text_broadcasts SET receiptDeliveryState = 'IN_FLIGHT', receiptAttemptCount = receiptAttemptCount + 1, lastReceiptAttemptAtEpochMillis = :attemptAt WHERE broadcastId = :broadcastId AND receiptDeliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markReceiptAttempt(broadcastId: String, attemptAt: Long): Int

    @Query("UPDATE text_broadcasts SET receiptDeliveryState = 'DELIVERED', receiptDeliveredAtEpochMillis = :deliveredAt WHERE broadcastId = :broadcastId")
    suspend fun markReceiptDelivered(broadcastId: String, deliveredAt: Long): Int

    @Query("UPDATE text_broadcasts SET receiptDeliveryState = 'FAILED' WHERE broadcastId = :broadcastId AND receiptDeliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markReceiptFailed(broadcastId: String): Int
}
