package com.example.helmet.service.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.core.model.SafetySensorTelemetry
import com.example.helmet.core.model.SafetyThresholdConfig
import com.example.helmet.core.model.canonicalFingerprint
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.SafetyDetectionCheckpointLoadResult
import com.example.helmet.data.local.SafetyStore
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyCheckpointCrashRecoveryInstrumentedTest {
    private lateinit var database: HelmetDatabase
    private lateinit var store: SafetyStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            HelmetDatabase::class.java,
        ).build()
        store = SafetyStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun durableSampleIsReplayedWhenAlarmPersistenceFailsBeforeCheckpoint() = runBlocking {
        verifyRecoveryAfterInjectedFailure(persistAlarmBeforeFailure = false)
    }

    @Test
    fun durableAlarmIsIdempotentlyReplayedWhenCheckpointCommitFails() = runBlocking {
        verifyRecoveryAfterInjectedFailure(persistAlarmBeforeFailure = true)
    }

    @Test
    fun laterLiveSampleCannotAdvancePastEarlierAlarmPersistenceFailureInSameProcessor() = runBlocking {
        val deviceId = "same-process-dirty"
        val config = SafetyThresholdConfig()
        val firstSample = impactSample(deviceId, config)
        val secondSample = firstSample.copy(
            sampleId = "$deviceId:78",
            sampleReference = 78,
            monotonicMillis = 901,
            accelerationXMilliG = 1_000,
            recordedAtEpochMillis = 1_001,
        )
        val sharedProcessor = SafetySampleProcessor(config)
        assertTrue(store.recordSample(firstSample))
        var failedAlarmId: Long? = null
        val failure = runCatching {
            recoverSafetyDerivationsDurably(
                processor = sharedProcessor,
                rebuildProcessor = sharedProcessor::reset,
                loadPendingSamples = {
                    store.samplesForConfig(deviceId, config.version, config.canonicalFingerprint())
                },
                persistAlarm = { alarm ->
                    failedAlarmId = alarm.event.alarmId
                    throw IOException("injected first alarm failure")
                },
                commitCheckpoint = { error("checkpoint must not lead failed alarm") },
            )
        }
        assertTrue(failure.exceptionOrNull() is IOException)
        assertTrue(store.detectionCheckpoint(deviceId) is SafetyDetectionCheckpointLoadResult.Missing)

        // The same in-memory processor is already dirty here. The production coordinator
        // must rebuild it before processing the later durable input.
        assertTrue(store.recordSample(secondSample))
        val order = mutableListOf<String>()
        var recoveredAlarmId: Long? = null
        recoverSafetyDerivationsDurably(
            processor = sharedProcessor,
            rebuildProcessor = sharedProcessor::reset,
            loadPendingSamples = {
                store.samplesForConfig(deviceId, config.version, config.canonicalFingerprint())
            },
            persistAlarm = { alarm ->
                recoveredAlarmId = alarm.event.alarmId
                order += "alarm:${alarm.event.sampleReference}"
                store.recordAlert(alarm.toRecord(deviceId))
                true
            },
            commitCheckpoint = { checkpoint ->
                order += "checkpoint:${checkpoint.lastSampleReference}"
                store.commitDetectionCheckpoint(checkpoint.toRecord(deviceId, 2_000))
            },
        )

        assertEquals(failedAlarmId, recoveredAlarmId)
        assertEquals(listOf("alarm:77", "checkpoint:78"), order)
        val checkpoint = store.detectionCheckpoint(deviceId) as SafetyDetectionCheckpointLoadResult.Valid
        assertEquals(78L, checkpoint.checkpoint.lastSampleReference)
        assertEquals(1, store.pendingAlertCount())
    }

    @Test
    fun corruptCheckpointQuarantinesOnlyDerivedHistoryAndImmediatelyReplaysPendingSample() = runBlocking {
        val deviceId = "corrupt-with-pending"
        val config = SafetyThresholdConfig()
        val impact = impactSample(deviceId, config)
        val baseline = impact.copy(
            sampleId = "$deviceId:76",
            sampleReference = 76,
            monotonicMillis = 899,
            accelerationXMilliG = 1_000,
            recordedAtEpochMillis = 999,
        )
        assertTrue(store.recordSample(baseline))
        val first = SafetySampleProcessor(config)
        recoverSafetyDerivationsDurably(
            processor = first,
            rebuildProcessor = first::reset,
            loadPendingSamples = {
                store.samplesForConfig(deviceId, config.version, config.canonicalFingerprint())
            },
            persistAlarm = { error("baseline must not alarm") },
            commitCheckpoint = { checkpoint ->
                store.commitDetectionCheckpoint(checkpoint.toRecord(deviceId, 1_000))
            },
        )
        assertTrue(store.recordSample(impact))
        val entity = requireNotNull(database.safetyDao().checkpoint(deviceId))
        database.safetyDao().upsertCheckpoint(entity.copy(payload = "corrupt"))
        assertTrue(store.detectionCheckpoint(deviceId) is SafetyDetectionCheckpointLoadResult.Invalid)

        val recoveredProcessor = SafetySampleProcessor(config)
        var recoveredAlarmId: Long? = null
        recoverUnusableSafetyCheckpointDurably(
            processor = recoveredProcessor,
            retiredIdentity = null,
            stageRetiredEpisodes = {},
            isolateAllDerivedSamples = { store.isolateAllDerivedSamples(deviceId) },
            isolateRetiredIdentity = { error("corrupt metadata has no trusted retired identity") },
            isolateOutsideCurrentIdentity = {
                store.isolateSamplesOutsideConfig(
                    deviceId,
                    config.version,
                    config.canonicalFingerprint(),
                )
            },
            loadPendingSamples = {
                store.samplesForConfig(deviceId, config.version, config.canonicalFingerprint())
            },
            terminateRetiredEpisodes = { true },
            deleteCheckpoint = { store.deleteDetectionCheckpoint(deviceId) },
            persistAlarm = { alarm ->
                recoveredAlarmId = alarm.event.alarmId
                store.recordAlert(alarm.toRecord(deviceId))
                true
            },
            commitCheckpoint = { checkpoint ->
                store.commitDetectionCheckpoint(checkpoint.toRecord(deviceId, 2_000))
            },
        )

        assertTrue(recoveredAlarmId != null)
        assertEquals(null, store.findSample(deviceId, 76)?.thresholdConfigVersion)
        assertEquals(config.version, store.findSample(deviceId, 77)?.thresholdConfigVersion)
        assertTrue(
            store.samplesForConfig(deviceId, config.version, config.canonicalFingerprint()).isEmpty(),
        )
        val checkpoint = store.detectionCheckpoint(deviceId) as SafetyDetectionCheckpointLoadResult.Valid
        assertEquals(77L, checkpoint.checkpoint.lastSampleReference)
        assertEquals(true, database.safetyDao().findSample(deviceId, 76)?.derivationCommitted)
        assertEquals(true, database.safetyDao().findSample(deviceId, 77)?.derivationCommitted)
    }

    @Test
    fun mixedMissingRecoveryProtectsCurrentPendingActiveWhileRetiringForeignFingerprint() = runBlocking {
        val deviceId = "mixed-missing"
        val foreignConfig = SafetyThresholdConfig(
            version = 1,
            electricCalibrationSamples = 3,
            electricConfirmationSamples = 1,
            heightCalibrationSamples = 3,
        )
        val currentConfig = foreignConfig.copy(heightThresholdMillimetres = 3_000)
        val foreign = fieldSample(deviceId, 1, 100, foreignConfig)
        val current = listOf(
            fieldSample(deviceId, 2, 100, currentConfig),
            fieldSample(deviceId, 3, 100, currentConfig),
            fieldSample(deviceId, 4, 100, currentConfig),
            fieldSample(deviceId, 5, 350, currentConfig),
        )
        assertTrue(store.recordSample(foreign))
        current.forEach { assertTrue(store.recordSample(it)) }
        val initialProcessor = SafetySampleProcessor(currentConfig)
        val originalAlarm = current.flatMap { initialProcessor.process(it).alarms }.single()
        assertEquals("NEAR_ELECTRIC", originalAlarm.event.alarmType)
        store.recordAlert(
            originalAlarm.toRecord(deviceId, currentConfig.canonicalFingerprint()),
        )
        assertTrue(store.detectionCheckpoint(deviceId) is SafetyDetectionCheckpointLoadResult.Missing)

        val retired = store.isolateSamplesOutsideConfig(
            deviceId,
            currentConfig.version,
            currentConfig.canonicalFingerprint(),
        )
        val pending = store.samplesForConfig(
            deviceId,
            currentConfig.version,
            currentConfig.canonicalFingerprint(),
        )
        val active = store.latestActiveAlerts(deviceId).single()
        assertEquals(1, retired)
        assertEquals(listOf(2L, 3L, 4L, 5L), pending.map(SafetySensorTelemetry::sampleReference))
        assertTrue(
            isProtectedCurrentPendingSafetyAlert(
                active,
                pending.mapTo(mutableSetOf(), SafetySensorTelemetry::sampleReference),
                currentConfig.canonicalFingerprint(),
            ),
        )

        val recovered = SafetySampleProcessor(currentConfig)
        recoverSafetyDerivationsDurably(
            processor = recovered,
            rebuildProcessor = recovered::reset,
            loadPendingSamples = { pending },
            beforeReplay = { samples ->
                assertTrue(
                    isProtectedCurrentPendingSafetyAlert(
                        active,
                        samples.mapTo(mutableSetOf(), SafetySensorTelemetry::sampleReference),
                        currentConfig.canonicalFingerprint(),
                    ),
                )
                true
            },
            persistAlarm = { alarm ->
                assertEquals(originalAlarm.event.alarmId, alarm.event.alarmId)
                store.recordAlert(alarm.toRecord(deviceId, currentConfig.canonicalFingerprint()))
                true
            },
            commitCheckpoint = { checkpoint ->
                store.commitDetectionCheckpoint(checkpoint.toRecord(deviceId, 2_000))
            },
        )
        assertEquals(true, store.latestActiveAlerts(deviceId).single().active)
        assertEquals(
            5L,
            (store.detectionCheckpoint(deviceId) as SafetyDetectionCheckpointLoadResult.Valid)
                .checkpoint.lastSampleReference,
        )
    }

    @Test
    fun invalidCheckpointClearCrashCannotReplayQuarantinedFaultEpisode() = runBlocking {
        val deviceId = "invalid-continuous-fault"
        val config = SafetyThresholdConfig(
            electricCalibrationSamples = 3,
            electricConfirmationSamples = 1,
            heightCalibrationSamples = 3,
        )
        (1L..3L).forEach { reference ->
            assertTrue(store.recordSample(fieldSample(deviceId, reference, 100, config)))
        }
        val baselineProcessor = SafetySampleProcessor(config)
        recoverSafetyDerivationsDurably(
            processor = baselineProcessor,
            rebuildProcessor = baselineProcessor::reset,
            loadPendingSamples = {
                store.samplesForConfig(deviceId, config.version, config.canonicalFingerprint())
            },
            persistAlarm = { error("baseline must not alarm") },
            commitCheckpoint = { checkpoint ->
                store.commitDetectionCheckpoint(checkpoint.toRecord(deviceId, 1_000))
            },
        )
        val baselineCheckpoint =
            (store.detectionCheckpoint(deviceId) as SafetyDetectionCheckpointLoadResult.Valid).checkpoint
        val faultFour = fieldSample(deviceId, 4, 6_000, config)
        assertTrue(store.recordSample(faultFour))
        val dirty = SafetySampleProcessor(config).also { it.restoreCheckpoint(baselineCheckpoint) }
        val oldFault = dirty.process(faultFour).alarms.single()
        assertEquals("SENSOR_FAULT", oldFault.event.alarmType)
        assertTrue(store.recordAlert(oldFault.toRecord(deviceId, config.canonicalFingerprint())))
        val oldAlarmId = requireNotNull(oldFault.event.alarmId)
        val checkpointEntity = requireNotNull(database.safetyDao().checkpoint(deviceId))
        database.safetyDao().upsertCheckpoint(checkpointEntity.copy(payload = "corrupt"))
        assertTrue(store.recordSample(fieldSample(deviceId, 5, 6_000, config)))

        val order = mutableListOf<String>()
        val firstAttempt = runCatching {
            recoverUnusableSafetyCheckpointDurably(
                processor = SafetySampleProcessor(config),
                retiredIdentity = null,
                stageRetiredEpisodes = {},
                isolateAllDerivedSamples = { store.isolateAllDerivedSamples(deviceId) },
                isolateRetiredIdentity = { error("corrupt checkpoint has no trusted identity") },
                isolateOutsideCurrentIdentity = {
                    store.isolateSamplesOutsideConfig(deviceId, config.version, config.canonicalFingerprint())
                },
                loadPendingSamples = {
                    store.samplesForConfig(deviceId, config.version, config.canonicalFingerprint())
                },
                terminateRetiredEpisodes = { pending ->
                    val active = store.latestActiveAlerts(deviceId)
                    terminateSafetyEpisodesUsingDurableSample(
                        pending = pending,
                        latestPublishedReferences = active.mapNotNull(SafetyAlertRecord::sampleReference),
                        quarantineBeforeTermination = { candidateReference ->
                            order += "quarantine:$candidateReference"
                            store.isolatePendingSamplesBefore(
                                deviceId,
                                candidateReference,
                                config.version,
                                config.canonicalFingerprint(),
                            )
                        },
                        terminateAt = { candidate ->
                            val stale = store.latestActiveAlerts(deviceId).single()
                            assertEquals(5L, candidate.sampleReference)
                            assertTrue(
                                store.recordAlert(
                                    stale.copy(
                                        messageId = "${stale.alertId}:CLEARED:${candidate.sampleReference}",
                                        active = false,
                                        sampleReference = candidate.sampleReference,
                                        monotonicMillis = candidate.monotonicMillis,
                                        occurredAtEpochMillis = 2_000,
                                    ),
                                ),
                            )
                            order += "clear:$oldAlarmId"
                            // Simulate process death after the clear is durable. Quarantine
                            // must already be durable and the invalid checkpoint must remain.
                            throw IOException("injected crash after durable clear")
                        },
                    )
                },
                deleteCheckpoint = { error("checkpoint must remain until termination completes") },
                persistAlarm = { error("invalid pending samples must not replay before clear") },
                commitCheckpoint = { error("invalid pending samples must not commit before clear") },
            )
        }
        assertTrue(firstAttempt.exceptionOrNull() is IOException)
        assertEquals(listOf("quarantine:5", "clear:$oldAlarmId"), order)
        assertTrue(store.detectionCheckpoint(deviceId) is SafetyDetectionCheckpointLoadResult.Invalid)
        assertEquals(null, store.findSample(deviceId, 4)?.thresholdConfigVersion)
        assertEquals(config.version, store.findSample(deviceId, 5)?.thresholdConfigVersion)
        assertEquals(false, store.latestAlert("$deviceId:$oldAlarmId")?.active)

        // A recreated service sees the durable clear and invalid checkpoint. The row before
        // the termination sample is already isolated, so only reference 5 can establish a
        // fresh baseline and it receives a distinct episode ID.
        val fresh = SafetySampleProcessor(config)
        var newAlarmId: Long? = null
        recoverUnusableSafetyCheckpointDurably(
            processor = fresh,
            retiredIdentity = null,
            stageRetiredEpisodes = {},
            isolateAllDerivedSamples = { store.isolateAllDerivedSamples(deviceId) },
            isolateRetiredIdentity = { error("corrupt checkpoint has no trusted identity") },
            isolateOutsideCurrentIdentity = {
                store.isolateSamplesOutsideConfig(deviceId, config.version, config.canonicalFingerprint())
            },
            loadPendingSamples = {
                store.samplesForConfig(deviceId, config.version, config.canonicalFingerprint())
            },
            terminateRetiredEpisodes = { pending ->
                assertEquals(listOf(5L), pending.map(SafetySensorTelemetry::sampleReference))
                terminateSafetyEpisodesUsingDurableSample(
                    pending = pending,
                    latestPublishedReferences = store.latestActiveAlerts(deviceId)
                        .mapNotNull(SafetyAlertRecord::sampleReference),
                    quarantineBeforeTermination = { error("durable clear needs no second quarantine") },
                    terminateAt = { error("durable clear must not be emitted twice") },
                )
            },
            deleteCheckpoint = { store.deleteDetectionCheckpoint(deviceId) },
            persistAlarm = { alarm ->
                newAlarmId = alarm.event.alarmId
                order += "active:${alarm.event.alarmId}"
                store.recordAlert(alarm.toRecord(deviceId, config.canonicalFingerprint()))
                true
            },
            commitCheckpoint = { checkpoint ->
                order += "checkpoint:${checkpoint.lastSampleReference}"
                store.commitDetectionCheckpoint(checkpoint.toRecord(deviceId, 3_000))
            },
        )

        assertTrue(newAlarmId != null && newAlarmId != oldAlarmId)
        assertEquals(false, store.latestAlert("$deviceId:$oldAlarmId")?.active)
        assertEquals(true, store.latestAlert("$deviceId:$newAlarmId")?.active)
        assertEquals(null, store.findSample(deviceId, 4)?.thresholdConfigVersion)
        assertEquals(config.version, store.findSample(deviceId, 5)?.thresholdConfigVersion)
        assertEquals(
            listOf("quarantine:5", "clear:$oldAlarmId", "active:$newAlarmId", "checkpoint:5"),
            order,
        )
    }

    @Test
    fun checkpointContinuesEpisodeAfterMoreThanTenThousandRoomSamplesArePruned() = runBlocking {
        val deviceId = "retention-window"
        val config = SafetyThresholdConfig(
            electricCalibrationSamples = 3,
            electricConfirmationSamples = 2,
            heightCalibrationSamples = 3,
        )
        val processor = SafetySampleProcessor(config)
        (1L..10_005L).forEach { reference ->
            fieldSample(
                deviceId = deviceId,
                reference = reference,
                value = if (reference <= 3) 100 else 300,
                config = config,
            ).also { assertTrue(store.recordSample(it)) }
        }
        var activeAlarmId: Long? = null

        recoverSafetyDerivationsDurably(
            processor = processor,
            rebuildProcessor = processor::reset,
            loadPendingSamples = {
                store.samplesForConfig(deviceId, config.version, config.canonicalFingerprint())
            },
            persistAlarm = { alarm ->
                assertTrue(alarm.event.active)
                activeAlarmId = alarm.event.alarmId
                store.recordAlert(alarm.toRecord(deviceId))
                true
            },
            commitCheckpoint = { checkpoint ->
                store.commitDetectionCheckpoint(checkpoint.toRecord(deviceId, 20_000))
            },
        )

        assertEquals(SafetyStore.MAX_RETAINED_SAMPLES, store.sampleCount(deviceId))
        assertEquals(null, store.findSample(deviceId, 5))
        assertTrue(store.findSample(deviceId, 6) != null)
        val valid = store.detectionCheckpoint(deviceId) as SafetyDetectionCheckpointLoadResult.Valid
        assertEquals(10_005L, valid.checkpoint.lastSampleReference)

        val restored = SafetySampleProcessor(config).also { it.restoreCheckpoint(valid.checkpoint) }
        val clearSamples = listOf(10_006L, 10_007L).map { reference ->
            fieldSample(deviceId, reference, 100, config)
                .also { assertTrue(store.recordSample(it)) }
        }
        var clearAlarmId: Long? = null
        recoverSafetyDerivationsDurably(
            processor = restored,
            rebuildProcessor = { restored.restoreCheckpoint(valid.checkpoint) },
            loadPendingSamples = { clearSamples },
            persistAlarm = { alarm ->
                assertFalse(alarm.event.active)
                clearAlarmId = alarm.event.alarmId
                store.recordAlert(alarm.toRecord(deviceId))
                true
            },
            commitCheckpoint = { checkpoint ->
                store.commitDetectionCheckpoint(checkpoint.toRecord(deviceId, 30_000))
            },
        )

        assertEquals(activeAlarmId, clearAlarmId)
        assertTrue(store.latestActiveAlerts(deviceId).isEmpty())
        assertEquals(false, store.latestAlert("$deviceId:$activeAlarmId")?.active)
        assertEquals(SafetyStore.MAX_RETAINED_SAMPLES, store.sampleCount(deviceId))
    }

    @Test
    fun heightCheckpointContinuesEpisodeAfterTenThousandAndFiveRoomSamplesThenClears() = runBlocking {
        val deviceId = "height-retention-window"
        val config = SafetyThresholdConfig(
            electricCalibrationSamples = 3,
            heightCalibrationSamples = 3,
            heightConfirmationSamples = 2,
            heightConfirmationMillis = 0,
        )
        (1L..10_005L).forEach { reference ->
            val altitude = if (reference <= 3) 100_000 else 102_500
            assertTrue(store.recordSample(heightSample(deviceId, reference, altitude, config)))
        }
        val first = SafetySampleProcessor(config)
        var activeAlarmId: Long? = null
        recoverSafetyDerivationsDurably(
            processor = first,
            rebuildProcessor = first::reset,
            loadPendingSamples = {
                store.samplesForConfig(deviceId, config.version, config.canonicalFingerprint())
            },
            persistAlarm = { alarm ->
                if (alarm.event.alarmType == "HEIGHT_LIMIT" && alarm.event.active) {
                    activeAlarmId = alarm.event.alarmId
                }
                store.recordAlert(alarm.toRecord(deviceId))
                true
            },
            commitCheckpoint = { checkpoint ->
                store.commitDetectionCheckpoint(checkpoint.toRecord(deviceId, 40_000))
            },
        )
        assertTrue(activeAlarmId != null)
        assertEquals(SafetyStore.MAX_RETAINED_SAMPLES, store.sampleCount(deviceId))

        val valid = store.detectionCheckpoint(deviceId) as SafetyDetectionCheckpointLoadResult.Valid
        (10_006L..10_007L).forEach { reference ->
            assertTrue(store.recordSample(heightSample(deviceId, reference, 100_000, config)))
        }
        val restored = SafetySampleProcessor(config)
        var clearAlarmId: Long? = null
        recoverSafetyDerivationsDurably(
            processor = restored,
            rebuildProcessor = { restored.restoreCheckpoint(valid.checkpoint) },
            loadPendingSamples = {
                store.samplesAfterCheckpoint(
                    deviceId,
                    valid.checkpoint.lastSampleReference,
                    config.version,
                    config.canonicalFingerprint(),
                )
            },
            persistAlarm = { alarm ->
                if (alarm.event.alarmType == "HEIGHT_LIMIT" && !alarm.event.active) {
                    clearAlarmId = alarm.event.alarmId
                }
                store.recordAlert(alarm.toRecord(deviceId))
                true
            },
            commitCheckpoint = { checkpoint ->
                store.commitDetectionCheckpoint(checkpoint.toRecord(deviceId, 50_000))
            },
        )

        assertEquals(activeAlarmId, clearAlarmId)
        assertTrue(store.latestActiveAlerts(deviceId).isEmpty())
        assertEquals(10_007L, (store.detectionCheckpoint(deviceId) as SafetyDetectionCheckpointLoadResult.Valid)
            .checkpoint.lastSampleReference)
    }

    private suspend fun verifyRecoveryAfterInjectedFailure(persistAlarmBeforeFailure: Boolean) {
        val deviceId = if (persistAlarmBeforeFailure) "checkpoint-failure" else "alarm-failure"
        val config = SafetyThresholdConfig()
        val sample = impactSample(deviceId, config)
        val firstProcessor = SafetySampleProcessor(config)
        val acknowledgements = mutableListOf<Pair<Int, Int>>()
        var firstAlarmId: Long? = null

        val failed = persistAndAcknowledgeSafetySample(
            acknowledgement = 41,
            persist = {
                assertTrue(store.recordSample(sample))
                recoverSafetyDerivationsDurably(
                    processor = firstProcessor,
                    rebuildProcessor = firstProcessor::reset,
                    loadPendingSamples = {
                        store.samplesForConfig(deviceId, config.version, config.canonicalFingerprint())
                    },
                    persistAlarm = { alarm ->
                        firstAlarmId = alarm.event.alarmId
                        if (!persistAlarmBeforeFailure) {
                            throw IOException("injected alarm persistence failure")
                        }
                        store.recordAlert(alarm.toRecord(deviceId))
                        true
                    },
                    commitCheckpoint = {
                        throw IOException("injected checkpoint commit failure")
                    },
                )
                true
            },
            acknowledge = { sequence, result -> acknowledgements += sequence to result },
        )

        assertFalse(failed.persisted)
        assertTrue(failed.error is IOException)
        assertTrue(acknowledgements.isEmpty())
        assertEquals(1, store.sampleCount(deviceId))
        assertEquals(if (persistAlarmBeforeFailure) 1 else 0, store.pendingAlertCount())
        assertTrue(store.detectionCheckpoint(deviceId) is SafetyDetectionCheckpointLoadResult.Missing)

        val recoveredProcessor = SafetySampleProcessor(config)
        var recoveredAlarmId: Long? = null
        var alarmDurable = false
        val recovery = recoverSafetyDerivationsDurably(
            processor = recoveredProcessor,
            rebuildProcessor = recoveredProcessor::reset,
            loadPendingSamples = {
                store.samplesForConfig(deviceId, config.version, config.canonicalFingerprint())
            },
            persistAlarm = { alarm ->
                recoveredAlarmId = alarm.event.alarmId
                store.recordAlert(alarm.toRecord(deviceId))
                alarmDurable = true
                true
            },
            commitCheckpoint = { checkpoint ->
                assertTrue(alarmDurable)
                store.commitDetectionCheckpoint(checkpoint.toRecord(deviceId, 2_000))
            },
        )
        assertEquals(1, recovery.restoredSamples)
        assertEquals(1, recovery.alarms.size)
        assertEquals(firstAlarmId, recoveredAlarmId)

        val checkpoint = store.detectionCheckpoint(deviceId)
        assertTrue(checkpoint is SafetyDetectionCheckpointLoadResult.Valid)
        assertEquals(1, store.pendingAlertCount())
        assertEquals(
            firstAlarmId,
            store.pendingAlerts().single().alertId.substringAfterLast(':').toLong(),
        )

        // A later wire retransmission is acknowledged only after the durable alarm and replay
        // waterline both exist. Restoring the checkpoint proves that no sample is re-derived.
        val retried = persistAndAcknowledgeSafetySample(
            acknowledgement = 41,
            persist = {
                assertFalse(store.recordSample(sample.copy(recordedAtEpochMillis = 3_000)))
                val valid = store.detectionCheckpoint(deviceId) as SafetyDetectionCheckpointLoadResult.Valid
                SafetySampleProcessor(config).restoreCheckpoint(valid.checkpoint)
                assertTrue(
                    store.samplesAfterCheckpoint(
                        deviceId,
                        valid.checkpoint.lastSampleReference,
                        config.version,
                        config.canonicalFingerprint(),
                    ).isEmpty(),
                )
                true
            },
            acknowledge = { sequence, result -> acknowledgements += sequence to result },
        )

        assertTrue(retried.persisted)
        assertEquals(listOf(41 to SAFETY_ALARM_RESULT_SUCCESS), acknowledgements)
        assertEquals(1, store.pendingAlertCount())
    }

    private fun impactSample(deviceId: String, config: SafetyThresholdConfig) = SafetySensorTelemetry(
        sampleId = "$deviceId:77",
        deviceId = deviceId,
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

    private fun fieldSample(
        deviceId: String,
        reference: Long,
        value: Int,
        config: SafetyThresholdConfig,
    ) = SafetySensorTelemetry(
        sampleId = "$deviceId:$reference",
        deviceId = deviceId,
        sampleReference = reference,
        monotonicMillis = reference,
        validFlags = 0x0002,
        accelerationXMilliG = null,
        accelerationYMilliG = null,
        accelerationZMilliG = null,
        gyroXMilliDegreesPerSecond = null,
        gyroYMilliDegreesPerSecond = null,
        gyroZMilliDegreesPerSecond = null,
        electricFieldMilliVolts = value,
        pressurePascals = null,
        temperatureCentiCelsius = null,
        altitudeMillimetres = null,
        simulated = false,
        recordedAtEpochMillis = reference,
        thresholdConfigVersion = config.version,
        thresholdConfigFingerprint = config.canonicalFingerprint(),
    )

    private fun heightSample(
        deviceId: String,
        reference: Long,
        altitudeMillimetres: Int,
        config: SafetyThresholdConfig,
    ) = SafetySensorTelemetry(
        sampleId = "$deviceId:$reference",
        deviceId = deviceId,
        sampleReference = reference,
        monotonicMillis = reference,
        validFlags = 0x0008,
        accelerationXMilliG = null,
        accelerationYMilliG = null,
        accelerationZMilliG = null,
        gyroXMilliDegreesPerSecond = null,
        gyroYMilliDegreesPerSecond = null,
        gyroZMilliDegreesPerSecond = null,
        electricFieldMilliVolts = null,
        pressurePascals = null,
        temperatureCentiCelsius = null,
        altitudeMillimetres = altitudeMillimetres,
        simulated = false,
        recordedAtEpochMillis = reference,
        thresholdConfigVersion = config.version,
        thresholdConfigFingerprint = config.canonicalFingerprint(),
    )

    private fun EvaluatedSafetyAlarm.toRecord(
        deviceId: String,
        thresholdConfigFingerprint: String? = null,
    ): SafetyAlertRecord {
        val stableAlarmId = requireNotNull(event.alarmId)
        val reference = requireNotNull(event.sampleReference)
        val state = if (event.active) "ACTIVE" else "CLEARED"
        return SafetyAlertRecord(
            messageId = "$deviceId:$stableAlarmId:$state:$reference",
            alertId = "$deviceId:$stableAlarmId",
            deviceId = deviceId,
            alarmType = event.alarmType,
            severity = EventSeverity.valueOf(event.severity),
            active = event.active,
            configVersion = event.configVersion,
            sampleReference = reference,
            monotonicMillis = event.monotonicMillis,
            occurredAtEpochMillis = 1_500,
            localActions = event.localActions,
            sensorFaults = event.sensorFaults,
            simulated = event.simulated,
            sensorSnapshotJson = JSONObject(
                mapOf(
                    "detectionOrigin" to event.origin.name,
                    "thresholdConfigFingerprint" to thresholdConfigFingerprint,
                ),
            ).toString(),
            latitude = null,
            longitude = null,
            horizontalAccuracyMeters = null,
            locationFixType = "NO_FIX",
            evidenceAssetId = null,
            deliveryState = DeliveryState.PENDING,
            attemptCount = 0,
        )
    }
}
