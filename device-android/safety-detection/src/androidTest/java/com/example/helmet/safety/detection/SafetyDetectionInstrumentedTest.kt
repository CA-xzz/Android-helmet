package com.example.helmet.safety.detection

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyDetectionInstrumentedTest {
    @Test
    fun deterministicFallPipelineRunsOnApi31Device() {
        val engine = SafetyDetectionEngine()
        val samples = listOf(
            imu(1, 100, 0, 0, 1_000),
            imu(2, 200, 0, 0, 100),
            imu(3, 340, 0, 0, 100),
            imu(4, 400, 2_400, 0, 0),
        )

        val decisions = samples.flatMap { engine.process(it).decisions }

        assertEquals(SafetyAlarmType.FALL, decisions.single().type)
        assertEquals(SafetySeverity.CRITICAL, decisions.single().severity)
        assertFalse(samples.any { it.electricFieldMilliVolts != null })
    }

    @Test
    fun deterministicNearElectricPipelineRunsOnApi31Device() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(
                electricCalibrationSamples = 3,
                electricConfirmationSamples = 2,
                heightCalibrationSamples = 3,
            ),
        )
        (1L..3L).forEach { reference -> engine.process(field(reference, reference * 100, 100)) }
        assertTrue(engine.process(field(4, 400, 300)).decisions.isEmpty())

        val evaluation = engine.process(field(5, 500, 300))

        assertEquals(ElectricFieldLevel.PRESENT, evaluation.electricFieldLevel)
        assertTrue(evaluation.electricFieldExposureMilliVoltMillis > 0)
        assertEquals(SafetyAlarmType.NEAR_ELECTRIC, evaluation.decisions.single().type)
        assertTrue(evaluation.decisions.single().active)
    }

    @Test
    fun deterministicHeightPipelineRunsOnApi31Device() {
        val engine = SafetyDetectionEngine(
            SafetyDetectionConfig(
                electricCalibrationSamples = 3,
                heightCalibrationSamples = 3,
                heightConfirmationSamples = 2,
                heightConfirmationMillis = 100,
            ),
        )
        (1L..3L).forEach { reference -> engine.process(height(reference, reference * 100, 100_000)) }
        assertTrue(engine.process(height(4, 400, 102_200)).decisions.isEmpty())

        val evaluation = engine.process(height(5, 500, 102_200))

        assertEquals(2_200, evaluation.relativeHeightMillimetres)
        assertEquals(SafetyAlarmType.HEIGHT_LIMIT, evaluation.decisions.single().type)
        assertTrue(evaluation.decisions.single().active)
    }

    private fun imu(reference: Long, time: Long, x: Int, y: Int, z: Int) = SafetySensorSample(
        sampleReference = reference,
        monotonicMillis = time,
        accelerationXMilliG = x,
        accelerationYMilliG = y,
        accelerationZMilliG = z,
        gyroXMilliDegreesPerSecond = 0,
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
        altitudeMillimetres = value,
    )
}
