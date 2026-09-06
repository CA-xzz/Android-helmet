package com.example.helmet.data.local

import androidx.room.withTransaction
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.core.model.SafetySensorTelemetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SafetyStore(
    private val database: HelmetDatabase,
    private val maxRetainedSamples: Int = MAX_RETAINED_SAMPLES,
    pruneInterval: Long = LEGACY_PRUNE_INTERVAL,
) {
    init {
        require(maxRetainedSamples > 0)
        // Kept for source compatibility with existing test/deployment construction.
        // Pruning now occurs atomically with checkpoint advancement, not by reference modulo.
        require(pruneInterval > 0)
    }

    suspend fun recordSample(sample: SafetySensorTelemetry): Boolean = database.withTransaction {
        val existing = database.safetyDao().findSample(sample.deviceId, sample.sampleReference)?.toModel()
        if (existing != null) {
            if (!existing.sameReadingAs(sample)) {
                throw DurableIdentityConflictException(
                    "safety sample reference conflicts with stored content",
                )
            }
            return@withTransaction false
        }
        val highWater = database.safetyDao().maxSampleReference(sample.deviceId)
        if (highWater != null && sample.sampleReference <= highWater) {
            throw DurableIdentityConflictException(
                "new safety sample reference must strictly advance the durable high-water mark",
            )
        }
        check(database.safetyDao().insertSample(sample.toEntity()) != -1L) {
            "failed to persist safety sample"
        }
        true
    }

    suspend fun findSample(deviceId: String, sampleReference: Long): SafetySensorTelemetry? =
        database.safetyDao().findSample(deviceId, sampleReference)?.toModel()

    suspend fun latestSample(deviceId: String): SafetySensorTelemetry? =
        database.safetyDao().latestSample(deviceId)?.toModel()

    suspend fun recentSamples(
        deviceId: String,
        limit: Int = maxRetainedSamples,
    ): List<SafetySensorTelemetry> {
        require(limit in 1..maxRetainedSamples)
        return database.safetyDao().recentSamples(deviceId, limit)
            .asReversed()
            .map(SafetySampleEntity::toModel)
    }

    fun observeRecentSamples(
        deviceId: String,
        limit: Int = 100,
    ): Flow<List<SafetySensorTelemetry>> {
        require(deviceId.isNotBlank())
        require(limit in 1..maxRetainedSamples)
        return database.safetyDao().observeRecentSamples(deviceId, limit)
            .map { rows -> rows.map(SafetySampleEntity::toModel) }
    }

    suspend fun recentSamplesIncluding(
        deviceId: String,
        sampleReference: Long,
    ): List<SafetySensorTelemetry> = database.withTransaction {
        val recent = database.safetyDao().recentSamples(deviceId, maxRetainedSamples)
            .map(SafetySampleEntity::toModel)
        val requested = database.safetyDao().findSample(deviceId, sampleReference)?.toModel()
        (recent + listOfNotNull(requested))
            .distinctBy { it.sampleReference }
            .sortedBy { it.sampleReference }
    }

    suspend fun samplesAfterCheckpoint(
        deviceId: String,
        afterSampleReference: Long,
        thresholdConfigVersion: Int,
        thresholdConfigFingerprint: String,
    ): List<SafetySensorTelemetry> = database.safetyDao()
        .samplesAfterCheckpoint(
            deviceId,
            afterSampleReference,
            thresholdConfigVersion,
            thresholdConfigFingerprint,
        )
        .map(SafetySampleEntity::toModel)

    suspend fun samplesForConfig(
        deviceId: String,
        thresholdConfigVersion: Int,
        thresholdConfigFingerprint: String,
    ): List<SafetySensorTelemetry> = database.safetyDao()
        .samplesForConfig(deviceId, thresholdConfigVersion, thresholdConfigFingerprint)
        .map(SafetySampleEntity::toModel)

    suspend fun isolateSamplesForConfig(
        deviceId: String,
        thresholdConfigVersion: Int,
        thresholdConfigFingerprint: String,
    ): Int = database.safetyDao().isolateSamplesForConfig(
        deviceId,
        thresholdConfigVersion,
        thresholdConfigFingerprint,
    )

    suspend fun isolateSamplesOutsideConfig(
        deviceId: String,
        thresholdConfigVersion: Int,
        thresholdConfigFingerprint: String,
    ): Int = database.safetyDao().isolateSamplesOutsideConfig(
        deviceId,
        thresholdConfigVersion,
        thresholdConfigFingerprint,
    )

    suspend fun isolateAllDerivedSamples(deviceId: String): Int =
        database.safetyDao().isolateAllDerivedSamples(deviceId)

    suspend fun isolatePendingSamplesBefore(
        deviceId: String,
        beforeSampleReference: Long,
        thresholdConfigVersion: Int,
        thresholdConfigFingerprint: String,
    ): Int = database.safetyDao().isolatePendingSamplesBefore(
        deviceId,
        beforeSampleReference,
        thresholdConfigVersion,
        thresholdConfigFingerprint,
    )

    /**
     * The caller invokes this only after all alarms derived through lastSampleReference
     * are durable. Room atomically advances the replay waterline and prunes raw history,
     * so a checkpoint can lag business records but can never lead them.
     */
    suspend fun commitDetectionCheckpoint(checkpoint: SafetyDetectionCheckpointRecord): Int =
        database.withTransaction {
            val sample = database.safetyDao()
                .findSample(checkpoint.deviceId, checkpoint.lastSampleReference)
            check(sample != null) {
                "checkpoint sample is not durable"
            }
            check(sample.thresholdConfigVersion == checkpoint.thresholdConfigVersion) {
                "checkpoint threshold version does not match its sample"
            }
            check(sample.thresholdConfigFingerprint == checkpoint.thresholdConfigFingerprint) {
                "checkpoint threshold fingerprint does not match its sample"
            }
            check(sample.monotonicMillis == checkpoint.lastMonotonicMillis) {
                "checkpoint monotonic waterline does not match its sample"
            }
            check(database.safetyDao().upsertCheckpoint(checkpoint.toEntity()) != -1L) {
                "failed to persist safety detection checkpoint"
            }
            check(
                database.safetyDao().markSamplesDerivedThrough(
                    deviceId = checkpoint.deviceId,
                    throughSampleReference = checkpoint.lastSampleReference,
                    thresholdConfigVersion = checkpoint.thresholdConfigVersion,
                    thresholdConfigFingerprint = checkpoint.thresholdConfigFingerprint,
                ) > 0,
            ) { "checkpoint did not commit a safety derivation waterline" }
            database.safetyDao().pruneSamples(checkpoint.deviceId, maxRetainedSamples)
        }

    suspend fun detectionCheckpoint(deviceId: String): SafetyDetectionCheckpointLoadResult =
        database.safetyDao().checkpoint(deviceId)?.toValidatedRecord()
            ?: SafetyDetectionCheckpointLoadResult.Missing

    suspend fun deleteDetectionCheckpoint(deviceId: String): Boolean =
        database.safetyDao().deleteCheckpoint(deviceId) == 1

    suspend fun sampleCount(deviceId: String): Int = database.safetyDao().sampleCount(deviceId)

    suspend fun recordAlert(alert: SafetyAlertRecord): Boolean = database.withTransaction {
        val inserted = database.safetyDao().insertAlert(alert.toEntity()) != -1L
        if (!inserted) {
            val existing = requireNotNull(database.safetyDao().findAlert(alert.messageId)).toModel()
            if (!existing.sameSourceEventAs(alert)) {
                throw DurableIdentityConflictException(
                    "safety alert message ID conflicts with stored content",
                )
            }
        }
        inserted
    }

    suspend fun findAlert(messageId: String): SafetyAlertRecord? =
        database.safetyDao().findAlert(messageId)?.toModel()

    suspend fun latestAlert(alertId: String): SafetyAlertRecord? =
        database.safetyDao().latestAlert(alertId)?.toModel()

    suspend fun latestActiveAlerts(deviceId: String): List<SafetyAlertRecord> =
        database.safetyDao().latestActiveAlerts(deviceId).map(SafetyAlertEntity::toModel)

    fun observeRecentAlerts(
        deviceId: String,
        limit: Int = 50,
    ): Flow<List<SafetyAlertRecord>> {
        require(deviceId.isNotBlank())
        require(limit in 1..200)
        return database.safetyDao().observeRecentAlerts(deviceId, limit)
            .map { rows -> rows.map(SafetyAlertEntity::toModel) }
    }

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
        private const val LEGACY_PRUNE_INTERVAL = 100L
        private const val MAX_ERROR_LENGTH = 1_024
    }
}

private fun SafetySensorTelemetry.sameReadingAs(other: SafetySensorTelemetry): Boolean =
    copy(
        recordedAtEpochMillis = other.recordedAtEpochMillis,
        // A byte-for-byte retransmission of a v11 sample remains legacy/unknown. Do not
        // rewrite or evaluate it under the current threshold version, but do acknowledge it.
        thresholdConfigVersion = if (thresholdConfigVersion == null) {
            other.thresholdConfigVersion
        } else {
            thresholdConfigVersion
        },
        thresholdConfigFingerprint = if (thresholdConfigFingerprint == null) {
            other.thresholdConfigFingerprint
        } else {
            thresholdConfigFingerprint
        },
    ) == other

private fun SafetyAlertRecord.sameSourceEventAs(other: SafetyAlertRecord): Boolean =
    messageId == other.messageId &&
        alertId == other.alertId &&
        deviceId == other.deviceId &&
        alarmType == other.alarmType &&
        severity == other.severity &&
        active == other.active &&
        configVersion == other.configVersion &&
        sampleReference == other.sampleReference &&
        monotonicMillis == other.monotonicMillis &&
        localActions == other.localActions &&
        sensorFaults == other.sensorFaults &&
        simulated == other.simulated
