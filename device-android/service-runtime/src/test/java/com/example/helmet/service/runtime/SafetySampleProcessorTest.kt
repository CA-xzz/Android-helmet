package com.example.helmet.service.runtime

import com.example.helmet.core.model.SafetyThresholdConfig
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslOutputPayloadCodec
import com.example.helmet.hardware.api.HardwareAlarmOrigin
import com.example.helmet.hardware.api.HardwareEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetySampleProcessorTest {
    @Test
    fun rawImuSamplesProduceAndroidFallAlarm() {
        val processor = SafetySampleProcessor(SafetyThresholdConfig())
        processor.process(imu(1, 1, 0, 0, 1_000))
        processor.process(imu(2, 101, 0, 0, 100))
        processor.process(imu(3, 241, 0, 0, 100))

        val alarm = processor.process(imu(4, 301, 2_400, 0, 0)).alarms.single()

        assertEquals("FALL", alarm.event.alarmType)
        assertEquals("CRITICAL", alarm.event.severity)
        assertEquals(HardwareAlarmOrigin.ANDROID_DETECTION, alarm.event.origin)
        assertFalse(alarm.event.simulated)
        assertEquals("FREE_FALL_THEN_IMPACT", alarm.decision.reasonCode)
        assertTrue(alarm.event.alarmId != null)
    }

    @Test
    fun electricAlarmAndClearShareEpisodeIdAndEvidence() {
        val processor = SafetySampleProcessor(
            SafetyThresholdConfig(
                electricCalibrationSamples = 3,
                electricConfirmationSamples = 2,
                heightCalibrationSamples = 3,
            ),
        )
        (1L..3L).forEach { processor.process(field(it, it * 100, 100)) }
        processor.process(field(4, 400, 300))
        val active = processor.process(field(5, 500, 300)).alarms.single()
        processor.process(field(6, 600, 100))
        val clear = processor.process(field(7, 700, 100)).alarms.single()

        assertTrue(active.event.active)
        assertFalse(clear.event.active)
        assertEquals(active.event.alarmId, clear.event.alarmId)
        assertEquals(100, active.evaluation.electricFieldBaselineMilliVolts)
        assertTrue(active.evaluation.electricFieldExposureMilliVoltMillis > 0)
    }

    @Test
    fun moduleClockRollbackResetsDetectionState() {
        val processor = SafetySampleProcessor(
            SafetyThresholdConfig(electricCalibrationSamples = 3, heightCalibrationSamples = 3),
        )
        processor.process(field(1, 100, 100))
        processor.process(field(2, 200, 100))
        processor.process(field(3, 300, 100))

        val result = processor.process(field(1, 10, 100))

        assertTrue(result.engineReset)
        assertTrue(result.alarms.isEmpty())
        assertEquals("CALIBRATING", result.evaluation.electricFieldLevel.name)
    }

    @Test
    fun androidAlarmCreatesReliableLatchedOutputCommand() {
        val processor = SafetySampleProcessor(SafetyThresholdConfig())
        processor.process(imu(1, 1, 0, 0, 1_000))
        val alarm = processor.process(imu(2, 50, 3_200, 0, 0)).alarms.single().event

        val command = requireNotNull(safetyOutputCommand(alarm, 0x8123))
        val output = HslOutputPayloadCodec.decode(command.payload)

        assertEquals(HslMessageType.SET_OUTPUT, command.type)
        assertEquals(HslFlags.ACK_REQUIRED, command.flags)
        assertEquals(0x8123, command.sequence)
        assertTrue(output.active)
        assertEquals(alarm.localActions, output.actionMask)
        assertTrue(output.requestId > 0)
    }

    @Test
    fun simulatedOrExternalAlarmDoesNotCommandOutputs() {
        val event = HardwareEvent.Alarm(
            monotonicMillis = 1,
            alarmType = "IMPACT",
            severity = "HIGH",
            simulated = true,
            alarmId = 1,
            localActions = 7,
            origin = HardwareAlarmOrigin.ANDROID_DETECTION,
        )

        assertNull(safetyOutputCommand(event, 1))
        assertNull(
            safetyOutputCommand(
                event.copy(simulated = false, origin = HardwareAlarmOrigin.EXTERNAL_MODULE),
                1,
            ),
        )
    }

    private fun imu(reference: Long, time: Long, x: Int, y: Int, z: Int) = HardwareEvent.SensorSample(
        monotonicMillis = time,
        sampleReference = reference,
        validFlags = 0x0001,
        accelerationXMilliG = x,
        accelerationYMilliG = y,
        accelerationZMilliG = z,
        gyroXMilliDegreesPerSecond = 0,
        gyroYMilliDegreesPerSecond = 0,
        gyroZMilliDegreesPerSecond = 0,
        electricFieldMilliVolts = null,
        pressurePascals = null,
        temperatureCentiCelsius = null,
        altitudeMillimetres = null,
        simulated = false,
    )

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
}
