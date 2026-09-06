package com.example.helmet.service.runtime

import com.example.helmet.core.model.RtkRuntimeConfig
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.protocol.Rtcm3FrameCodec
import com.example.helmet.hardware.api.HardwareStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RtkCorrectionControllerTest {
    @Test
    fun packetizerPreservesValidatedFrameAcrossBoundedHslChunks() {
        val frame = Rtcm3FrameCodec.encode(ByteArray(1_023) { index -> index.toByte() })
        val chunks = RtkCorrectionPacketizer.chunks(frame)

        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.size <= RtkCorrectionPacketizer.MAX_HSL_CHUNK_BYTES })
        assertArrayEquals(frame, chunks.fold(ByteArray(0)) { all, chunk -> all + chunk })
    }

    @Test
    fun packetizerRejectsCorruptCorrectionData() {
        val frame = Rtcm3FrameCodec.encode(byteArrayOf(1, 2, 3)).also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertThrows(IllegalArgumentException::class.java) {
            RtkCorrectionPacketizer.chunks(frame)
        }
    }

    @Test
    fun simulatedHardwareCannotProduceExternalReceiverFixes() = runBlocking {
        val controller = RtkCorrectionController(
            scope = this,
            deviceId = "rtk-simulator-rejection",
            config = RtkRuntimeConfig(enabled = true),
            hardwareStatus = { HardwareStatus(connected = true, simulated = true) },
            correctionSink = { error("must not send") },
        )

        controller.acceptReceiverBytes((sentence(
            "GNGGA,123519,3112.0000,N,12124.0000,E,4,18,0.7,12.3,M,8.1,M,0.8,0042",
        ) + "\r\n").toByteArray())

        assertEquals(0L, controller.status.value.receiverSentences)
        assertEquals(RtkCorrectionState.WAITING_HARDWARE, controller.status.value.state)
        assertTrue(controller.status.value.lastError!!.contains("simulated hardware"))
    }

    @Test
    fun burstFixQueueRejectsWithAnObservableCounterInsteadOfSilentLoss() = runBlocking {
        val queue = RtkFixQueue(capacity = 1)
        val first = LocationFix(
            fixId = "first",
            deviceId = "device-1",
            occurredAtEpochMillis = 1,
            elapsedRealtimeNanos = 1,
            source = LocationSource.EXTERNAL_NMEA,
            quality = FixQuality.RTK_FIXED,
            latitude = 31.0,
            longitude = 121.0,
        )

        assertTrue(queue.offer(first).accepted)
        val rejected = queue.offer(first.copy(fixId = "second"))
        assertEquals(false, rejected.accepted)
        assertEquals(1L, rejected.overflowCount)
        assertEquals(first, queue.fixes.first())
        assertTrue(queue.offer(first.copy(fixId = "third")).accepted)
        queue.close()
        Unit
    }

    @Test
    fun receiverNoFixUpdatesObservableQualityAndClearsCorrectionGga() = runBlocking {
        val controller = RtkCorrectionController(
            scope = this,
            deviceId = "rtk-no-fix",
            config = RtkRuntimeConfig(enabled = false),
            hardwareStatus = { HardwareStatus(connected = true, simulated = false) },
            correctionSink = { error("must not send") },
        )
        try {
            controller.acceptReceiverBytes((sentence(
                "GNGGA,123519,3112.0000,N,12124.0000,E,4,18,0.7,12.3,M,8.1,M,0.8,0042",
            ) + "\r\n").toByteArray())
            assertEquals(FixQuality.RTK_FIXED, controller.status.value.lastFixQuality)

            controller.acceptReceiverBytes((sentence("GNGGA,123520,,,,,0,00,99.9,,,,,,") + "\r\n").toByteArray())
            assertEquals(FixQuality.NO_FIX, controller.status.value.lastFixQuality)
        } finally {
            controller.close()
        }
    }

    private fun sentence(body: String): String {
        val checksum = body.fold(0) { value, character -> value xor character.code }
        return "$" + body + "*" + checksum.toString(16).uppercase().padStart(2, '0')
    }
}
