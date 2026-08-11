package com.example.helmet.safety.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyDetectionEngineTest {
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
    fun heightUsesStableBaselineConfirmationAndHysteresis() {
        val config = SafetyDetectionConfig(
            electricCalibrationSamples = 3,
            heightCalibrationSamples = 3,
            heightConfirmationSamples = 2,
        )
        val engine = SafetyDetectionEngine(config)
        (1L..3L).forEach { engine.process(height(it, it * 100, 100_000)) }

        assertTrue(engine.process(height(4, 400, 102_200)).decisions.isEmpty())
        val high = engine.process(height(5, 500, 102_200))
        assertEquals(2_200, high.relativeHeightMillimetres)
        assertTrue(high.decisions.single().active)

        engine.process(height(6, 600, 101_700))
        val clear = engine.process(height(7, 700, 101_700))
        assertFalse(clear.decisions.single().active)
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
}
