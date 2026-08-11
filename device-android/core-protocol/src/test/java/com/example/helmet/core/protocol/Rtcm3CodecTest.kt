package com.example.helmet.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Rtcm3CodecTest {
    @Test
    fun encodesValidFrameAndDecodesAcrossChunks() {
        val payload = ByteArray(300) { index -> (index * 17).toByte() }
        val frame = Rtcm3FrameCodec.encode(payload)
        val decoder = Rtcm3StreamDecoder()

        assertTrue(Rtcm3FrameCodec.validate(frame))
        assertTrue(decoder.feed(byteArrayOf(0x01, 0x02)).isEmpty())
        assertTrue(decoder.feed(frame.copyOfRange(0, 79)).isEmpty())
        val decoded = decoder.feed(frame.copyOfRange(79, frame.size))

        assertEquals(1, decoded.size)
        assertArrayEquals(frame, decoded.single())
        assertEquals(1, decoder.stats.acceptedFrames)
        assertEquals(2, decoder.stats.discardedBytes)
    }

    @Test
    fun rejectsReservedHeaderBitsAndBadCrcThenRecovers() {
        val valid = Rtcm3FrameCodec.encode(byteArrayOf(0x3E, 0x00, 0x01, 0x02))
        val badHeader = valid.copyOf().also { it[1] = (it[1].toInt() or 0x40).toByte() }
        val badCrc = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val decoder = Rtcm3StreamDecoder()

        assertFalse(Rtcm3FrameCodec.validate(badHeader))
        assertFalse(Rtcm3FrameCodec.validate(badCrc))
        assertTrue(decoder.feed(badHeader + badCrc).isEmpty())
        assertArrayEquals(valid, decoder.feed(valid).single())
        assertTrue(decoder.stats.headerErrors >= 1)
        assertEquals(1, decoder.stats.crcErrors)
    }

    @Test
    fun supportsMaximumRtcmPayload() {
        val frame = Rtcm3FrameCodec.encode(ByteArray(Rtcm3FrameCodec.MAX_PAYLOAD_SIZE) { 0x55 })
        assertEquals(Rtcm3FrameCodec.MAX_FRAME_SIZE, frame.size)
        assertTrue(Rtcm3FrameCodec.validate(frame))
    }
}
