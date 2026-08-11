package com.example.helmet.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HslPayloadCodecTest {
    @Test
    fun heartbeatHasStableEightByteLayout() {
        val heartbeat = HslHeartbeat(
            uptimeMillis = 0x0403_0201,
            operationalState = 5,
            protocolVersion = 1,
            errorCount = 0x0706,
        )

        val encoded = HslPayloadCodec.encodeHeartbeat(heartbeat)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 1, 6, 7), encoded)
        assertEquals(heartbeat, HslPayloadCodec.decodeHeartbeat(encoded))
    }

    @Test
    fun ackRoundTripsDetail() {
        val ack = HslAck(acknowledgedSequence = 0x1234, resultCode = 7, detail = byteArrayOf(8, 9))

        val decoded = HslPayloadCodec.decodeAck(HslPayloadCodec.encodeAck(ack))
        assertEquals(ack.acknowledgedSequence, decoded.acknowledgedSequence)
        assertEquals(ack.resultCode, decoded.resultCode)
        assertArrayEquals(ack.detail, decoded.detail)
    }

    @Test(expected = IllegalArgumentException::class)
    fun alarmRejectsWrongPayloadLength() {
        HslPayloadCodec.decodeAlarmEvent(ByteArray(19))
    }

    @Test
    fun alarmCarriesAnAtomicEmbeddedSensorSnapshot() {
        val sample = HslSensorSample(
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
            HslPayloadCodec.encodeSensorSample(sample).copyInto(this, 20)
        }

        val decoded = HslPayloadCodec.decodeAlarmEvent(payload)

        assertEquals(9L, decoded.alarmId)
        assertNotNull(decoded.embeddedSample)
        assertEquals(sample, decoded.embeddedSample)
        payload[20] = 78
        assertThrows(IllegalArgumentException::class.java) { HslPayloadCodec.decodeAlarmEvent(payload) }
    }

    @Test
    fun sensorSampleHasStableSignedFortyByteLayout() {
        val sample = HslSensorSample(
            sampleReference = 0x0403_0201,
            monotonicMillis = 0x0807_0605,
            validFlags = 0x000F,
            accelerationXMilliG = -1,
            accelerationYMilliG = 2,
            accelerationZMilliG = -3,
            gyroXMilliDegreesPerSecond = -100_000,
            gyroYMilliDegreesPerSecond = 200_000,
            gyroZMilliDegreesPerSecond = -300_000,
            electricFieldMilliVolts = 1_234,
            pressurePascals = 101_325,
            temperatureCentiCelsius = -250,
            altitudeMillimetres = -12_345,
        )

        val encoded = HslPayloadCodec.encodeSensorSample(sample)

        assertEquals(40, encoded.size)
        assertEquals(sample, HslPayloadCodec.decodeSensorSample(encoded))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), encoded.copyOfRange(0, 8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sensorSampleRejectsWrongPayloadLength() {
        HslPayloadCodec.decodeSensorSample(ByteArray(39))
    }

    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putU32Le(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }
}
