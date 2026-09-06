package com.example.helmet.safety.detection

import java.io.Reader

enum class ReplayLabel {
    NONE,
    FALL,
    IMPACT,
    VIOLENT_SHAKE,
    NEAR_ELECTRIC,
    HEIGHT_LIMIT,
    SENSOR_FAULT,
    INACTIVITY,
}

data class ReplayRow(
    val sample: SafetySensorSample,
    val expectedLabel: ReplayLabel,
)

data class ReplayDataset(
    val datasetId: String,
    val sampleRateHertz: Int,
    val source: String,
    val finalHardwareEvidence: Boolean,
    val rows: List<ReplayRow>,
) {
    init {
        require(datasetId.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        require(sampleRateHertz in 1..1_000)
        require(source.isNotBlank())
        require(rows.isNotEmpty())
        require(rows.zipWithNext().all { (first, second) ->
            second.sample.sampleReference > first.sample.sampleReference &&
                second.sample.monotonicMillis > first.sample.monotonicMillis
        }) { "dataset rows must be strictly ordered" }
    }
}

data class LabelStatistics(
    val truePositive: Int,
    val falsePositive: Int,
    val falseNegative: Int,
    val trueNegative: Int,
) {
    val precision: Double
        get() = ratio(truePositive, truePositive + falsePositive)
    val recall: Double
        get() = ratio(truePositive, truePositive + falseNegative)
    val falsePositiveRate: Double
        get() = ratio(falsePositive, falsePositive + trueNegative)

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator
}

data class ReplayReport(
    val datasetId: String,
    val sampleCount: Int,
    val finalHardwareEvidence: Boolean,
    val statistics: Map<ReplayLabel, LabelStatistics>,
)

object SafetyReplayCsv {
    private val columns = listOf(
        "sample_ref",
        "monotonic_ms",
        "accel_x_mg",
        "accel_y_mg",
        "accel_z_mg",
        "gyro_x_mdps",
        "gyro_y_mdps",
        "gyro_z_mdps",
        "electric_mv",
        "pressure_pa",
        "temperature_centi_c",
        "altitude_mm",
        "label",
    )

    fun parse(reader: Reader): ReplayDataset {
        val metadata = linkedMapOf<String, String>()
        val dataLines = mutableListOf<String>()
        reader.useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#") -> {
                        val parts = line.removePrefix("#").trim().split('=', limit = 2)
                        require(parts.size == 2) { "invalid metadata line: $line" }
                        metadata[parts[0].trim()] = parts[1].trim()
                    }
                    else -> dataLines += line
                }
            }
        }
        require(dataLines.isNotEmpty()) { "missing CSV header" }
        require(dataLines.first().split(',') == columns) { "unexpected CSV columns" }
        val rows = dataLines.drop(1).mapIndexed { index, line ->
            val values = line.split(',')
            require(values.size == columns.size) { "row ${index + 2} has ${values.size} columns" }
            fun optionalInt(column: Int): Int? = values[column].takeIf(String::isNotEmpty)?.toInt()
            ReplayRow(
                sample = SafetySensorSample(
                    sampleReference = values[0].toLong(),
                    monotonicMillis = values[1].toLong(),
                    accelerationXMilliG = optionalInt(2),
                    accelerationYMilliG = optionalInt(3),
                    accelerationZMilliG = optionalInt(4),
                    gyroXMilliDegreesPerSecond = optionalInt(5),
                    gyroYMilliDegreesPerSecond = optionalInt(6),
                    gyroZMilliDegreesPerSecond = optionalInt(7),
                    electricFieldMilliVolts = optionalInt(8),
                    pressurePascals = optionalInt(9),
                    temperatureCentiCelsius = optionalInt(10),
                    altitudeMillimetres = optionalInt(11),
                ),
                expectedLabel = ReplayLabel.valueOf(values[12]),
            )
        }
        return ReplayDataset(
            datasetId = metadata.requireValue("dataset_id"),
            sampleRateHertz = metadata.requireValue("sample_rate_hz").toInt(),
            source = metadata.requireValue("source"),
            finalHardwareEvidence = metadata.requireValue("final_hardware_evidence").toBooleanStrict(),
            rows = rows,
        )
    }

    private fun Map<String, String>.requireValue(key: String): String =
        requireNotNull(this[key]) { "missing metadata: $key" }
}

class SafetyReplayEvaluator(
    private val engineFactory: () -> SafetyDetectionEngine = { SafetyDetectionEngine() },
) {
    fun evaluate(dataset: ReplayDataset): ReplayReport {
        val engine = engineFactory()
        val pairs = dataset.rows.map { row ->
            val predicted = engine.process(row.sample).decisions
                .filter(SafetyDecision::active)
                .mapNotNull { it.type.toReplayLabel() }
                .toSet()
            row.expectedLabel to predicted
        }
        val labels = ReplayLabel.entries.filter { it != ReplayLabel.NONE }
        return ReplayReport(
            datasetId = dataset.datasetId,
            sampleCount = dataset.rows.size,
            finalHardwareEvidence = dataset.finalHardwareEvidence,
            statistics = labels.associateWith { label ->
                var truePositive = 0
                var falsePositive = 0
                var falseNegative = 0
                var trueNegative = 0
                pairs.forEach { (expected, predicted) ->
                    val actualPositive = expected == label
                    val predictedPositive = label in predicted
                    when {
                        actualPositive && predictedPositive -> truePositive += 1
                        !actualPositive && predictedPositive -> falsePositive += 1
                        actualPositive -> falseNegative += 1
                        else -> trueNegative += 1
                    }
                }
                LabelStatistics(truePositive, falsePositive, falseNegative, trueNegative)
            },
        )
    }
}

private fun SafetyAlarmType.toReplayLabel(): ReplayLabel = when (this) {
    SafetyAlarmType.FALL -> ReplayLabel.FALL
    SafetyAlarmType.IMPACT -> ReplayLabel.IMPACT
    SafetyAlarmType.VIOLENT_SHAKE -> ReplayLabel.VIOLENT_SHAKE
    SafetyAlarmType.NEAR_ELECTRIC -> ReplayLabel.NEAR_ELECTRIC
    SafetyAlarmType.HEIGHT_LIMIT -> ReplayLabel.HEIGHT_LIMIT
    SafetyAlarmType.SENSOR_FAULT -> ReplayLabel.SENSOR_FAULT
    SafetyAlarmType.INACTIVITY -> ReplayLabel.INACTIVITY
}
