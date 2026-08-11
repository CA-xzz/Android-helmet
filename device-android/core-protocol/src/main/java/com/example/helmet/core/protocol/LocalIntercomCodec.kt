package com.example.helmet.core.protocol

enum class LocalIntercomAction(val wireValue: Int) {
    JOIN(1),
    LEAVE(2),
    START_TRANSMIT(3),
    STOP_TRANSMIT(4),
    SELF_TEST(5),
    ;

    companion object {
        fun fromWire(value: Int): LocalIntercomAction = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("unknown local intercom action")
    }
}

enum class LocalIntercomModuleState(val wireValue: Int) {
    UNAVAILABLE(0),
    READY(1),
    RECEIVING(2),
    TRANSMITTING(3),
    FAULT(4),
    ;

    companion object {
        fun fromWire(value: Int): LocalIntercomModuleState = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("unknown local intercom state")
    }
}

enum class LocalIntercomCodec(val wireValue: Int, val nominalSampleRateHertz: Int) {
    MODULE_NEGOTIATED(0, 0),
    CODEC2_2400(1, 8_000),
    MELPE_2400(2, 8_000),
    VENDOR_NARROWBAND(255, 8_000),
    ;

    companion object {
        fun fromWire(value: Int): LocalIntercomCodec = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("unknown local intercom codec")
    }
}

data class HslLocalIntercomCommand(
    val action: LocalIntercomAction,
    val requestId: Long,
    val groupId: Int,
    val channel: Int,
    val keySlot: Int,
    val codec: LocalIntercomCodec = LocalIntercomCodec.MODULE_NEGOTIATED,
)

data class HslLocalIntercomStatus(
    val state: LocalIntercomModuleState,
    val requestId: Long,
    val peerCount: Int,
    val codec: LocalIntercomCodec,
    val sampleRateHertz: Int,
    val rssiDbm: Int,
    val snrTenthsDb: Int,
    val packetLossPermille: Int,
    val oneWayLatencyMillis: Int,
    val transmittedPackets: Long,
    val receivedPackets: Long,
    val faultCode: Int,
)

object LocalIntercomPayloadCodec {
    const val SCHEMA_VERSION = 1
    const val COMMAND_SIZE = 12
    const val STATUS_SIZE = 28

    fun encodeCommand(command: HslLocalIntercomCommand): ByteArray {
        require(command.requestId in 0..0xFFFF_FFFFL)
        require(command.groupId in 1..0xFFFF)
        require(command.channel in 0..0xFF)
        require(command.keySlot in 1..0xFF)
        return ByteArray(COMMAND_SIZE).also { output ->
            output[0] = SCHEMA_VERSION.toByte()
            output[1] = command.action.wireValue.toByte()
            output.putU32Le(2, command.requestId)
            output.putU16Le(6, command.groupId)
            output[8] = command.channel.toByte()
            output[9] = command.keySlot.toByte()
            output[10] = command.codec.wireValue.toByte()
            output[11] = 0
        }
    }

    fun decodeCommand(payload: ByteArray): HslLocalIntercomCommand {
        require(payload.size == COMMAND_SIZE) { "local intercom command length must be $COMMAND_SIZE" }
        require(payload[0].toInt() and 0xFF == SCHEMA_VERSION) { "unsupported local intercom schema" }
        require(payload[11].toInt() == 0) { "local intercom reserved byte must be zero" }
        return HslLocalIntercomCommand(
            action = LocalIntercomAction.fromWire(payload[1].toInt() and 0xFF),
            requestId = payload.u32Le(2),
            groupId = payload.u16Le(6),
            channel = payload[8].toInt() and 0xFF,
            keySlot = payload[9].toInt() and 0xFF,
            codec = LocalIntercomCodec.fromWire(payload[10].toInt() and 0xFF),
        ).also { command ->
            require(command.groupId > 0 && command.keySlot > 0)
        }
    }

    fun encodeStatus(status: HslLocalIntercomStatus): ByteArray {
        require(status.requestId in 0..0xFFFF_FFFFL)
        require(status.peerCount in 0..0xFF)
        require(status.sampleRateHertz in 0..0xFFFF)
        require(status.rssiDbm in Short.MIN_VALUE..Short.MAX_VALUE)
        require(status.snrTenthsDb in Short.MIN_VALUE..Short.MAX_VALUE)
        require(status.packetLossPermille in 0..1_000)
        require(status.oneWayLatencyMillis in 0..0xFFFF)
        require(status.transmittedPackets in 0..0xFFFF_FFFFL)
        require(status.receivedPackets in 0..0xFFFF_FFFFL)
        require(status.faultCode in 0..0xFFFF)
        return ByteArray(STATUS_SIZE).also { output ->
            output[0] = SCHEMA_VERSION.toByte()
            output[1] = status.state.wireValue.toByte()
            output.putU32Le(2, status.requestId)
            output[6] = status.peerCount.toByte()
            output[7] = status.codec.wireValue.toByte()
            output.putU16Le(8, status.sampleRateHertz)
            output.putI16Le(10, status.rssiDbm)
            output.putI16Le(12, status.snrTenthsDb)
            output.putU16Le(14, status.packetLossPermille)
            output.putU16Le(16, status.oneWayLatencyMillis)
            output.putU32Le(18, status.transmittedPackets)
            output.putU32Le(22, status.receivedPackets)
            output.putU16Le(26, status.faultCode)
        }
    }

    fun decodeStatus(payload: ByteArray): HslLocalIntercomStatus {
        require(payload.size == STATUS_SIZE) { "local intercom status length must be $STATUS_SIZE" }
        require(payload[0].toInt() and 0xFF == SCHEMA_VERSION) { "unsupported local intercom schema" }
        return HslLocalIntercomStatus(
            state = LocalIntercomModuleState.fromWire(payload[1].toInt() and 0xFF),
            requestId = payload.u32Le(2),
            peerCount = payload[6].toInt() and 0xFF,
            codec = LocalIntercomCodec.fromWire(payload[7].toInt() and 0xFF),
            sampleRateHertz = payload.u16Le(8),
            rssiDbm = payload.i16Le(10),
            snrTenthsDb = payload.i16Le(12),
            packetLossPermille = payload.u16Le(14),
            oneWayLatencyMillis = payload.u16Le(16),
            transmittedPackets = payload.u32Le(18),
            receivedPackets = payload.u32Le(22),
            faultCode = payload.u16Le(26),
        ).also { status -> require(status.packetLossPermille <= 1_000) }
    }

    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putI16Le(offset: Int, value: Int) = putU16Le(offset, value and 0xFFFF)

    private fun ByteArray.putU32Le(offset: Int, value: Long) {
        repeat(4) { index -> this[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte() }
    }

    private fun ByteArray.u16Le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.i16Le(offset: Int): Int = u16Le(offset).toShort().toInt()

    private fun ByteArray.u32Le(offset: Int): Long =
        (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
}
