package com.example.helmet.safety.detection

enum class SafetyAlarmType {
    FALL,
    IMPACT,
    VIOLENT_SHAKE,
    NEAR_ELECTRIC,
    HEIGHT_LIMIT,
    SENSOR_FAULT,
    INACTIVITY,
}

enum class SafetySeverity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class ElectricFieldLevel {
    CALIBRATING,
    CLEAR,
    PRESENT,
    HIGH,
    CRITICAL,
    FAULT,
}

enum class HeightInputSource {
    ALTITUDE,
    PRESSURE,
}

data class SafetySensorSample(
    val sampleReference: Long,
    val monotonicMillis: Long,
    val accelerationXMilliG: Int? = null,
    val accelerationYMilliG: Int? = null,
    val accelerationZMilliG: Int? = null,
    val gyroXMilliDegreesPerSecond: Int? = null,
    val gyroYMilliDegreesPerSecond: Int? = null,
    val gyroZMilliDegreesPerSecond: Int? = null,
    val electricFieldMilliVolts: Int? = null,
    val pressurePascals: Int? = null,
    val temperatureCentiCelsius: Int? = null,
    val altitudeMillimetres: Int? = null,
) {
    init {
        require(sampleReference in 0..0xFFFF_FFFFL) { "sample reference must fit u32" }
        require(monotonicMillis in 0..0xFFFF_FFFFL) { "monotonic time must fit u32" }
        require(
            listOf(accelerationXMilliG, accelerationYMilliG, accelerationZMilliG).all { it == null } ||
                listOf(accelerationXMilliG, accelerationYMilliG, accelerationZMilliG).none { it == null },
        ) { "all acceleration axes must be present together" }
        require(
            listOf(gyroXMilliDegreesPerSecond, gyroYMilliDegreesPerSecond, gyroZMilliDegreesPerSecond).all { it == null } ||
                listOf(gyroXMilliDegreesPerSecond, gyroYMilliDegreesPerSecond, gyroZMilliDegreesPerSecond).none { it == null },
        ) { "all gyroscope axes must be present together" }
    }

    val hasImu: Boolean
        get() = accelerationXMilliG != null
}

data class SafetyDecision(
    val type: SafetyAlarmType,
    val severity: SafetySeverity,
    val active: Boolean,
    val sampleReference: Long,
    val monotonicMillis: Long,
    val configVersion: Int,
    val reasonCode: String,
    val measuredValue: Long,
    val thresholdValue: Long,
    val accumulatedValue: Long = 0,
    val requestedLocalActions: Int = LOCAL_ACTION_LED or LOCAL_ACTION_VIBRATION or LOCAL_ACTION_BUZZER,
    val sensorFaults: Int = 0,
) {
    companion object {
        const val LOCAL_ACTION_LED = 0x01
        const val LOCAL_ACTION_VIBRATION = 0x02
        const val LOCAL_ACTION_BUZZER = 0x04
    }
}

data class SafetyEvaluation(
    val sampleReference: Long,
    val accelerationMagnitudeMilliG: Int?,
    val electricFieldLevel: ElectricFieldLevel,
    val electricFieldBaselineMilliVolts: Int?,
    val electricFieldExcessMilliVolts: Int?,
    val electricFieldExposureMilliVoltMillis: Long,
    val relativeHeightMillimetres: Int?,
    val heightInputSource: HeightInputSource?,
    val heightBaselineMillimetres: Int?,
    val heightChangeRateMillimetresPerSecond: Int?,
    val decisions: List<SafetyDecision>,
)

/** Complete, versioned state required to continue detection without replaying raw history. */
data class SafetyDetectionCheckpoint(
    val algorithmVersion: Int,
    val thresholdConfigVersion: Int,
    val thresholdConfigFingerprint: String,
    val lastMonotonicMillis: Long?,
    val motion: MotionDetectorCheckpoint,
    val electric: ElectricFieldDetectorCheckpoint,
    val height: HeightDetectorCheckpoint,
) {
    init {
        require(algorithmVersion > 0)
        require(thresholdConfigVersion in 1..0xFFFF)
        require(thresholdConfigFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(lastMonotonicMillis == null || lastMonotonicMillis in 0..0xFFFF_FFFFL)
    }
}

data class MotionDetectorCheckpoint(
    val freeFallStartMillis: Long?,
    val fallArmedUntilMillis: Long?,
    val cooldownUntilMillis: Long,
    val impactArmed: Boolean,
    val shakeWindowStartMillis: Long?,
    val shakeDirection: Int,
    val shakeDirectionChanges: Int,
    val shakeArmed: Boolean,
    val inactivityStartMillis: Long?,
    val inactivityActive: Boolean,
) {
    init {
        require(freeFallStartMillis == null || freeFallStartMillis in 0..0xFFFF_FFFFL)
        require(fallArmedUntilMillis == null || fallArmedUntilMillis >= 0)
        require(cooldownUntilMillis >= 0)
        require(shakeWindowStartMillis == null || shakeWindowStartMillis in 0..0xFFFF_FFFFL)
        require(shakeDirection in -1..1)
        require(shakeDirectionChanges >= 0)
        require(inactivityStartMillis == null || inactivityStartMillis in 0..0xFFFF_FFFFL)
        require(!inactivityActive || inactivityStartMillis != null)
    }
}

data class ElectricFieldDetectorCheckpoint(
    val calibrationCount: Int,
    val calibrationSum: Long,
    val calibrationMinimum: Int,
    val calibrationMaximum: Int,
    val baselineMilliVolts: Int?,
    val level: ElectricFieldLevel,
    val candidateLevel: ElectricFieldLevel?,
    val candidateCount: Int,
    val exposureMilliVoltMillis: Long,
    val lastMillis: Long?,
    val faultActive: Boolean,
) {
    init {
        require(calibrationCount >= 0)
        require(candidateCount >= 0)
        require(exposureMilliVoltMillis >= 0)
        require(lastMillis == null || lastMillis in 0..0xFFFF_FFFFL)
    }
}

data class HeightDetectorCheckpoint(
    val calibrationCount: Int,
    val calibrationSum: Long,
    val calibrationMinimum: Int,
    val calibrationMaximum: Int,
    val baselineMillimetres: Double?,
    val active: Boolean,
    val candidateActive: Boolean?,
    val candidateCount: Int,
    val candidateStartMillis: Long?,
    val inputSource: HeightInputSource?,
    val lastHeightMillimetres: Int?,
    val lastHeightMillis: Long?,
    val driftStableSinceMillis: Long?,
    val faultActive: Boolean,
) {
    init {
        require(calibrationCount >= 0)
        require(baselineMillimetres == null || baselineMillimetres.isFinite())
        require(candidateCount >= 0)
        require(candidateStartMillis == null || candidateStartMillis in 0..0xFFFF_FFFFL)
        require(lastHeightMillis == null || lastHeightMillis in 0..0xFFFF_FFFFL)
        require(driftStableSinceMillis == null || driftStableSinceMillis in 0..0xFFFF_FFFFL)
    }
}

typealias SafetyDetectionConfig = com.example.helmet.core.model.SafetyThresholdConfig
