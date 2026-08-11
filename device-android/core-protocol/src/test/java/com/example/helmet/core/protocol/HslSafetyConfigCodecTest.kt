package com.example.helmet.core.protocol

import com.example.helmet.core.model.SafetyThresholdConfig
import java.security.MessageDigest
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
            "db5705aa6ccf3eb6fb75ac1bac2a2fda0dd3913484a5beba570cbde636433c9c",
            encoded.copyOfRange(64, 96).toHex(),
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
        val expected = MessageDigest.getInstance("SHA-256").digest(encoded.copyOfRange(0, 64))
        assertArrayEquals(expected, encoded.copyOfRange(64, 96))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
