package com.example.helmet.feature.location

import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.GnssQuality
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource
import java.util.UUID

data class ExternalRtkUpdate(
    val rawSentence: String,
    val sentence: NmeaSentence,
    val fix: LocationFix?,
)

data class ExternalRtkParserStats(
    val acceptedSentences: Long = 0,
    val rejectedLines: Long = 0,
    val overlongLines: Long = 0,
)

class ExternalRtkFixAssembler(
    private val deviceId: String,
    private val epochClock: () -> Long = System::currentTimeMillis,
    private val monotonicClockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class TimedSentence<T : NmeaSentence>(
        val receivedAtMillis: Long,
        val sentence: T,
    )

    private val lineBuffer = StringBuilder()
    private var latestRmc: TimedSentence<NmeaSentence.Rmc>? = null
    private var latestGsa: TimedSentence<NmeaSentence.Gsa>? = null
    private var latestGsv: TimedSentence<NmeaSentence.Gsv>? = null
    private var mutableStats = ExternalRtkParserStats()

    @Volatile
    var latestValidGga: String? = null
        private set

    val stats: ExternalRtkParserStats
        get() = mutableStats

    @Synchronized
    fun feed(bytes: ByteArray): List<ExternalRtkUpdate> = buildList {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            when {
                value == '\r'.code || value == '\n'.code -> finishLine()?.let(::add)
                value in 0x20..0x7E -> {
                    if (lineBuffer.length >= MAX_NMEA_LINE_LENGTH) {
                        lineBuffer.setLength(0)
                        mutableStats = mutableStats.copy(overlongLines = mutableStats.overlongLines + 1)
                    } else {
                        lineBuffer.append(value.toChar())
                    }
                }
                else -> {
                    if (lineBuffer.isNotEmpty()) {
                        lineBuffer.setLength(0)
                        mutableStats = mutableStats.copy(rejectedLines = mutableStats.rejectedLines + 1)
                    }
                }
            }
        }
    }

    @Synchronized
    fun reset() {
        lineBuffer.setLength(0)
        latestRmc = null
        latestGsa = null
        latestGsv = null
        latestValidGga = null
    }

    private fun finishLine(): ExternalRtkUpdate? {
        if (lineBuffer.isEmpty()) return null
        val raw = lineBuffer.toString()
        lineBuffer.setLength(0)
        val sentence = NmeaParser.parse(raw)
        if (sentence == null) {
            mutableStats = mutableStats.copy(rejectedLines = mutableStats.rejectedLines + 1)
            return null
        }
        mutableStats = mutableStats.copy(acceptedSentences = mutableStats.acceptedSentences + 1)
        val now = monotonicClockMillis()
        val fix = when (sentence) {
            is NmeaSentence.Rmc -> {
                latestRmc = TimedSentence(now, sentence)
                null
            }
            is NmeaSentence.Gsa -> {
                latestGsa = TimedSentence(now, sentence)
                null
            }
            is NmeaSentence.Gsv -> {
                latestGsv = TimedSentence(now, sentence)
                null
            }
            is NmeaSentence.Gga -> {
                if (sentence.quality != FixQuality.NO_FIX) latestValidGga = raw
                sentence.toLocationFix(now)
            }
        }
        return ExternalRtkUpdate(raw, sentence, fix)
    }

    private fun NmeaSentence.Gga.toLocationFix(now: Long): LocationFix? {
        if (quality == FixQuality.NO_FIX || latitude == null || longitude == null) return null
        val rmc = latestRmc.fresh(now)?.takeIf { it.active }
        val gsa = latestGsa.fresh(now)
        val gsv = latestGsv.fresh(now)
        return LocationFix(
            fixId = idFactory(),
            deviceId = deviceId,
            occurredAtEpochMillis = rmc?.epochMillis ?: epochClock(),
            elapsedRealtimeNanos = now * 1_000_000L,
            source = LocationSource.EXTERNAL_NMEA,
            quality = quality,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitudeMeters,
            horizontalAccuracyMeters = null,
            speedMetersPerSecond = rmc?.speedMetersPerSecond?.toFloat(),
            bearingDegrees = rmc?.courseDegrees?.toFloat(),
            gnss = GnssQuality(
                satellitesUsed = satellitesUsed ?: gsa?.satelliteIds?.size,
                satellitesVisible = gsv?.satellitesVisible,
                pdop = gsa?.pdop,
                hdop = hdop ?: gsa?.hdop,
                vdop = gsa?.vdop,
                correctionAgeSeconds = correctionAgeSeconds,
                correctionStationId = correctionStationId,
            ),
            provider = EXTERNAL_RTK_PROVIDER,
            isMock = false,
        )
    }

    private fun <T : NmeaSentence> TimedSentence<T>?.fresh(now: Long): T? =
        this?.takeIf { now - it.receivedAtMillis in 0..AUXILIARY_SENTENCE_FRESHNESS_MILLIS }?.sentence

    companion object {
        const val EXTERNAL_RTK_PROVIDER = "external-rtk-uart"
        private const val MAX_NMEA_LINE_LENGTH = 128
        private const val AUXILIARY_SENTENCE_FRESHNESS_MILLIS = 2_500L
    }
}
