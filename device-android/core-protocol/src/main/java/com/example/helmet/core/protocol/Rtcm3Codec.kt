package com.example.helmet.core.protocol

data class Rtcm3DecoderStats(
    val acceptedFrames: Long = 0,
    val crcErrors: Long = 0,
    val headerErrors: Long = 0,
    val discardedBytes: Long = 0,
)

object Rtcm3FrameCodec {
    const val PREAMBLE = 0xD3
    const val MAX_PAYLOAD_SIZE = 1023
    const val FRAME_OVERHEAD_SIZE = 6
    const val MAX_FRAME_SIZE = MAX_PAYLOAD_SIZE + FRAME_OVERHEAD_SIZE

    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_SIZE) { "RTCM payload is too large" }
        val frame = ByteArray(payload.size + FRAME_OVERHEAD_SIZE)
        frame[0] = PREAMBLE.toByte()
        frame[1] = ((payload.size ushr 8) and 0x03).toByte()
        frame[2] = (payload.size and 0xFF).toByte()
        payload.copyInto(frame, destinationOffset = 3)
        val crc = Crc24Q.compute(frame, 0, payload.size + 3)
        frame[frame.lastIndex - 2] = ((crc ushr 16) and 0xFF).toByte()
        frame[frame.lastIndex - 1] = ((crc ushr 8) and 0xFF).toByte()
        frame[frame.lastIndex] = (crc and 0xFF).toByte()
        return frame
    }

    fun validate(frame: ByteArray): Boolean {
        if (frame.size !in FRAME_OVERHEAD_SIZE..MAX_FRAME_SIZE) return false
        if (frame[0].toInt() and 0xFF != PREAMBLE) return false
        if (frame[1].toInt() and 0xFC != 0) return false
        val payloadLength = ((frame[1].toInt() and 0x03) shl 8) or (frame[2].toInt() and 0xFF)
        if (frame.size != payloadLength + FRAME_OVERHEAD_SIZE) return false
        val expected = ((frame[frame.lastIndex - 2].toInt() and 0xFF) shl 16) or
            ((frame[frame.lastIndex - 1].toInt() and 0xFF) shl 8) or
            (frame[frame.lastIndex].toInt() and 0xFF)
        return Crc24Q.compute(frame, 0, frame.size - 3) == expected
    }
}

object Crc24Q {
    private const val POLYNOMIAL = 0x1864CFB
    private const val MASK = 0xFFFFFF

    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        var crc = 0
        for (index in offset until offset + length) {
            crc = crc xor ((bytes[index].toInt() and 0xFF) shl 16)
            repeat(8) {
                crc = crc shl 1
                if (crc and 0x1000000 != 0) crc = crc xor POLYNOMIAL
            }
        }
        return crc and MASK
    }
}

class Rtcm3StreamDecoder {
    private val frameBuffer = ByteArray(Rtcm3FrameCodec.MAX_FRAME_SIZE)
    private var frameSize = 0
    private var expectedFrameSize = 0
    private var mutableStats = Rtcm3DecoderStats()

    val stats: Rtcm3DecoderStats
        get() = mutableStats

    fun feed(bytes: ByteArray): List<ByteArray> = buildList {
        bytes.forEach { byte -> acceptByte(byte)?.let(::add) }
    }

    fun reset() {
        frameSize = 0
        expectedFrameSize = 0
    }

    private fun acceptByte(byte: Byte): ByteArray? {
        val unsigned = byte.toInt() and 0xFF
        if (frameSize == 0) {
            if (unsigned != Rtcm3FrameCodec.PREAMBLE) {
                mutableStats = mutableStats.copy(discardedBytes = mutableStats.discardedBytes + 1)
                return null
            }
            frameBuffer[frameSize++] = byte
            return null
        }
        if (frameSize == 1 && unsigned and 0xFC != 0) {
            mutableStats = mutableStats.copy(headerErrors = mutableStats.headerErrors + 1)
            reset()
            return acceptByte(byte)
        }
        frameBuffer[frameSize++] = byte
        if (frameSize == 3) {
            val payloadLength = ((frameBuffer[1].toInt() and 0x03) shl 8) or
                (frameBuffer[2].toInt() and 0xFF)
            expectedFrameSize = payloadLength + Rtcm3FrameCodec.FRAME_OVERHEAD_SIZE
        }
        if (expectedFrameSize == 0 || frameSize < expectedFrameSize) return null

        val frame = frameBuffer.copyOf(expectedFrameSize)
        reset()
        return if (Rtcm3FrameCodec.validate(frame)) {
            mutableStats = mutableStats.copy(acceptedFrames = mutableStats.acceptedFrames + 1)
            frame
        } else {
            mutableStats = mutableStats.copy(crcErrors = mutableStats.crcErrors + 1)
            null
        }
    }
}
