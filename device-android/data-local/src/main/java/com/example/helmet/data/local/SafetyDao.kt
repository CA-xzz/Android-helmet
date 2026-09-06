package com.example.helmet.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SafetyDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSample(sample: SafetySampleEntity): Long

    @Query("SELECT * FROM safety_samples WHERE deviceId = :deviceId AND sampleReference = :sampleReference LIMIT 1")
    suspend fun findSample(deviceId: String, sampleReference: Long): SafetySampleEntity?

    @Query("SELECT * FROM safety_samples WHERE deviceId = :deviceId ORDER BY rowid DESC LIMIT 1")
    suspend fun latestSample(deviceId: String): SafetySampleEntity?

    @Query("SELECT * FROM safety_samples WHERE deviceId = :deviceId ORDER BY rowid DESC LIMIT :limit")
    suspend fun recentSamples(deviceId: String, limit: Int): List<SafetySampleEntity>

    @Query("SELECT * FROM safety_samples WHERE deviceId = :deviceId ORDER BY rowid DESC LIMIT :limit")
    fun observeRecentSamples(deviceId: String, limit: Int): Flow<List<SafetySampleEntity>>

    @Query(
        "SELECT * FROM safety_samples WHERE deviceId = :deviceId " +
            "AND thresholdConfigVersion = :thresholdConfigVersion " +
            "AND thresholdConfigFingerprint = :thresholdConfigFingerprint " +
            "AND derivationCommitted = 0 " +
            "AND rowid > COALESCE((SELECT rowid FROM safety_samples WHERE deviceId = :deviceId " +
            "AND sampleReference = :afterSampleReference LIMIT 1), 9223372036854775807) " +
            "ORDER BY rowid",
    )
    suspend fun samplesAfterCheckpoint(
        deviceId: String,
        afterSampleReference: Long,
        thresholdConfigVersion: Int,
        thresholdConfigFingerprint: String,
    ): List<SafetySampleEntity>

    @Query(
        "SELECT * FROM safety_samples WHERE deviceId = :deviceId " +
            "AND thresholdConfigVersion = :thresholdConfigVersion " +
            "AND thresholdConfigFingerprint = :thresholdConfigFingerprint " +
            "AND derivationCommitted = 0 ORDER BY rowid",
    )
    suspend fun samplesForConfig(
        deviceId: String,
        thresholdConfigVersion: Int,
        thresholdConfigFingerprint: String,
    ): List<SafetySampleEntity>

    @Query(
        "UPDATE safety_samples SET thresholdConfigVersion = NULL, thresholdConfigFingerprint = NULL " +
            "WHERE deviceId = :deviceId AND thresholdConfigVersion = :thresholdConfigVersion " +
            "AND thresholdConfigFingerprint = :thresholdConfigFingerprint",
    )
    suspend fun isolateSamplesForConfig(
        deviceId: String,
        thresholdConfigVersion: Int,
        thresholdConfigFingerprint: String,
    ): Int

    @Query(
        "UPDATE safety_samples SET thresholdConfigVersion = NULL, thresholdConfigFingerprint = NULL " +
            "WHERE deviceId = :deviceId AND thresholdConfigVersion IS NOT NULL AND (" +
            "thresholdConfigVersion != :thresholdConfigVersion OR " +
            "thresholdConfigFingerprint IS NULL OR " +
            "thresholdConfigFingerprint != :thresholdConfigFingerprint)",
    )
    suspend fun isolateSamplesOutsideConfig(
        deviceId: String,
        thresholdConfigVersion: Int,
        thresholdConfigFingerprint: String,
    ): Int

    @Query(
        "UPDATE safety_samples SET thresholdConfigVersion = NULL, thresholdConfigFingerprint = NULL " +
            "WHERE deviceId = :deviceId AND derivationCommitted = 1 " +
            "AND thresholdConfigVersion IS NOT NULL",
    )
    suspend fun isolateAllDerivedSamples(deviceId: String): Int

    @Query(
        "UPDATE safety_samples SET thresholdConfigVersion = NULL, thresholdConfigFingerprint = NULL " +
            "WHERE deviceId = :deviceId AND thresholdConfigVersion = :thresholdConfigVersion " +
            "AND thresholdConfigFingerprint = :thresholdConfigFingerprint " +
            "AND derivationCommitted = 0 AND rowid < (SELECT rowid FROM safety_samples " +
            "WHERE deviceId = :deviceId AND sampleReference = :beforeSampleReference LIMIT 1)",
    )
    suspend fun isolatePendingSamplesBefore(
        deviceId: String,
        beforeSampleReference: Long,
        thresholdConfigVersion: Int,
        thresholdConfigFingerprint: String,
    ): Int

    @Query(
        "UPDATE safety_samples SET derivationCommitted = 1 WHERE deviceId = :deviceId " +
            "AND thresholdConfigVersion = :thresholdConfigVersion " +
            "AND thresholdConfigFingerprint = :thresholdConfigFingerprint " +
            "AND rowid <= (SELECT rowid FROM safety_samples WHERE deviceId = :deviceId " +
            "AND sampleReference = :throughSampleReference LIMIT 1)",
    )
    suspend fun markSamplesDerivedThrough(
        deviceId: String,
        throughSampleReference: Long,
        thresholdConfigVersion: Int,
        thresholdConfigFingerprint: String,
    ): Int

    @Query("SELECT MAX(sampleReference) FROM safety_samples WHERE deviceId = :deviceId")
    suspend fun maxSampleReference(deviceId: String): Long?

    @Query("SELECT COUNT(*) FROM safety_samples WHERE deviceId = :deviceId")
    suspend fun sampleCount(deviceId: String): Int

    @Query("DELETE FROM safety_samples WHERE deviceId = :deviceId AND sampleId NOT IN (SELECT sampleId FROM safety_samples WHERE deviceId = :deviceId ORDER BY rowid DESC LIMIT :keep)")
    suspend fun pruneSamples(deviceId: String, keep: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: SafetyDetectionCheckpointEntity): Long

    @Query("SELECT * FROM safety_detection_checkpoints WHERE deviceId = :deviceId LIMIT 1")
    suspend fun checkpoint(deviceId: String): SafetyDetectionCheckpointEntity?

    @Query("DELETE FROM safety_detection_checkpoints WHERE deviceId = :deviceId")
    suspend fun deleteCheckpoint(deviceId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlert(alert: SafetyAlertEntity): Long

    @Query("SELECT * FROM safety_alerts WHERE messageId = :messageId LIMIT 1")
    suspend fun findAlert(messageId: String): SafetyAlertEntity?

    @Query("SELECT * FROM safety_alerts WHERE alertId = :alertId ORDER BY rowid DESC LIMIT 1")
    suspend fun latestAlert(alertId: String): SafetyAlertEntity?

    @Query(
        """
        SELECT candidate.* FROM safety_alerts AS candidate
        WHERE candidate.deviceId = :deviceId
          AND candidate.active = 1
          AND NOT EXISTS (
              SELECT 1 FROM safety_alerts AS newer
              WHERE newer.deviceId = candidate.deviceId
                AND newer.alertId = candidate.alertId
                AND newer.rowid > candidate.rowid
          )
        ORDER BY candidate.rowid
        """,
    )
    suspend fun latestActiveAlerts(deviceId: String): List<SafetyAlertEntity>

    @Query("SELECT * FROM safety_alerts WHERE deviceId = :deviceId ORDER BY rowid DESC LIMIT :limit")
    fun observeRecentAlerts(deviceId: String, limit: Int): Flow<List<SafetyAlertEntity>>

    @Query("SELECT * FROM safety_alerts WHERE deliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED') ORDER BY rowid LIMIT :limit")
    suspend fun pendingAlerts(limit: Int): List<SafetyAlertEntity>

    @Query("SELECT COUNT(*) FROM safety_alerts WHERE deliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED')")
    suspend fun pendingAlertCount(): Int

    @Query("UPDATE safety_alerts SET deliveryState = 'IN_FLIGHT', attemptCount = attemptCount + 1, lastAttemptAtEpochMillis = :attemptAt, lastError = NULL WHERE messageId = :messageId AND deliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markAlertAttempt(messageId: String, attemptAt: Long): Int

    @Query("UPDATE safety_alerts SET deliveryState = 'DELIVERED', deliveredAtEpochMillis = :deliveredAt, lastError = NULL WHERE messageId = :messageId")
    suspend fun markAlertDelivered(messageId: String, deliveredAt: Long): Int

    @Query("UPDATE safety_alerts SET deliveryState = 'FAILED', lastError = :error WHERE messageId = :messageId AND deliveryState NOT IN ('DELIVERED', 'REJECTED')")
    suspend fun markAlertFailed(messageId: String, error: String): Int

    @Query("UPDATE safety_alerts SET deliveryState = 'REJECTED', lastError = :error WHERE messageId = :messageId AND deliveryState != 'DELIVERED'")
    suspend fun markAlertRejected(messageId: String, error: String): Int
}
