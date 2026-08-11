package com.example.helmet.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.SafetyAlertRecord

@Entity(
    tableName = "safety_alerts",
    indices = [
        Index(value = ["alertId", "occurredAtEpochMillis"]),
        Index(value = ["deliveryState", "occurredAtEpochMillis"]),
    ],
)
data class SafetyAlertEntity(
    @PrimaryKey val messageId: String,
    val alertId: String,
    val deviceId: String,
    val alarmType: String,
    val severity: String,
    val active: Boolean,
    val configVersion: Int?,
    val sampleReference: Long?,
    val monotonicMillis: Long,
    val occurredAtEpochMillis: Long,
    val localActions: Int,
    val sensorFaults: Int,
    val simulated: Boolean,
    val sensorSnapshotJson: String,
    val latitude: Double?,
    val longitude: Double?,
    val horizontalAccuracyMeters: Float?,
    val locationFixType: String,
    val evidenceAssetId: String?,
    val deliveryState: String,
    val attemptCount: Int,
    val lastAttemptAtEpochMillis: Long?,
    val deliveredAtEpochMillis: Long?,
    val lastError: String?,
) {
    fun toModel() = SafetyAlertRecord(
        messageId = messageId,
        alertId = alertId,
        deviceId = deviceId,
        alarmType = alarmType,
        severity = EventSeverity.valueOf(severity),
        active = active,
        configVersion = configVersion,
        sampleReference = sampleReference,
        monotonicMillis = monotonicMillis,
        occurredAtEpochMillis = occurredAtEpochMillis,
        localActions = localActions,
        sensorFaults = sensorFaults,
        simulated = simulated,
        sensorSnapshotJson = sensorSnapshotJson,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = horizontalAccuracyMeters,
        locationFixType = locationFixType,
        evidenceAssetId = evidenceAssetId,
        deliveryState = DeliveryState.valueOf(deliveryState),
        attemptCount = attemptCount,
    )
}

internal fun SafetyAlertRecord.toEntity() = SafetyAlertEntity(
    messageId = messageId,
    alertId = alertId,
    deviceId = deviceId,
    alarmType = alarmType,
    severity = severity.name,
    active = active,
    configVersion = configVersion,
    sampleReference = sampleReference,
    monotonicMillis = monotonicMillis,
    occurredAtEpochMillis = occurredAtEpochMillis,
    localActions = localActions,
    sensorFaults = sensorFaults,
    simulated = simulated,
    sensorSnapshotJson = sensorSnapshotJson,
    latitude = latitude,
    longitude = longitude,
    horizontalAccuracyMeters = horizontalAccuracyMeters,
    locationFixType = locationFixType,
    evidenceAssetId = evidenceAssetId,
    deliveryState = deliveryState.name,
    attemptCount = attemptCount,
    lastAttemptAtEpochMillis = null,
    deliveredAtEpochMillis = null,
    lastError = null,
)
