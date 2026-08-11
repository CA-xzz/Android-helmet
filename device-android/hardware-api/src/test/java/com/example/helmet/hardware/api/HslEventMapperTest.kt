package com.example.helmet.hardware.api

import com.example.helmet.core.protocol.HslFrame
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslPayloadCodec
import com.example.helmet.core.protocol.HslSensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HslEventMapperTest {
    @Test
    fun mapsCameraShortPressToPhoto() {
        val payload = ByteArray(12).apply {
            this[4] = 1
            this[5] = 3
            putU32Le(8, 12_345)
        }

        val event = HslEventMapper.map(frame(HslMessageType.KEY_EVENT, payload))

        assertTrue(event is HardwareEvent.Key)
        assertEquals(SimulatedInput.PHOTO_SHORT, (event as HardwareEvent.Key).input)
        assertEquals(12_345L, event.monotonicMillis)
    }

    @Test
    fun mapsActiveAlarmWithoutMarkingItSimulated() {
        val payload = ByteArray(20).apply {
            putU32Le(0, 1)
            this[4] = 2
            this[5] = 4
            this[6] = 1
            putU16Le(7, 1)
            putU32Le(9, 456)
            putU32Le(13, 7)
        }

        val event = HslEventMapper.map(frame(HslMessageType.ALARM_EVENT, payload))

        assertTrue(event is HardwareEvent.Alarm)
        event as HardwareEvent.Alarm
        assertEquals("NEAR_ELECTRIC", event.alarmType)
        assertEquals("CRITICAL", event.severity)
        assertEquals(false, event.simulated)
    }

    @Test
    fun mapsReleasedAlarmAndIgnoresIncompleteKeyPress() {
        val alarm = ByteArray(20).apply {
            putU32Le(0, 2)
            this[4] = 1
            this[5] = 3
            this[6] = 0
            putU16Le(7, 1)
            putU32Le(9, 10)
            putU32Le(13, 11)
        }
        val key = ByteArray(12).apply {
            this[4] = 1
            this[5] = 1
        }

        val released = HslEventMapper.map(frame(HslMessageType.ALARM_EVENT, alarm))
        assertTrue(released is HardwareEvent.Alarm)
        assertEquals(false, (released as HardwareEvent.Alarm).active)
        assertNull(HslEventMapper.map(frame(HslMessageType.KEY_EVENT, key)))
    }

    @Test
    fun mapsValidSensorFieldsAndMasksAbsentValues() {
        val payload = HslPayloadCodec.encodeSensorSample(
            HslSensorSample(
                sampleReference = 7,
                monotonicMillis = 900,
                validFlags = 0x0003,
                accelerationXMilliG = -10,
                accelerationYMilliG = 20,
                accelerationZMilliG = 980,
                gyroXMilliDegreesPerSecond = 1,
                gyroYMilliDegreesPerSecond = 2,
                gyroZMilliDegreesPerSecond = 3,
                electricFieldMilliVolts = 250,
                pressurePascals = 101_325,
                temperatureCentiCelsius = 2_500,
                altitudeMillimetres = 123,
            ),
        )

        val event = HslEventMapper.map(frame(HslMessageType.SENSOR_SAMPLE, payload))

        assertTrue(event is HardwareEvent.SensorSample)
        event as HardwareEvent.SensorSample
        assertEquals(7L, event.sampleReference)
        assertEquals(-10, event.accelerationXMilliG)
        assertEquals(250, event.electricFieldMilliVolts)
        assertNull(event.pressurePascals)
        assertNull(event.altitudeMillimetres)
        assertEquals(false, event.simulated)
    }

    @Test
    fun mapsDurableAlarmWithEmbeddedSensorSnapshot() {
        val sample = HslPayloadCodec.encodeSensorSample(
            HslSensorSample(
                sampleReference = 77,
                monotonicMillis = 900,
                validFlags = 0x0007,
                accelerationXMilliG = 4_000,
                accelerationYMilliG = 0,
                accelerationZMilliG = 0,
                gyroXMilliDegreesPerSecond = 1,
                gyroYMilliDegreesPerSecond = 2,
                gyroZMilliDegreesPerSecond = 3,
                electricFieldMilliVolts = 250,
                pressurePascals = 101_325,
                temperatureCentiCelsius = 2_500,
                altitudeMillimetres = 123,
            ),
        )
        val payload = ByteArray(60).apply {
            putU32Le(0, 9)
            this[4] = 4
            this[5] = 4
            this[6] = 1
            putU16Le(7, 3)
            putU32Le(9, 900)
            putU32Le(13, 77)
            this[17] = 7
            sample.copyInto(this, 20)
        }

        val event = HslEventMapper.map(frame(HslMessageType.ALARM_EVENT, payload))

        assertTrue(event is HardwareEvent.Alarm)
        event as HardwareEvent.Alarm
        assertEquals(1, event.sequence)
        assertEquals(77L, event.embeddedSample?.sampleReference)
        assertEquals(4_000, event.embeddedSample?.accelerationXMilliG)
        assertEquals(0, reliableFrameResult(frame(HslMessageType.ALARM_EVENT, payload)))
        assertTrue(requiresPersistenceBeforeAck(HslMessageType.ALARM_EVENT))
        assertEquals(false, requiresPersistenceBeforeAck(HslMessageType.KEY_EVENT))
        payload[20] = 78
        assertEquals(4, reliableFrameResult(frame(HslMessageType.ALARM_EVENT, payload)))
        assertEquals(3, reliableFrameResult(frame(HslMessageType.ALARM_EVENT, ByteArray(59))))
    }

    private fun frame(type: Int, payload: ByteArray) = HslFrame(
        flags = if (type == HslMessageType.ALARM_EVENT) {
            HslFlags.ACK_REQUIRED
        } else {
            0
        },
        type = type,
        sequence = 1,
        payload = payload,
    )

    private fun ByteArray.putU32Le(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }
}
