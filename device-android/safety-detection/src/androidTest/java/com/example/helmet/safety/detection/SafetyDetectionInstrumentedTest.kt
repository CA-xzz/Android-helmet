package com.example.helmet.safety.detection

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
