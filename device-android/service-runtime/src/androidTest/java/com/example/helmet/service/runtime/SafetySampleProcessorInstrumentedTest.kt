package com.example.helmet.service.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.SafetyThresholdConfig
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslOutputPayloadCodec
import com.example.helmet.hardware.api.HardwareAlarmOrigin
import com.example.helmet.hardware.api.HardwareEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetySampleProcessorInstrumentedTest {
    @Test
    fun rawFieldSamplesCreatePersistentEpisodeAndExternalOutputRequest() {
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

        assertEquals(HardwareAlarmOrigin.ANDROID_DETECTION, active.event.origin)
        assertEquals(active.event.alarmId, clear.event.alarmId)
        assertTrue(active.event.active)
        assertFalse(clear.event.active)
        assertTrue(active.evaluation.electricFieldExposureMilliVoltMillis > 0)

        val activeCommand = requireNotNull(safetyOutputCommand(active.event, 0x8000))
        val clearCommand = requireNotNull(safetyOutputCommand(clear.event, 0x8001))
        assertEquals(HslMessageType.SET_OUTPUT, activeCommand.type)
        assertTrue(HslOutputPayloadCodec.decode(activeCommand.payload).active)
        assertFalse(HslOutputPayloadCodec.decode(clearCommand.payload).active)
        assertEquals(
            HslOutputPayloadCodec.decode(activeCommand.payload).requestId,
            HslOutputPayloadCodec.decode(clearCommand.payload).requestId,
        )
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
}
