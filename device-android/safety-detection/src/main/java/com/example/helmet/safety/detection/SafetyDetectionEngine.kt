package com.example.helmet.safety.detection

import kotlin.math.abs
import kotlin.math.sqrt

class SafetyDetectionEngine(
    private val config: SafetyDetectionConfig = SafetyDetectionConfig(),
) {
    private val motion = MotionDetector(config)
    private val electric = ElectricFieldDetector(config)
    private val height = HeightDetector(config)
    private var lastMonotonicMillis: Long? = null

    fun process(sample: SafetySensorSample): SafetyEvaluation {
        val previous = lastMonotonicMillis
        require(previous == null || sample.monotonicMillis > previous) {
            "samples must have strictly increasing monotonic time"
        }
        lastMonotonicMillis = sample.monotonicMillis

        val motionResult = motion.process(sample)
        val electricResult = electric.process(sample)
        val heightResult = height.process(sample)
        return SafetyEvaluation(
            sampleReference = sample.sampleReference,
            accelerationMagnitudeMilliG = motionResult.magnitudeMilliG,
            electricFieldLevel = electricResult.level,
            electricFieldBaselineMilliVolts = electricResult.baselineMilliVolts,
            electricFieldExcessMilliVolts = electricResult.excessMilliVolts,
            electricFieldExposureMilliVoltMillis = electricResult.exposureMilliVoltMillis,
            relativeHeightMillimetres = heightResult.relativeHeightMillimetres,
            decisions = motionResult.decisions + electricResult.decisions + heightResult.decisions,
        )
    }

    fun reset() {
        motion.reset()
        electric.reset()
        height.reset()
        lastMonotonicMillis = null
    }
}

private data class MotionResult(
    val magnitudeMilliG: Int?,
    val decisions: List<SafetyDecision>,
)

private class MotionDetector(private val config: SafetyDetectionConfig) {
    private var freeFallStartMillis: Long? = null
    private var fallArmedUntilMillis: Long? = null
    private var cooldownUntilMillis = 0L
    private var shakeWindowStartMillis: Long? = null
    private var shakeDirection = 0
    private var shakeDirectionChanges = 0

    fun process(sample: SafetySensorSample): MotionResult {
        if (!sample.hasImu) return MotionResult(null, emptyList())
        val x = requireNotNull(sample.accelerationXMilliG)
        val y = requireNotNull(sample.accelerationYMilliG)
        val z = requireNotNull(sample.accelerationZMilliG)
        val magnitude = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z).toInt()
        val now = sample.monotonicMillis

        if (magnitude <= config.freeFallThresholdMilliG) {
            val start = freeFallStartMillis ?: now.also { freeFallStartMillis = it }
            if (now - start >= config.freeFallMinimumMillis) {
                fallArmedUntilMillis = now + config.fallImpactWindowMillis
            }
        } else {
            freeFallStartMillis = null
        }
        if (fallArmedUntilMillis != null && now > requireNotNull(fallArmedUntilMillis)) {
            fallArmedUntilMillis = null
        }

