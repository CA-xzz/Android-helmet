package com.example.helmet.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(asset: MediaEntity): Long

    @Query("SELECT * FROM media_assets ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_assets WHERE transferState IN ('PENDING', 'IN_FLIGHT', 'FAILED') ORDER BY createdAtEpochMillis ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<MediaEntity>

    @Query("SELECT * FROM media_assets WHERE assetId = :assetId LIMIT 1")
    suspend fun find(assetId: String): MediaEntity?

    @Query("SELECT COUNT(*) FROM media_assets WHERE transferState IN ('PENDING', 'IN_FLIGHT', 'FAILED')")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM media_assets")
    suspend fun totalCount(): Int

    @Query("UPDATE media_assets SET transferState = 'IN_FLIGHT', attemptCount = attemptCount + 1, lastAttemptAtEpochMillis = :attemptAt, lastError = NULL WHERE assetId = :assetId AND transferState != 'DELIVERED'")
    suspend fun markAttempt(assetId: String, attemptAt: Long): Int

    @Query("UPDATE media_assets SET transferState = 'DELIVERED', deliveredAtEpochMillis = :deliveredAt, lastError = NULL WHERE assetId = :assetId")
    suspend fun markDelivered(assetId: String, deliveredAt: Long): Int

    @Query("UPDATE media_assets SET transferState = 'FAILED', lastError = :error WHERE assetId = :assetId AND transferState != 'DELIVERED'")
    suspend fun markFailed(assetId: String, error: String): Int

    @Query("UPDATE media_assets SET transferState = 'REJECTED', lastError = :error WHERE assetId = :assetId AND transferState != 'DELIVERED'")
    suspend fun markRejected(assetId: String, error: String): Int
}
