package com.example.helmet.data.local

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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyStoreInstrumentedTest {
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
    fun sampleAndAlertAreIdempotentAndRetainEvidenceFields() = runBlocking {
        val sample = SafetySensorTelemetry(
            sampleId = "device:7",
            deviceId = "device",
            sampleReference = 7,
            monotonicMillis = 900,
            validFlags = 0x0007,
            accelerationXMilliG = -10,
            accelerationYMilliG = 20,
            accelerationZMilliG = 980,
            gyroXMilliDegreesPerSecond = 1,
            gyroYMilliDegreesPerSecond = 2,
            gyroZMilliDegreesPerSecond = 3,
            electricFieldMilliVolts = 250,
            pressurePascals = 101_325,
            temperatureCentiCelsius = null,
            altitudeMillimetres = 2_100,
            simulated = false,
            recordedAtEpochMillis = 1_000,
            thresholdConfigVersion = 2,
            thresholdConfigFingerprint = fingerprint(2),
        )
        assertTrue(store.recordSample(sample))
        assertFalse(store.recordSample(sample))
        assertFalse(store.recordSample(sample.copy(recordedAtEpochMillis = 1_100)))
        assertEquals(sample, store.findSample("device", 7))
        assertEquals(1, store.sampleCount("device"))

        val legacy = sample.copy(
            sampleId = "device:8",
            sampleReference = 8,
            thresholdConfigVersion = null,
            thresholdConfigFingerprint = null,
        )
        assertTrue(store.recordSample(legacy))
        assertFalse(
            store.recordSample(
                legacy.copy(
                    thresholdConfigVersion = 3,
                    thresholdConfigFingerprint = fingerprint(3),
                ),
            ),
        )
        assertEquals(null, store.findSample("device", 8)?.thresholdConfigVersion)

        val afterModuleRestart = sample.copy(
            monotonicMillis = 10,
            electricFieldMilliVolts = 100,
            recordedAtEpochMillis = 2_000,
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.recordSample(afterModuleRestart) }
        }
        assertEquals(sample, store.findSample("device", 7))
        assertEquals(2, store.sampleCount("device"))

        val alert = SafetyAlertRecord(
            messageId = "device:alert-1:active",
            alertId = "device:alert-1",
            deviceId = "device",
            alarmType = "NEAR_ELECTRIC",
            severity = EventSeverity.CRITICAL,
            active = true,
            configVersion = 2,
            sampleReference = 7,
            monotonicMillis = 900,
            occurredAtEpochMillis = 1_000,
            localActions = 7,
            sensorFaults = 0,
            simulated = false,
            sensorSnapshotJson = "{\"electricFieldMilliVolts\":250}",
            latitude = 30.0,
            longitude = 114.0,
            horizontalAccuracyMeters = 2f,
            locationFixType = "STANDARD",
            evidenceAssetId = "photo-1",
            deliveryState = DeliveryState.PENDING,
            attemptCount = 0,
        )
        assertTrue(store.recordAlert(alert))
        assertFalse(store.recordAlert(alert))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.recordAlert(alert.copy(alarmType = "FALL")) }
        }
        assertEquals(1, store.pendingAlertCount())
        assertEquals(alert, store.findAlert(alert.messageId))
        assertTrue(store.markAttempt(alert.messageId, 1_100))
        assertEquals(1, store.pendingAlerts().single().attemptCount)
        assertTrue(store.markDelivered(alert.messageId, 1_200))
        assertEquals(0, store.pendingAlertCount())
        assertNotNull(store.findAlert(alert.messageId))
    }

    @Test
    fun rejectedAlertLeavesPendingQueue() = runBlocking {
        val alert = SafetyAlertRecord(
            messageId = "message",
            alertId = "alert",
            deviceId = "device",
            alarmType = "FALL",
            severity = EventSeverity.CRITICAL,
            active = true,
            configVersion = 1,
            sampleReference = null,
            monotonicMillis = 1,
            occurredAtEpochMillis = 2,
            localActions = 7,
            sensorFaults = 0,
            simulated = true,
            sensorSnapshotJson = "{}",
            latitude = null,
            longitude = null,
            horizontalAccuracyMeters = null,
            locationFixType = "NO_FIX",
            evidenceAssetId = null,
            deliveryState = DeliveryState.PENDING,
            attemptCount = 0,
        )
        assertTrue(store.recordAlert(alert))
        assertTrue(store.markRejected(alert.messageId, "invalid"))
        assertEquals(0, store.pendingAlertCount())
        assertEquals(DeliveryState.REJECTED, store.findAlert(alert.messageId)?.deliveryState)
    }

    @Test
    fun recentSamplesReturnsBoundedChronologicalHistory() = runBlocking {
        listOf(1L to 300L, 2L to 100L, 3L to 200L).forEach { (reference, recordedAt) ->
            assertTrue(
                store.recordSample(
                    SafetySensorTelemetry(
                        sampleId = "device:$reference",
                        deviceId = "device",
                        sampleReference = reference,
                        monotonicMillis = recordedAt,
                        validFlags = 0,
                        accelerationXMilliG = null,
                        accelerationYMilliG = null,
                        accelerationZMilliG = null,
                        gyroXMilliDegreesPerSecond = null,
                        gyroYMilliDegreesPerSecond = null,
                        gyroZMilliDegreesPerSecond = null,
                        electricFieldMilliVolts = null,
                        pressurePascals = 101_325,
                        temperatureCentiCelsius = null,
                        altitudeMillimetres = null,
                        simulated = false,
                        recordedAtEpochMillis = recordedAt,
                    ),
                ),
            )
        }

        assertEquals(listOf(2L, 3L), store.recentSamples("device", limit = 2).map { it.sampleReference })
        assertEquals(3L, store.latestSample("device")?.sampleReference)
    }

    @Test
    fun checkpointWaterlinePrunesAtomicallyAndRejectsNonAdvancingNewReference() = runBlocking {
        val bounded = SafetyStore(database, maxRetainedSamples = 2)
        assertTrue(bounded.recordSample(sample(reference = 1, recordedAt = 300)))
        assertTrue(bounded.recordSample(sample(reference = 2, recordedAt = 100)))
        assertTrue(bounded.recordSample(sample(reference = 3, recordedAt = 200)))

        // No raw evidence is removed before the replay waterline is durable.
        assertEquals(3, bounded.sampleCount("device"))
        assertEquals(
            1,
            bounded.commitDetectionCheckpoint(checkpoint(reference = 3, monotonic = 3)),
        )
        assertEquals(listOf(2L, 3L), bounded.recentSamples("device").map { it.sampleReference })
        assertThrows(DurableIdentityConflictException::class.java) {
            runBlocking {
                bounded.recordSample(sample(reference = 0, recordedAt = 50))
            }
        }
        assertTrue(
            bounded.samplesAfterCheckpoint("device", 3, 1, fingerprint(1)).isEmpty(),
        )
        assertEquals(listOf(2L, 3L), bounded.recentSamples("device").map { it.sampleReference })
    }

    @Test
    fun checkpointCannotAdvancePastMismatchedSampleMetadata() = runBlocking {
        assertTrue(store.recordSample(sample(reference = 1, recordedAt = 100)))

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.commitDetectionCheckpoint(
                    checkpoint(reference = 1, monotonic = 2),
                )
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.commitDetectionCheckpoint(
                    checkpoint(reference = 1, monotonic = 1).copy(
                        thresholdConfigVersion = 2,
                        thresholdConfigFingerprint = fingerprint(2),
                    ),
                )
            }
        }
        assertTrue(store.detectionCheckpoint("device") is SafetyDetectionCheckpointLoadResult.Missing)
    }

    @Test
    fun checkpointIntegrityFailureIsReportedWithoutReturningState() = runBlocking {
        assertTrue(store.recordSample(sample(reference = 1, recordedAt = 100)))
        store.commitDetectionCheckpoint(checkpoint(reference = 1, monotonic = 1))
        val valid = store.detectionCheckpoint("device")
        assertTrue(valid is SafetyDetectionCheckpointLoadResult.Valid)

        val entity = requireNotNull(database.safetyDao().checkpoint("device"))
        database.safetyDao().upsertCheckpoint(entity.copy(payload = "corrupt-payload"))

        val invalid = store.detectionCheckpoint("device")
        assertTrue(invalid is SafetyDetectionCheckpointLoadResult.Invalid)
        assertEquals(
            "PAYLOAD_INTEGRITY_MISMATCH",
            (invalid as SafetyDetectionCheckpointLoadResult.Invalid).reason,
        )
    }

    @Test
    fun recoverySelectsOnlyExplicitConfigVersionAndIsolationPreservesRawEvidence() = runBlocking {
        assertTrue(store.recordSample(sample(reference = 1, recordedAt = 100)))
        assertTrue(
            store.recordSample(
                sample(reference = 2, recordedAt = 200).copy(
                    thresholdConfigVersion = 2,
                    thresholdConfigFingerprint = fingerprint(2),
                ),
            ),
        )
        assertTrue(
            store.recordSample(
                sample(reference = 3, recordedAt = 300).copy(
                    thresholdConfigVersion = null,
                    thresholdConfigFingerprint = null,
                ),
            ),
        )

        assertEquals(
            listOf(1L),
            store.samplesForConfig("device", 1, fingerprint(1)).map { it.sampleReference },
        )
        assertEquals(
            listOf(2L),
            store.samplesForConfig("device", 2, fingerprint(2)).map { it.sampleReference },
        )
        assertEquals(1, store.isolateSamplesForConfig("device", 1, fingerprint(1)))
        assertTrue(store.samplesForConfig("device", 1, fingerprint(1)).isEmpty())
        assertEquals(null, store.findSample("device", 1)?.thresholdConfigVersion)
        assertEquals(3, store.sampleCount("device"))
    }

    @Test
    fun missingCheckpointQuarantinesSameVersionDifferentFingerprintBeforeRetransmission() = runBlocking {
        val oldConfig = SafetyThresholdConfig(version = 1)
        val currentConfig = oldConfig.copy(heightThresholdMillimetres = 3_000)
        val oldSample = sample(reference = 41, recordedAt = 100).copy(
            thresholdConfigFingerprint = oldConfig.canonicalFingerprint(),
        )
        assertTrue(store.recordSample(oldSample))

        assertEquals(
            1,
            store.isolateSamplesOutsideConfig(
                "device",
                currentConfig.version,
                currentConfig.canonicalFingerprint(),
            ),
        )
        val retransmission = oldSample.copy(
            recordedAtEpochMillis = 200,
            thresholdConfigVersion = currentConfig.version,
            thresholdConfigFingerprint = currentConfig.canonicalFingerprint(),
        )
        assertFalse(store.recordSample(retransmission))
        assertEquals(null, store.findSample("device", 41)?.thresholdConfigVersion)
        assertTrue(
            store.samplesForConfig(
                "device",
                currentConfig.version,
                currentConfig.canonicalFingerprint(),
            ).isEmpty(),
        )
    }


    @Test
    fun unpersistedGapBelowHighWaterIsRejectedWithoutChangingLatestSample() = runBlocking {
        assertTrue(store.recordSample(sample(reference = 101, recordedAt = 101)))

        assertThrows(DurableIdentityConflictException::class.java) {
            runBlocking { store.recordSample(sample(reference = 100, recordedAt = 100)) }
        }
        assertEquals(listOf(101L), store.recentSamples("device").map { it.sampleReference })
        assertEquals(101L, store.latestSample("device")?.sampleReference)
    }

    @Test
    fun latestActiveAlertsExcludeEpisodesWhoseLatestStateIsCleared() = runBlocking {
        val activeOne = alert(
            messageId = "device:101:ACTIVE:1",
            alertId = "device:101",
            active = true,
            occurredAt = 100,
        )
        val clearedOne = activeOne.copy(
            messageId = "device:101:CLEARED:2",
            active = false,
            sampleReference = 2,
            monotonicMillis = 2,
            occurredAtEpochMillis = 200,
        )
        val activeTwo = alert(
            messageId = "device:202:ACTIVE:3",
            alertId = "device:202",
            active = true,
            occurredAt = 300,
        )
        assertTrue(store.recordAlert(activeOne))
        assertTrue(store.recordAlert(clearedOne))
        assertTrue(store.recordAlert(activeTwo))
        assertTrue(store.recordAlert(activeTwo.copy(messageId = "other:303:ACTIVE:4", alertId = "other:303", deviceId = "other")))

        assertEquals(listOf(activeTwo.messageId), store.latestActiveAlerts("device").map { it.messageId })
    }

    @Test
    fun latestAlertAndPendingOrderFollowDurableInsertionOrderAcrossWallClockRollback() = runBlocking {
        val active = alert(
            messageId = "device:501:ACTIVE:10",
            alertId = "device:501",
            active = true,
            occurredAt = 2_000,
        )
        val clear = active.copy(
            messageId = "device:501:CLEARED:11",
            active = false,
            sampleReference = 11,
            monotonicMillis = 11,
            occurredAtEpochMillis = 1_000,
        )
        assertTrue(store.recordAlert(active))
        assertTrue(store.recordAlert(clear))
        assertFalse(store.recordAlert(active))

        assertEquals(clear.messageId, store.latestAlert(active.alertId)?.messageId)
        assertTrue(store.latestActiveAlerts("device").isEmpty())
        assertEquals(
            listOf(active.messageId, clear.messageId),
            store.pendingAlerts().map { it.messageId },
        )
    }

    private fun sample(reference: Long, recordedAt: Long) = SafetySensorTelemetry(
        sampleId = "device:$reference",
        deviceId = "device",
        sampleReference = reference,
        monotonicMillis = reference,
        validFlags = 0,
        accelerationXMilliG = null,
        accelerationYMilliG = null,
        accelerationZMilliG = null,
        gyroXMilliDegreesPerSecond = null,
        gyroYMilliDegreesPerSecond = null,
        gyroZMilliDegreesPerSecond = null,
        electricFieldMilliVolts = null,
        pressurePascals = 101_325,
        temperatureCentiCelsius = null,
        altitudeMillimetres = null,
        simulated = false,
        recordedAtEpochMillis = recordedAt,
        thresholdConfigVersion = 1,
        thresholdConfigFingerprint = fingerprint(1),
    )

    private fun checkpoint(reference: Long, monotonic: Long) = SafetyDetectionCheckpointRecord(
        deviceId = "device",
        schemaVersion = 3,
        algorithmVersion = 1,
        thresholdConfigVersion = 1,
        thresholdConfigFingerprint = fingerprint(1),
        lastSampleReference = reference,
        lastMonotonicMillis = monotonic,
        payload = "checkpoint-$reference",
        updatedAtEpochMillis = 1_000 + reference,
    )

    private fun alert(
        messageId: String,
        alertId: String,
        active: Boolean,
        occurredAt: Long,
    ) = SafetyAlertRecord(
        messageId = messageId,
        alertId = alertId,
        deviceId = "device",
        alarmType = "NEAR_ELECTRIC",
        severity = EventSeverity.HIGH,
        active = active,
        configVersion = 1,
        sampleReference = occurredAt,
        monotonicMillis = occurredAt,
        occurredAtEpochMillis = occurredAt,
        localActions = 7,
        sensorFaults = 0,
        simulated = false,
        sensorSnapshotJson = "{\"detectionOrigin\":\"ANDROID_DETECTION\"}",
        latitude = null,
        longitude = null,
        horizontalAccuracyMeters = null,
        locationFixType = "NO_FIX",
        evidenceAssetId = null,
        deliveryState = DeliveryState.PENDING,
        attemptCount = 0,
    )

    private fun fingerprint(version: Int): String =
        SafetyThresholdConfig(version = version).canonicalFingerprint()
}
