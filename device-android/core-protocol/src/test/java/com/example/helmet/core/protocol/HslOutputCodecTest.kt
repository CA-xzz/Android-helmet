package com.example.helmet.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class HslOutputCodecTest {
    @Test
    fun activeAndClearCommandsRoundTripWithStableRequestId() {
        val active = HslOutputCommand(
            requestId = 0xFE12_3456L,
            active = true,
            actionMask = HslOutputCommand.ACTION_LED or HslOutputCommand.ACTION_VIBRATION,
        )
        val clear = active.copy(active = false)

        assertEquals(active, HslOutputPayloadCodec.decode(HslOutputPayloadCodec.encode(active)))
        assertEquals(clear, HslOutputPayloadCodec.decode(HslOutputPayloadCodec.encode(clear)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownActionBits() {
        HslOutputPayloadCodec.decode(
            HslOutputPayloadCodec.encode(
                HslOutputCommand(1, active = true, actionMask = HslOutputCommand.ACTION_LED),
            ).also { it[2] = 0x08 },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonZeroReservedByte() {
        HslOutputPayloadCodec.decode(
            HslOutputPayloadCodec.encode(
                HslOutputCommand(1, active = true, actionMask = HslOutputCommand.ACTION_LED),
            ).also { it[3] = 1 },
        )
    }
}
