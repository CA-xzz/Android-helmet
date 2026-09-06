package com.example.helmet.core.protocol

object HslCapability {
    const val PHYSICAL_KEYS: Long = 1L shl 0
    const val LED_OUTPUT: Long = 1L shl 1
    const val VIBRATION_OUTPUT: Long = 1L shl 2
    const val BUZZER_OUTPUT: Long = 1L shl 3
    const val MMA8452_ACCELEROMETER: Long = 1L shl 4
    const val RTK_NMEA: Long = 1L shl 5
    const val RTK_CORRECTION: Long = 1L shl 6
    const val LOCAL_INTERCOM: Long = 1L shl 7

    const val KNOWN_MASK: Long = PHYSICAL_KEYS or LED_OUTPUT or VIBRATION_OUTPUT or BUZZER_OUTPUT or
        MMA8452_ACCELEROMETER or RTK_NMEA or RTK_CORRECTION or LOCAL_INTERCOM
}

data class HslModuleHello(
    val schemaVersion: Int = HslHelloCodec.SCHEMA_VERSION,
    val contractMajor: Int,
    val contractMinor: Int,
    val contractPatch: Int,
    val capabilityMask: Long,
    val firmwareMajor: Int,
    val firmwareMinor: Int,
    val firmwarePatch: Int,
    val hardwareRevision: Int,
    val bootSessionId: Long,
)

/** Fixed 20-byte compatibility handshake sent by the module after every UART session or boot. */
object HslHelloCodec {
    const val SCHEMA_VERSION = 1
    const val ENCODED_SIZE = 20

    fun encode(hello: HslModuleHello): ByteArray {
        validate(hello)
        return ByteArray(ENCODED_SIZE).also { output ->
            output[0] = hello.schemaVersion.toByte()
            output[1] = hello.contractMajor.toByte()
            output[2] = hello.contractMinor.toByte()
            output[3] = hello.contractPatch.toByte()
            output.putU64Le(4, hello.capabilityMask)
            output[12] = hello.firmwareMajor.toByte()
            output[13] = hello.firmwareMinor.toByte()
            output[14] = hello.firmwarePatch.toByte()
            output[15] = hello.hardwareRevision.toByte()
            output.putU32Le(16, hello.bootSessionId)
        }
    }

    fun decode(payload: ByteArray): HslModuleHello {
        require(payload.size == ENCODED_SIZE) { "HELLO payload length must be $ENCODED_SIZE" }
        return HslModuleHello(
            schemaVersion = payload.u8(0),
            contractMajor = payload.u8(1),
            contractMinor = payload.u8(2),
            contractPatch = payload.u8(3),
            capabilityMask = payload.u64Le(4),
            firmwareMajor = payload.u8(12),
            firmwareMinor = payload.u8(13),
            firmwarePatch = payload.u8(14),
            hardwareRevision = payload.u8(15),
            bootSessionId = payload.u32Le(16),
        ).also(::validate)
    }

    private fun validate(hello: HslModuleHello) {
        require(hello.schemaVersion == SCHEMA_VERSION) { "unsupported HELLO schema" }
        require(hello.contractMajor in 1..0xFF) { "HELLO contract major must be non-zero" }
        require(hello.contractMinor in 0..0xFF && hello.contractPatch in 0..0xFF)
        require(hello.capabilityMask and HslCapability.KNOWN_MASK.inv() == 0L) {
            "HELLO contains unknown capabilities"
        }
        require(hello.firmwareMajor in 0..0xFF)
        require(hello.firmwareMinor in 0..0xFF)
        require(hello.firmwarePatch in 0..0xFF)
        require(hello.hardwareRevision in 0..0xFF)
        require(hello.bootSessionId in 1..0xFFFF_FFFFL) { "HELLO boot session ID must be non-zero" }
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.u32Le(offset: Int): Long =
        (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)

    private fun ByteArray.u64Le(offset: Int): Long {
        var value = 0L
        repeat(Long.SIZE_BYTES) { index ->
            value = value or ((this[offset + index].toLong() and 0xFF) shl (index * 8))
        }
        return value
    }

    private fun ByteArray.putU32Le(offset: Int, value: Long) {
        repeat(Int.SIZE_BYTES) { index ->
            this[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
    }

    private fun ByteArray.putU64Le(offset: Int, value: Long) {
        repeat(Long.SIZE_BYTES) { index ->
            this[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
    }
}