        val decisions = mutableListOf<SafetyDecision>()
        if (now >= cooldownUntilMillis && magnitude >= config.fallImpactThresholdMilliG && fallArmedUntilMillis != null) {
            decisions += decision(
                sample,
                SafetyAlarmType.FALL,
                SafetySeverity.CRITICAL,
                "FREE_FALL_THEN_IMPACT",
                magnitude,
                config.fallImpactThresholdMilliG,
            )
            cooldownUntilMillis = now + config.motionCooldownMillis
            fallArmedUntilMillis = null
            resetShake()
        } else if (now >= cooldownUntilMillis && magnitude >= config.impactThresholdMilliG) {
            decisions += decision(
                sample,
                SafetyAlarmType.IMPACT,
                SafetySeverity.HIGH,
                "IMPACT_MAGNITUDE",
                magnitude,
                config.impactThresholdMilliG,
            )
            cooldownUntilMillis = now + config.motionCooldownMillis
            resetShake()
        } else if (now >= cooldownUntilMillis && updateShake(sample, x, y, z)) {
            decisions += decision(
                sample,
                SafetyAlarmType.VIOLENT_SHAKE,
                SafetySeverity.MEDIUM,
                "ACCEL_GYRO_DIRECTION_CHANGES",
                shakeDirectionChanges.toLong(),
                config.shakeDirectionChanges.toLong(),
            )
            cooldownUntilMillis = now + config.motionCooldownMillis
            resetShake()
        }
        return MotionResult(magnitude, decisions)
    }

    private fun updateShake(sample: SafetySensorSample, x: Int, y: Int, z: Int): Boolean {
        val maxGyro = listOf(
            sample.gyroXMilliDegreesPerSecond,
            sample.gyroYMilliDegreesPerSecond,
            sample.gyroZMilliDegreesPerSecond,
        ).filterNotNull().maxOfOrNull(::abs) ?: 0
        val dominant = listOf(x, y, z).maxByOrNull(::abs) ?: 0
        val energetic = abs(dominant) >= config.shakeAccelerationThresholdMilliG &&
            maxGyro >= config.shakeGyroThresholdMilliDegreesPerSecond
        if (!energetic) return false

        val now = sample.monotonicMillis
        val start = shakeWindowStartMillis
        if (start == null || now - start > config.shakeWindowMillis) {
            shakeWindowStartMillis = now
            shakeDirection = dominant.sign()
            shakeDirectionChanges = 0
            return false
        }
        val direction = dominant.sign()
        if (direction != 0 && shakeDirection != 0 && direction != shakeDirection) {
            shakeDirectionChanges += 1
        }
        shakeDirection = direction
        return shakeDirectionChanges >= config.shakeDirectionChanges
    }

    private fun decision(
        sample: SafetySensorSample,
        type: SafetyAlarmType,
        severity: SafetySeverity,
        reason: String,
        value: Number,
        threshold: Number,
    ) = SafetyDecision(
        type = type,
        severity = severity,
        active = true,
        sampleReference = sample.sampleReference,
        monotonicMillis = sample.monotonicMillis,
        configVersion = config.version,
        reasonCode = reason,
        measuredValue = value.toLong(),
        thresholdValue = threshold.toLong(),
    )

    fun reset() {
        freeFallStartMillis = null
        fallArmedUntilMillis = null
        cooldownUntilMillis = 0
        resetShake()
    }

    private fun resetShake() {
        shakeWindowStartMillis = null
        shakeDirection = 0
        shakeDirectionChanges = 0
    }
}

private data class ElectricResult(
    val level: ElectricFieldLevel,
    val baselineMilliVolts: Int?,
    val excessMilliVolts: Int?,
    val exposureMilliVoltMillis: Long,
    val decisions: List<SafetyDecision>,
)

private class ElectricFieldDetector(private val config: SafetyDetectionConfig) {
    private var calibrationCount = 0
    private var calibrationSum = 0L
    private var calibrationMinimum = Int.MAX_VALUE
    private var calibrationMaximum = Int.MIN_VALUE
    private var baseline: Int? = null
    private var level = ElectricFieldLevel.CALIBRATING
    private var candidateLevel: ElectricFieldLevel? = null
    private var candidateCount = 0
    private var exposure = 0L
    private var lastMillis: Long? = null
    private var faultActive = false

    fun process(sample: SafetySensorSample): ElectricResult {
        val value = sample.electricFieldMilliVolts ?: return result(null, emptyList())
        val now = sample.monotonicMillis
        val deltaMillis = ((lastMillis?.let { now - it }) ?: 0L).coerceIn(0, 1_000)
        lastMillis = now
        if (value !in config.electricMinimumMilliVolts..config.electricMaximumMilliVolts) {
            val decisions = if (!faultActive) {
                faultActive = true
                listOf(
                    SafetyDecision(
                        SafetyAlarmType.SENSOR_FAULT,
                        SafetySeverity.HIGH,
                        true,
                        sample.sampleReference,
                        now,
                        config.version,
                        "ELECTRIC_FIELD_RANGE",
                        value.toLong(),
                        config.electricMaximumMilliVolts.toLong(),
                        sensorFaults = SENSOR_FAULT_ELECTRIC,
                    ),
                )
            } else {
                emptyList()
            }
            level = ElectricFieldLevel.FAULT
            return result(null, decisions)
        }

        val decisions = mutableListOf<SafetyDecision>()
        if (faultActive) {
            faultActive = false
            decisions += SafetyDecision(
                SafetyAlarmType.SENSOR_FAULT,
                SafetySeverity.INFO,
                false,
                sample.sampleReference,
                now,
                config.version,
                "ELECTRIC_FIELD_RECOVERED",
                value.toLong(),
                config.electricMaximumMilliVolts.toLong(),
                sensorFaults = SENSOR_FAULT_ELECTRIC,
            )
            level = if (baseline == null) ElectricFieldLevel.CALIBRATING else ElectricFieldLevel.CLEAR
        }

        if (baseline == null) {
            calibrationMinimum = minOf(calibrationMinimum, value)
            calibrationMaximum = maxOf(calibrationMaximum, value)
            calibrationCount += 1
            calibrationSum += value
            if (calibrationMaximum - calibrationMinimum > config.electricCalibrationStabilityMilliVolts) {
                calibrationCount = 1
                calibrationSum = value.toLong()
                calibrationMinimum = value
                calibrationMaximum = value
            } else if (calibrationCount >= config.electricCalibrationSamples) {
                baseline = (calibrationSum / calibrationCount).toInt()
                level = ElectricFieldLevel.CLEAR
            }
            return result(0, decisions)
        }

        val currentBaseline = requireNotNull(baseline)
        val excess = abs(value - currentBaseline)
        exposure = if (excess >= config.electricPresentThresholdMilliVolts) {
            (exposure + excess.toLong() * deltaMillis).coerceAtMost(MAX_EXPOSURE)
        } else {
            (exposure - config.electricPresentThresholdMilliVolts.toLong() * deltaMillis).coerceAtLeast(0)
        }
        if (excess < config.electricPresentThresholdMilliVolts / 2) {
            baseline = currentBaseline + (value - currentBaseline) / BASELINE_DIVISOR
        }

        val target = classify(excess)
        if (target == level) {
            candidateLevel = null
            candidateCount = 0
        } else if (candidateLevel == target) {
            candidateCount += 1
        } else {
            candidateLevel = target
            candidateCount = 1
        }
        if (candidateCount >= config.electricConfirmationSamples) {
            val oldLevel = level
            level = target
            candidateLevel = null
            candidateCount = 0
            if (oldLevel != level) {
                decisions += SafetyDecision(
                    type = SafetyAlarmType.NEAR_ELECTRIC,
                    severity = severity(level),
                    active = level != ElectricFieldLevel.CLEAR,
                    sampleReference = sample.sampleReference,
                    monotonicMillis = now,
                    configVersion = config.version,
                    reasonCode = if (level == ElectricFieldLevel.CLEAR) "ELECTRIC_FIELD_CLEARED" else "ELECTRIC_FIELD_LEVEL_${level.name}",
                    measuredValue = excess.toLong(),
                    thresholdValue = threshold(level).toLong(),
                    accumulatedValue = exposure,
                )
            }
        }
        return result(excess, decisions)
    }

