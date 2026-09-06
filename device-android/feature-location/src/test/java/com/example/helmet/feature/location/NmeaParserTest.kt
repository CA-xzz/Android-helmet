package com.example.helmet.feature.location

import com.example.helmet.core.model.FixQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaParserTest {
    @Test
    fun parsesGgaPositionAndQuality() {
        val sentence = NmeaParser.parse(
            "$" + "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47",
        ) as NmeaSentence.Gga

        assertEquals("GP", sentence.talker)
        assertEquals(FixQuality.STANDARD, sentence.quality)
        assertEquals(48.1173, sentence.latitude!!, 0.000001)
        assertEquals(11.5166667, sentence.longitude!!, 0.000001)
        assertEquals(8, sentence.satellitesUsed)
        assertEquals(0.9, sentence.hdop!!, 0.0001)
        assertEquals(545.4, sentence.altitudeMeters!!, 0.0001)
    }

    @Test
    fun parsesRmcDateSpeedAndCourse() {
        val sentence = NmeaParser.parse(
            "$" + "GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A",
        ) as NmeaSentence.Rmc

        assertTrue(sentence.active)
        assertEquals(764426119000L, sentence.epochMillis)
        assertEquals(11.5235456, sentence.speedMetersPerSecond!!, 0.0001)
        assertEquals(84.4, sentence.courseDegrees!!, 0.0001)
    }

    @Test
    fun rejectsChecksumAndCoordinatesOnNoFix() {
        assertFalse(NmeaParser.hasValidChecksum("$" + "GPGGA,broken*00"))
        assertNull(NmeaParser.parse("$" + "GPGGA,broken*00"))
        assertNull(NmeaParser.parse("$" + "GPGGA,123519,4807.038,N,01131.000,E,0,00,99.9,,,,,,*4F"))
    }

    @Test
    fun rejectsOutOfRangeCoordinatesAndNegativeQualityValues() {
        assertNull(NmeaParser.parse(sentence("GPGGA,123519,9001.000,N,01131.000,E,1,08,0.9,1.0,M,0.0,M,,")))
        assertNull(NmeaParser.parse(sentence("GPGGA,123519,4807.000,N,01131.000,E,1,08,-0.1,1.0,M,0.0,M,,")))
        assertNull(NmeaParser.parse(sentence("GPRMC,123519,A,4807.000,N,01131.000,E,-1.0,084.4,230394,,,A")))
        assertNull(NmeaParser.parse(sentence("GPRMC,123519,A,4807.000,N,01131.000,E,1.0,361.0,230394,,,A")))
        assertNull(NmeaParser.parse(sentence("GPGSA,A,3,01,,,,,,,,,,,,1.2,-0.1,0.9")))
        assertNull(NmeaParser.parse(sentence("GPGSV,2,3,12,01,40,083,42")))
    }

    private fun sentence(body: String): String {
        val checksum = body.fold(0) { value, character -> value xor character.code }
        return "$" + body + "*" + checksum.toString(16).uppercase().padStart(2, '0')
    }
}
