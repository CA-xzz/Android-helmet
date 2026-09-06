package com.example.helmet.hardware.api

import com.example.helmet.core.protocol.HslFrame
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslPayloadCodec
import com.example.helmet.core.protocol.HslSensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HslEventMapperTest {
    @Test
    fun mapsCameraShortPressToPhoto() {
        val payload = ByteArray(12).apply {
            putU32Le(0, 77)
            this[4] = 1
            this[5] = 3
            putU32Le(8, 12_345)
        }

        val frame = frame(HslMessageType.KEY_EVENT, payload)
        val acknowledgement = HardwareAcknowledgement(frame.sequence, ProviderTransportEpoch())
        val event = HslEventMapper.map(frame, acknowledgement)

        assertTrue(event is HardwareEvent.Key)
        assertEquals(SimulatedInput.PHOTO_SHORT, (event as HardwareEvent.Key).input)
        assertEquals(12_345L, event.monotonicMillis)
        assertEquals(77L, event.eventId)
        assertEquals(1, event.sequence)
        assertSame(acknowledgement, event.acknowledgement)
    }

    @Test
    fun everyValidatedCompletedKeyActionMapsToADurableBusinessEvent() {
        for (key in 1..5) {
            for (action in 3..5) {
                val payload = ByteArray(12).apply {
                    putU32Le(0, (key * 10) + action)
                    this[4] = key.toByte()
                    this[5] = action.toByte()
                }
                val frame = frame(HslMessageType.KEY_EVENT, payload)

                assertEquals("key=$key action=$action", 0, reliableFrameResult(frame))
                assertTrue("key=$key action=$action", HslEventMapper.map(frame) is HardwareEvent.Key)
            }
        }
    }

    @Test
    fun keySequenceIsPresentOnlyForReliableFrames() {
        val payload = ByteArray(12).apply {
            putU32Le(0, 91)
            this[4] = 1
            this[5] = 3
        }
        val unreliable = HslFrame(flags = 0, type = HslMessageType.KEY_EVENT, sequence = 8, payload = payload)

        assertNull((HslEventMapper.map(unreliable) as HardwareEvent.Key).sequence)
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

        val mappedFrame = frame(HslMessageType.ALARM_EVENT, payload)
        val acknowledgement = HardwareAcknowledgement(mappedFrame.sequence, ProviderTransportEpoch())
        val event = HslEventMapper.map(mappedFrame, acknowledgement)

        assertTrue(event is HardwareEvent.Alarm)
        event as HardwareEvent.Alarm
        assertEquals("NEAR_ELECTRIC", event.alarmType)
        assertEquals("CRITICAL", event.severity)
        assertEquals(false, event.simulated)
        assertSame(acknowledgement, event.acknowledgement)
    }

    @Test
    fun mapsExternalInactivityAlarm() {
        val payload = ByteArray(20).apply {
            putU32Le(0, 8)
            this[4] = 7
            this[5] = 3
            this[6] = 1
            putU16Le(7, 2)
            putU32Le(9, 600_000)
            putU32Le(13, 99)
        }

        val event = HslEventMapper.map(frame(HslMessageType.ALARM_EVENT, payload))

        assertEquals("INACTIVITY", (event as HardwareEvent.Alarm).alarmType)
        assertTrue(event.active)
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
        assertNull(event.sequence)
    }

    @Test
    fun mapsAltitudeAndPressureValidityIndependently() {
        fun map(validFlags: Int): HardwareEvent.SensorSample {
            val payload = HslPayloadCodec.encodeSensorSample(
                HslSensorSample(
                    sampleReference = validFlags.toLong(),
                    monotonicMillis = 900,
                    validFlags = validFlags,
                    accelerationXMilliG = 0,
                    accelerationYMilliG = 0,
                    accelerationZMilliG = 0,
                    gyroXMilliDegreesPerSecond = 0,
                    gyroYMilliDegreesPerSecond = 0,
                    gyroZMilliDegreesPerSecond = 0,
                    electricFieldMilliVolts = 0,
                    pressurePascals = 101_325,
                    temperatureCentiCelsius = 2_500,
                    altitudeMillimetres = 123,
                ),
            )
            return HslEventMapper.map(frame(HslMessageType.SENSOR_SAMPLE, payload)) as
                HardwareEvent.SensorSample
        }

        val altitude = map(0x0004)
        assertEquals(123, altitude.altitudeMillimetres)
        assertNull(altitude.pressurePascals)

        val pressure = map(0x0010)
        assertEquals(101_325L, pressure.pressurePascals)
        assertNull(pressure.altitudeMillimetres)
    }

    @Test
    fun reliableSensorSampleCarriesSequenceAndWaitsForPersistenceAcknowledgement() {
        val payload = HslPayloadCodec.encodeSensorSample(
            HslSensorSample(
                sampleReference = 8,
                monotonicMillis = 901,
                validFlags = 0x0001,
                accelerationXMilliG = 0,
                accelerationYMilliG = 0,
                accelerationZMilliG = 1_000,
                gyroXMilliDegreesPerSecond = 0,
                gyroYMilliDegreesPerSecond = 0,
                gyroZMilliDegreesPerSecond = 0,
                electricFieldMilliVolts = 0,
                pressurePascals = 0,
                temperatureCentiCelsius = 0,
                altitudeMillimetres = 0,
            ),
        )
        val frame = HslFrame(
            flags = HslFlags.ACK_REQUIRED,
            type = HslMessageType.SENSOR_SAMPLE,
            sequence = 42,
            payload = payload,
        )

        val acknowledgement = HardwareAcknowledgement(frame.sequence, ProviderTransportEpoch())
        val event = HslEventMapper.map(frame, acknowledgement) as HardwareEvent.SensorSample

        assertEquals(42, event.sequence)
        assertSame(acknowledgement, event.acknowledgement)
        assertTrue(requiresPersistenceBeforeAck(HslMessageType.SENSOR_SAMPLE))
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

        val embeddedAlarmFrame = frame(HslMessageType.ALARM_EVENT, payload)
        val acknowledgement = HardwareAcknowledgement(
            embeddedAlarmFrame.sequence,
            ProviderTransportEpoch(),
        )
        val event = HslEventMapper.map(embeddedAlarmFrame, acknowledgement)

        assertTrue(event is HardwareEvent.Alarm)
        event as HardwareEvent.Alarm
        assertEquals(1, event.sequence)
        assertSame(acknowledgement, event.acknowledgement)
        assertEquals(77L, event.embeddedSample?.sampleReference)
        assertEquals(4_000, event.embeddedSample?.accelerationXMilliG)
        assertNull(event.embeddedSample?.acknowledgement)
        assertEquals(0, reliableFrameResult(embeddedAlarmFrame))
        assertTrue(requiresPersistenceBeforeAck(HslMessageType.ALARM_EVENT))
        assertTrue(requiresPersistenceBeforeAck(HslMessageType.KEY_EVENT))
        payload[20] = 78
        assertEquals(4, reliableFrameResult(frame(HslMessageType.ALARM_EVENT, payload)))
        assertEquals(3, reliableFrameResult(frame(HslMessageType.ALARM_EVENT, ByteArray(59))))
    }

    private fun frame(type: Int, payload: ByteArray) = HslFrame(
        flags = if (type == HslMessageType.ALARM_EVENT || type == HslMessageType.KEY_EVENT) {
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
