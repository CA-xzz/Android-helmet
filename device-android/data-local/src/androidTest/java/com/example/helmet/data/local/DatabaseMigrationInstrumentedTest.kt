package com.example.helmet.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Assert.assertEquals
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
    fun migrationOneToEightPreservesEventsAndCreatesAllSchemas() {
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
            8,
            true,
            HelmetDatabase.MIGRATION_1_2,
            HelmetDatabase.MIGRATION_2_3,
            HelmetDatabase.MIGRATION_3_4,
            HelmetDatabase.MIGRATION_4_5,
            HelmetDatabase.MIGRATION_5_6,
            HelmetDatabase.MIGRATION_6_7,
            HelmetDatabase.MIGRATION_7_8,
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

    companion object {
        private const val TEST_DATABASE = "migration-test"
        private const val VERSION_SIX_DATABASE = "migration-six-test"
    }
}
