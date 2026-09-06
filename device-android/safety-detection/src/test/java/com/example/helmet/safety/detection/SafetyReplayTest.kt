package com.example.helmet.safety.detection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyReplayTest {
    @Test
    fun parsesRequiredMetadataAndDetectsSyntheticFall() {
        val dataset = SafetyReplayCsv.parse(
            File("simulator/datasets/stage-6/fall-synthetic.csv").reader(),
        )
        val report = SafetyReplayEvaluator().evaluate(dataset)

        assertEquals("fall-synthetic-v1", dataset.datasetId)
        assertEquals(10, dataset.sampleRateHertz)
        assertFalse(dataset.finalHardwareEvidence)
        assertEquals(4, report.sampleCount)
        assertEquals(1, report.statistics.getValue(ReplayLabel.FALL).truePositive)
        assertEquals(0, report.statistics.getValue(ReplayLabel.FALL).falseNegative)
        assertEquals(1.0, report.statistics.getValue(ReplayLabel.FALL).recall, 0.0)
    }

    @Test
    fun replayReportDoesNotPromoteSyntheticDataToHardwareEvidence() {
        val dataset = SafetyReplayCsv.parse(
            File("simulator/datasets/stage-6/electric-synthetic.csv").reader(),
        )
        val report = SafetyReplayEvaluator {
            SafetyDetectionEngine(
                SafetyDetectionConfig(
                    electricCalibrationSamples = 3,
                    electricConfirmationSamples = 2,
                    heightCalibrationSamples = 3,
                ),
            )
        }.evaluate(dataset)

        assertFalse(report.finalHardwareEvidence)
        assertTrue(report.statistics.getValue(ReplayLabel.NEAR_ELECTRIC).truePositive >= 1)
        assertEquals(0, report.statistics.getValue(ReplayLabel.NEAR_ELECTRIC).falseNegative)
    }

    @Test
    fun replayDatasetsExerciseImpactShakeAndHeightProductionPaths() {
        val expected = mapOf(
            "impact-synthetic.csv" to ReplayLabel.IMPACT,
            "violent-shake-synthetic.csv" to ReplayLabel.VIOLENT_SHAKE,
            "height-synthetic.csv" to ReplayLabel.HEIGHT_LIMIT,
        )

        expected.forEach { (fileName, label) ->
            val dataset = SafetyReplayCsv.parse(
                File("simulator/datasets/stage-6/$fileName").reader(),
            )
            val report = SafetyReplayEvaluator().evaluate(dataset)

            assertFalse(report.finalHardwareEvidence)
            assertTrue("$fileName did not produce $label", report.statistics.getValue(label).truePositive >= 1)
            assertEquals(0, report.statistics.getValue(label).falseNegative)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingEvidenceMetadata() {
        SafetyReplayCsv.parse(
            """
            # dataset_id=invalid
            # sample_rate_hz=10
            # source=synthetic
            sample_ref,monotonic_ms,accel_x_mg,accel_y_mg,accel_z_mg,gyro_x_mdps,gyro_y_mdps,gyro_z_mdps,electric_mv,pressure_pa,temperature_centi_c,altitude_mm,label
            1,100,0,0,1000,0,0,0,,,,,NONE
            """.trimIndent().reader(),
        )
    }
}
