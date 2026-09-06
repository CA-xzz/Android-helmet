package com.example.helmet.core.protocol

data class HslDecoderStats(
    val decodedFrames: Long = 0,
    val discardedBytes: Long = 0,
    val crcErrors: Long = 0,
    val lengthErrors: Long = 0,
    val versionErrors: Long = 0,
)

class HslStreamDecoder {
    private val buffer = ByteArray(MAX_BUFFER_SIZE)
    private var bufferStart = 0
    private var bufferEnd = 0
    private var mutableStats = HslDecoderStats()

    val stats: HslDecoderStats
        get() = mutableStats

    internal val bufferedByteCount: Int
        get() = bufferEnd - bufferStart

    fun feed(input: ByteArray): List<HslFrame> {
        if (input.isEmpty()) return emptyList()
        val frames = mutableListOf<HslFrame>()
        var inputOffset = 0

        while (inputOffset < input.size) {
            compactForAppend()
            check(bufferEnd < buffer.size) { "HSL decoder buffer made no progress" }
            val copied = minOf(input.size - inputOffset, buffer.size - bufferEnd)
            input.copyInto(
                destination = buffer,
                destinationOffset = bufferEnd,
                startIndex = inputOffset,
                endIndex = inputOffset + copied,
            )
            bufferEnd += copied
            inputOffset += copied
            decodeAvailable(frames)
        }
        return frames
    }

    fun reset() {
        bufferStart = 0
        bufferEnd = 0
        mutableStats = HslDecoderStats()
    }

    private fun decodeAvailable(frames: MutableList<HslFrame>) {
        while (true) {
            val sofIndex = findStartOfFrame()
            if (sofIndex < 0) {
                val keepLast = bufferedByteCount > 0 &&
                    (buffer[bufferEnd - 1].toInt() and 0xFF) == SOF_0
                val discarded = bufferedByteCount - if (keepLast) 1 else 0
                discardPrefix(discarded)
                return
            }
            if (sofIndex > bufferStart) discardPrefix(sofIndex - bufferStart)
            if (bufferedByteCount < HEADER_SIZE) return

            val version = buffer[bufferStart + 2].toInt() and 0xFF
            if (version != HslFrameCodec.SUPPORTED_VERSION) {
                mutableStats = mutableStats.copy(versionErrors = mutableStats.versionErrors + 1)
                discardPrefix(1)
                continue
            }

            val payloadLength = u16Le(bufferStart + 7)
            if (payloadLength > HslFrameCodec.MAX_PAYLOAD_SIZE) {
                mutableStats = mutableStats.copy(lengthErrors = mutableStats.lengthErrors + 1)
                discardPrefix(1)
                continue
            }

            val frameLength = HslFrameCodec.FRAME_OVERHEAD_SIZE + payloadLength
            if (bufferedByteCount < frameLength) return
            val candidate = buffer.copyOfRange(bufferStart, bufferStart + frameLength)
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

    private fun compactForAppend() {
        if (bufferEnd < buffer.size || bufferStart == 0) return
        val retained = bufferedByteCount
        if (retained > 0) {
            buffer.copyInto(
                destination = buffer,
                destinationOffset = 0,
                startIndex = bufferStart,
                endIndex = bufferEnd,
            )
        }
        bufferStart = 0
        bufferEnd = retained
    }

    private fun discardPrefix(count: Int, countAsDiscarded: Boolean = true) {
        if (count <= 0) return
        val actual = count.coerceAtMost(bufferedByteCount)
        bufferStart += actual
        if (bufferStart == bufferEnd) {
            bufferStart = 0
            bufferEnd = 0
        }
        if (countAsDiscarded) {
            mutableStats = mutableStats.copy(discardedBytes = mutableStats.discardedBytes + actual)
        }
    }

    private fun findStartOfFrame(): Int {
        for (index in bufferStart until bufferEnd - 1) {
            if ((buffer[index].toInt() and 0xFF) == SOF_0 &&
                (buffer[index + 1].toInt() and 0xFF) == SOF_1
            ) {
                return index
            }
        }
        return -1
    }

    private fun u16Le(offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)

    companion object {
        private const val SOF_0 = 0xA5
        private const val SOF_1 = 0x5A
        private const val HEADER_SIZE = 9
        private const val MAX_BUFFER_SIZE =
            HslFrameCodec.MAX_PAYLOAD_SIZE + HslFrameCodec.FRAME_OVERHEAD_SIZE
    }
}
