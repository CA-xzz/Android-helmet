package com.example.helmet.safety.detection

import com.example.helmet.core.model.canonicalFingerprint
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class SafetyDetectionEngine(
    private val config: SafetyDetectionConfig = SafetyDetectionConfig(),
) {
    private val configFingerprint = config.canonicalFingerprint()
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
            heightInputSource = heightResult.inputSource,
            heightBaselineMillimetres = heightResult.baselineMillimetres,
            heightChangeRateMillimetresPerSecond = heightResult.changeRateMillimetresPerSecond,
            decisions = motionResult.decisions + electricResult.decisions + heightResult.decisions,
        )
    }

    fun reset() {
        motion.reset()
        electric.reset()
        height.reset()
        lastMonotonicMillis = null
    }

    fun checkpoint(): SafetyDetectionCheckpoint = SafetyDetectionCheckpoint(
        algorithmVersion = ALGORITHM_VERSION,
        thresholdConfigVersion = config.version,
        thresholdConfigFingerprint = configFingerprint,
        lastMonotonicMillis = lastMonotonicMillis,
        motion = motion.checkpoint(),
        electric = electric.checkpoint(),
        height = height.checkpoint(),
    )

    fun restore(checkpoint: SafetyDetectionCheckpoint) {
        require(checkpoint.algorithmVersion == ALGORITHM_VERSION) {
            "unsupported safety detection algorithm version"
        }
        require(checkpoint.thresholdConfigVersion == config.version) {
            "safety threshold configuration version changed"
        }
        require(checkpoint.thresholdConfigFingerprint == configFingerprint) {
            "safety threshold configuration content changed"
        }
        motion.restore(checkpoint.motion)
        electric.restore(checkpoint.electric)
        height.restore(checkpoint.height)
        lastMonotonicMillis = checkpoint.lastMonotonicMillis
    }

    companion object {
        const val ALGORITHM_VERSION = 2
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
    private var impactArmed = true
    private var shakeWindowStartMillis: Long? = null
    private var shakeDirection = 0
    private var shakeDirectionChanges = 0
    private var shakeArmed = true
    private var inactivityStartMillis: Long? = null
    private var inactivityActive = false

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
        if (!impactArmed && magnitude < config.fallImpactThresholdMilliG) {
            impactArmed = true
        }

        val decisions = mutableListOf<SafetyDecision>()
        if (
            now >= cooldownUntilMillis &&
            impactArmed &&
            magnitude >= config.fallImpactThresholdMilliG &&
            fallArmedUntilMillis != null
        ) {
            decisions += decision(
                sample,
                SafetyAlarmType.FALL,
                SafetySeverity.CRITICAL,
                "FREE_FALL_THEN_IMPACT",
                magnitude,
                config.fallImpactThresholdMilliG,
            )
            cooldownUntilMillis = now + config.motionCooldownMillis
            impactArmed = false
            fallArmedUntilMillis = null
            resetShake()
        } else if (now >= cooldownUntilMillis && impactArmed && magnitude >= config.impactThresholdMilliG) {
            decisions += decision(
                sample,
                SafetyAlarmType.IMPACT,
                SafetySeverity.HIGH,
                "IMPACT_MAGNITUDE",
                magnitude,
                config.impactThresholdMilliG,
            )
            cooldownUntilMillis = now + config.motionCooldownMillis
            impactArmed = false
            resetShake()
        } else if (
            now >= cooldownUntilMillis &&
            magnitude < config.impactThresholdMilliG &&
            updateShake(sample, x, y, z)
        ) {
            decisions += decision(
                sample,
                SafetyAlarmType.VIOLENT_SHAKE,
                SafetySeverity.MEDIUM,
                "ACCEL_GYRO_DIRECTION_CHANGES",
                shakeDirectionChanges.toLong(),
                config.shakeDirectionChanges.toLong(),
            )
            cooldownUntilMillis = now + config.motionCooldownMillis
            shakeArmed = false
            resetShake()
        }
        updateInactivity(sample, magnitude)?.let(decisions::add)
        return MotionResult(magnitude, decisions)
    }

    private fun updateInactivity(sample: SafetySensorSample, magnitude: Int): SafetyDecision? {
        val maximumGyro = listOf(
            sample.gyroXMilliDegreesPerSecond,
            sample.gyroYMilliDegreesPerSecond,
            sample.gyroZMilliDegreesPerSecond,
        ).filterNotNull().maxOfOrNull(::abs) ?: 0
        val stationary = abs(magnitude - STANDARD_GRAVITY_MILLI_G) <=
            config.inactivityAccelerationToleranceMilliG &&
            maximumGyro <= config.inactivityGyroToleranceMilliDegreesPerSecond
        val now = sample.monotonicMillis
        if (!stationary) {
            inactivityStartMillis = null
            if (!inactivityActive) return null
            inactivityActive = false
            return decision(
                sample = sample,
                type = SafetyAlarmType.INACTIVITY,
                severity = SafetySeverity.INFO,
                reason = "MOTION_RESUMED",
                value = 0,
                threshold = config.inactivityMinimumMillis,
                active = false,
            )
        }
        val start = inactivityStartMillis ?: now.also { inactivityStartMillis = it }
        val duration = now - start
        if (inactivityActive || duration < config.inactivityMinimumMillis) return null
        inactivityActive = true
        return decision(
            sample = sample,
            type = SafetyAlarmType.INACTIVITY,
            severity = SafetySeverity.HIGH,
            reason = "MOTIONLESS_DURATION",
            value = duration,
            threshold = config.inactivityMinimumMillis,
        )
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
        if (!energetic) {
            resetShake()
            shakeArmed = true
            return false
        }
        if (!shakeArmed) return false

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
        active: Boolean = true,
    ) = SafetyDecision(
        type = type,
        severity = severity,
        active = active,
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
        impactArmed = true
        shakeArmed = true
        resetShake()
        inactivityStartMillis = null
        inactivityActive = false
    }

    fun checkpoint() = MotionDetectorCheckpoint(
        freeFallStartMillis = freeFallStartMillis,
        fallArmedUntilMillis = fallArmedUntilMillis,
        cooldownUntilMillis = cooldownUntilMillis,
        impactArmed = impactArmed,
        shakeWindowStartMillis = shakeWindowStartMillis,
        shakeDirection = shakeDirection,
        shakeDirectionChanges = shakeDirectionChanges,
        shakeArmed = shakeArmed,
        inactivityStartMillis = inactivityStartMillis,
        inactivityActive = inactivityActive,
    )

    fun restore(checkpoint: MotionDetectorCheckpoint) {
        require(checkpoint.shakeDirectionChanges <= config.shakeDirectionChanges)
        freeFallStartMillis = checkpoint.freeFallStartMillis
        fallArmedUntilMillis = checkpoint.fallArmedUntilMillis
        cooldownUntilMillis = checkpoint.cooldownUntilMillis
        impactArmed = checkpoint.impactArmed
        shakeWindowStartMillis = checkpoint.shakeWindowStartMillis
        shakeDirection = checkpoint.shakeDirection
        shakeDirectionChanges = checkpoint.shakeDirectionChanges
        shakeArmed = checkpoint.shakeArmed
        inactivityStartMillis = checkpoint.inactivityStartMillis
        inactivityActive = checkpoint.inactivityActive
    }

    private fun resetShake() {
        shakeWindowStartMillis = null
        shakeDirection = 0
        shakeDirectionChanges = 0
    }

    private companion object {
        const val STANDARD_GRAVITY_MILLI_G = 1_000
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
                buildList {
                    if (level in ACTIVE_ELECTRIC_LEVELS) {
                        add(
                            SafetyDecision(
                                SafetyAlarmType.NEAR_ELECTRIC,
                                SafetySeverity.INFO,
                                false,
                                sample.sampleReference,
                                now,
                                config.version,
                                "ELECTRIC_FIELD_SENSOR_UNAVAILABLE",
                                baseline?.let { abs(value - it).toLong() } ?: 0,
                                config.electricPresentThresholdMilliVolts.toLong(),
                                accumulatedValue = exposure,
                                requestedLocalActions = ELECTRIC_LOCAL_ACTIONS,
                            ),
                        )
                    }
                    add(
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
                }
            } else {
                emptyList()
            }
            level = ElectricFieldLevel.FAULT
            candidateLevel = null
            candidateCount = 0
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
                    requestedLocalActions = ELECTRIC_LOCAL_ACTIONS,
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

    fun checkpoint() = ElectricFieldDetectorCheckpoint(
        calibrationCount = calibrationCount,
        calibrationSum = calibrationSum,
        calibrationMinimum = calibrationMinimum,
        calibrationMaximum = calibrationMaximum,
        baselineMilliVolts = baseline,
        level = level,
        candidateLevel = candidateLevel,
        candidateCount = candidateCount,
        exposureMilliVoltMillis = exposure,
        lastMillis = lastMillis,
        faultActive = faultActive,
    )

    fun restore(checkpoint: ElectricFieldDetectorCheckpoint) {
        require(checkpoint.calibrationCount <= config.electricCalibrationSamples)
        require(checkpoint.candidateCount <= config.electricConfirmationSamples)
        require(checkpoint.exposureMilliVoltMillis <= MAX_EXPOSURE)
        require(checkpoint.baselineMilliVolts == null ||
            checkpoint.baselineMilliVolts in config.electricMinimumMilliVolts..config.electricMaximumMilliVolts)
        calibrationCount = checkpoint.calibrationCount
        calibrationSum = checkpoint.calibrationSum
        calibrationMinimum = checkpoint.calibrationMinimum
        calibrationMaximum = checkpoint.calibrationMaximum
        baseline = checkpoint.baselineMilliVolts
        level = checkpoint.level
        candidateLevel = checkpoint.candidateLevel
        candidateCount = checkpoint.candidateCount
        exposure = checkpoint.exposureMilliVoltMillis
        lastMillis = checkpoint.lastMillis
        faultActive = checkpoint.faultActive
    }

    private companion object {
        const val BASELINE_DIVISOR = 64
        const val SENSOR_FAULT_ELECTRIC = 0x0002
        const val MAX_EXPOSURE = 3_600_000_000L
        const val ELECTRIC_LOCAL_ACTIONS = SafetyDecision.LOCAL_ACTION_VIBRATION
        val ACTIVE_ELECTRIC_LEVELS = setOf(
            ElectricFieldLevel.PRESENT,
            ElectricFieldLevel.HIGH,
            ElectricFieldLevel.CRITICAL,
        )
    }
}

