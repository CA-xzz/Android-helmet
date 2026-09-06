package com.example.helmet.feature.location

import com.example.helmet.core.model.FixQuality
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

sealed interface NmeaSentence {
    val talker: String

    data class Gga(
        override val talker: String,
        val utcSecondsOfDay: Double?,
        val latitude: Double?,
        val longitude: Double?,
        val quality: FixQuality,
        val satellitesUsed: Int?,
        val hdop: Double?,
        val altitudeMeters: Double?,
        val geoidSeparationMeters: Double?,
        val correctionAgeSeconds: Double?,
        val correctionStationId: String?,
    ) : NmeaSentence

    data class Rmc(
        override val talker: String,
        val epochMillis: Long?,
        val active: Boolean,
        val latitude: Double?,
        val longitude: Double?,
        val speedMetersPerSecond: Double?,
        val courseDegrees: Double?,
    ) : NmeaSentence

    data class Gsa(
        override val talker: String,
        val automatic: Boolean,
        val dimension: Int,
        val satelliteIds: List<String>,
        val pdop: Double?,
        val hdop: Double?,
        val vdop: Double?,
    ) : NmeaSentence

    data class Gsv(
        override val talker: String,
        val sentenceCount: Int,
        val sentenceNumber: Int,
        val satellitesVisible: Int,
    ) : NmeaSentence
}

object NmeaParser {
    fun parse(raw: String): NmeaSentence? {
        val line = raw.trim()
        if (!hasValidChecksum(line)) return null
        val star = line.lastIndexOf('*')
        val fields = line.substring(1, star).split(',')
        val address = fields.firstOrNull() ?: return null
        if (address.length < 5) return null
        val talker = address.dropLast(3)
        return when (address.takeLast(3)) {
            "GGA" -> parseGga(talker, fields)
            "RMC" -> parseRmc(talker, fields)
            "GSA" -> parseGsa(talker, fields)
            "GSV" -> parseGsv(talker, fields)
            else -> null
        }
    }

    fun hasValidChecksum(raw: String): Boolean {
        val star = raw.lastIndexOf('*')
        if (!raw.startsWith('$') || star <= 1 || star + 3 != raw.length) return false
        val expected = raw.substring(star + 1).toIntOrNull(16) ?: return false
        val actual = raw.substring(1, star).fold(0) { checksum, character ->
            checksum xor character.code
        }
        return actual == expected
    }

    private fun parseGga(talker: String, fields: List<String>): NmeaSentence.Gga? {
        if (fields.size < 15) return null
        val qualityCode = fields[6].toIntOrNull() ?: 0
        if (qualityCode < 0) return null
        val quality = when (qualityCode) {
            0 -> FixQuality.NO_FIX
            1 -> FixQuality.STANDARD
            2 -> FixQuality.DIFFERENTIAL
            4 -> FixQuality.RTK_FIXED
            5 -> FixQuality.RTK_FLOAT
            6 -> FixQuality.DEAD_RECKONING
            else -> FixQuality.UNVALIDATED
        }
        val latitude = parseCoordinate(fields[2], fields[3], latitude = true)
        val longitude = parseCoordinate(fields[4], fields[5], latitude = false)
        if (quality == FixQuality.NO_FIX && (latitude != null || longitude != null)) return null
        if (quality != FixQuality.NO_FIX && (latitude == null || longitude == null)) return null
        val satellitesUsed = optionalNonNegativeInt(fields[7]) ?: if (fields[7].isBlank()) null else return null
        val hdop = optionalNonNegativeDouble(fields[8]) ?: if (fields[8].isBlank()) null else return null
        val altitudeMeters = fields[9].toDoubleOrNull()
        if (fields[9].isNotBlank() && (altitudeMeters == null || !altitudeMeters.isFinite())) return null
        val geoidSeparationMeters = fields[11].toDoubleOrNull()
        if (fields[11].isNotBlank() && (geoidSeparationMeters == null || !geoidSeparationMeters.isFinite())) {
            return null
        }
        val correctionAgeSeconds = optionalNonNegativeDouble(fields[13])
            ?: if (fields[13].isBlank()) null else return null
        return NmeaSentence.Gga(
            talker = talker,
            utcSecondsOfDay = parseTimeOfDay(fields[1]),
            latitude = latitude,
            longitude = longitude,
            quality = quality,
            satellitesUsed = satellitesUsed,
            hdop = hdop,
            altitudeMeters = altitudeMeters,
            geoidSeparationMeters = geoidSeparationMeters,
            correctionAgeSeconds = correctionAgeSeconds,
            correctionStationId = fields[14].ifBlank { null },
        )
    }

