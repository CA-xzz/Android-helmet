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
        assertEquals(2L, decoder.stats.decodedFrames)
        assertEquals(4L, decoder.stats.discardedBytes)
        assertEquals(0, decoder.bufferedByteCount)
    }

    @Test
    fun decodesNinetySixMaximumFramesFromOneFeed() {
        val expected = (0 until 96).map { index ->
            HslFrame(
                flags = HslFlags.ACK_REQUIRED,
                type = HslMessageType.SENSOR_SAMPLE,
                sequence = index,
                payload = ByteArray(HslFrameCodec.MAX_PAYLOAD_SIZE) { payloadIndex ->
                    (index xor payloadIndex).toByte()
                },
            )
        }
        val encoded = concatenate(expected.map(HslFrameCodec::encode))
        val decoder = HslStreamDecoder()

        assertEquals(96 * (HslFrameCodec.MAX_PAYLOAD_SIZE + HslFrameCodec.FRAME_OVERHEAD_SIZE), encoded.size)
        assertEquals(expected, decoder.feed(encoded))
        assertEquals(
            HslDecoderStats(decodedFrames = 96),
            decoder.stats,
        )
        assertEquals(0, decoder.bufferedByteCount)
    }

    @Test
    fun retainsOnePartialFrameAcrossAdversarialLongGarbage() {
        val garbage = ByteArray(
            (HslFrameCodec.MAX_PAYLOAD_SIZE + HslFrameCodec.FRAME_OVERHEAD_SIZE) * 512 + 17,
        ) { 0x33 }
        val maximumFrame = HslFrame(
            flags = 0,
            type = HslMessageType.SENSOR_SAMPLE,
            sequence = 0xBEEF,
            payload = ByteArray(HslFrameCodec.MAX_PAYLOAD_SIZE) { it.toByte() },
        )
        val encoded = HslFrameCodec.encode(maximumFrame)
        val decoder = HslStreamDecoder()

        assertTrue(decoder.feed(garbage + encoded.copyOf(encoded.size - 1)).isEmpty())
        assertEquals(garbage.size.toLong(), decoder.stats.discardedBytes)
        assertEquals(encoded.size - 1, decoder.bufferedByteCount)

        assertEquals(listOf(maximumFrame), decoder.feed(encoded.copyOfRange(encoded.size - 1, encoded.size)))
        assertEquals(1L, decoder.stats.decodedFrames)
        assertEquals(garbage.size.toLong(), decoder.stats.discardedBytes)
        assertEquals(0, decoder.bufferedByteCount)
    }

    @Test
    fun preservesStartOfFrameAcrossFeedBoundary() {
        val encoded = HslFrameCodec.encode(second)
        val decoder = HslStreamDecoder()

        assertTrue(decoder.feed(byteArrayOf(0x11, 0x22, 0xA5.toByte())).isEmpty())
        assertEquals(2L, decoder.stats.discardedBytes)
        assertEquals(1, decoder.bufferedByteCount)

        assertEquals(
            listOf(second),
            decoder.feed(byteArrayOf(0x5A) + encoded.copyOfRange(2, encoded.size)),
        )
        assertEquals(HslDecoderStats(decodedFrames = 1, discardedBytes = 2), decoder.stats)
        assertEquals(0, decoder.bufferedByteCount)
    }

    @Test
    fun resynchronizesAfterCorruptCrcAndOversizedLength() {
        val corrupt = HslFrameCodec.encode(first).also {
            it[it.lastIndex - 1] = 0x11
            it[it.lastIndex] = 0x22
        }
        val oversizedHeader = byteArrayOf(
            0xA5.toByte(), 0x5A, 1, 0, 1, 0, 0, 1, 4,
        )
        val unsupportedVersion = byteArrayOf(
            0xA5.toByte(), 0x5A, 2, 0, 1, 0, 0, 0, 0, 0x11, 0x22,
        )
        val decoder = HslStreamDecoder()

        assertEquals(
            listOf(second),
            decoder.feed(unsupportedVersion + corrupt + oversizedHeader + HslFrameCodec.encode(second)),
        )
        assertEquals(
            HslDecoderStats(
                decodedFrames = 1,
                discardedBytes = (unsupportedVersion.size + corrupt.size + oversizedHeader.size).toLong(),
                crcErrors = 1,
                lengthErrors = 1,
                versionErrors = 1,
            ),
            decoder.stats,
        )
        assertEquals(0, decoder.bufferedByteCount)
    }

    @Test
    fun waitsForTruncatedFrame() {
        val decoder = HslStreamDecoder()
        val encoded = HslFrameCodec.encode(second)

        assertTrue(decoder.feed(encoded.copyOf(encoded.size - 1)).isEmpty())
        assertEquals(listOf(second), decoder.feed(encoded.copyOfRange(encoded.size - 1, encoded.size)))
    }

    private fun concatenate(parts: List<ByteArray>): ByteArray {
        val result = ByteArray(parts.sumOf(ByteArray::size))
        var offset = 0
        parts.forEach { part ->
            part.copyInto(result, destinationOffset = offset)
            offset += part.size
        }
        return result
    }
}