private data class HeightResult(
    val relativeHeightMillimetres: Int?,
    val inputSource: HeightInputSource?,
    val baselineMillimetres: Int?,
    val changeRateMillimetresPerSecond: Int?,
    val decisions: List<SafetyDecision>,
)

private class HeightDetector(private val config: SafetyDetectionConfig) {
    private var calibrationCount = 0
    private var calibrationSum = 0L
    private var calibrationMinimum = Int.MAX_VALUE
    private var calibrationMaximum = Int.MIN_VALUE
    private var baseline: Double? = null
    private var active = false
    private var candidateActive: Boolean? = null
    private var candidateCount = 0
    private var candidateStartMillis: Long? = null
    private var inputSource: HeightInputSource? = null
    private var lastHeightMillimetres: Int? = null
    private var lastHeightMillis: Long? = null
    private var driftStableSinceMillis: Long? = null
    private var faultActive = false

    fun process(sample: SafetySensorSample): HeightResult {
        val rawPressure = sample.pressurePascals
        if (
            rawPressure != null &&
            rawPressure !in MIN_PRESSURE_PASCALS.toLong()..MAX_PRESSURE_PASCALS.toLong()
        ) {
            return enterFault(
                sample = sample,
                reasonCode = "HEIGHT_PRESSURE_RANGE",
                measuredValue = rawPressure.toLong(),
                thresholdValue = MAX_PRESSURE_PASCALS.toLong(),
            )
        }
        val source = when {
            sample.altitudeMillimetres != null -> HeightInputSource.ALTITUDE
            rawPressure != null -> HeightInputSource.PRESSURE
            else -> return HeightResult(
                relativeHeightMillimetres = null,
                inputSource = null,
                baselineMillimetres = baseline?.roundToInt(),
                changeRateMillimetresPerSecond = null,
                decisions = emptyList(),
            )
        }
        val height = when (source) {
            HeightInputSource.ALTITUDE -> requireNotNull(sample.altitudeMillimetres)
            HeightInputSource.PRESSURE -> pressureAltitudeMillimetres(requireNotNull(rawPressure).toInt())
        }
        if (height !in config.heightMinimumMillimetres..config.heightMaximumMillimetres) {
            return enterFault(
                sample = sample,
                reasonCode = "HEIGHT_ALTITUDE_RANGE",
                measuredValue = height.toLong(),
                thresholdValue = config.heightMaximumMillimetres.toLong(),
            )
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
                height.toLong(),
                config.heightMaximumMillimetres.toLong(),
                sensorFaults = SENSOR_FAULT_HEIGHT,
            )
        }

