package com.example.helmet.core.protocol

data class HslDecoderStats(
    val decodedFrames: Long = 0,
    val discardedBytes: Long = 0,
    val crcErrors: Long = 0,
    val lengthErrors: Long = 0,
    val versionErrors: Long = 0,
)

class HslStreamDecoder {
    private var buffer = ByteArray(0)
    private var mutableStats = HslDecoderStats()

    val stats: HslDecoderStats
        get() = mutableStats

    fun feed(input: ByteArray): List<HslFrame> {
        if (input.isEmpty()) return emptyList()
        appendBounded(input)
        val frames = mutableListOf<HslFrame>()

        while (true) {
            val sofIndex = findStartOfFrame(buffer)
            if (sofIndex < 0) {
                val keepLast = buffer.lastOrNull()?.toInt()?.and(0xFF) == SOF_0
                val discarded = buffer.size - if (keepLast) 1 else 0
                discardPrefix(discarded)
                return frames
            }
            if (sofIndex > 0) discardPrefix(sofIndex)
            if (buffer.size < HEADER_SIZE) return frames

            val version = buffer[2].toInt() and 0xFF
            if (version != HslFrameCodec.SUPPORTED_VERSION) {
                mutableStats = mutableStats.copy(versionErrors = mutableStats.versionErrors + 1)
                discardPrefix(1)
                continue
            }

            val payloadLength = u16Le(buffer, 7)
            if (payloadLength > HslFrameCodec.MAX_PAYLOAD_SIZE) {
                mutableStats = mutableStats.copy(lengthErrors = mutableStats.lengthErrors + 1)
                discardPrefix(1)
                continue
            }

            val frameLength = HslFrameCodec.FRAME_OVERHEAD_SIZE + payloadLength
            if (buffer.size < frameLength) return frames
            val candidate = buffer.copyOfRange(0, frameLength)
            val frame = runCatching { HslFrameCodec.decode(candidate) }.getOrNull()
            if (frame == null) {
                mutableStats = mutableStats.copy(crcErrors = mutableStats.crcErrors + 1)
                discardPrefix(1)
                continue
            }

            frames += frame
            mutableStats = mutableStats.copy(decodedFrames = mutableStats.decodedFrames + 1)
            discardPrefix(frameLength, countAsDiscarded = false)
        }
    }

    fun reset() {
        buffer = ByteArray(0)
        mutableStats = HslDecoderStats()
    }

    private fun appendBounded(input: ByteArray) {
        buffer += input
        if (buffer.size > MAX_BUFFER_SIZE) {
            val overflow = buffer.size - MAX_BUFFER_SIZE
            discardPrefix(overflow)
        }
    }

    private fun discardPrefix(count: Int, countAsDiscarded: Boolean = true) {
        if (count <= 0) return
        val actual = count.coerceAtMost(buffer.size)
        buffer = buffer.copyOfRange(actual, buffer.size)
        if (countAsDiscarded) {
            mutableStats = mutableStats.copy(discardedBytes = mutableStats.discardedBytes + actual)
        }
    }

    private fun findStartOfFrame(bytes: ByteArray): Int {
        for (index in 0 until bytes.size - 1) {
            if ((bytes[index].toInt() and 0xFF) == SOF_0 &&
                (bytes[index + 1].toInt() and 0xFF) == SOF_1
            ) {
                return index
            }
        }
        return -1
    }

    private fun u16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    companion object {
        private const val SOF_0 = 0xA5
        private const val SOF_1 = 0x5A
        private const val HEADER_SIZE = 9
        private const val MAX_BUFFER_SIZE = (HslFrameCodec.MAX_PAYLOAD_SIZE + HslFrameCodec.FRAME_OVERHEAD_SIZE) * 4
    }
}
