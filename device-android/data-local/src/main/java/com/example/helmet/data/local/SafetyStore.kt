package com.example.helmet.data.local

import androidx.room.withTransaction
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.core.model.SafetySensorTelemetry

class SafetyStore(private val database: HelmetDatabase) {
    suspend fun recordSample(sample: SafetySensorTelemetry): Boolean = database.withTransaction {
        val existing = database.safetyDao().findSample(sample.deviceId, sample.sampleReference)?.toModel()
        if (existing?.sameReadingAs(sample) == true) return@withTransaction false
        check(database.safetyDao().insertSample(sample.toEntity()) != -1L) {
            "failed to persist safety sample"
        }
        if (sample.sampleReference % PRUNE_INTERVAL == 0L) {
            database.safetyDao().pruneSamples(sample.deviceId, MAX_RETAINED_SAMPLES)
        }
        true
    }

    suspend fun findSample(deviceId: String, sampleReference: Long): SafetySensorTelemetry? =
        database.safetyDao().findSample(deviceId, sampleReference)?.toModel()

    suspend fun latestSample(deviceId: String): SafetySensorTelemetry? =
        database.safetyDao().latestSample(deviceId)?.toModel()

    suspend fun sampleCount(deviceId: String): Int = database.safetyDao().sampleCount(deviceId)

    suspend fun recordAlert(alert: SafetyAlertRecord): Boolean =
        database.safetyDao().insertAlert(alert.toEntity()) != -1L

    suspend fun findAlert(messageId: String): SafetyAlertRecord? =
        database.safetyDao().findAlert(messageId)?.toModel()

    suspend fun latestAlert(alertId: String): SafetyAlertRecord? =
        database.safetyDao().latestAlert(alertId)?.toModel()

    suspend fun pendingAlerts(limit: Int = 100): List<SafetyAlertRecord> =
        database.safetyDao().pendingAlerts(limit).map(SafetyAlertEntity::toModel)

    suspend fun pendingAlertCount(): Int = database.safetyDao().pendingAlertCount()

    suspend fun markAttempt(messageId: String, attemptAt: Long): Boolean =
        database.safetyDao().markAlertAttempt(messageId, attemptAt) == 1

    suspend fun markDelivered(messageId: String, deliveredAt: Long): Boolean =
        database.safetyDao().markAlertDelivered(messageId, deliveredAt) == 1

    suspend fun markFailed(messageId: String, error: String): Boolean =
        database.safetyDao().markAlertFailed(messageId, error.take(MAX_ERROR_LENGTH)) == 1

    suspend fun markRejected(messageId: String, error: String): Boolean =
        database.safetyDao().markAlertRejected(messageId, error.take(MAX_ERROR_LENGTH)) == 1

    companion object {
        const val MAX_RETAINED_SAMPLES = 10_000
        private const val PRUNE_INTERVAL = 100L
        private const val MAX_ERROR_LENGTH = 1_024
    }
}

private fun SafetySensorTelemetry.sameReadingAs(other: SafetySensorTelemetry): Boolean =
    copy(recordedAtEpochMillis = other.recordedAtEpochMillis) == other