    private fun classify(excess: Int): ElectricFieldLevel {
        if (level == ElectricFieldLevel.CRITICAL && excess >= config.electricCriticalThresholdMilliVolts - config.electricHysteresisMilliVolts) {
            return ElectricFieldLevel.CRITICAL
        }
        if (level == ElectricFieldLevel.HIGH && excess >= config.electricHighThresholdMilliVolts - config.electricHysteresisMilliVolts) {
            return if (excess >= config.electricCriticalThresholdMilliVolts) ElectricFieldLevel.CRITICAL else ElectricFieldLevel.HIGH
        }
        if (level == ElectricFieldLevel.PRESENT && excess >= config.electricPresentThresholdMilliVolts - config.electricHysteresisMilliVolts) {
            return when {
                excess >= config.electricCriticalThresholdMilliVolts -> ElectricFieldLevel.CRITICAL
                excess >= config.electricHighThresholdMilliVolts -> ElectricFieldLevel.HIGH
                else -> ElectricFieldLevel.PRESENT
            }
        }
        return when {
            excess >= config.electricCriticalThresholdMilliVolts -> ElectricFieldLevel.CRITICAL
            excess >= config.electricHighThresholdMilliVolts -> ElectricFieldLevel.HIGH
            excess >= config.electricPresentThresholdMilliVolts -> ElectricFieldLevel.PRESENT
            else -> ElectricFieldLevel.CLEAR
        }
    }

    private fun threshold(value: ElectricFieldLevel): Int = when (value) {
        ElectricFieldLevel.CRITICAL -> config.electricCriticalThresholdMilliVolts
        ElectricFieldLevel.HIGH -> config.electricHighThresholdMilliVolts
        ElectricFieldLevel.PRESENT -> config.electricPresentThresholdMilliVolts
        else -> config.electricPresentThresholdMilliVolts - config.electricHysteresisMilliVolts
    }

    private fun severity(value: ElectricFieldLevel): SafetySeverity = when (value) {
        ElectricFieldLevel.CRITICAL -> SafetySeverity.CRITICAL
        ElectricFieldLevel.HIGH -> SafetySeverity.HIGH
        ElectricFieldLevel.PRESENT -> SafetySeverity.MEDIUM
        else -> SafetySeverity.INFO
    }

    private fun result(excess: Int?, decisions: List<SafetyDecision>) = ElectricResult(
        level = level,
        baselineMilliVolts = baseline,
        excessMilliVolts = excess,
        exposureMilliVoltMillis = exposure,
        decisions = decisions,
    )

    fun reset() {
        calibrationCount = 0
        calibrationSum = 0
        calibrationMinimum = Int.MAX_VALUE
        calibrationMaximum = Int.MIN_VALUE
        baseline = null
        level = ElectricFieldLevel.CALIBRATING
        candidateLevel = null
        candidateCount = 0
        exposure = 0
        lastMillis = null
        faultActive = false
    }

