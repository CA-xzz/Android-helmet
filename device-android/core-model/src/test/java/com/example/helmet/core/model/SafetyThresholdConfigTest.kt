package com.example.helmet.core.model

import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyThresholdConfigTest {
    @Test
    fun electricThresholdsMustFitWireAndSensorRange() {
        assertInvalid { SafetyThresholdConfig(electricMinimumMilliVolts = -1) }
        assertInvalid { SafetyThresholdConfig(electricMaximumMilliVolts = 0x1_0000) }
        assertInvalid {
            SafetyThresholdConfig(
                electricMinimumMilliVolts = 100,
                electricMaximumMilliVolts = 500,
                electricCriticalThresholdMilliVolts = 800,
            )
        }
        assertInvalid { SafetyThresholdConfig(electricCalibrationStabilityMilliVolts = 6_000) }
    }

    @Test
    fun electricHysteresisMustLeaveDistinctRiskBands() {
        assertInvalid {
            SafetyThresholdConfig(
                electricPresentThresholdMilliVolts = 150,
                electricHighThresholdMilliVolts = 200,
                electricCriticalThresholdMilliVolts = 800,
                electricHysteresisMilliVolts = 50,
            )
        }
        assertInvalid {
            SafetyThresholdConfig(
                electricPresentThresholdMilliVolts = 150,
                electricHighThresholdMilliVolts = 400,
                electricCriticalThresholdMilliVolts = 450,
                electricHysteresisMilliVolts = 50,
            )
        }
    }

    @Test
    fun heightThresholdsMustFitWireAndConfiguredSensorRange() {
        assertInvalid { SafetyThresholdConfig(heightCalibrationStabilityMillimetres = 0x1_0000) }
        assertInvalid { SafetyThresholdConfig(heightThresholdMillimetres = 0) }
        assertInvalid { SafetyThresholdConfig(heightHysteresisMillimetres = -1) }
        assertInvalid {
            SafetyThresholdConfig(
                heightMinimumMillimetres = 0,
                heightMaximumMillimetres = 1_000,
                heightThresholdMillimetres = 2_000,
            )
        }
    }

    private fun assertInvalid(create: () -> SafetyThresholdConfig) {
        assertTrue(runCatching(create).exceptionOrNull() is IllegalArgumentException)
    }
}
