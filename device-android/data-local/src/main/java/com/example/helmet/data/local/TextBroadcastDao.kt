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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<TextBroadcastEntity>): List<Long>

    @Query("SELECT * FROM text_broadcasts WHERE commandStreamId = :commandStreamId AND broadcastId = :broadcastId LIMIT 1")
    suspend fun find(commandStreamId: String, broadcastId: String): TextBroadcastEntity?

    @Query("SELECT b.* FROM text_broadcasts b INNER JOIN command_stream_lineage l ON l.targetStreamId = :targetStreamId AND l.sourceStreamId = b.commandStreamId AND l.deviceId = b.deviceId WHERE b.deviceId = :deviceId AND b.broadcastId = :broadcastId")
    suspend fun findInSourceLineage(
        targetStreamId: String,
        deviceId: String,
        broadcastId: String,
    ): List<TextBroadcastEntity>

    @Query("SELECT * FROM text_broadcasts WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId ORDER BY serverSequence")
    suspend fun broadcastsForStream(commandStreamId: String, deviceId: String): List<TextBroadcastEntity>

    @Query("SELECT * FROM text_broadcasts WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND serverSequence > :afterSequence ORDER BY serverSequence LIMIT :limit")
    suspend fun broadcastsAfter(
        commandStreamId: String,
        deviceId: String,
        afterSequence: Long,
        limit: Int,
    ): List<TextBroadcastEntity>

    @Query("SELECT * FROM text_broadcasts WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND serverSequence = :serverSequence LIMIT 1")
    suspend fun findBySequence(
        commandStreamId: String,
        deviceId: String,
        serverSequence: Long,
    ): TextBroadcastEntity?

    @Query("SELECT COUNT(*) FROM text_broadcasts WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId")
    suspend fun countForStream(commandStreamId: String, deviceId: String): Long

    @Query("UPDATE text_broadcasts SET receiptDeliveryState = 'PENDING', receiptDeliveredAtEpochMillis = NULL, receiptLastError = NULL WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND serverSequence = :serverSequence")
    suspend fun resetReceiptForAuthoritativeReplay(
        commandStreamId: String,
        deviceId: String,
        serverSequence: Long,
    ): Int

    @Query("SELECT b.* FROM text_broadcasts b INNER JOIN active_command_streams a ON a.deviceId = b.deviceId AND a.commandStreamId = b.commandStreamId ORDER BY b.receivedAtEpochMillis DESC, b.broadcastId DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TextBroadcastEntity>>

    @Query("SELECT * FROM text_broadcasts WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND receiptDeliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED') ORDER BY serverSequence LIMIT :limit")
    suspend fun pendingReceipts(
        commandStreamId: String,
        deviceId: String,
        limit: Int,
    ): List<TextBroadcastEntity>

    @Query("SELECT COUNT(*) FROM text_broadcasts WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND receiptDeliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED')")
    suspend fun pendingReceiptCount(commandStreamId: String, deviceId: String): Int

    @Query("SELECT COUNT(*) FROM text_broadcasts b INNER JOIN active_command_streams a ON a.deviceId = b.deviceId AND a.commandStreamId = b.commandStreamId WHERE b.receiptDeliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED')")
    suspend fun totalPendingReceiptCount(): Int

    @Query(
        "UPDATE text_broadcasts SET playbackState = :state, " +
            "playingAtEpochMillis = COALESCE(:playingAt, playingAtEpochMillis), " +
            "playedAtEpochMillis = COALESCE(:playedAt, playedAtEpochMillis), lastError = :error, " +
            "receiptDeliveryState = CASE WHEN receiptDeliveryState = 'REJECTED' THEN 'REJECTED' ELSE 'PENDING' END, " +
            "receiptDeliveredAtEpochMillis = CASE WHEN receiptDeliveryState = 'REJECTED' THEN receiptDeliveredAtEpochMillis ELSE NULL END, " +
            "receiptLastError = CASE WHEN receiptDeliveryState = 'REJECTED' THEN receiptLastError ELSE NULL END " +
            "WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND broadcastId = :broadcastId AND (" +
            "(:state = 'PLAYING' AND playbackState IN ('RECEIVED', 'PLAYING') AND playedAtEpochMillis IS NULL " +
            "AND :playingAt IS NOT NULL AND :playingAt > receivedAtEpochMillis " +
            "AND (playbackState != 'PLAYING' OR playingAtEpochMillis = :playingAt)) OR " +
            "(:state IN ('PLAYED', 'FAILED', 'EXPIRED') AND playbackState IN ('RECEIVED', 'PLAYING') " +
            "AND :playedAt IS NOT NULL AND :playedAt > COALESCE(playingAtEpochMillis, receivedAtEpochMillis)))",
    )
    suspend fun updatePlayback(
        commandStreamId: String,
        deviceId: String,
        broadcastId: String,
        state: String,
        playingAt: Long?,
        playedAt: Long?,
        error: String?,
    ): Int

    @Query("UPDATE text_broadcasts SET receiptDeliveryState = 'IN_FLIGHT', receiptAttemptCount = receiptAttemptCount + 1, lastReceiptAttemptAtEpochMillis = :attemptAt, receiptLastError = NULL WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND broadcastId = :broadcastId AND receiptDeliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markReceiptAttempt(
        commandStreamId: String,
        deviceId: String,
        broadcastId: String,
        attemptAt: Long,
    ): Int

    @Query("UPDATE text_broadcasts SET receiptDeliveryState = 'DELIVERED', receiptDeliveredAtEpochMillis = :deliveredAt, receiptLastError = NULL WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND broadcastId = :broadcastId AND receiptDeliveryState = 'IN_FLIGHT'")
    suspend fun markReceiptDelivered(
        commandStreamId: String,
        deviceId: String,
        broadcastId: String,
        deliveredAt: Long,
    ): Int

    @Query("UPDATE text_broadcasts SET receiptDeliveryState = 'FAILED', receiptLastError = :error WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND broadcastId = :broadcastId AND receiptDeliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markReceiptFailed(
        commandStreamId: String,
        deviceId: String,
        broadcastId: String,
        error: String?,
    ): Int

    @Query("UPDATE text_broadcasts SET receiptDeliveryState = 'REJECTED', receiptLastError = :error WHERE commandStreamId = :commandStreamId AND deviceId = :deviceId AND broadcastId = :broadcastId AND receiptDeliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markReceiptRejected(
        commandStreamId: String,
        deviceId: String,
        broadcastId: String,
        error: String,
    ): Int
}
