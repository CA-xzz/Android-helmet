package com.example.helmet.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalIntercomCodecTest {
    @Test
    fun commandRoundTripsWithGroupAndProvisionedKeySlot() {
        val command = HslLocalIntercomCommand(
            action = LocalIntercomAction.START_TRANSMIT,
            requestId = 0xFEDCBA98L,
            groupId = 42,
            channel = 7,
            keySlot = 3,
            codec = LocalIntercomCodec.CODEC2_2400,
        )
        assertEquals(command, LocalIntercomPayloadCodec.decodeCommand(LocalIntercomPayloadCodec.encodeCommand(command)))
    }

    @Test
    fun statusRoundTripsQualityMetrics() {
        val status = HslLocalIntercomStatus(
            state = LocalIntercomModuleState.TRANSMITTING,
            requestId = 99,
            peerCount = 4,
            codec = LocalIntercomCodec.VENDOR_NARROWBAND,
            sampleRateHertz = 8_000,
            rssiDbm = -102,
            snrTenthsDb = 35,
            packetLossPermille = 17,
            oneWayLatencyMillis = 165,
            transmittedPackets = 100_000,
            receivedPackets = 88_000,
            faultCode = 0,
        )
        assertEquals(status, LocalIntercomPayloadCodec.decodeStatus(LocalIntercomPayloadCodec.encodeStatus(status)))
    }

    @Test
    fun rejectsInvalidLossAndUnknownCodec() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalIntercomPayloadCodec.encodeStatus(
                HslLocalIntercomStatus(
                    LocalIntercomModuleState.READY,
                    1,
                    0,
                    LocalIntercomCodec.MODULE_NEGOTIATED,
                    8_000,
                    -90,
                    10,
                    1_001,
                    100,
                    0,
                    0,
                    0,
                ),
            )
        }
        val command = LocalIntercomPayloadCodec.encodeCommand(
            HslLocalIntercomCommand(LocalIntercomAction.JOIN, 1, 1, 1, 1),
        ).also { it[10] = 77 }
        assertThrows(IllegalArgumentException::class.java) {
            LocalIntercomPayloadCodec.decodeCommand(command)
        }
    }
}
