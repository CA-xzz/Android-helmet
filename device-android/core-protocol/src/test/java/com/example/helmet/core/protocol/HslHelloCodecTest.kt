package com.example.helmet.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HslHelloCodecTest {
    @Test
    fun roundTripsFixedCompatibilityHandshake() {
        val hello = HslModuleHello(
            contractMajor = 1,
            contractMinor = 2,
            contractPatch = 3,
            capabilityMask = HslCapability.PHYSICAL_KEYS or HslCapability.MMA8452_ACCELEROMETER or
                HslCapability.RTK_NMEA,
            firmwareMajor = 4,
            firmwareMinor = 5,
            firmwarePatch = 6,
            hardwareRevision = 7,
            bootSessionId = 0xFEDC_BA98L,
        )

        val encoded = HslHelloCodec.encode(hello)

        assertEquals(HslHelloCodec.ENCODED_SIZE, encoded.size)
        assertEquals(hello, HslHelloCodec.decode(encoded))
    }

    @Test
    fun rejectsUnknownCapabilitiesAndZeroSession() {
        assertThrows(IllegalArgumentException::class.java) {
            HslHelloCodec.encode(validHello().copy(capabilityMask = 1L shl 40))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HslHelloCodec.encode(validHello().copy(bootSessionId = 0))
        }
    }

    private fun validHello() = HslModuleHello(
        contractMajor = 1,
        contractMinor = 0,
        contractPatch = 0,
        capabilityMask = HslCapability.PHYSICAL_KEYS,
        firmwareMajor = 1,
        firmwareMinor = 0,
        firmwarePatch = 0,
        hardwareRevision = 1,
        bootSessionId = 1,
    )
}
