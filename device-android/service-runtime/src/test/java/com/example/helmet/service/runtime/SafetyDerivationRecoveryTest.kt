package com.example.helmet.service.runtime

import com.example.helmet.core.model.SafetySensorTelemetry
import com.example.helmet.core.model.SafetyThresholdConfig
import com.example.helmet.core.model.canonicalFingerprint
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyDerivationRecoveryTest {
    @Test
    fun failedAlarmWithholdsAckAndRecreatedProcessorReplaysSameIdBeforeCheckpoint() = runBlocking {
        val config = SafetyThresholdConfig()
        val sample = impactSample(config)
        val acknowledgements = mutableListOf<Pair<Int, Int>>()
        var firstAlarmId: Long? = null
        var failedCheckpointCommitted = false

        val failed = persistAndAcknowledgeSafetySample(
            acknowledgement = 17,
            persist = {
                val processor = SafetySampleProcessor(config)
                recoverSafetyDerivationsDurably(
                    processor = processor,
                    rebuildProcessor = processor::reset,
                    loadPendingSamples = { listOf(sample) },
                    persistAlarm = { alarm ->
                        firstAlarmId = alarm.event.alarmId
                        throw IOException("injected alarm failure")
                    },
                    commitCheckpoint = {
                        failedCheckpointCommitted = true
                    },
                )
            },
            acknowledge = { sequence, result -> acknowledgements += sequence to result },
        )

        assertFalse(failed.persisted)
        assertTrue(failed.error is IOException)
        assertFalse(failedCheckpointCommitted)
        assertTrue(acknowledgements.isEmpty())

        val order = mutableListOf<String>()
        var replayedAlarmId: Long? = null
        val recovered = persistAndAcknowledgeSafetySample(
            acknowledgement = 17,
            persist = {
                val processor = SafetySampleProcessor(config)
                recoverSafetyDerivationsDurably(
                    processor = processor,
                    rebuildProcessor = processor::reset,
                    loadPendingSamples = { listOf(sample) },
                    persistAlarm = { alarm ->
                        replayedAlarmId = alarm.event.alarmId
                        order += "alarm"
                        true
                    },
                    commitCheckpoint = {
                        order += "checkpoint"
                    },
                )
            },
            acknowledge = { sequence, result -> acknowledgements += sequence to result },
        )

        assertTrue(recovered.persisted)
        assertEquals(firstAlarmId, replayedAlarmId)
        assertEquals(listOf("alarm", "checkpoint"), order)
        assertEquals(listOf(17 to SAFETY_ALARM_RESULT_SUCCESS), acknowledgements)
    }

    @Test
    fun checkpointFailurePropagatesAfterAlarmAndStillWithholdsAck() = runBlocking {
        val config = SafetyThresholdConfig()
        val acknowledgements = mutableListOf<Pair<Int, Int>>()
        var alarmPersisted = false

        val failed = persistAndAcknowledgeSafetySample(
            acknowledgement = 18,
            persist = {
                val processor = SafetySampleProcessor(config)
                recoverSafetyDerivationsDurably(
                    processor = processor,
                    rebuildProcessor = processor::reset,
                    loadPendingSamples = { listOf(impactSample(config)) },
                    persistAlarm = {
                        alarmPersisted = true
                        true
                    },
                    commitCheckpoint = {
                        throw IOException("injected checkpoint failure")
                    },
                )
            },
            acknowledge = { sequence, result -> acknowledgements += sequence to result },
        )

        assertTrue(alarmPersisted)
        assertFalse(failed.persisted)
        assertTrue(failed.error is IOException)
        assertTrue(acknowledgements.isEmpty())
    }

    @Test
    fun unusableCheckpointQuarantinesWaterlineBeforePublishingClear() = runBlocking {
        val config = SafetyThresholdConfig()
        val first = impactSample(config).copy(sampleReference = 100, sampleId = "device:100")
        val candidate = impactSample(config).copy(sampleReference = 101, sampleId = "device:101")
        val order = mutableListOf<String>()

        val failure = runCatching {
            terminateSafetyEpisodesUsingDurableSample(
                pending = listOf(first, candidate),
                latestPublishedReferences = listOf(100),
                quarantineBeforeTermination = { reference -> order += "quarantine:$reference" },
                terminateAt = { sample ->
                    order += "clear:${sample.sampleReference}"
                    throw IOException("injected crash after clear")
                },
            )
        }

        assertTrue(failure.exceptionOrNull() is IOException)
        assertEquals(listOf("quarantine:101", "clear:101"), order)
    }

    private fun impactSample(config: SafetyThresholdConfig) = SafetySensorTelemetry(
        sampleId = "device:77",
        deviceId = "device",
        sampleReference = 77,
        monotonicMillis = 900,
        validFlags = 0x0001,
        accelerationXMilliG = 3_200,
        accelerationYMilliG = 0,
        accelerationZMilliG = 0,
        gyroXMilliDegreesPerSecond = 0,
        gyroYMilliDegreesPerSecond = 0,
        gyroZMilliDegreesPerSecond = 0,
        electricFieldMilliVolts = null,
        pressurePascals = null,
        temperatureCentiCelsius = null,
        altitudeMillimetres = null,
        simulated = false,
        recordedAtEpochMillis = 1_000,
        thresholdConfigVersion = config.version,
        thresholdConfigFingerprint = config.canonicalFingerprint(),
    )
}
