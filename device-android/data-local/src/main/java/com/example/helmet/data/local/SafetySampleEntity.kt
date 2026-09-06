package com.example.helmet.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.helmet.core.model.SafetySensorTelemetry

@Entity(
    tableName = "safety_samples",
    indices = [
        Index(value = ["deviceId", "sampleReference"], unique = true),
        Index(value = ["deviceId", "recordedAtEpochMillis"]),
        Index(
            value = [
                "deviceId",
                "thresholdConfigVersion",
                "thresholdConfigFingerprint",
                "derivationCommitted",
            ],
        ),
    ],
)
data class SafetySampleEntity(
    @PrimaryKey val sampleId: String,
    val deviceId: String,
    val sampleReference: Long,
    val monotonicMillis: Long,
    val validFlags: Int,
    val accelerationXMilliG: Int?,
    val accelerationYMilliG: Int?,
    val accelerationZMilliG: Int?,
    val gyroXMilliDegreesPerSecond: Int?,
    val gyroYMilliDegreesPerSecond: Int?,
    val gyroZMilliDegreesPerSecond: Int?,
    val electricFieldMilliVolts: Int?,
    val pressurePascals: Long?,
    val temperatureCentiCelsius: Int?,
    val altitudeMillimetres: Int?,
    val simulated: Boolean,
    val recordedAtEpochMillis: Long,
    val thresholdConfigVersion: Int?,
    val thresholdConfigFingerprint: String?,
    val derivationCommitted: Boolean,
) {
    fun toModel() = SafetySensorTelemetry(
        sampleId = sampleId,
        deviceId = deviceId,
        sampleReference = sampleReference,
        monotonicMillis = monotonicMillis,
        validFlags = validFlags,
        accelerationXMilliG = accelerationXMilliG,
        accelerationYMilliG = accelerationYMilliG,
        accelerationZMilliG = accelerationZMilliG,
        gyroXMilliDegreesPerSecond = gyroXMilliDegreesPerSecond,
        gyroYMilliDegreesPerSecond = gyroYMilliDegreesPerSecond,
        gyroZMilliDegreesPerSecond = gyroZMilliDegreesPerSecond,
        electricFieldMilliVolts = electricFieldMilliVolts,
        pressurePascals = pressurePascals,
        temperatureCentiCelsius = temperatureCentiCelsius,
        altitudeMillimetres = altitudeMillimetres,
        simulated = simulated,
        recordedAtEpochMillis = recordedAtEpochMillis,
        thresholdConfigVersion = thresholdConfigVersion,
        thresholdConfigFingerprint = thresholdConfigFingerprint,
    )
}

internal fun SafetySensorTelemetry.toEntity() = SafetySampleEntity(
    sampleId = sampleId,
    deviceId = deviceId,
    sampleReference = sampleReference,
    monotonicMillis = monotonicMillis,
    validFlags = validFlags,
    accelerationXMilliG = accelerationXMilliG,
    accelerationYMilliG = accelerationYMilliG,
    accelerationZMilliG = accelerationZMilliG,
    gyroXMilliDegreesPerSecond = gyroXMilliDegreesPerSecond,
    gyroYMilliDegreesPerSecond = gyroYMilliDegreesPerSecond,
    gyroZMilliDegreesPerSecond = gyroZMilliDegreesPerSecond,
    electricFieldMilliVolts = electricFieldMilliVolts,
    pressurePascals = pressurePascals,
    temperatureCentiCelsius = temperatureCentiCelsius,
    altitudeMillimetres = altitudeMillimetres,
    simulated = simulated,
    recordedAtEpochMillis = recordedAtEpochMillis,
    thresholdConfigVersion = thresholdConfigVersion,
    thresholdConfigFingerprint = thresholdConfigFingerprint,
    derivationCommitted = false,
)
