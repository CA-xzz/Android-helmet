package com.example.helmet.service.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.SafetyThresholdConfig
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslOutputPayloadCodec
import com.example.helmet.hardware.api.HardwareAlarmOrigin
import com.example.helmet.hardware.api.HardwareEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetySampleProcessorInstrumentedTest {
    @Test
    fun rawImuProductionProcessorCoversFourMotionStatesAndRecovery() {
        val config = SafetyThresholdConfig(
            motionCooldownMillis = 500,
            inactivityMinimumMillis = 1_000,
        )
        val processor = SafetySampleProcessor(config)
        val alarms = mutableListOf<EvaluatedSafetyAlarm>()

        processor.process(imu(1, 100, 0, 0, 1_000))
        processor.process(imu(2, 200, 0, 0, 100))
        processor.process(imu(3, 340, 0, 0, 100))
        alarms += processor.process(imu(4, 400, 2_400, 0, 0)).alarms.single()

        processor.process(imu(5, 1_000, 0, 0, 1_000))
        alarms += processor.process(imu(6, 1_100, 3_200, 0, 0)).alarms.single()
        assertTrue(processor.process(imu(7, 1_800, 3_200, 0, 0)).alarms.isEmpty())
        processor.process(imu(8, 1_900, 1_200, 0, 0))

        listOf(1, -1, 1, -1, 1).forEachIndexed { index, direction ->
            alarms += processor.process(
                imu(
                    reference = 9L + index,
                    time = 2_000L + index * 100L,
                    x = direction * 1_800,
                    y = 0,
                    z = 0,
                    gyroX = direction * 220_000,
                ),
            ).alarms
        }
        assertTrue(processor.process(imu(14, 3_000, -1_800, 0, 0, -220_000)).alarms.isEmpty())
        processor.process(imu(15, 3_100, 1_200, 0, 0))
        processor.process(imu(16, 3_200, 0, 0, 1_000))
        alarms += processor.process(imu(17, 4_200, 0, 0, 1_000)).alarms.single()

        assertEquals(
            listOf("FALL", "IMPACT", "VIOLENT_SHAKE", "INACTIVITY"),
            alarms.map { it.event.alarmType },
        )
        alarms.forEachIndexed { index, alarm ->
            assertEquals(HardwareAlarmOrigin.ANDROID_DETECTION, alarm.event.origin)
            assertFalse(alarm.event.simulated)
            assertEquals(7, alarm.event.localActions)
            assertNotNull(safetyOutputCommand(alarm.event, 0x8100 + index, requestId = 100L + index))
            assertTrue(shouldCaptureSafetyEvidenceVideo(alarm.event.alarmType, true, false, false))
        }

        val restored = SafetySampleProcessor(config).also {
            it.restoreCheckpoint(processor.checkpoint().toRecord("h618-motion", 1_000))
        }
        val inactivityClear = restored.process(imu(18, 4_300, 1_200, 0, 0)).alarms.single()
        assertEquals("INACTIVITY", inactivityClear.event.alarmType)
        assertFalse(inactivityClear.event.active)
        assertEquals(alarms.last().event.alarmId, inactivityClear.event.alarmId)
        assertNotNull(safetyOutputCommand(inactivityClear.event, 0x8110, requestId = 103))
    }

    @Test
    fun rawFieldSamplesCoverCalibrationGradesExposureRecoveryAndFault() {
        val processor = SafetySampleProcessor(
            SafetyThresholdConfig(
                electricCalibrationSamples = 3,
                electricConfirmationSamples = 2,
                heightCalibrationSamples = 3,
            ),
        )
        (1L..3L).forEach { processor.process(field(it, it * 100, 100)) }
        processor.process(field(4, 400, 300))
        val present = processor.process(field(5, 500, 300)).alarms.single()
        processor.process(field(6, 600, 600))
        val high = processor.process(field(7, 700, 600)).alarms.single()
        processor.process(field(8, 800, 1_000))
        val critical = processor.process(field(9, 900, 1_000)).alarms.single()
        (10L..12L).forEach { reference ->
            assertTrue(processor.process(field(reference, reference * 100, 860)).alarms.isEmpty())
        }

        val restored = SafetySampleProcessor(
            SafetyThresholdConfig(
                electricCalibrationSamples = 3,
                electricConfirmationSamples = 2,
                heightCalibrationSamples = 3,
            ),
        ).also {
            it.restoreCheckpoint(processor.checkpoint().toRecord("h618-electric", 1_200))
        }
        restored.process(field(13, 1_300, 100))
        val clear = restored.process(field(14, 1_400, 100)).alarms.single()
        val fault = restored.process(field(15, 1_500, 6_000)).alarms.single()
        val faultClear = restored.process(field(16, 1_600, 100)).alarms.single()

        val riskUpdates = listOf(present, high, critical)
        assertEquals(listOf("MEDIUM", "HIGH", "CRITICAL"), riskUpdates.map { it.event.severity })
        assertTrue(riskUpdates.all { it.event.origin == HardwareAlarmOrigin.ANDROID_DETECTION })
        assertTrue(riskUpdates.all { it.event.active })
        assertTrue(riskUpdates.all { it.event.localActions == 2 })
        assertEquals(1, riskUpdates.map { it.event.alarmId }.distinct().size)
        assertEquals(present.event.alarmId, clear.event.alarmId)
        assertFalse(clear.event.active)
        assertEquals(2, clear.event.localActions)
        assertTrue(present.evaluation.electricFieldExposureMilliVoltMillis > 0)
        assertTrue(high.evaluation.electricFieldExposureMilliVoltMillis >
            present.evaluation.electricFieldExposureMilliVoltMillis)
        assertTrue(critical.evaluation.electricFieldExposureMilliVoltMillis >
            high.evaluation.electricFieldExposureMilliVoltMillis)
        assertEquals("SENSOR_FAULT", fault.event.alarmType)
        assertTrue(fault.event.active)
        assertEquals(fault.event.alarmId, faultClear.event.alarmId)
        assertFalse(faultClear.event.active)

        val activeCommand = requireNotNull(safetyOutputCommand(present.event, 0x8000, requestId = 55))
        val clearCommand = requireNotNull(safetyOutputCommand(clear.event, 0x8001, requestId = 55))
        assertEquals(HslMessageType.SET_OUTPUT, activeCommand.type)
        assertTrue(HslOutputPayloadCodec.decode(activeCommand.payload).active)
        assertEquals(2, HslOutputPayloadCodec.decode(activeCommand.payload).actionMask)
        assertFalse(HslOutputPayloadCodec.decode(clearCommand.payload).active)
        assertEquals(2, HslOutputPayloadCodec.decode(clearCommand.payload).actionMask)
        assertEquals(
            HslOutputPayloadCodec.decode(activeCommand.payload).requestId,
            HslOutputPayloadCodec.decode(clearCommand.payload).requestId,
        )
    }

    @Test
    fun rawHeightAndPressureSamplesCoverRiskUpdatesOutputsAndFaultRecovery() {
        val config = SafetyThresholdConfig(
            electricCalibrationSamples = 3,
            heightCalibrationSamples = 3,
            heightConfirmationSamples = 2,
            heightConfirmationMillis = 100,
        )
        val processor = SafetySampleProcessor(config)
        (1L..3L).forEach { processor.process(height(it, it * 100, 100_000)) }
        val missing = processor.process(imu(4, 400, 0, 0, 1_000)).evaluation
        processor.process(height(5, 500, 102_200))
        val altitudeActive = processor.process(height(6, 600, 102_200)).alarms.single()
        (7L..8L).forEach { reference ->
            assertTrue(processor.process(height(reference, reference * 100, 101_800)).alarms.isEmpty())
        }
        processor.process(height(9, 900, 101_700))
        val altitudeClear = processor.process(height(10, 1_000, 101_700)).alarms.single()

        (11L..13L).forEach { processor.process(pressure(it, it * 100, 101_325)) }
        processor.process(pressure(14, 1_400, 101_285))
        val pressureActive = processor.process(pressure(15, 1_500, 101_285)).alarms.single()
        val fault = processor.process(pressure(16, 1_600, 200_000)).alarms
        val heightUnavailable = fault.single { it.event.alarmType == "HEIGHT_LIMIT" }
        val faultActive = fault.single { it.event.alarmType == "SENSOR_FAULT" }

        val restored = SafetySampleProcessor(config).also {
            it.restoreCheckpoint(processor.checkpoint().toRecord("h618-height", 1_600))
        }
        val faultClear = restored.process(pressure(17, 1_700, 101_325)).alarms.single()

        assertEquals(null, missing.heightInputSource)
        assertEquals(null, missing.relativeHeightMillimetres)
        assertTrue(altitudeActive.event.active)
        assertEquals(altitudeActive.event.alarmId, altitudeClear.event.alarmId)
        assertFalse(altitudeClear.event.active)
        assertTrue(pressureActive.event.active)
        assertEquals("PRESSURE", pressureActive.evaluation.heightInputSource?.name)
        assertEquals(3, pressureActive.event.localActions)
        assertFalse(heightUnavailable.event.active)
        assertEquals(pressureActive.event.alarmId, heightUnavailable.event.alarmId)
        assertTrue(faultActive.event.active)
        assertFalse(faultClear.event.active)
        assertEquals(faultActive.event.alarmId, faultClear.event.alarmId)

        val activeCommand = requireNotNull(safetyOutputCommand(pressureActive.event, 0x8200, requestId = 77))
        val clearCommand = requireNotNull(safetyOutputCommand(heightUnavailable.event, 0x8201, requestId = 77))
        assertEquals(3, HslOutputPayloadCodec.decode(activeCommand.payload).actionMask)
        assertTrue(HslOutputPayloadCodec.decode(activeCommand.payload).active)
        assertEquals(3, HslOutputPayloadCodec.decode(clearCommand.payload).actionMask)
        assertFalse(HslOutputPayloadCodec.decode(clearCommand.payload).active)
    }

    private fun field(reference: Long, time: Long, value: Int) = HardwareEvent.SensorSample(
        monotonicMillis = time,
        sampleReference = reference,
        validFlags = 0x0002,
        accelerationXMilliG = null,
        accelerationYMilliG = null,
        accelerationZMilliG = null,
        gyroXMilliDegreesPerSecond = null,
        gyroYMilliDegreesPerSecond = null,
        gyroZMilliDegreesPerSecond = null,
        electricFieldMilliVolts = value,
        pressurePascals = null,
        temperatureCentiCelsius = null,
        altitudeMillimetres = null,
        simulated = false,
    )

    private fun imu(
        reference: Long,
        time: Long,
        x: Int,
        y: Int,
        z: Int,
        gyroX: Int = 0,
    ) = HardwareEvent.SensorSample(
        monotonicMillis = time,
        sampleReference = reference,
        validFlags = 0x0001,
        accelerationXMilliG = x,
        accelerationYMilliG = y,
        accelerationZMilliG = z,
        gyroXMilliDegreesPerSecond = gyroX,
        gyroYMilliDegreesPerSecond = 0,
        gyroZMilliDegreesPerSecond = 0,
        electricFieldMilliVolts = null,
        pressurePascals = null,
        temperatureCentiCelsius = null,
        altitudeMillimetres = null,
        simulated = false,
    )

    private fun height(reference: Long, time: Long, value: Int) = HardwareEvent.SensorSample(
        monotonicMillis = time,
        sampleReference = reference,
        validFlags = 0x0004,
        accelerationXMilliG = null,
        accelerationYMilliG = null,
        accelerationZMilliG = null,
        gyroXMilliDegreesPerSecond = null,
        gyroYMilliDegreesPerSecond = null,
        gyroZMilliDegreesPerSecond = null,
        electricFieldMilliVolts = null,
        pressurePascals = null,
        temperatureCentiCelsius = null,
        altitudeMillimetres = value,
        simulated = false,
    )

    private fun pressure(reference: Long, time: Long, value: Long) = HardwareEvent.SensorSample(
        monotonicMillis = time,
        sampleReference = reference,
        validFlags = 0x0010,
        accelerationXMilliG = null,
        accelerationYMilliG = null,
        accelerationZMilliG = null,
        gyroXMilliDegreesPerSecond = null,
        gyroYMilliDegreesPerSecond = null,
        gyroZMilliDegreesPerSecond = null,
        electricFieldMilliVolts = null,
        pressurePascals = value,
        temperatureCentiCelsius = null,
        altitudeMillimetres = null,
        simulated = false,
    )
}