    private companion object {
        const val BASELINE_DIVISOR = 64
        const val SENSOR_FAULT_ELECTRIC = 0x0002
        const val MAX_EXPOSURE = 3_600_000_000L
    }
}

private data class HeightResult(
    val relativeHeightMillimetres: Int?,
    val decisions: List<SafetyDecision>,
)

private class HeightDetector(private val config: SafetyDetectionConfig) {
    private var calibrationCount = 0
    private var calibrationSum = 0L
    private var calibrationMinimum = Int.MAX_VALUE
    private var calibrationMaximum = Int.MIN_VALUE
    private var baseline: Int? = null
    private var active = false
    private var candidateActive: Boolean? = null
    private var candidateCount = 0
    private var faultActive = false

    fun process(sample: SafetySensorSample): HeightResult {
        val altitude = sample.altitudeMillimetres ?: return HeightResult(null, emptyList())
        val invalidPressure = sample.pressurePascals?.let { it !in 30_000..110_000 } ?: false
        if (altitude !in config.heightMinimumMillimetres..config.heightMaximumMillimetres || invalidPressure) {
            val decisions = if (!faultActive) {
                faultActive = true
                listOf(
                    SafetyDecision(
                        SafetyAlarmType.SENSOR_FAULT,
                        SafetySeverity.HIGH,
                        true,
                        sample.sampleReference,
                        sample.monotonicMillis,
                        config.version,
                        if (invalidPressure) "HEIGHT_PRESSURE_RANGE" else "HEIGHT_ALTITUDE_RANGE",
                        altitude.toLong(),
                        config.heightMaximumMillimetres.toLong(),
                        sensorFaults = SENSOR_FAULT_HEIGHT,
                    ),
                )
            } else {
                emptyList()
            }
            return HeightResult(null, decisions)
        }

        val decisions = mutableListOf<SafetyDecision>()
        if (faultActive) {
            faultActive = false
            decisions += SafetyDecision(
                SafetyAlarmType.SENSOR_FAULT,
                SafetySeverity.INFO,
                false,
                sample.sampleReference,
                sample.monotonicMillis,
                config.version,
                "HEIGHT_SENSOR_RECOVERED",
                altitude.toLong(),
                config.heightMaximumMillimetres.toLong(),
                sensorFaults = SENSOR_FAULT_HEIGHT,
            )
        }

        if (baseline == null) {
            calibrationMinimum = minOf(calibrationMinimum, altitude)
            calibrationMaximum = maxOf(calibrationMaximum, altitude)
            calibrationCount += 1
            calibrationSum += altitude
            if (calibrationMaximum - calibrationMinimum > config.heightCalibrationStabilityMillimetres) {
                calibrationCount = 1
                calibrationSum = altitude.toLong()
                calibrationMinimum = altitude
                calibrationMaximum = altitude
            } else if (calibrationCount >= config.heightCalibrationSamples) {
                baseline = (calibrationSum / calibrationCount).toInt()
            }
            return HeightResult(0, decisions)
        }

        val relative = altitude - requireNotNull(baseline)
        val targetActive = if (active) {
            relative >= config.heightThresholdMillimetres - config.heightHysteresisMillimetres
        } else {
            relative >= config.heightThresholdMillimetres
        }
        if (targetActive == active) {
            candidateActive = null
            candidateCount = 0
        } else if (candidateActive == targetActive) {
            candidateCount += 1
        } else {
            candidateActive = targetActive
            candidateCount = 1
        }
        if (candidateCount >= config.heightConfirmationSamples) {
            active = targetActive
            candidateActive = null
            candidateCount = 0
            decisions += SafetyDecision(
                type = SafetyAlarmType.HEIGHT_LIMIT,
                severity = if (active) SafetySeverity.HIGH else SafetySeverity.INFO,
                active = active,
                sampleReference = sample.sampleReference,
                monotonicMillis = sample.monotonicMillis,
                configVersion = config.version,
                reasonCode = if (active) "HEIGHT_THRESHOLD_EXCEEDED" else "HEIGHT_THRESHOLD_CLEARED",
                measuredValue = relative.toLong(),
                thresholdValue = config.heightThresholdMillimetres.toLong(),
            )
        }
        return HeightResult(relative, decisions)
    }

    fun reset() {
        calibrationCount = 0
        calibrationSum = 0
        calibrationMinimum = Int.MAX_VALUE
        calibrationMaximum = Int.MIN_VALUE
        baseline = null
        active = false
        candidateActive = null
        candidateCount = 0
        faultActive = false
    }

    private companion object {
        const val SENSOR_FAULT_HEIGHT = 0x0004
    }
}

private fun Int.sign(): Int = when {
    this < 0 -> -1
    this > 0 -> 1
    else -> 0
}