        if (inputSource != null && inputSource != source) {
            if (active) {
                decisions += heightDecision(
                    sample = sample,
                    isActive = false,
                    reasonCode = "HEIGHT_SOURCE_CHANGED",
                    measuredValue = 0,
                )
            }
            resetTracking(keepFaultState = true)
        }
        inputSource = source

        if (baseline == null) {
            calibrationMinimum = minOf(calibrationMinimum, height)
            calibrationMaximum = maxOf(calibrationMaximum, height)
            calibrationCount += 1
            calibrationSum += height
            if (calibrationMaximum - calibrationMinimum > config.heightCalibrationStabilityMillimetres) {
                calibrationCount = 1
                calibrationSum = height.toLong()
                calibrationMinimum = height
                calibrationMaximum = height
            } else if (calibrationCount >= config.heightCalibrationSamples) {
                baseline = calibrationSum.toDouble() / calibrationCount
            }
            lastHeightMillimetres = height
            lastHeightMillis = sample.monotonicMillis
            return result(0, 0, decisions)
        }

        val now = sample.monotonicMillis
        val rate = heightChangeRate(height, now)
        val relative = (height - requireNotNull(baseline)).roundToInt()
        val targetActive = if (active) {
            relative >= config.heightThresholdMillimetres - config.heightHysteresisMillimetres
        } else {
            relative >= config.heightThresholdMillimetres
        }
        if (targetActive == active) {
            candidateActive = null
            candidateCount = 0
            candidateStartMillis = null
        } else if (candidateActive == targetActive) {
            candidateCount = (candidateCount + 1).coerceAtMost(config.heightConfirmationSamples)
        } else {
            candidateActive = targetActive
            candidateCount = 1
            candidateStartMillis = now
        }
        val candidateDuration = candidateStartMillis?.let { now - it } ?: 0
        if (
            candidateCount >= config.heightConfirmationSamples &&
            candidateDuration >= config.heightConfirmationMillis
        ) {
            active = targetActive
            candidateActive = null
            candidateCount = 0
            candidateStartMillis = null
            decisions += heightDecision(
                sample = sample,
                isActive = active,
                reasonCode = if (active) "HEIGHT_THRESHOLD_EXCEEDED" else "HEIGHT_THRESHOLD_CLEARED",
                measuredValue = relative,
            )
        }