    private fun parseRmc(talker: String, fields: List<String>): NmeaSentence.Rmc? {
        if (fields.size < 10) return null
        val active = fields[2] == "A"
        val latitude = parseCoordinate(fields[3], fields[4], latitude = true)
        val longitude = parseCoordinate(fields[5], fields[6], latitude = false)
        if (active && (latitude == null || longitude == null)) return null
        val speedKnots = optionalNonNegativeDouble(fields[7])
            ?: if (fields[7].isBlank()) null else return null
        val courseDegrees = fields[8].toDoubleOrNull()
        if (fields[8].isNotBlank() &&
            (courseDegrees == null || !courseDegrees.isFinite() || courseDegrees !in 0.0..360.0)
        ) {
            return null
        }
        return NmeaSentence.Rmc(
            talker = talker,
            epochMillis = parseEpochMillis(fields[9], fields[1]),
            active = active,
            latitude = if (active) latitude else null,
            longitude = if (active) longitude else null,
            speedMetersPerSecond = speedKnots?.times(KNOTS_TO_METERS_PER_SECOND),
            courseDegrees = courseDegrees,
        )
    }

    private fun parseGsa(talker: String, fields: List<String>): NmeaSentence.Gsa? {
        if (fields.size < 18) return null
        val dimension = fields[2].toIntOrNull()?.takeIf { it in 1..3 } ?: return null
        val pdop = optionalNonNegativeDouble(fields[15]) ?: if (fields[15].isBlank()) null else return null
        val hdop = optionalNonNegativeDouble(fields[16]) ?: if (fields[16].isBlank()) null else return null
        val vdop = optionalNonNegativeDouble(fields[17]) ?: if (fields[17].isBlank()) null else return null
        return NmeaSentence.Gsa(
            talker = talker,
            automatic = fields[1] == "A",
            dimension = dimension,
            satelliteIds = fields.subList(3, 15).filter(String::isNotBlank),
            pdop = pdop,
            hdop = hdop,
            vdop = vdop,
        )
    }

    private fun parseGsv(talker: String, fields: List<String>): NmeaSentence.Gsv? {
        if (fields.size < 4) return null
        val sentenceCount = fields[1].toIntOrNull()?.takeIf { it in 1..64 } ?: return null
        val sentenceNumber = fields[2].toIntOrNull()?.takeIf { it in 1..sentenceCount } ?: return null
        val satellitesVisible = optionalNonNegativeInt(fields[3]) ?: return null
        return NmeaSentence.Gsv(
            talker = talker,
            sentenceCount = sentenceCount,
            sentenceNumber = sentenceNumber,
            satellitesVisible = satellitesVisible,
        )
    }

    private fun parseCoordinate(value: String, hemisphere: String, latitude: Boolean): Double? {
        val raw = value.toDoubleOrNull() ?: return null
        val degrees = (raw / 100).toInt()
        val minutes = raw - degrees * 100
        val maximumDegrees = if (latitude) 90 else 180
        if (degrees !in 0..maximumDegrees || minutes !in 0.0..<60.0) return null
        if (degrees == maximumDegrees && minutes != 0.0) return null
        val sign = when (hemisphere) {
            "N", "E" -> 1
            "S", "W" -> -1
            else -> return null
        }
        return sign * (degrees + minutes / 60.0)
    }

    private fun parseTimeOfDay(value: String): Double? {
        if (value.length < 6) return null
        val hour = value.substring(0, 2).toIntOrNull() ?: return null
        val minute = value.substring(2, 4).toIntOrNull() ?: return null
        val second = value.substring(4).toDoubleOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0.0..<60.0) return null
        return hour * 3600 + minute * 60 + second
    }

    private fun parseEpochMillis(dateValue: String, timeValue: String): Long? {
        if (dateValue.length != 6 || timeValue.length < 6) return null
        return runCatching {
            val day = dateValue.substring(0, 2).toInt()
            val month = dateValue.substring(2, 4).toInt()
            val yearTwoDigits = dateValue.substring(4, 6).toInt()
            val year = if (yearTwoDigits >= 80) 1900 + yearTwoDigits else 2000 + yearTwoDigits
            val seconds = parseTimeOfDay(timeValue) ?: return null
            val wholeSeconds = seconds.toInt()
            val nanos = ((seconds - wholeSeconds) * 1_000_000_000).toInt()
            val time = LocalTime.ofSecondOfDay(wholeSeconds.toLong()).withNano(nanos)
            LocalDate.of(year, month, day).atTime(time).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private fun optionalNonNegativeDouble(value: String): Double? =
        value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

    private fun optionalNonNegativeInt(value: String): Int? =
        value.toIntOrNull()?.takeIf { it >= 0 }

    private const val KNOTS_TO_METERS_PER_SECOND = 0.514444
}
