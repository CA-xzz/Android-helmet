package com.example.helmet.core.model

import java.security.MessageDigest

data class SafetyThresholdConfig(
    val version: Int = 1,
    val freeFallThresholdMilliG: Int = 450,
    val freeFallMinimumMillis: Long = 120,
    val fallImpactThresholdMilliG: Int = 2_200,
    val impactThresholdMilliG: Int = 3_000,
    val fallImpactWindowMillis: Long = 1_000,
    val motionCooldownMillis: Long = 1_500,
    val shakeAccelerationThresholdMilliG: Int = 1_600,
    val shakeGyroThresholdMilliDegreesPerSecond: Int = 180_000,
    val shakeDirectionChanges: Int = 4,
    val shakeWindowMillis: Long = 1_000,
    val inactivityAccelerationToleranceMilliG: Int = 80,
    val inactivityGyroToleranceMilliDegreesPerSecond: Int = 5_000,
    val inactivityMinimumMillis: Long = 300_000,
    val electricCalibrationSamples: Int = 10,
    val electricCalibrationStabilityMilliVolts: Int = 40,
    val electricPresentThresholdMilliVolts: Int = 150,
    val electricHighThresholdMilliVolts: Int = 400,
    val electricCriticalThresholdMilliVolts: Int = 800,
    val electricHysteresisMilliVolts: Int = 50,
    val electricConfirmationSamples: Int = 3,
    val electricMinimumMilliVolts: Int = 0,
    val electricMaximumMilliVolts: Int = 5_000,
    val heightCalibrationSamples: Int = 10,
    val heightCalibrationStabilityMillimetres: Int = 100,
    val heightThresholdMillimetres: Int = 2_000,
    val heightHysteresisMillimetres: Int = 250,
    val heightConfirmationSamples: Int = 3,
    val heightConfirmationMillis: Long = 200,
    val heightDriftStabilityMillis: Long = 30_000,
    val heightDriftMaximumRateMillimetresPerSecond: Int = 50,
    val heightDriftWindowMillimetres: Int = 500,
    val heightBaselineAdjustmentDivisor: Int = 128,
    val heightMinimumMillimetres: Int = -500_000,
    val heightMaximumMillimetres: Int = 10_000_000,
) {
    init {
        require(version in 1..0xFFFF)
        require(freeFallThresholdMilliG in 50 until fallImpactThresholdMilliG)
        require(fallImpactThresholdMilliG in 50..0xFFFF)
        require(fallImpactThresholdMilliG <= impactThresholdMilliG)
        require(impactThresholdMilliG in 50..0xFFFF)
        require(freeFallMinimumMillis in 20..5_000)
        require(fallImpactWindowMillis in freeFallMinimumMillis..10_000)
        require(motionCooldownMillis in 100..60_000)
        require(shakeAccelerationThresholdMilliG in 1..0xFFFF)
        require(shakeGyroThresholdMilliDegreesPerSecond > 0)
        require(shakeDirectionChanges in 2..20)
        require(shakeWindowMillis in 100..10_000)
        require(inactivityAccelerationToleranceMilliG in 10..500)
        require(inactivityGyroToleranceMilliDegreesPerSecond in 100..50_000)
        require(inactivityMinimumMillis in 1_000..86_400_000)
        require(electricCalibrationSamples in 3..10_000)
        require(electricCalibrationStabilityMilliVolts in 0..0xFFFF)
        require(electricMinimumMilliVolts in 0..0xFFFF)
        require(electricMaximumMilliVolts in 0..0xFFFF)
        require(electricMinimumMilliVolts < electricMaximumMilliVolts)
        require(electricPresentThresholdMilliVolts in 1..0xFFFF)
        require(electricPresentThresholdMilliVolts < electricHighThresholdMilliVolts)
        require(electricHighThresholdMilliVolts < electricCriticalThresholdMilliVolts)
        require(electricCriticalThresholdMilliVolts <= electricMaximumMilliVolts - electricMinimumMilliVolts)
        require(electricHysteresisMilliVolts in 0 until electricPresentThresholdMilliVolts)
        require(electricHysteresisMilliVolts < electricHighThresholdMilliVolts - electricPresentThresholdMilliVolts)
        require(electricHysteresisMilliVolts < electricCriticalThresholdMilliVolts - electricHighThresholdMilliVolts)
        require(electricCalibrationStabilityMilliVolts <= electricMaximumMilliVolts - electricMinimumMilliVolts)
        require(electricConfirmationSamples in 1..100)
        require(heightCalibrationSamples in 3..10_000)
        require(heightCalibrationStabilityMillimetres in 0..0xFFFF)
        require(heightThresholdMillimetres > 0)
        require(heightHysteresisMillimetres in 0 until heightThresholdMillimetres)
        require(heightConfirmationSamples in 1..100)
        require(heightConfirmationMillis in 0..60_000)
        require(heightDriftStabilityMillis in 0..3_600_000)
        require(heightDriftMaximumRateMillimetresPerSecond in 0..10_000)
        require(heightDriftWindowMillimetres in 1 until heightThresholdMillimetres)
        require(heightBaselineAdjustmentDivisor in 2..10_000)
        require(heightMinimumMillimetres < heightMaximumMillimetres)
        require(heightThresholdMillimetres.toLong() <=
            heightMaximumMillimetres.toLong() - heightMinimumMillimetres.toLong())
        require(heightCalibrationStabilityMillimetres.toLong() <=
            heightMaximumMillimetres.toLong() - heightMinimumMillimetres.toLong())
    }
}

