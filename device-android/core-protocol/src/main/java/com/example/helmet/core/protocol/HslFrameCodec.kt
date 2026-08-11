package com.example.helmet.core.protocol

data class HslFrame(
    val version: Int = 1,
    val flags: Int,
    val type: Int,
    val sequence: Int,
    val payload: ByteArray,
) {
    init {
        require(version in 0..0xFF)
        require(flags in 0..0xFF)
        require(type in 0..0xFF)
        require(sequence in 0..0xFFFF)
        require(payload.size <= HslFrameCodec.MAX_PAYLOAD_SIZE)
    }

    override fun equals(other: Any?): Boolean =
        other is HslFrame &&
            version == other.version &&
            flags == other.flags &&
            type == other.type &&
            sequence == other.sequence &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        listOf(version, flags, type, sequence, payload.contentHashCode()).hashCode()
}

object HslFrameCodec {
    const val SUPPORTED_VERSION = 1
    const val MAX_PAYLOAD_SIZE = 1024
    const val FRAME_OVERHEAD_SIZE = 11
    private const val SOF_0 = 0xA5
    private const val SOF_1 = 0x5A
    private const val HEADER_WITHOUT_SOF_SIZE = 7

    fun encode(frame: HslFrame): ByteArray {
        val output = ByteArray(FRAME_OVERHEAD_SIZE + frame.payload.size)
        output[0] = SOF_0.toByte()
        output[1] = SOF_1.toByte()
        output[2] = frame.version.toByte()
        output[3] = frame.flags.toByte()
        output[4] = frame.type.toByte()
        output.putU16Le(5, frame.sequence)
        output.putU16Le(7, frame.payload.size)
        frame.payload.copyInto(output, destinationOffset = 9)
        val crc = Crc16Ccitt.compute(output, offset = 2, length = HEADER_WITHOUT_SOF_SIZE + frame.payload.size)
        output.putU16Le(output.size - 2, crc)
        return output
    }

    fun decode(bytes: ByteArray): HslFrame {
        require(bytes.size >= FRAME_OVERHEAD_SIZE) { "frame is shorter than minimum size" }
        require(bytes[0].toInt() and 0xFF == SOF_0 && bytes[1].toInt() and 0xFF == SOF_1) {
            "invalid start of frame"
        }
        val payloadLength = bytes.u16Le(7)
        require(bytes[2].toInt() and 0xFF == SUPPORTED_VERSION) { "unsupported protocol version" }
        require(payloadLength <= MAX_PAYLOAD_SIZE) { "payload is too large" }
        require(bytes.size == FRAME_OVERHEAD_SIZE + payloadLength) { "frame length mismatch" }
        val expectedCrc = bytes.u16Le(bytes.size - 2)
        val actualCrc = Crc16Ccitt.compute(bytes, offset = 2, length = HEADER_WITHOUT_SOF_SIZE + payloadLength)
        require(expectedCrc == actualCrc) { "CRC mismatch" }
        return HslFrame(
            version = bytes[2].toInt() and 0xFF,
            flags = bytes[3].toInt() and 0xFF,
            type = bytes[4].toInt() and 0xFF,
            sequence = bytes.u16Le(5),
            payload = bytes.copyOfRange(9, 9 + payloadLength),
        )
    }

    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun ByteArray.u16Le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}
