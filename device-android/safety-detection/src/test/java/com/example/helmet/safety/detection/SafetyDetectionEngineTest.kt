package com.example.helmet.safety.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyDetectionEngineTest {
    @Test
    fun rejectsMotionThresholdsThatCannotBeRepresentedOrWouldMatchEverySample() {
        assertThrows(IllegalArgumentException::class.java) {
            SafetyDetectionConfig(shakeAccelerationThresholdMilliG = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafetyDetectionConfig(shakeGyroThresholdMilliDegreesPerSecond = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafetyDetectionConfig(impactThresholdMilliG = 65_536)
        }
    }

    @Test
    fun freeFallFollowedByImpactProducesCriticalFall() {
        val engine = SafetyDetectionEngine()
        engine.process(imu(1, 0, 0, 0, 1_000))
        engine.process(imu(2, 100, 0, 0, 100))
        engine.process(imu(3, 240, 0, 0, 100))
        val evaluation = engine.process(imu(4, 300, 2_400, 0, 0))

        val decision = evaluation.decisions.single()
        assertEquals(SafetyAlarmType.FALL, decision.type)
        assertEquals(SafetySeverity.CRITICAL, decision.severity)
        assertEquals("FREE_FALL_THEN_IMPACT", decision.reasonCode)
    }

    @Test
    fun isolatedImpactDoesNotBecomeFall() {
        val engine = SafetyDetectionEngine()
        engine.process(imu(1, 0, 0, 0, 1_000))
        val decision = engine.process(imu(2, 50, 3_200, 0, 0)).decisions.single()

        assertEquals(SafetyAlarmType.IMPACT, decision.type)
        assertEquals(SafetySeverity.HIGH, decision.severity)
    }

    @Test
    fun sustainedImpactIsOneEpisodeUntilAccelerationRecovers() {
        val engine = SafetyDetectionEngine()
        engine.process(imu(1, 0, 0, 0, 1_000))
        assertEquals(
            SafetyAlarmType.IMPACT,
            engine.process(imu(2, 100, 3_200, 0, 0)).decisions.single().type,
        )

        assertTrue(engine.process(imu(3, 2_000, 3_200, 0, 0)).decisions.isEmpty())
        assertTrue(engine.process(imu(4, 2_100, 0, 0, 1_000)).decisions.isEmpty())
        assertEquals(
            SafetyAlarmType.IMPACT,
            engine.process(imu(5, 2_200, 3_200, 0, 0)).decisions.single().type,
        )
    }

    @Test
    fun impactDisarmSurvivesCheckpointAndPreventsRestartDuplicate() {
        val first = SafetyDetectionEngine()
        first.process(imu(1, 0, 0, 0, 1_000))
        first.process(imu(2, 100, 3_200, 0, 0))

        val restored = SafetyDetectionEngine().also { it.restore(first.checkpoint()) }
        assertTrue(restored.process(imu(3, 2_000, 3_200, 0, 0)).decisions.isEmpty())
        restored.process(imu(4, 2_100, 0, 0, 1_000))
        assertEquals(
            SafetyAlarmType.IMPACT,
            restored.process(imu(5, 2_200, 3_200, 0, 0)).decisions.single().type,
        )
    }

    @Test
    fun alternatingHighEnergyMotionProducesViolentShake() {
        val engine = SafetyDetectionEngine()
        engine.process(imu(1, 0, 0, 0, 1_000))
        val decisions = (2L..8L).flatMap { sequence ->
            val direction = if (sequence % 2L == 0L) 1 else -1
            engine.process(
                imu(
                    sequence,
                    sequence * 100,
                    direction * 1_800,
                    0,
                    0,
                    gyroX = direction * 220_000,
                ),
            ).decisions
        }

        assertEquals(1, decisions.size)
        assertEquals(SafetyAlarmType.VIOLENT_SHAKE, decisions.single().type)
    }

    @Test
    fun calmSamplesBreakShakeEpisodeAndSustainedShakeRequiresRecoveryBeforeRearming() {
        val engine = SafetyDetectionEngine()
        engine.process(imu(1, 0, 0, 0, 1_000))
        engine.process(imu(2, 100, 1_800, 0, 0, gyroX = 220_000))
        engine.process(imu(3, 200, 0, 0, 1_000))
        engine.process(imu(4, 300, -1_800, 0, 0, gyroX = -220_000))
        engine.process(imu(5, 400, 0, 0, 1_000))
        assertTrue(engine.process(imu(6, 500, 1_800, 0, 0, gyroX = 220_000)).decisions.isEmpty())

        val firstEpisode = (7L..11L).flatMap { reference ->
            val direction = if (reference % 2L == 0L) 1 else -1
            engine.process(
                imu(reference, reference * 100, direction * 1_800, 0, 0, direction * 220_000),
            ).decisions
        }
        assertEquals(SafetyAlarmType.VIOLENT_SHAKE, firstEpisode.single().type)

        val sustained = (12L..20L).flatMap { reference ->
            val direction = if (reference % 2L == 0L) 1 else -1
            engine.process(
                imu(reference, 2_000 + reference * 100, direction * 1_800, 0, 0, direction * 220_000),
            ).decisions
        }
        assertTrue(sustained.isEmpty())

        engine.process(imu(21, 4_100, 0, 0, 1_000))
        val secondEpisode = (22L..26L).flatMap { reference ->
            val direction = if (reference % 2L == 0L) 1 else -1
            engine.process(
                imu(reference, 4_100 + (reference - 21) * 100, direction * 1_800, 0, 0, direction * 220_000),
            ).decisions
        }
        assertEquals(SafetyAlarmType.VIOLENT_SHAKE, secondEpisode.single().type)
    }

    @Test
    fun sustainedInactivityProducesAlarmAndMotionClearsIt() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(inactivityMinimumMillis = 1_000),
        )
        assertTrue(engine.process(imu(1, 100, 0, 0, 1_000)).decisions.isEmpty())

        val active = engine.process(imu(2, 1_100, 0, 0, 1_000)).decisions.single()
        val clear = engine.process(imu(3, 1_200, 1_400, 0, 0)).decisions.single()

        assertEquals(SafetyAlarmType.INACTIVITY, active.type)
        assertEquals(SafetySeverity.HIGH, active.severity)
        assertTrue(active.active)
        assertEquals("MOTIONLESS_DURATION", active.reasonCode)
        assertEquals(SafetyAlarmType.INACTIVITY, clear.type)
        assertFalse(clear.active)
        assertEquals("MOTION_RESUMED", clear.reasonCode)
    }

    @Test
    fun checkpointRestoresPendingInactivityDuration() {
        val config = SafetyDetectionConfig(inactivityMinimumMillis = 1_000)
        val first = SafetyDetectionEngine(config)
        first.process(imu(1, 100, 0, 0, 1_000))

        val restored = SafetyDetectionEngine(config).also { it.restore(first.checkpoint()) }
        val active = restored.process(imu(2, 1_100, 0, 0, 1_000)).decisions.single()

        assertEquals(SafetyAlarmType.INACTIVITY, active.type)
        assertTrue(active.active)
    }

    @Test
    fun electricFieldCalibratesLevelsAccumulatesAndClears() {
        val config = SafetyDetectionConfig(
            electricCalibrationSamples = 3,
            electricConfirmationSamples = 2,
            heightCalibrationSamples = 3,
        )
        val engine = SafetyDetectionEngine(config)
        (1L..3L).forEach { engine.process(field(it, it * 100, 100)) }

        assertTrue(engine.process(field(4, 400, 300)).decisions.isEmpty())
        val present = engine.process(field(5, 500, 300))
        assertEquals(ElectricFieldLevel.PRESENT, present.electricFieldLevel)
        assertTrue(present.electricFieldExposureMilliVoltMillis > 0)
        assertEquals(SafetyAlarmType.NEAR_ELECTRIC, present.decisions.single().type)
        assertTrue(present.decisions.single().active)

        engine.process(field(6, 600, 100))
        val clear = engine.process(field(7, 700, 100))
        assertEquals(ElectricFieldLevel.CLEAR, clear.electricFieldLevel)
        assertFalse(clear.decisions.single().active)
    }

    @Test
    fun electricCalibrationRequiresConsecutiveStableValidSamples() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(
                electricCalibrationSamples = 3,
                electricConfirmationSamples = 2,
                heightCalibrationSamples = 3,
            ),
        )

        assertEquals(ElectricFieldLevel.CALIBRATING, engine.process(field(1, 100, 100)).electricFieldLevel)
        assertEquals(ElectricFieldLevel.CALIBRATING, engine.process(field(2, 200, 200)).electricFieldLevel)
        assertEquals(ElectricFieldLevel.CALIBRATING, engine.process(field(3, 300, 200)).electricFieldLevel)
        val calibrated = engine.process(field(4, 400, 200))

        assertEquals(ElectricFieldLevel.CLEAR, calibrated.electricFieldLevel)
        assertEquals(200, calibrated.electricFieldBaselineMilliVolts)
        assertTrue(calibrated.decisions.isEmpty())
    }

    @Test
    fun missingElectricSamplesDoNotCalibrateOrFabricateExposure() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(
                electricCalibrationSamples = 3,
                electricConfirmationSamples = 2,
                heightCalibrationSamples = 3,
            ),
        )
        engine.process(field(1, 100, 100))
        assertEquals(ElectricFieldLevel.CALIBRATING, engine.process(imu(2, 200, 0, 0, 1_000)).electricFieldLevel)
        engine.process(field(3, 300, 100))
        assertEquals(ElectricFieldLevel.CLEAR, engine.process(field(4, 400, 100)).electricFieldLevel)

        engine.process(field(5, 500, 300))
        engine.process(imu(6, 2_000, 0, 0, 1_000))
        val active = engine.process(field(7, 2_100, 300))

        assertEquals(220_000L, active.electricFieldExposureMilliVoltMillis)
        assertTrue(active.decisions.single().active)
    }

    @Test
    fun electricLevelsUseHysteresisAndDoNotRepeatDuringStableRisk() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(
                electricCalibrationSamples = 3,
                electricConfirmationSamples = 2,
                heightCalibrationSamples = 3,
            ),
        )
        (1L..3L).forEach { engine.process(field(it, it * 100, 100)) }
        engine.process(field(4, 400, 300))
        val present = engine.process(field(5, 500, 300)).decisions.single()
        engine.process(field(6, 600, 600))
        val high = engine.process(field(7, 700, 600)).decisions.single()
        engine.process(field(8, 800, 1_000))
        val critical = engine.process(field(9, 900, 1_000)).decisions.single()

        (10L..30L).forEach { reference ->
            assertTrue(engine.process(field(reference, reference * 100, 860)).decisions.isEmpty())
        }
        engine.process(field(31, 3_100, 100))
        val clear = engine.process(field(32, 3_200, 100)).decisions.single()

        assertEquals(listOf(SafetySeverity.MEDIUM, SafetySeverity.HIGH, SafetySeverity.CRITICAL),
            listOf(present.severity, high.severity, critical.severity))
        assertTrue(listOf(present, high, critical, clear).all {
            it.requestedLocalActions == SafetyDecision.LOCAL_ACTION_VIBRATION
        })
        assertFalse(clear.active)
    }

    @Test
    fun heightUsesStableBaselineConfirmationAndHysteresis() {
        val config = SafetyDetectionConfig(
            electricCalibrationSamples = 3,
            heightCalibrationSamples = 3,
            heightConfirmationSamples = 2,
            heightConfirmationMillis = 100,
        )
        val engine = SafetyDetectionEngine(config)
        (1L..3L).forEach { engine.process(height(it, it * 100, 100_000)) }

        assertTrue(engine.process(height(4, 400, 102_200)).decisions.isEmpty())
        val high = engine.process(height(5, 500, 102_200))
        assertEquals(2_200, high.relativeHeightMillimetres)
        assertTrue(high.decisions.single().active)
        assertEquals(
            SafetyDecision.LOCAL_ACTION_LED or SafetyDecision.LOCAL_ACTION_VIBRATION,
            high.decisions.single().requestedLocalActions,
        )

        engine.process(height(6, 600, 101_700))
        val clear = engine.process(height(7, 700, 101_700))
        assertFalse(clear.decisions.single().active)
    }

    @Test
    fun missingHeightSampleReportsNoCurrentSourceWithoutDiscardingBaseline() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(
                electricCalibrationSamples = 3,
                heightCalibrationSamples = 3,
                heightConfirmationSamples = 2,
                heightConfirmationMillis = 100,
            ),
        )
        (1L..3L).forEach { engine.process(height(it, it * 100, 100_000)) }

        val missing = engine.process(imu(4, 400, 0, 0, 1_000))
        val resumed = engine.process(height(5, 500, 102_200))

        assertEquals(null, missing.heightInputSource)
        assertEquals(null, missing.relativeHeightMillimetres)
        assertEquals(100_000, missing.heightBaselineMillimetres)
        assertEquals(HeightInputSource.ALTITUDE, resumed.heightInputSource)
        assertEquals(2_200, resumed.relativeHeightMillimetres)
        assertTrue(resumed.decisions.isEmpty())
    }

    @Test
    fun checkpointRestoresLongDurationHeightCandidateAfterSampleCountSaturates() {
        val config = SafetyDetectionConfig(
            electricCalibrationSamples = 3,
            heightCalibrationSamples = 3,
            heightConfirmationSamples = 3,
            heightConfirmationMillis = 1_000,
        )
        val first = SafetyDetectionEngine(config)
        (1L..3L).forEach { first.process(height(it, it * 100, 100_000)) }
        (4L..8L).forEach { reference ->
            assertTrue(first.process(height(reference, reference * 100, 102_200)).decisions.isEmpty())
        }

        val restored = SafetyDetectionEngine(config).also { it.restore(first.checkpoint()) }
        val active = restored.process(height(9, 1_400, 102_200)).decisions.single()

        assertTrue(active.active)
        assertEquals("HEIGHT_THRESHOLD_EXCEEDED", active.reasonCode)
    }

    @Test
    fun rejectsSemanticallyImpossibleHeightCheckpoint() {
        val config = SafetyDetectionConfig(
            electricCalibrationSamples = 3,
            heightCalibrationSamples = 3,
        )
        val engine = SafetyDetectionEngine(config)
        (1L..3L).forEach { engine.process(height(it, it * 100, 100_000)) }
        val checkpoint = engine.checkpoint()
        val impossible = checkpoint.copy(
            height = checkpoint.height.copy(
                baselineMillimetres = null,
                active = true,
            ),
        )

        assertTrue(
            runCatching { SafetyDetectionEngine(config).restore(impossible) }.exceptionOrNull() is
                IllegalArgumentException,
        )
    }

    @Test
    fun pressureOnlySamplesCalibrateTriggerAndClearHeightAlarm() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(
                electricCalibrationSamples = 3,
                heightCalibrationSamples = 3,
                heightConfirmationSamples = 3,
                heightConfirmationMillis = 200,
            ),
        )
        (1L..3L).forEach { engine.process(pressure(it, it * 100, 101_325)) }

        engine.process(pressure(4, 400, 101_285))
        engine.process(pressure(5, 500, 101_285))
        val active = engine.process(pressure(6, 600, 101_285))

        assertEquals(HeightInputSource.PRESSURE, active.heightInputSource)
        assertTrue(requireNotNull(active.relativeHeightMillimetres) > 2_000)
        assertTrue(active.decisions.single().active)

        engine.process(pressure(7, 700, 101_325))
        engine.process(pressure(8, 800, 101_325))
        val clear = engine.process(pressure(9, 900, 101_325))
        assertFalse(clear.decisions.single().active)
    }

    @Test
    fun invalidPressureOnlySampleRaisesFaultAndValidSampleRecovers() {
        val engine = SafetyDetectionEngine()

        val fault = engine.process(pressure(1, 100, 200_000)).decisions.single()
        val recovered = engine.process(pressure(2, 200, 101_325)).decisions.single()

        assertEquals("HEIGHT_PRESSURE_RANGE", fault.reasonCode)
        assertTrue(fault.active)
        assertEquals("HEIGHT_SENSOR_RECOVERED", recovered.reasonCode)
        assertFalse(recovered.active)
    }

    @Test
    fun isolatedHeightSpikeDoesNotMeetContinuousConfirmation() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(
                electricCalibrationSamples = 3,
                heightCalibrationSamples = 3,
                heightConfirmationSamples = 2,
                heightConfirmationMillis = 100,
            ),
        )
        (1L..3L).forEach { engine.process(height(it, it * 100, 100_000)) }

        assertTrue(engine.process(height(4, 400, 103_000)).decisions.isEmpty())
        assertTrue(engine.process(height(5, 500, 100_000)).decisions.isEmpty())
    }

    @Test
    fun stableSlowDriftAdjustsBaselineWithoutAlarm() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(
                electricCalibrationSamples = 3,
                heightCalibrationSamples = 3,
                heightDriftStabilityMillis = 200,
                heightDriftMaximumRateMillimetresPerSecond = 100,
                heightDriftWindowMillimetres = 500,
                heightBaselineAdjustmentDivisor = 2,
            ),
        )
        (1L..3L).forEach { engine.process(height(it, it * 100, 100_000)) }
        engine.process(height(4, 400, 100_005))
        engine.process(height(5, 500, 100_010))
        val evaluation = engine.process(height(6, 600, 100_015))

        assertTrue(evaluation.decisions.isEmpty())
        assertTrue(requireNotNull(evaluation.heightBaselineMillimetres) > 100_000)
        assertEquals(50, evaluation.heightChangeRateMillimetresPerSecond)
    }

    @Test
    fun changingHeightSourceClearsActiveEpisodeAndRecalibrates() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(
                electricCalibrationSamples = 3,
                heightCalibrationSamples = 3,
                heightConfirmationSamples = 3,
                heightConfirmationMillis = 200,
            ),
        )
        (1L..3L).forEach { engine.process(height(it, it * 100, 100_000)) }
        (4L..6L).forEach { engine.process(height(it, it * 100, 102_500)) }

        val changed = engine.process(pressure(7, 700, 101_325))

        assertEquals("HEIGHT_SOURCE_CHANGED", changed.decisions.single().reasonCode)
        assertFalse(changed.decisions.single().active)
        assertEquals(HeightInputSource.PRESSURE, changed.heightInputSource)
        assertEquals(null, changed.heightBaselineMillimetres)
    }

    @Test
    fun convertsStandardPressureToRelativeBarometricAltitude() {
        assertEquals(0, pressureAltitudeMillimetres(101_325))
        assertTrue(pressureAltitudeMillimetres(101_285) > 2_000)
    }

    @Test
    fun invalidSensorValueRaisesFaultOnceAndRecovery() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(electricCalibrationSamples = 3, heightCalibrationSamples = 3),
        )
        val fault = engine.process(field(1, 100, 6_000)).decisions.single()
        assertEquals(SafetyAlarmType.SENSOR_FAULT, fault.type)
        assertTrue(fault.active)
        assertTrue(engine.process(field(2, 200, 6_000)).decisions.isEmpty())
        val recovered = engine.process(field(3, 300, 100)).decisions.single()
        assertFalse(recovered.active)
    }

    @Test
    fun electricRangeFaultClearsActiveNearElectricBeforeFaultAndRecovery() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(
                electricCalibrationSamples = 3,
                electricConfirmationSamples = 1,
                heightCalibrationSamples = 3,
            ),
        )
        (1L..3L).forEach { engine.process(field(it, it * 100, 100)) }
        assertTrue(engine.process(field(4, 400, 350)).decisions.single().active)

        val fault = engine.process(field(5, 500, 6_000)).decisions
        val recovery = engine.process(field(6, 600, 100)).decisions.single()

        assertEquals(2, fault.size)
        assertEquals(SafetyAlarmType.NEAR_ELECTRIC, fault[0].type)
        assertFalse(fault[0].active)
        assertEquals("ELECTRIC_FIELD_SENSOR_UNAVAILABLE", fault[0].reasonCode)
        assertEquals(SafetyAlarmType.SENSOR_FAULT, fault[1].type)
        assertTrue(fault[1].active)
        assertEquals(SafetyAlarmType.SENSOR_FAULT, recovery.type)
        assertFalse(recovery.active)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonMonotonicSamples() {
        val engine = SafetyDetectionEngine()
        engine.process(imu(1, 100, 0, 0, 1_000))
        engine.process(imu(2, 100, 0, 0, 1_000))
    }

    @Test
    fun resetRequiresFreshCalibration() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(electricCalibrationSamples = 3, heightCalibrationSamples = 3),
        )
        (1L..3L).forEach { engine.process(field(it, it * 100, 100)) }
        engine.reset()
        val evaluation = engine.process(field(1, 100, 100))
        assertEquals(ElectricFieldLevel.CALIBRATING, evaluation.electricFieldLevel)
        assertNotNull(evaluation.electricFieldExcessMilliVolts)
    }

    @Test
    fun checkpointRestoresArmedFallCandidateWithoutRawReplay() {
        val config = SafetyDetectionConfig(version = 7)
        val first = SafetyDetectionEngine(config)
        first.process(imu(1, 100, 0, 0, 1_000))
        first.process(imu(2, 200, 0, 0, 100))
        first.process(imu(3, 340, 0, 0, 100))

        val restored = SafetyDetectionEngine(config).also { it.restore(first.checkpoint()) }
        val decision = restored.process(imu(4, 400, 2_400, 0, 0)).decisions.single()

        assertEquals(SafetyAlarmType.FALL, decision.type)
        assertEquals(7, decision.configVersion)
    }

    @Test
    fun checkpointRestoresElectricExposureHeightEpisodeCandidatesAndDrift() {
        val config = SafetyDetectionConfig(
            version = 9,
            electricCalibrationSamples = 3,
            electricConfirmationSamples = 2,
            heightCalibrationSamples = 3,
            heightConfirmationSamples = 2,
            heightConfirmationMillis = 100,
            heightDriftStabilityMillis = 100,
        )
        val uninterrupted = SafetyDetectionEngine(config)
        (1L..3L).forEach { reference ->
            uninterrupted.process(
                combined(reference, reference * 100, electric = 100, altitude = 100_000),
            )
        }
        uninterrupted.process(combined(4, 400, electric = 350, altitude = 102_500))
        val beforeRestart = uninterrupted.process(combined(5, 500, electric = 350, altitude = 102_500))
        assertEquals(2, beforeRestart.decisions.size)

        val checkpoint = uninterrupted.checkpoint()
        val restored = SafetyDetectionEngine(config).also { it.restore(checkpoint) }

        val expectedContinuation = uninterrupted.process(combined(6, 600, electric = 350, altitude = 102_500))
        val actualContinuation = restored.process(combined(6, 600, electric = 350, altitude = 102_500))
        assertEquals(expectedContinuation, actualContinuation)
        assertEquals(uninterrupted.checkpoint(), restored.checkpoint())

        uninterrupted.process(combined(7, 700, electric = 100, altitude = 100_000))
        restored.process(combined(7, 700, electric = 100, altitude = 100_000))
        assertEquals(
            uninterrupted.process(combined(8, 800, electric = 100, altitude = 100_000)),
            restored.process(combined(8, 800, electric = 100, altitude = 100_000)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun checkpointCannotBeRestoredUnderDifferentThresholdVersion() {
        val checkpoint = SafetyDetectionEngine(SafetyDetectionConfig(version = 2)).apply {
            process(field(1, 100, 100))
        }.checkpoint()

        SafetyDetectionEngine(SafetyDetectionConfig(version = 3)).restore(checkpoint)
    }

    private fun imu(
        reference: Long,
        time: Long,
        x: Int,
        y: Int,
        z: Int,
        gyroX: Int = 0,
    ) = SafetySensorSample(
        sampleReference = reference,
        monotonicMillis = time,
        accelerationXMilliG = x,
        accelerationYMilliG = y,
        accelerationZMilliG = z,
        gyroXMilliDegreesPerSecond = gyroX,
        gyroYMilliDegreesPerSecond = 0,
        gyroZMilliDegreesPerSecond = 0,
    )

    private fun field(reference: Long, time: Long, value: Int) = SafetySensorSample(
        sampleReference = reference,
        monotonicMillis = time,
        electricFieldMilliVolts = value,
    )

    private fun height(reference: Long, time: Long, value: Int) = SafetySensorSample(
        sampleReference = reference,
        monotonicMillis = time,
        pressurePascals = 101_325,
        altitudeMillimetres = value,
    )

    private fun pressure(reference: Long, time: Long, value: Int) = SafetySensorSample(
        sampleReference = reference,
        monotonicMillis = time,
        pressurePascals = value,
    )

    private fun combined(
        reference: Long,
        time: Long,
        electric: Int,
        altitude: Int,
    ) = SafetySensorSample(
        sampleReference = reference,
        monotonicMillis = time,
        electricFieldMilliVolts = electric,
        pressurePascals = 101_325,
        altitudeMillimetres = altitude,
    )
}
