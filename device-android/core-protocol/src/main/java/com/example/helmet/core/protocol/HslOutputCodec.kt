package com.example.helmet.core.protocol

data class HslOutputCommand(
    val requestId: Long,
    val active: Boolean,
    val actionMask: Int,
    val durationMillis: Int = 0,
) {
    init {
        require(requestId in 1..0xFFFF_FFFFL) { "request ID must fit non-zero u32" }
        require(actionMask in 1..ALL_ACTIONS) { "output action mask is invalid" }
        require(durationMillis in 0..0xFFFF) { "output duration must fit u16" }
        require(active || durationMillis == 0) { "clear output command cannot have a duration" }
    }

    companion object {
        const val ACTION_LED = 0x01
        const val ACTION_VIBRATION = 0x02
        const val ACTION_BUZZER = 0x04
        const val ALL_ACTIONS = ACTION_LED or ACTION_VIBRATION or ACTION_BUZZER
    }
}

object HslOutputPayloadCodec {
    const val SCHEMA_VERSION = 1
    const val ENCODED_SIZE = 10

    fun encode(command: HslOutputCommand): ByteArray = ByteArray(ENCODED_SIZE).also { output ->
        output[0] = SCHEMA_VERSION.toByte()
        output[1] = if (command.active) 1 else 0
        output[2] = command.actionMask.toByte()
        output[3] = 0
        output.putU32Le(4, command.requestId)
        output.putU16Le(8, command.durationMillis)
    }

    fun decode(payload: ByteArray): HslOutputCommand {
        require(payload.size == ENCODED_SIZE) { "SET_OUTPUT payload length must be $ENCODED_SIZE" }
        require(payload[0].toInt() and 0xFF == SCHEMA_VERSION) { "unsupported SET_OUTPUT schema" }
        val activeValue = payload[1].toInt() and 0xFF
        require(activeValue in 0..1) { "SET_OUTPUT active flag is invalid" }
        require(payload[3].toInt() == 0) { "SET_OUTPUT reserved byte must be zero" }
        return HslOutputCommand(
            requestId = payload.u32Le(4),
            active = activeValue == 1,
            actionMask = payload[2].toInt() and 0xFF,
            durationMillis = payload.u16Le(8),
        )
    }

    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putU32Le(offset: Int, value: Long) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun ByteArray.u16Le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32Le(offset: Int): Long =
        (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
}
