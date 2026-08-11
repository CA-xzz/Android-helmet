package com.example.helmet.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SafetyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: SafetySampleEntity): Long

    @Query("SELECT * FROM safety_samples WHERE deviceId = :deviceId AND sampleReference = :sampleReference LIMIT 1")
    suspend fun findSample(deviceId: String, sampleReference: Long): SafetySampleEntity?

    @Query("SELECT * FROM safety_samples WHERE deviceId = :deviceId ORDER BY recordedAtEpochMillis DESC, sampleReference DESC LIMIT 1")
    suspend fun latestSample(deviceId: String): SafetySampleEntity?

    @Query("SELECT COUNT(*) FROM safety_samples WHERE deviceId = :deviceId")
    suspend fun sampleCount(deviceId: String): Int

    @Query("DELETE FROM safety_samples WHERE deviceId = :deviceId AND sampleId NOT IN (SELECT sampleId FROM safety_samples WHERE deviceId = :deviceId ORDER BY recordedAtEpochMillis DESC, sampleReference DESC LIMIT :keep)")
    suspend fun pruneSamples(deviceId: String, keep: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlert(alert: SafetyAlertEntity): Long

    @Query("SELECT * FROM safety_alerts WHERE messageId = :messageId LIMIT 1")
    suspend fun findAlert(messageId: String): SafetyAlertEntity?

    @Query("SELECT * FROM safety_alerts WHERE alertId = :alertId ORDER BY occurredAtEpochMillis DESC, messageId DESC LIMIT 1")
    suspend fun latestAlert(alertId: String): SafetyAlertEntity?

    @Query("SELECT * FROM safety_alerts WHERE deliveryState IN ('PENDING', 'IN_FLIGHT', 'FAILED') ORDER BY occurredAtEpochMillis, messageId LIMIT :limit")
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
