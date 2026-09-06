package com.example.helmet.feature.location

import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalRtkFixAssemblerTest {
    @Test
    fun assemblesFixedRtkFixFromSplitMultiConstellationNmea() {
        var monotonic = 1_000L
        val assembler = ExternalRtkFixAssembler(
            deviceId = "helmet-rtk-1",
            epochClock = { 1_700_000_000_000L },
            monotonicClockMillis = { monotonic++ },
            idFactory = { "fix-rtk-1" },
        )
        val input = listOf(
            sentence("GNRMC,123519.00,A,3112.0000,N,12124.0000,E,1.20,84.4,100826,,,A"),
            sentence("GNGSA,A,3,01,02,03,04,05,06,,,,,,,1.2,0.7,0.9"),
            sentence("GNGSV,1,1,18,01,40,083,42"),
            sentence("GNGGA,123519.00,3112.0000,N,12124.0000,E,4,18,0.7,12.3,M,8.1,M,0.8,0042"),
        ).joinToString("\r\n", postfix = "\r\n").toByteArray()

        val first = assembler.feed(input.copyOfRange(0, 37))
        val updates = first + assembler.feed(input.copyOfRange(37, input.size))
        val fix = updates.mapNotNull(ExternalRtkUpdate::fix).single()

        assertEquals(LocationSource.EXTERNAL_NMEA, fix.source)
        assertEquals(FixQuality.RTK_FIXED, fix.quality)
        assertEquals(31.2, fix.latitude!!, 0.000001)
        assertEquals(121.4, fix.longitude!!, 0.000001)
        assertEquals(18, fix.gnss.satellitesUsed)
        assertEquals(18, fix.gnss.satellitesVisible)
        assertEquals(0.8, fix.gnss.correctionAgeSeconds!!, 0.0001)
        assertEquals("0042", fix.gnss.correctionStationId)
        assertNull(fix.horizontalAccuracyMeters)
        assertEquals(ExternalRtkFixAssembler.EXTERNAL_RTK_PROVIDER, fix.provider)
        assertEquals(updates.last().rawSentence, assembler.latestValidGga)
    }

    @Test
    fun rejectsBadChecksumBinaryAndNoFixWithoutInventingPosition() {
        val assembler = ExternalRtkFixAssembler("helmet-rtk-2")
        val bad = "$" + "GNGGA,123519,3112.0,N,12124.0,E,4,18,0.7,12.3,M,8.1,M,0.8,0042*00\r\n"
        val noFix = sentence("GNGGA,123520,,,,,0,00,99.9,,,,,,") + "\r\n"

        assertTrue(assembler.feed(byteArrayOf(0x01, 0x02) + bad.toByteArray()).isEmpty())
        val updates = assembler.feed(noFix.toByteArray())

        assertEquals(1, updates.size)
        assertNull(updates.single().fix)
        assertNull(assembler.latestValidGga)
        assertFalse(assembler.stats.rejectedLines == 0L)
    }

    @Test
    fun noFixAndSilenceExpireTheGgaUsedForCorrections() {
        var monotonic = 1_000L
        val assembler = ExternalRtkFixAssembler(
            deviceId = "helmet-rtk-expiry",
            monotonicClockMillis = { monotonic },
        )
        val valid = sentence("GNGGA,123519,3112.0000,N,12124.0000,E,4,18,0.7,12.3,M,8.1,M,0.8,0042")
        val noFix = sentence("GNGGA,123520,,,,,0,00,99.9,,,,,,")

        assembler.feed("$valid\r\n".toByteArray())
        assertEquals(valid, assembler.latestValidGga)
        monotonic += ExternalRtkFixAssembler.VALID_GGA_FRESHNESS_MILLIS + 1
        assertNull(assembler.latestValidGga)

        assembler.feed("$valid\r\n".toByteArray())
        assertEquals(valid, assembler.latestValidGga)
        assembler.feed("$noFix\r\n".toByteArray())
        assertNull(assembler.latestValidGga)
    }

    @Test
    fun overlongLineIsDiscardedUntilItsTerminator() {
        val assembler = ExternalRtkFixAssembler("helmet-rtk-overlong")
        val valid = sentence("GNGGA,123519,3112.0000,N,12124.0000,E,4,18,0.7,12.3,M,8.1,M,0.8,0042")

        assertTrue(assembler.feed(("X".repeat(129) + valid + "\r\n").toByteArray()).isEmpty())
        assertNull(assembler.latestValidGga)
        assertEquals(1L, assembler.stats.overlongLines)
        assertEquals(1, assembler.feed("$valid\r\n".toByteArray()).size)
    }

    private fun sentence(body: String): String {
        val checksum = body.fold(0) { value, character -> value xor character.code }
        return "$" + body + "*" + checksum.toString(16).uppercase().padStart(2, '0')
    }
}
