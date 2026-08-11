package com.example.helmet.core.model

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
    val heightMinimumMillimetres: Int = -500_000,
    val heightMaximumMillimetres: Int = 10_000_000,
) {
    init {
        require(version in 1..0xFFFF)
        require(freeFallThresholdMilliG in 50 until fallImpactThresholdMilliG)
        require(fallImpactThresholdMilliG <= impactThresholdMilliG)
        require(freeFallMinimumMillis in 20..5_000)
        require(fallImpactWindowMillis in freeFallMinimumMillis..10_000)
        require(motionCooldownMillis in 100..60_000)
        require(shakeDirectionChanges in 2..20)
        require(shakeWindowMillis in 100..10_000)
        require(electricCalibrationSamples in 3..10_000)
        require(electricPresentThresholdMilliVolts > electricHysteresisMilliVolts)
        require(electricPresentThresholdMilliVolts < electricHighThresholdMilliVolts)
        require(electricHighThresholdMilliVolts < electricCriticalThresholdMilliVolts)
        require(electricMinimumMilliVolts < electricMaximumMilliVolts)
        require(electricConfirmationSamples in 1..100)
        require(heightCalibrationSamples in 3..10_000)
        require(heightThresholdMillimetres > heightHysteresisMillimetres)
        require(heightConfirmationSamples in 1..100)
        require(heightMinimumMillimetres < heightMaximumMillimetres)
    }
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
) {
    init {
        require(sampleId.isNotBlank())
        require(deviceId.isNotBlank())
        require(sampleReference in 0..0xFFFF_FFFFL)
        require(monotonicMillis in 0..0xFFFF_FFFFL)
        require(validFlags in 0..0xFFFF)
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
        require(locationFixType.isNotBlank())
    }
}
