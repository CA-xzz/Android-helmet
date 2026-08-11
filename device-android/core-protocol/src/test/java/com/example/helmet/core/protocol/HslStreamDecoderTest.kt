package com.example.helmet.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HslStreamDecoderTest {
    private val first = HslFrame(flags = 0, type = HslMessageType.HEARTBEAT, sequence = 1, payload = byteArrayOf())
    private val second = HslFrame(flags = HslFlags.ACK_REQUIRED, type = HslMessageType.KEY_EVENT, sequence = 2, payload = byteArrayOf(1, 2, 3))

    @Test
    fun acceptsEveryFragmentBoundary() {
        val encoded = HslFrameCodec.encode(second)
        for (split in 1 until encoded.size) {
            val decoder = HslStreamDecoder()
            assertTrue(decoder.feed(encoded.copyOfRange(0, split)).isEmpty())
            assertEquals(listOf(second), decoder.feed(encoded.copyOfRange(split, encoded.size)))
        }
    }

    @Test
    fun decodesNoiseAndConcatenatedFrames() {
        val bytes = byteArrayOf(0, 1, 2, 0xA5.toByte()) +
            HslFrameCodec.encode(first) + HslFrameCodec.encode(second)
        val decoder = HslStreamDecoder()

        assertEquals(listOf(first, second), decoder.feed(bytes))
        assertEquals(2, decoder.stats.decodedFrames)
        assertTrue(decoder.stats.discardedBytes >= 4)
    }

    @Test
    fun resynchronizesAfterCorruptCrcAndOversizedLength() {
        val corrupt = HslFrameCodec.encode(first).also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val oversizedHeader = byteArrayOf(
            0xA5.toByte(), 0x5A, 1, 0, 1, 0, 0, 1, 4,
        )
        val decoder = HslStreamDecoder()

        assertEquals(listOf(second), decoder.feed(corrupt + oversizedHeader + HslFrameCodec.encode(second)))
        assertEquals(1, decoder.stats.crcErrors)
        assertEquals(1, decoder.stats.lengthErrors)
    }

    @Test
    fun waitsForTruncatedFrame() {
        val decoder = HslStreamDecoder()
        val encoded = HslFrameCodec.encode(second)

        assertTrue(decoder.feed(encoded.copyOf(encoded.size - 1)).isEmpty())
        assertEquals(listOf(second), decoder.feed(encoded.copyOfRange(encoded.size - 1, encoded.size)))
    }
}
