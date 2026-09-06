package com.example.helmet.core.protocol

import com.example.helmet.core.model.SafetyThresholdConfig
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HslSafetyConfigCodecTest {
    @Test
    fun defaultProfileRoundTripsWithStableSha256() {
        val config = SafetyThresholdConfig()

        val encoded = HslSafetyConfigCodec.encode(config)

        assertEquals(HslSafetyConfigCodec.ENCODED_SIZE, encoded.size)
        assertEquals(config, HslSafetyConfigCodec.decode(encoded))
        assertEquals(
            "5e3ba79e20cff41a13c9d0b18eb848ff18f6157a4b795522b8781631fe3bd586",
            encoded.copyOfRange(74, 106).toHex(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun modifiedPayloadFailsClosed() {
        val encoded = HslSafetyConfigCodec.encode(SafetyThresholdConfig())
        encoded[10] = (encoded[10].toInt() xor 1).toByte()
        HslSafetyConfigCodec.decode(encoded)
    }

    @Test
    fun digestCoversHeaderAndEveryThresholdByte() {
        val encoded = HslSafetyConfigCodec.encode(SafetyThresholdConfig(version = 9))
        val expected = MessageDigest.getInstance("SHA-256").digest(encoded.copyOfRange(0, 74))
        assertArrayEquals(expected, encoded.copyOfRange(74, 106))
    }

    @Test
    fun customInactivityThresholdsRoundTrip() {
        val config = SafetyThresholdConfig(
            inactivityAccelerationToleranceMilliG = 125,
            inactivityGyroToleranceMilliDegreesPerSecond = 7_500,
            inactivityMinimumMillis = 480_000,
        )

        assertEquals(config, HslSafetyConfigCodec.decode(HslSafetyConfigCodec.encode(config)))
    }

    @Test
    fun legacyV1PayloadDefaultsNewInactivityThresholds() {
        val legacy = Base64.getDecoder().decode(
            "AQEAOwDCAXgAmAi4C+gD3AVABiC/AgAE6AMKACgAlgCQASADMgADAACIEwoAZADQBwAA" +
                "+gAAAAPgXvj/gJaYANtXBapszz62+3WsG6wqL9oN05E0hKW+ulcMveY2Qzyc",
        )

        assertEquals(SafetyThresholdConfig(), HslSafetyConfigCodec.decode(legacy))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