        updateDriftBaseline(height, relative, rate, now)
        lastHeightMillimetres = height
        lastHeightMillis = now
        return result(relative, rate, decisions)
    }

    fun reset() {
        resetTracking(keepFaultState = false)
        inputSource = null
    }

    fun checkpoint() = HeightDetectorCheckpoint(
        calibrationCount = calibrationCount,
        calibrationSum = calibrationSum,
        calibrationMinimum = calibrationMinimum,
        calibrationMaximum = calibrationMaximum,
        baselineMillimetres = baseline,
        active = active,
        candidateActive = candidateActive,
        candidateCount = candidateCount,
        candidateStartMillis = candidateStartMillis,
        inputSource = inputSource,
        lastHeightMillimetres = lastHeightMillimetres,
        lastHeightMillis = lastHeightMillis,
        driftStableSinceMillis = driftStableSinceMillis,
        faultActive = faultActive,
    )

    fun restore(checkpoint: HeightDetectorCheckpoint) {
        require(checkpoint.calibrationCount <= config.heightCalibrationSamples)
        require(checkpoint.candidateCount <= config.heightConfirmationSamples)
        require(checkpoint.baselineMillimetres == null ||
            checkpoint.baselineMillimetres in
            config.heightMinimumMillimetres.toDouble()..config.heightMaximumMillimetres.toDouble())
        require((checkpoint.lastHeightMillimetres == null) == (checkpoint.lastHeightMillis == null))
        require((checkpoint.candidateActive == null) == (checkpoint.candidateCount == 0))
        require((checkpoint.candidateStartMillis == null) == (checkpoint.candidateCount == 0))
        require(!checkpoint.active || checkpoint.baselineMillimetres != null)
        require(checkpoint.inputSource != null || checkpoint.baselineMillimetres == null)
        require(checkpoint.inputSource != null || checkpoint.lastHeightMillimetres == null)
        require(checkpoint.baselineMillimetres == null ||
            checkpoint.calibrationCount == config.heightCalibrationSamples)
        require(checkpoint.baselineMillimetres != null ||
            checkpoint.calibrationCount < config.heightCalibrationSamples)
        require(!checkpoint.faultActive || checkpoint.inputSource == null)
        calibrationCount = checkpoint.calibrationCount
        calibrationSum = checkpoint.calibrationSum
        calibrationMinimum = checkpoint.calibrationMinimum
        calibrationMaximum = checkpoint.calibrationMaximum
        baseline = checkpoint.baselineMillimetres
        active = checkpoint.active
        candidateActive = checkpoint.candidateActive
        candidateCount = checkpoint.candidateCount
        candidateStartMillis = checkpoint.candidateStartMillis
        inputSource = checkpoint.inputSource
        lastHeightMillimetres = checkpoint.lastHeightMillimetres
        lastHeightMillis = checkpoint.lastHeightMillis
        driftStableSinceMillis = checkpoint.driftStableSinceMillis
        faultActive = checkpoint.faultActive
    }

    private fun enterFault(
        sample: SafetySensorSample,
        reasonCode: String,
        measuredValue: Long,
        thresholdValue: Long,
    ): HeightResult {
        val decisions = mutableListOf<SafetyDecision>()
        if (active) {
            decisions += heightDecision(
                sample = sample,
                isActive = false,
                reasonCode = "HEIGHT_SENSOR_UNAVAILABLE",
                measuredValue = measuredValue.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        if (!faultActive) {
            decisions += SafetyDecision(
                SafetyAlarmType.SENSOR_FAULT,
                SafetySeverity.HIGH,
                true,
                sample.sampleReference,
                sample.monotonicMillis,
                config.version,
                reasonCode,
                measuredValue,
                thresholdValue,
                sensorFaults = SENSOR_FAULT_HEIGHT,
            )
        }
        resetTracking(keepFaultState = true)
        inputSource = null
        faultActive = true
        return result(null, null, decisions)
    }

    private fun resetTracking(keepFaultState: Boolean) {
        calibrationCount = 0
        calibrationSum = 0
        calibrationMinimum = Int.MAX_VALUE
        calibrationMaximum = Int.MIN_VALUE
        baseline = null
        active = false
        candidateActive = null
        candidateCount = 0
        candidateStartMillis = null
        lastHeightMillimetres = null
        lastHeightMillis = null
        driftStableSinceMillis = null
        if (!keepFaultState) faultActive = false
    }

    private fun heightChangeRate(height: Int, now: Long): Int? {
        val previousHeight = lastHeightMillimetres ?: return null
        val previousMillis = lastHeightMillis ?: return null
        val elapsed = now - previousMillis
        if (elapsed <= 0) return null
        return ((height.toLong() - previousHeight) * MILLIS_PER_SECOND / elapsed)
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun updateDriftBaseline(height: Int, relative: Int, rate: Int?, now: Long) {
        val stable = !active &&
            candidateActive == null &&
            abs(relative) <= config.heightDriftWindowMillimetres &&
            rate != null &&
            abs(rate.toLong()) <= config.heightDriftMaximumRateMillimetresPerSecond
        if (!stable) {
            driftStableSinceMillis = null
            return
        }
        val stableSince = driftStableSinceMillis ?: now.also { driftStableSinceMillis = it }
        if (now - stableSince >= config.heightDriftStabilityMillis) {
            val currentBaseline = requireNotNull(baseline)
            baseline = currentBaseline + (height - currentBaseline) / config.heightBaselineAdjustmentDivisor
        }
    }

    private fun heightDecision(
        sample: SafetySensorSample,
        isActive: Boolean,
        reasonCode: String,
        measuredValue: Int,
    ) = SafetyDecision(
        type = SafetyAlarmType.HEIGHT_LIMIT,
        severity = if (isActive) SafetySeverity.HIGH else SafetySeverity.INFO,
        active = isActive,
        sampleReference = sample.sampleReference,
        monotonicMillis = sample.monotonicMillis,
        configVersion = config.version,
        reasonCode = reasonCode,
        measuredValue = measuredValue.toLong(),
        thresholdValue = config.heightThresholdMillimetres.toLong(),
        requestedLocalActions = HEIGHT_LOCAL_ACTIONS,
    )

    private fun result(
        relative: Int?,
        rate: Int?,
        decisions: List<SafetyDecision>,
    ) = HeightResult(
        relativeHeightMillimetres = relative,
        inputSource = inputSource,
        baselineMillimetres = baseline?.roundToInt(),
        changeRateMillimetresPerSecond = rate,
        decisions = decisions,
    )

    private companion object {
        const val MIN_PRESSURE_PASCALS = 30_000
        const val MAX_PRESSURE_PASCALS = 110_000
        private const val MILLIS_PER_SECOND = 1_000L
        const val SENSOR_FAULT_HEIGHT = 0x0004
        const val HEIGHT_LOCAL_ACTIONS =
            SafetyDecision.LOCAL_ACTION_LED or SafetyDecision.LOCAL_ACTION_VIBRATION

    }
}

internal fun pressureAltitudeMillimetres(pressurePascals: Int): Int {
    require(pressurePascals in 30_000..110_000)
    return (
        44_330_000.0 *
            (1.0 - (pressurePascals / 101_325.0).pow(1.0 / 5.255))
        ).roundToInt()
}

private fun Int.sign(): Int = when {
    this < 0 -> -1
    this > 0 -> 1
    else -> 0
}
