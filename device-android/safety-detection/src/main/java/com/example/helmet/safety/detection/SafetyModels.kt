package com.example.helmet.safety.detection

enum class SafetyAlarmType {
    FALL,
    IMPACT,
    VIOLENT_SHAKE,
    NEAR_ELECTRIC,
    HEIGHT_LIMIT,
    SENSOR_FAULT,
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
    val decisions: List<SafetyDecision>,
)

typealias SafetyDetectionConfig = com.example.helmet.core.model.SafetyThresholdConfig
