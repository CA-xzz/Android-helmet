package com.example.helmet.core.protocol

import com.example.helmet.core.model.SafetyThresholdConfig
import java.security.MessageDigest

object HslSafetyConfigCodec {
    const val SCHEMA_VERSION = 2
    const val ENCODED_SIZE = 106
    private const val HEADER_SIZE = 5
    private const val BODY_SIZE = 69
    private const val LEGACY_SCHEMA_VERSION = 1
    private const val LEGACY_BODY_SIZE = 59
    private const val DIGEST_SIZE = 32

    fun encode(config: SafetyThresholdConfig): ByteArray {
        val output = ByteArray(ENCODED_SIZE)
        output[0] = SCHEMA_VERSION.toByte()
        output.putU16Le(1, config.version)
        output.putU16Le(3, BODY_SIZE)
        var offset = HEADER_SIZE
        fun u16(value: Int) {
            require(value in 0..0xFFFF)
            output.putU16Le(offset, value)
            offset += 2
        }
        fun u16(value: Long) = u16(value.toInt().also { require(value == it.toLong()) })
        fun u32(value: Long) {
            require(value in 0..0xFFFF_FFFFL)
            output.putU32Le(offset, value)
            offset += 4
        }
        fun i32(value: Int) {
            output.putU32Le(offset, value.toLong() and 0xFFFF_FFFFL)
            offset += 4
        }
        fun u8(value: Int) {
            require(value in 0..0xFF)
            output[offset++] = value.toByte()
        }
        u16(config.freeFallThresholdMilliG)
        u16(config.freeFallMinimumMillis)
        u16(config.fallImpactThresholdMilliG)
        u16(config.impactThresholdMilliG)
        u16(config.fallImpactWindowMillis)
        u16(config.motionCooldownMillis)
        u16(config.shakeAccelerationThresholdMilliG)
        u32(config.shakeGyroThresholdMilliDegreesPerSecond.toLong())
        u8(config.shakeDirectionChanges)
        u16(config.shakeWindowMillis)
        u16(config.inactivityAccelerationToleranceMilliG)
        u32(config.inactivityGyroToleranceMilliDegreesPerSecond.toLong())
        u32(config.inactivityMinimumMillis)
        u16(config.electricCalibrationSamples)
        u16(config.electricCalibrationStabilityMilliVolts)
        u16(config.electricPresentThresholdMilliVolts)
        u16(config.electricHighThresholdMilliVolts)
        u16(config.electricCriticalThresholdMilliVolts)
        u16(config.electricHysteresisMilliVolts)
        u8(config.electricConfirmationSamples)
        u16(config.electricMinimumMilliVolts)
        u16(config.electricMaximumMilliVolts)
        u16(config.heightCalibrationSamples)
        u16(config.heightCalibrationStabilityMillimetres)
        u32(config.heightThresholdMillimetres.toLong())
        u32(config.heightHysteresisMillimetres.toLong())
        u8(config.heightConfirmationSamples)
        i32(config.heightMinimumMillimetres)
        i32(config.heightMaximumMillimetres)
        check(offset == HEADER_SIZE + BODY_SIZE)
        MessageDigest.getInstance("SHA-256")
            .digest(output.copyOfRange(0, offset))
            .copyInto(output, offset)
        return output
    }

    fun decode(payload: ByteArray): SafetyThresholdConfig {
        val schemaVersion = payload.firstOrNull()?.toInt()?.and(0xFF)
        val bodySize = when (schemaVersion) {
            LEGACY_SCHEMA_VERSION -> LEGACY_BODY_SIZE
            SCHEMA_VERSION -> BODY_SIZE
            else -> throw IllegalArgumentException("unsupported safety config schema")
        }
        val encodedSize = HEADER_SIZE + bodySize + DIGEST_SIZE
        require(payload.size == encodedSize) { "safety config payload length must be $encodedSize" }
        require(payload.u16Le(3) == bodySize) { "invalid safety config body length" }
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(payload.copyOfRange(0, HEADER_SIZE + bodySize))
        require(
            MessageDigest.isEqual(expected, payload.copyOfRange(HEADER_SIZE + bodySize, encodedSize)),
        ) { "safety config SHA-256 mismatch" }
        var offset = HEADER_SIZE
        fun u8(): Int = payload[offset++].toInt() and 0xFF
        fun u16(): Int = payload.u16Le(offset).also { offset += 2 }
        fun u32(): Long = payload.u32Le(offset).also { offset += 4 }
        fun i32(): Int = u32().toInt()
        val config = SafetyThresholdConfig(
            version = payload.u16Le(1),
            freeFallThresholdMilliG = u16(),
            freeFallMinimumMillis = u16().toLong(),
            fallImpactThresholdMilliG = u16(),
            impactThresholdMilliG = u16(),
            fallImpactWindowMillis = u16().toLong(),
            motionCooldownMillis = u16().toLong(),
            shakeAccelerationThresholdMilliG = u16(),
            shakeGyroThresholdMilliDegreesPerSecond = u32().toInt(),
            shakeDirectionChanges = u8(),
            shakeWindowMillis = u16().toLong(),
            inactivityAccelerationToleranceMilliG = if (schemaVersion >= 2) u16() else
                SafetyThresholdConfig().inactivityAccelerationToleranceMilliG,
            inactivityGyroToleranceMilliDegreesPerSecond = if (schemaVersion >= 2) u32().toInt() else
                SafetyThresholdConfig().inactivityGyroToleranceMilliDegreesPerSecond,
            inactivityMinimumMillis = if (schemaVersion >= 2) u32() else
                SafetyThresholdConfig().inactivityMinimumMillis,
            electricCalibrationSamples = u16(),
            electricCalibrationStabilityMilliVolts = u16(),
            electricPresentThresholdMilliVolts = u16(),
            electricHighThresholdMilliVolts = u16(),
            electricCriticalThresholdMilliVolts = u16(),
            electricHysteresisMilliVolts = u16(),
            electricConfirmationSamples = u8(),
            electricMinimumMilliVolts = u16(),
            electricMaximumMilliVolts = u16(),
            heightCalibrationSamples = u16(),
            heightCalibrationStabilityMillimetres = u16(),
            heightThresholdMillimetres = u32().toInt(),
            heightHysteresisMillimetres = u32().toInt(),
            heightConfirmationSamples = u8(),
            heightMinimumMillimetres = i32(),
            heightMaximumMillimetres = i32(),
        )
        check(offset == HEADER_SIZE + bodySize)
        return config
    }

    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putU32Le(offset: Int, value: Long) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    private fun ByteArray.u16Le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32Le(offset: Int): Long =
        (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
}
