package com.example.helmet.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.security.MessageDigest

@Entity(tableName = "safety_detection_checkpoints")
data class SafetyDetectionCheckpointEntity(
    @PrimaryKey val deviceId: String,
    val schemaVersion: Int,
    val algorithmVersion: Int,
    val thresholdConfigVersion: Int,
    val thresholdConfigFingerprint: String?,
    val lastSampleReference: Long,
    val lastMonotonicMillis: Long,
    val payload: String,
    val payloadSha256: String,
    val updatedAtEpochMillis: Long,
)

data class SafetyDetectionCheckpointRecord(
    val deviceId: String,
    val schemaVersion: Int,
    val algorithmVersion: Int,
    val thresholdConfigVersion: Int,
    val thresholdConfigFingerprint: String,
    val lastSampleReference: Long,
    val lastMonotonicMillis: Long,
    val payload: String,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(deviceId.isNotBlank())
        require(schemaVersion > 0)
        require(algorithmVersion > 0)
        require(thresholdConfigVersion in 1..0xFFFF)
        require(thresholdConfigFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(lastSampleReference in 0..0xFFFF_FFFFL)
        require(lastMonotonicMillis in 0..0xFFFF_FFFFL)
        require(payload.isNotBlank() && payload.length <= MAX_CHECKPOINT_PAYLOAD_LENGTH)
    }
}

sealed interface SafetyDetectionCheckpointLoadResult {
    data object Missing : SafetyDetectionCheckpointLoadResult
    data class Valid(val checkpoint: SafetyDetectionCheckpointRecord) : SafetyDetectionCheckpointLoadResult
    data class Invalid(val reason: String) : SafetyDetectionCheckpointLoadResult
}

internal fun SafetyDetectionCheckpointRecord.toEntity() = SafetyDetectionCheckpointEntity(
    deviceId = deviceId,
    schemaVersion = schemaVersion,
    algorithmVersion = algorithmVersion,
    thresholdConfigVersion = thresholdConfigVersion,
    thresholdConfigFingerprint = thresholdConfigFingerprint,
    lastSampleReference = lastSampleReference,
    lastMonotonicMillis = lastMonotonicMillis,
    payload = payload,
    payloadSha256 = checkpointSha256(
        deviceId = deviceId,
        schemaVersion = schemaVersion,
        algorithmVersion = algorithmVersion,
        thresholdConfigVersion = thresholdConfigVersion,
        thresholdConfigFingerprint = thresholdConfigFingerprint,
        lastSampleReference = lastSampleReference,
        lastMonotonicMillis = lastMonotonicMillis,
        payload = payload,
    ),
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun SafetyDetectionCheckpointEntity.toValidatedRecord(): SafetyDetectionCheckpointLoadResult {
    if (payload.length !in 1..MAX_CHECKPOINT_PAYLOAD_LENGTH) {
        return SafetyDetectionCheckpointLoadResult.Invalid("PAYLOAD_LENGTH_INVALID")
    }
    val fingerprint = thresholdConfigFingerprint
        ?: return SafetyDetectionCheckpointLoadResult.Invalid("THRESHOLD_FINGERPRINT_MISSING")
    if (!fingerprint.matches(Regex("[0-9a-f]{64}"))) {
        return SafetyDetectionCheckpointLoadResult.Invalid("THRESHOLD_FINGERPRINT_INVALID")
    }
    if (
        !payloadSha256.matches(Regex("[0-9a-f]{64}")) ||
        payloadSha256 != checkpointSha256(
            deviceId = deviceId,
            schemaVersion = schemaVersion,
            algorithmVersion = algorithmVersion,
            thresholdConfigVersion = thresholdConfigVersion,
            thresholdConfigFingerprint = fingerprint,
            lastSampleReference = lastSampleReference,
            lastMonotonicMillis = lastMonotonicMillis,
            payload = payload,
        )
    ) {
        return SafetyDetectionCheckpointLoadResult.Invalid("PAYLOAD_INTEGRITY_MISMATCH")
    }
    return runCatching {
        SafetyDetectionCheckpointRecord(
            deviceId = deviceId,
            schemaVersion = schemaVersion,
            algorithmVersion = algorithmVersion,
            thresholdConfigVersion = thresholdConfigVersion,
            thresholdConfigFingerprint = fingerprint,
            lastSampleReference = lastSampleReference,
            lastMonotonicMillis = lastMonotonicMillis,
            payload = payload,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }.fold(
        onSuccess = SafetyDetectionCheckpointLoadResult::Valid,
        onFailure = { SafetyDetectionCheckpointLoadResult.Invalid("METADATA_INVALID") },
    )
}

internal fun checkpointSha256(
    deviceId: String,
    schemaVersion: Int,
    algorithmVersion: Int,
    thresholdConfigVersion: Int,
    thresholdConfigFingerprint: String,
    lastSampleReference: Long,
    lastMonotonicMillis: Long,
    payload: String,
): String = MessageDigest.getInstance("SHA-256")
    .digest(
        listOf(
            deviceId,
            schemaVersion,
            algorithmVersion,
            thresholdConfigVersion,
            thresholdConfigFingerprint,
            lastSampleReference,
            lastMonotonicMillis,
            payload,
        ).joinToString("|").toByteArray(Charsets.UTF_8),
    )
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

private const val MAX_CHECKPOINT_PAYLOAD_LENGTH = 64 * 1024
