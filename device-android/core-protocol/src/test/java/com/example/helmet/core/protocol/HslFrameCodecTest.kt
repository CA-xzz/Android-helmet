package com.example.helmet.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HslFrameCodecTest {
    @Test
    fun crcMatchesStandardVector() {
        assertEquals(0x29B1, Crc16Ccitt.compute("123456789".encodeToByteArray()))
    }

    @Test
    fun frameRoundTrips() {
        val original = HslFrame(
            flags = 1,
            type = 0x12,
            sequence = 65530,
            payload = byteArrayOf(1, 2, 3, 4),
        )

        assertEquals(original, HslFrameCodec.decode(HslFrameCodec.encode(original)))
        assertArrayEquals(
            byteArrayOf(
                0xA5.toByte(), 0x5A, 0x01, 0x01, 0x12, 0xFA.toByte(), 0xFF.toByte(),
                0x04, 0x00, 0x01, 0x02, 0x03, 0x04, 0xB8.toByte(), 0x84.toByte(),
            ),
            HslFrameCodec.encode(original),
        )
    }

    @Test
    fun corruptedFrameIsRejected() {
        val encoded = HslFrameCodec.encode(
            HslFrame(flags = 0, type = 1, sequence = 7, payload = byteArrayOf(8, 9)),
        )
        encoded[9] = 0

        assertThrows(IllegalArgumentException::class.java) {
            HslFrameCodec.decode(encoded)
        }
    }
}
