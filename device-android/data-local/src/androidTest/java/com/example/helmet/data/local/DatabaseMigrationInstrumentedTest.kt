package com.example.helmet.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HelmetDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    @Throws(IOException::class)
    fun migrationOneToFifteenPreservesEventsAndCreatesAllSchemas() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO helmet_events (
                    messageId, eventType, severity, payloadJson, occurredAtEpochMillis,
                    deliveryState, attemptCount, lastAttemptAtEpochMillis, deliveredAtEpochMillis
                ) VALUES ('event-1', 'TEST', 'INFO', '{}', 100, 'PENDING', 0, NULL, NULL)
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            15,
            true,
            HelmetDatabase.MIGRATION_1_2,
            HelmetDatabase.MIGRATION_2_3,
            HelmetDatabase.MIGRATION_3_4,
            HelmetDatabase.MIGRATION_4_5,
            HelmetDatabase.MIGRATION_5_6,
            HelmetDatabase.MIGRATION_6_7,
            HelmetDatabase.MIGRATION_7_8,
            HelmetDatabase.MIGRATION_8_9,
            HelmetDatabase.MIGRATION_9_10,
            HelmetDatabase.MIGRATION_10_11,
            HelmetDatabase.MIGRATION_11_12,
            HelmetDatabase.MIGRATION_12_13,
            HelmetDatabase.MIGRATION_13_14,
            HelmetDatabase.MIGRATION_14_15,
        )
        database.query("SELECT COUNT(*) FROM helmet_events").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        database.query("PRAGMA table_info(helmet_events)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
            assertEquals(
                setOf("messageId", "eventType", "severity", "payloadJson", "occurredAtEpochMillis"),
                columns,
            )
        }
        database.query("SELECT COUNT(*) FROM media_assets").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.query("PRAGMA table_info(media_assets)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
            assertEquals(true, "personId" in columns)
            assertEquals(true, "latitude" in columns)
            assertEquals(true, "longitude" in columns)
            assertEquals(true, "horizontalAccuracyMeters" in columns)
            assertEquals(true, "locationFixType" in columns)
            assertEquals(true, "voiceSenderId" in columns)
            assertEquals(true, "voiceSenderRole" in columns)
            assertEquals(true, "voiceAllowedRoles" in columns)
            assertEquals(true, "voiceCallId" in columns)
        }
        database.query("SELECT COUNT(*) FROM track_points").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM call_sessions").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM text_broadcasts").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM device_commands").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM safety_samples").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM safety_alerts").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM call_media_recovery").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM call_state_outbox").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM safety_detection_checkpoints").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM device_command_cursors").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM active_command_streams").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM quarantined_command_streams").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrationElevenToTwelvePreservesV11CallAndHardwareStateAndIsolatesLegacySamples() {
        helper.createDatabase(VERSION_ELEVEN_DATABASE, 11).apply {
            execSQL(
                """
                INSERT INTO safety_samples (
                    sampleId, deviceId, sampleReference, monotonicMillis, validFlags,
                    accelerationXMilliG, accelerationYMilliG, accelerationZMilliG,
                    gyroXMilliDegreesPerSecond, gyroYMilliDegreesPerSecond,
                    gyroZMilliDegreesPerSecond, electricFieldMilliVolts, pressurePascals,
                    temperatureCentiCelsius, altitudeMillimetres, simulated, recordedAtEpochMillis
                ) VALUES (
                    'device-1:7', 'device-1', 7, 700, 2,
                    NULL, NULL, NULL, NULL, NULL, NULL, 250, NULL, NULL, NULL, 0, 1000
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO call_state_outbox (
                    callId, stateSequence, state, actorId, occurredAtEpochMillis, reason,
                    deliveryState, attemptCount, lastAttemptAtEpochMillis, deliveredAtEpochMillis, lastError
                ) VALUES ('call-1', 2, 'RINGING', 'operator-1', 200, NULL, 'PENDING', 0, NULL, NULL, NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO hardware_key_actions (
                    actionId, input, plannedAction, rejectionReason, targetCallId,
                    simulated, monotonicMillis, hardwareEventId, sequence, state,
                    attemptCount, lastError, receivedAtEpochMillis, updatedAtEpochMillis,
                    plannedArgument, businessRequestId
                ) VALUES (
                    'key-1', 'PHOTO_SHORT', 'PHOTO', NULL, NULL,
                    0, 300, 9, 11, 'RECEIVED', 0, NULL, 300, 300, NULL, 12
                )
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            VERSION_ELEVEN_DATABASE,
            12,
            true,
            HelmetDatabase.MIGRATION_11_12,
        )
        database.query(
            "SELECT sampleReference, thresholdConfigVersion FROM safety_samples WHERE sampleId = 'device-1:7'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(7L, cursor.getLong(0))
            assertEquals(true, cursor.isNull(1))
        }
        database.query("SELECT state, deliveryState FROM call_state_outbox WHERE callId = 'call-1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("RINGING", cursor.getString(0))
            assertEquals("PENDING", cursor.getString(1))
        }
        database.query(
            "SELECT plannedAction, state, businessRequestId FROM hardware_key_actions WHERE actionId = 'key-1'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("PHOTO", cursor.getString(0))
            assertEquals("RECEIVED", cursor.getString(1))
            assertEquals(12L, cursor.getLong(2))
        }
        database.query("SELECT COUNT(*) FROM safety_detection_checkpoints").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrationTwelveToThirteenPreservesV12CallAndHardwareStateAndQuarantinesSafetyIdentity() {
        helper.createDatabase(VERSION_TWELVE_DATABASE, 12).apply {
            execSQL(
                """
                INSERT INTO safety_samples (
                    sampleId, deviceId, sampleReference, monotonicMillis, validFlags,
                    accelerationXMilliG, accelerationYMilliG, accelerationZMilliG,
                    gyroXMilliDegreesPerSecond, gyroYMilliDegreesPerSecond,
                    gyroZMilliDegreesPerSecond, electricFieldMilliVolts, pressurePascals,
                    temperatureCentiCelsius, altitudeMillimetres, simulated,
                    recordedAtEpochMillis, thresholdConfigVersion
                ) VALUES (
                    'device-12:7', 'device-12', 7, 700, 2,
                    NULL, NULL, NULL, NULL, NULL, NULL, 250, NULL,
                    NULL, NULL, 0, 1000, 4
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO safety_detection_checkpoints (
                    deviceId, schemaVersion, algorithmVersion, thresholdConfigVersion,
                    lastSampleReference, lastMonotonicMillis, payload, payloadSha256,
                    updatedAtEpochMillis
                ) VALUES ('device-12', 1, 1, 4, 7, 700, 'legacy', '${"a".repeat(64)}', 1100)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO call_state_outbox (
                    callId, stateSequence, state, actorId, occurredAtEpochMillis, reason,
                    deliveryState, attemptCount, lastAttemptAtEpochMillis,
                    deliveredAtEpochMillis, lastError
                ) VALUES ('call-12', 3, 'CONNECTED', 'operator-1', 200, NULL,
                    'PENDING', 0, NULL, NULL, NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO hardware_key_actions (
                    actionId, input, plannedAction, rejectionReason, targetCallId,
                    simulated, monotonicMillis, hardwareEventId, sequence, state,
                    attemptCount, lastError, receivedAtEpochMillis, updatedAtEpochMillis,
                    plannedArgument, businessRequestId
                ) VALUES ('key-12', 'PHOTO_SHORT', 'PHOTO', NULL, NULL,
                    0, 300, 9, 11, 'RECEIVED', 0, NULL, 300, 300, NULL, 12)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            VERSION_TWELVE_DATABASE,
            15,
            true,
            HelmetDatabase.MIGRATION_12_13,
            HelmetDatabase.MIGRATION_13_14,
            HelmetDatabase.MIGRATION_14_15,
        ).apply {
            query(
                "SELECT thresholdConfigVersion, thresholdConfigFingerprint, " +
                    "derivationCommitted FROM safety_samples WHERE sampleId = 'device-12:7'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
                assertEquals(0, cursor.getInt(2))
            }
            query(
                "SELECT thresholdConfigFingerprint FROM safety_detection_checkpoints " +
                    "WHERE deviceId = 'device-12'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            query("SELECT state FROM call_state_outbox WHERE callId = 'call-12'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("CONNECTED", cursor.getString(0))
            }
            query("SELECT state FROM hardware_key_actions WHERE actionId = 'key-12'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("RECEIVED", cursor.getString(0))
            }
            close()
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val migrated = Room.databaseBuilder(context, HelmetDatabase::class.java, VERSION_TWELVE_DATABASE)
            .addMigrations(
                HelmetDatabase.MIGRATION_12_13,
                HelmetDatabase.MIGRATION_13_14,
                HelmetDatabase.MIGRATION_14_15,
            )
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val safetyStore = SafetyStore(migrated)
                val sample = safetyStore.findSample("device-12", 7)
                assertEquals(null, sample?.thresholdConfigVersion)
                assertEquals(null, sample?.thresholdConfigFingerprint)
                val checkpoint = safetyStore.detectionCheckpoint("device-12")
                assertTrue(checkpoint is SafetyDetectionCheckpointLoadResult.Invalid)
                assertEquals(
                    "THRESHOLD_FINGERPRINT_MISSING",
                    (checkpoint as SafetyDetectionCheckpointLoadResult.Invalid).reason,
                )
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationElevenToThirteenChainsHistoricMigrationsAndKeepsLegacySampleReadable() {
        helper.createDatabase(VERSION_ELEVEN_TO_THIRTEEN_DATABASE, 11).apply {
            execSQL(
                """
                INSERT INTO safety_samples (
                    sampleId, deviceId, sampleReference, monotonicMillis, validFlags,
                    accelerationXMilliG, accelerationYMilliG, accelerationZMilliG,
                    gyroXMilliDegreesPerSecond, gyroYMilliDegreesPerSecond,
                    gyroZMilliDegreesPerSecond, electricFieldMilliVolts, pressurePascals,
                    temperatureCentiCelsius, altitudeMillimetres, simulated, recordedAtEpochMillis
                ) VALUES ('device-chain:9', 'device-chain', 9, 900, 2,
                    NULL, NULL, NULL, NULL, NULL, NULL, 300, NULL, NULL, NULL, 0, 900)
                """.trimIndent(),
            )
            close()
        }
        helper.runMigrationsAndValidate(
            VERSION_ELEVEN_TO_THIRTEEN_DATABASE,
            13,
            true,
            HelmetDatabase.MIGRATION_11_12,
            HelmetDatabase.MIGRATION_12_13,
        ).apply {
            query(
                "SELECT thresholdConfigVersion, thresholdConfigFingerprint, derivationCommitted " +
                    "FROM safety_samples WHERE sampleId = 'device-chain:9'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
                assertEquals(0, cursor.getInt(2))
            }
            close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationThirteenToFifteenQuarantinesLegacyCommandAndBroadcastStreams() {
        helper.createDatabase(VERSION_THIRTEEN_DATABASE, 13).apply {
            execSQL(
                """
                INSERT INTO device_commands (
                    commandId, deviceId, serverSequence, type, payloadJson,
                    createdAtEpochMillis, state, receivedAtEpochMillis,
                    appliedAtEpochMillis, lastError, ackDeliveryState, ackAttemptCount,
                    lastAckAttemptAtEpochMillis, ackDeliveredAtEpochMillis
                ) VALUES (
                    'legacy-command', 'device-legacy', 7, 'CALL_STATE', '{}',
                    100, 'APPLIED', 101, 102, NULL, 'DELIVERED', 1, 102, 103
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO text_broadcasts (
                    broadcastId, deviceId, serverSequence, text, language, priority,
                    expiresAtEpochMillis, playbackState, receivedAtEpochMillis,
                    playingAtEpochMillis, playedAtEpochMillis, lastError,
                    receiptDeliveryState, receiptAttemptCount,
                    lastReceiptAttemptAtEpochMillis, receiptDeliveredAtEpochMillis
                ) VALUES (
                    'legacy-broadcast', 'device-legacy', 7, 'legacy', 'en-US', 1,
                    NULL, 'PLAYED', 100, 101, 102, NULL, 'DELIVERED', 1, 101, 103
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            VERSION_THIRTEEN_DATABASE,
            15,
            true,
            HelmetDatabase.MIGRATION_13_14,
            HelmetDatabase.MIGRATION_14_15,
        ).apply {
            query(
                "SELECT commandStreamId, state, ackDeliveryState FROM device_commands " +
                    "WHERE commandId = 'legacy-command'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(DeviceCommandStore.LEGACY_COMMAND_STREAM_ID, cursor.getString(0))
                assertEquals("APPLIED", cursor.getString(1))
                assertEquals("DELIVERED", cursor.getString(2))
            }
            query(
                "SELECT commandStreamId, playbackState, receiptDeliveryState FROM text_broadcasts " +
                    "WHERE broadcastId = 'legacy-broadcast'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(DeviceCommandStore.LEGACY_COMMAND_STREAM_ID, cursor.getString(0))
                assertEquals("PLAYED", cursor.getString(1))
                assertEquals("DELIVERED", cursor.getString(2))
            }
            query(
                "SELECT afterSequence, maxObservedHighWater FROM device_command_cursors WHERE commandStreamId = ? " +
                    "AND deviceId = 'device-legacy'",
                arrayOf(DeviceCommandStore.LEGACY_COMMAND_STREAM_ID),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
                assertEquals(7L, cursor.getLong(1))
            }
            query(
                "SELECT COUNT(*) FROM device_command_cursors WHERE commandStreamId = ?",
                arrayOf(NEW_COMMAND_STREAM_ID),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            execSQL(
                """
                INSERT INTO device_commands (
                    commandId, commandStreamId, deviceId, serverSequence, type, payloadJson,
                    createdAtEpochMillis, state, receivedAtEpochMillis, appliedAtEpochMillis,
                    lastError, ackDeliveryState, ackAttemptCount,
                    lastAckAttemptAtEpochMillis, ackDeliveredAtEpochMillis
                ) VALUES ('legacy-command', '$NEW_COMMAND_STREAM_ID', 'device-legacy', 1,
                    'CALL_STATE', '{}', 200, 'RECEIVED', 201, NULL, NULL, 'PENDING', 0, NULL, NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO text_broadcasts (
                    broadcastId, commandStreamId, deviceId, serverSequence, text, language,
                    priority, expiresAtEpochMillis, playbackState, receivedAtEpochMillis,
                    playingAtEpochMillis, playedAtEpochMillis, lastError,
                    receiptDeliveryState, receiptAttemptCount,
                    lastReceiptAttemptAtEpochMillis, receiptDeliveredAtEpochMillis
                ) VALUES ('legacy-broadcast', '$NEW_COMMAND_STREAM_ID', 'device-legacy', 7,
                    'new', 'en-US', 1, NULL, 'RECEIVED', 200, NULL, NULL, NULL,
                    'PENDING', 0, NULL, NULL)
                """.trimIndent(),
            )
            query("SELECT COUNT(*) FROM device_commands WHERE deviceId = 'device-legacy'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM text_broadcasts WHERE deviceId = 'device-legacy'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM command_stream_adoptions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM command_stream_lineage").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM quarantined_command_streams").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT commandStreamId FROM active_command_streams WHERE deviceId='device-legacy'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(DeviceCommandStore.LEGACY_COMMAND_STREAM_ID, cursor.getString(0))
                }
            close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationFourteenToFifteenPreservesScopedRowsAndCreatesEmptyAdoptionStaging() {
        helper.createDatabase(VERSION_FOURTEEN_DATABASE, 14).apply {
            execSQL(
                """
                INSERT INTO device_commands (
                    commandId, commandStreamId, deviceId, serverSequence, type, payloadJson,
                    createdAtEpochMillis, state, receivedAtEpochMillis, appliedAtEpochMillis,
                    lastError, ackDeliveryState, ackAttemptCount,
                    lastAckAttemptAtEpochMillis, ackDeliveredAtEpochMillis
                ) VALUES ('v14-command', '$NEW_COMMAND_STREAM_ID', 'device-v14', 1,
                    'CALL_STATE', '{}', 100, 'RECEIVED', 101, NULL, NULL,
                    'PENDING', 0, NULL, NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO device_commands (
                    commandId, commandStreamId, deviceId, serverSequence, type, payloadJson,
                    createdAtEpochMillis, state, receivedAtEpochMillis, appliedAtEpochMillis,
                    lastError, ackDeliveryState, ackAttemptCount,
                    lastAckAttemptAtEpochMillis, ackDeliveredAtEpochMillis
                ) VALUES
                    ('failed-unattempted', '$NEW_COMMAND_STREAM_ID', 'device-errors', 1,
                        'CALL_STATE', '{}', 100, 'FAILED', 101, 102,
                        'EXECUTION_FAILURE', 'PENDING', 0, NULL, NULL),
                    ('failed-rejected', '$NEW_COMMAND_STREAM_ID', 'device-errors', 2,
                        'CALL_STATE', '{}', 110, 'FAILED', 111, 112,
                        'HTTP 422', 'REJECTED', 1, 113, NULL),
                    ('applied-rejected', '$NEW_COMMAND_STREAM_ID', 'device-errors', 3,
                        'CALL_STATE', '{}', 120, 'APPLIED', 121, 122,
                        'HTTP 409', 'REJECTED', 1, 123, NULL),
                    ('failed-delivered', '$NEW_COMMAND_STREAM_ID', 'device-errors', 4,
                        'CALL_STATE', '{}', 130, 'FAILED', 131, 132,
                        'EXECUTION DELIVERED', 'DELIVERED', 1, 133, 134),
                    ('failed-in-flight', '$NEW_COMMAND_STREAM_ID', 'device-errors', 5,
                        'CALL_STATE', '{}', 140, 'FAILED', 141, 142,
                        'EXECUTION IN FLIGHT', 'IN_FLIGHT', 1, 143, NULL),
                    ('failed-pending-contaminated', '$NEW_COMMAND_STREAM_ID', 'device-errors', 6,
                        'CALL_STATE', '{}', 150, 'FAILED', 151, 152,
                        'HTTP RESET', 'PENDING', 1, 153, NULL),
                    ('failed-delivered-contaminated', '$NEW_COMMAND_STREAM_ID', 'device-errors', 7,
                        'CALL_STATE', '{}', 160, 'FAILED', 161, 162,
                        'HTTP OLD', 'DELIVERED', 2, 163, 164)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO text_broadcasts (
                    broadcastId, commandStreamId, deviceId, serverSequence, text, language,
                    priority, expiresAtEpochMillis, playbackState, receivedAtEpochMillis,
                    playingAtEpochMillis, playedAtEpochMillis, lastError,
                    receiptDeliveryState, receiptAttemptCount,
                    lastReceiptAttemptAtEpochMillis, receiptDeliveredAtEpochMillis
                ) VALUES
                    ('played-rejected', '$NEW_COMMAND_STREAM_ID', 'device-errors', 1,
                        'played', 'en-US', 1, NULL, 'PLAYED', 100, 101, 102,
                        'HTTP RECEIPT', 'REJECTED', 1, 103, NULL),
                    ('failed-rejected-broadcast', '$NEW_COMMAND_STREAM_ID', 'device-errors', 2,
                        'failed', 'en-US', 1, NULL, 'FAILED', 110, 111, 112,
                        'HTTP RECEIPT', 'REJECTED', 1, 113, NULL),
                    ('expired-rejected', '$NEW_COMMAND_STREAM_ID', 'device-errors', 3,
                        'expired', 'en-US', 1, NULL, 'EXPIRED', 120, 121, 122,
                        'HTTP RECEIPT', 'REJECTED', 1, 123, NULL),
                    ('failed-pending', '$NEW_COMMAND_STREAM_ID', 'device-errors', 4,
                        'failed pending', 'en-US', 1, NULL, 'FAILED', 130, 131, 132,
                        'TTS FAILURE', 'PENDING', 0, NULL, NULL)
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO device_command_cursors VALUES ('$NEW_COMMAND_STREAM_ID', 'device-v14', 1)",
            )
            execSQL(
                "INSERT INTO device_command_cursors VALUES ('$NEW_COMMAND_STREAM_ID', 'device-errors', 7)",
            )
            execSQL(
                "INSERT INTO device_command_cursors VALUES ('${DeviceCommandStore.LEGACY_COMMAND_STREAM_ID}', 'device-multi', 7)",
            )
            execSQL(
                "INSERT INTO device_command_cursors VALUES ('$NEW_COMMAND_STREAM_ID', 'device-multi', 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            VERSION_FOURTEEN_DATABASE,
            15,
            true,
            HelmetDatabase.MIGRATION_14_15,
        ).apply {
            query("SELECT commandStreamId, serverSequence FROM device_commands WHERE commandId='v14-command'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(NEW_COMMAND_STREAM_ID, cursor.getString(0))
                    assertEquals(1L, cursor.getLong(1))
                }
            query(
                "SELECT commandId, lastError, ackLastError FROM device_commands " +
                    "WHERE deviceId='device-errors' ORDER BY serverSequence",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("failed-unattempted", cursor.getString(0))
                assertEquals("EXECUTION_FAILURE", cursor.getString(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.moveToNext())
                assertEquals("failed-rejected", cursor.getString(0))
                assertEquals("LEGACY_COMMAND_FAILURE_REASON_UNAVAILABLE", cursor.getString(1))
                assertEquals("HTTP 422", cursor.getString(2))
                assertTrue(cursor.moveToNext())
                assertEquals("applied-rejected", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertEquals("HTTP 409", cursor.getString(2))
                assertTrue(cursor.moveToNext())
                assertEquals("failed-delivered", cursor.getString(0))
                assertEquals("EXECUTION DELIVERED", cursor.getString(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.moveToNext())
                assertEquals("failed-in-flight", cursor.getString(0))
                assertEquals("EXECUTION IN FLIGHT", cursor.getString(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.moveToNext())
                assertEquals("failed-pending-contaminated", cursor.getString(0))
                assertEquals("LEGACY_COMMAND_FAILURE_REASON_UNAVAILABLE", cursor.getString(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.moveToNext())
                assertEquals("failed-delivered-contaminated", cursor.getString(0))
                assertEquals("LEGACY_COMMAND_FAILURE_REASON_UNAVAILABLE", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
            query(
                "SELECT broadcastId, lastError, receiptLastError FROM text_broadcasts " +
                    "WHERE deviceId='device-errors' ORDER BY serverSequence",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("played-rejected", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertEquals("HTTP RECEIPT", cursor.getString(2))
                assertTrue(cursor.moveToNext())
                assertEquals("failed-rejected-broadcast", cursor.getString(0))
                assertEquals("LEGACY_BROADCAST_FAILURE_REASON_UNAVAILABLE", cursor.getString(1))
                assertEquals("HTTP RECEIPT", cursor.getString(2))
                assertTrue(cursor.moveToNext())
                assertEquals("expired-rejected", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertEquals("HTTP RECEIPT", cursor.getString(2))
                assertTrue(cursor.moveToNext())
                assertEquals("failed-pending", cursor.getString(0))
                assertEquals("TTS FAILURE", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
            query(
                "SELECT afterSequence, maxObservedHighWater FROM device_command_cursors " +
                    "WHERE deviceId='device-v14'",
            )
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                    assertEquals(1L, cursor.getLong(1))
                }
            query("SELECT COUNT(*) FROM command_stream_adoptions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM command_stream_adoption_rows").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM command_stream_lineage").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM quarantined_command_streams").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT commandStreamId FROM active_command_streams WHERE deviceId='device-v14'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(NEW_COMMAND_STREAM_ID, cursor.getString(0))
                }
            query("SELECT COUNT(*) FROM active_command_streams WHERE deviceId='device-multi'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationTenToElevenRejectsUnsafeRelativePlansAndPreservesSafePlans() {
        helper.createDatabase(VERSION_TEN_DATABASE, 10).apply {
            fun insert(actionId: String, input: String, action: String, state: String) {
                execSQL(
                    """
                    INSERT INTO hardware_key_actions (
                        actionId, input, plannedAction, rejectionReason, targetCallId,
                        simulated, monotonicMillis, hardwareEventId, sequence, state,
                        attemptCount, lastError, receivedAtEpochMillis, updatedAtEpochMillis
                    ) VALUES (
                        '$actionId', '$input', '$action', NULL, NULL,
                        0, 100, 7, 9, '$state', 0, NULL, 1000, 1000
                    )
                    """.trimIndent(),
                )
            }
            insert("legacy-toggle", "CALL", "LOCAL_INTERCOM_TOGGLE", "RECEIVED")
            insert("legacy-volume", "VOLUME_UP", "NO_OP", "RECEIVED")
            insert("legacy-call", "CALL", "CALL_START", "RECEIVED")
            insert("safe-photo", "PHOTO_SHORT", "PHOTO", "RECEIVED")
            insert("terminal-toggle", "CALL", "LOCAL_INTERCOM_TOGGLE", "APPLIED")
            close()
        }

        val database = helper.runMigrationsAndValidate(
            VERSION_TEN_DATABASE,
            11,
            true,
            HelmetDatabase.MIGRATION_10_11,
        )
        database.query(
            "SELECT actionId, state, attemptCount, lastError, plannedArgument, businessRequestId " +
                "FROM hardware_key_actions ORDER BY actionId",
        ).use { cursor ->
            val rows = buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(0),
                        listOf(
                            cursor.getString(1),
                            cursor.getInt(2).toString(),
                            cursor.getString(3),
                            cursor.getString(4),
                            cursor.getString(5),
                        ),
                    )
                }
            }
            assertEquals(
                listOf("FAILED", "1", "UNSAFE_V10_RELATIVE_KEY_PLAN", null, null),
                rows["legacy-toggle"],
            )
            assertEquals(
                listOf("FAILED", "1", "UNSAFE_V10_RELATIVE_KEY_PLAN", null, null),
                rows["legacy-volume"],
            )
            assertEquals(
                listOf("FAILED", "1", "UNSAFE_V10_MISSING_CALL_ID", null, null),
                rows["legacy-call"],
            )
            assertEquals(listOf("RECEIVED", "0", null, null, null), rows["safe-photo"])
            assertEquals(listOf("APPLIED", "0", null, null, null), rows["terminal-toggle"])
        }
        database.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrationSixToSevenPreservesMediaAndAddsEmptyVoiceMetadata() {
        helper.createDatabase(VERSION_SIX_DATABASE, 6).apply {
            execSQL(
                """
                INSERT INTO media_assets (
                    assetId, kind, filePath, mimeType, byteSize, sha256, width, height,
                    durationMillis, createdAtEpochMillis, deviceId, relatedEventId,
                    transferState, attemptCount, lastAttemptAtEpochMillis, deliveredAtEpochMillis,
                    lastError, personId, latitude, longitude, horizontalAccuracyMeters, locationFixType
                ) VALUES (
                    'photo-1', 'PHOTO', '/private/photo.jpg', 'image/jpeg', 123, '${"a".repeat(64)}',
                    640, 360, NULL, 100, 'device-1', NULL, 'PENDING', 0, NULL, NULL,
                    NULL, NULL, NULL, NULL, NULL, 'NO_FIX'
                )
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            VERSION_SIX_DATABASE,
            7,
            true,
            HelmetDatabase.MIGRATION_6_7,
        )
        database.query(
            "SELECT voiceSenderId, voiceSenderRole, voiceAllowedRoles, voiceCallId FROM media_assets WHERE assetId = 'photo-1'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(true, cursor.isNull(0))
            assertEquals(true, cursor.isNull(1))
            assertEquals("", cursor.getString(2))
            assertEquals(true, cursor.isNull(3))
        }
        database.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrationNineToTenCreatesRecoverableOutgoingCallHistory() {
        helper.createDatabase(VERSION_NINE_DATABASE, 9).apply {
            execSQL(
                """
                INSERT INTO call_sessions (
                    callId, deviceId, direction, mediaMode, state, stateSequence,
                    createdAtEpochMillis, updatedAtEpochMillis, relatedEventId, simulated,
                    lastReason, deliveryState, attemptCount, lastAttemptAtEpochMillis,
                    deliveredAtEpochMillis
                ) VALUES (
                    'call-advanced', 'device-1', 'OUTGOING_DEVICE', 'VIDEO_UPLINK', 'ENDED', 4,
                    100, 400, 'event-1', 0, 'DEVICE_KEY_HANGUP', 'DELIVERED', 2, 350, 450
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO call_sessions (
                    callId, deviceId, direction, mediaMode, state, stateSequence,
                    createdAtEpochMillis, updatedAtEpochMillis, relatedEventId, simulated,
                    lastReason, deliveryState, attemptCount, lastAttemptAtEpochMillis,
                    deliveredAtEpochMillis
                ) VALUES (
                    'call-connected', 'device-2', 'OUTGOING_DEVICE', 'VIDEO_UPLINK', 'CONNECTED', 9,
                    1000, 1900, NULL, 0, NULL, 'FAILED', 3, 1850, NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO call_sessions (
                    callId, deviceId, direction, mediaMode, state, stateSequence,
                    createdAtEpochMillis, updatedAtEpochMillis, relatedEventId, simulated,
                    lastReason, deliveryState, attemptCount, lastAttemptAtEpochMillis,
                    deliveredAtEpochMillis
                ) VALUES (
                    'call-confirmed', 'device-3', 'OUTGOING_DEVICE', 'VIDEO_UPLINK', 'CONNECTED', 5,
                    2000, 2500, NULL, 0, NULL, 'DELIVERED', 1, 2450, 2550
                )
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            VERSION_NINE_DATABASE,
            10,
            true,
            HelmetDatabase.MIGRATION_9_10,
        )
        database.query(
            "SELECT stateSequence, state, deliveryState, deliveredAtEpochMillis, lastError " +
                "FROM call_state_outbox " +
                "WHERE callId = 'call-advanced' ORDER BY stateSequence",
        ).use { cursor ->
            val values = buildList<List<Any?>> {
                while (cursor.moveToNext()) {
                    add(
                        listOf(
                            cursor.getLong(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            if (cursor.isNull(3)) null else cursor.getLong(3),
                            cursor.getString(4),
                        ),
                    )
                }
            }
            assertEquals(
                listOf(
                    listOf(1L, "REQUESTED", "PENDING", null, null),
                    listOf(
                        4L,
                        "ENDED",
                        "PENDING",
                        null,
                        CallStore.MIGRATED_V9_RECONCILIATION_MARKER,
                    ),
                ),
                values,
            )
        }
        database.query(
            "SELECT stateSequence, state, deliveryState, lastError FROM call_state_outbox " +
                "WHERE callId = 'call-connected' ORDER BY stateSequence",
        ).use { cursor ->
            val values = buildList<List<Any?>> {
                while (cursor.moveToNext()) {
                    add(listOf(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)))
                }
            }
            assertEquals(
                listOf(
                    listOf(1L, "REQUESTED", "PENDING", null),
                    listOf(9L, "CONNECTED", "PENDING", CallStore.MIGRATED_V9_RECONCILIATION_MARKER),
                ),
                values,
            )
        }
        database.query(
            "SELECT stateSequence, state, deliveryState, lastError FROM call_state_outbox " +
                "WHERE callId = 'call-confirmed' ORDER BY stateSequence",
        ).use { cursor ->
            val values = buildList<List<Any?>> {
                while (cursor.moveToNext()) {
                    add(listOf(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)))
                }
            }
            assertEquals(
                listOf(
                    listOf(1L, "REQUESTED", "PENDING", null),
                    listOf(
                        5L,
                        "CONNECTED",
                        "PENDING",
                        CallStore.MIGRATED_V9_RECONCILIATION_MARKER,
                    ),
                ),
                values,
            )
        }
        database.query(
            "SELECT stateSequence FROM call_sessions WHERE callId = 'call-confirmed'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(5L, cursor.getLong(0))
        }
        database.query("SELECT COUNT(*) FROM hardware_key_actions").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.close()
    }

    companion object {
        private const val TEST_DATABASE = "migration-test"
        private const val VERSION_SIX_DATABASE = "migration-six-test"
        private const val VERSION_NINE_DATABASE = "migration-nine-test"
        private const val VERSION_TEN_DATABASE = "migration-ten-test"
        private const val VERSION_ELEVEN_DATABASE = "migration-eleven-test"
        private const val VERSION_TWELVE_DATABASE = "migration-twelve-test"
        private const val VERSION_ELEVEN_TO_THIRTEEN_DATABASE = "migration-eleven-thirteen-test"
        private const val VERSION_THIRTEEN_DATABASE = "migration-thirteen-test"
        private const val VERSION_FOURTEEN_DATABASE = "migration-fourteen-test"
        private const val NEW_COMMAND_STREAM_ID = "00000000-0000-0000-0000-000000000014"
    }
}