/** Fixed-order identity for every value that changes detector interpretation. */
fun SafetyThresholdConfig.canonicalFingerprint(): String {
    val canonicalValues = mutableListOf<Any>(
        version,
        freeFallThresholdMilliG,
        freeFallMinimumMillis,
        fallImpactThresholdMilliG,
        impactThresholdMilliG,
        fallImpactWindowMillis,
        motionCooldownMillis,
        shakeAccelerationThresholdMilliG,
        shakeGyroThresholdMilliDegreesPerSecond,
        shakeDirectionChanges,
        shakeWindowMillis,
        electricCalibrationSamples,
        electricCalibrationStabilityMilliVolts,
        electricPresentThresholdMilliVolts,
        electricHighThresholdMilliVolts,
        electricCriticalThresholdMilliVolts,
        electricHysteresisMilliVolts,
        electricConfirmationSamples,
        electricMinimumMilliVolts,
        electricMaximumMilliVolts,
        heightCalibrationSamples,
        heightCalibrationStabilityMillimetres,
        heightThresholdMillimetres,
        heightHysteresisMillimetres,
        heightConfirmationSamples,
        heightConfirmationMillis,
        heightDriftStabilityMillis,
        heightDriftMaximumRateMillimetresPerSecond,
        heightDriftWindowMillimetres,
        heightBaselineAdjustmentDivisor,
        heightMinimumMillimetres,
        heightMaximumMillimetres,
    )
    val defaults = SafetyThresholdConfig()
    if (
        inactivityAccelerationToleranceMilliG != defaults.inactivityAccelerationToleranceMilliG ||
        inactivityGyroToleranceMilliDegreesPerSecond != defaults.inactivityGyroToleranceMilliDegreesPerSecond ||
        inactivityMinimumMillis != defaults.inactivityMinimumMillis
    ) {
        canonicalValues += inactivityAccelerationToleranceMilliG
        canonicalValues += inactivityGyroToleranceMilliDegreesPerSecond
        canonicalValues += inactivityMinimumMillis
    }
    val canonical = canonicalValues.joinToString(separator = "|")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

data class SafetySensorTelemetry(
    val sampleId: String,
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
    /**
     * Threshold configuration that was active when this sample entered the Android
     * detection pipeline. Null is reserved for samples written before schema 13 or
     * deliberately isolated during recovery; callers must not reinterpret those samples.
     */
    val thresholdConfigVersion: Int? = null,
    /** Null is legacy/isolated provenance and must never be evaluated. */
    val thresholdConfigFingerprint: String? = null,
) {
    init {
        require(sampleId.isNotBlank())
        require(deviceId.isNotBlank())
        require(sampleReference in 0..0xFFFF_FFFFL)
        require(monotonicMillis in 0..0xFFFF_FFFFL)
        require(validFlags in 0..0xFFFF)
        require(thresholdConfigVersion == null || thresholdConfigVersion in 1..0xFFFF)
        require(
            thresholdConfigFingerprint == null ||
                thresholdConfigFingerprint.matches(Regex("[0-9a-f]{64}")),
        )
        require((thresholdConfigVersion == null) == (thresholdConfigFingerprint == null))
    }
}

data class SafetyAlertRecord(
    val messageId: String,
    val alertId: String,
    val deviceId: String,
    val alarmType: String,
    val severity: EventSeverity,
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
    val deliveryState: DeliveryState,
    val attemptCount: Int,
) {
    init {
        require(messageId.isNotBlank())
        require(alertId.isNotBlank())
        require(deviceId.isNotBlank())
        require(alarmType.matches(Regex("[A-Z0-9_]{1,40}")))
        require(configVersion == null || configVersion in 1..0xFFFF)
        require(sampleReference == null || sampleReference in 0..0xFFFF_FFFFL)
        require(monotonicMillis in 0..0xFFFF_FFFFL)
        require(localActions in 0..0xFF)
        require(sensorFaults in 0..0xFFFF)
        require((latitude == null) == (longitude == null))
        require(latitude == null || latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude == null || longitude.isFinite() && longitude in -180.0..180.0)
        require(
            horizontalAccuracyMeters == null ||
                horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters >= 0f,
        )
        require(latitude != null || horizontalAccuracyMeters == null)
        require(latitude != null || locationFixType == "NO_FIX")
        require(locationFixType.isNotBlank())
    }
}
