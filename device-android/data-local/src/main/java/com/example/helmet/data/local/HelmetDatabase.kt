package com.example.helmet.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        EventEntity::class,
        MediaEntity::class,
        TrackPointEntity::class,
        CallSessionEntity::class,
        TextBroadcastEntity::class,
        DeviceCommandEntity::class,
        SafetySampleEntity::class,
        SafetyAlertEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class HelmetDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun mediaDao(): MediaDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun callSessionDao(): CallSessionDao
    abstract fun textBroadcastDao(): TextBroadcastDao
    abstract fun deviceCommandDao(): DeviceCommandDao
    abstract fun safetyDao(): SafetyDao

    companion object {
        @Volatile
        private var instance: HelmetDatabase? = null

        fun get(context: Context): HelmetDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HelmetDatabase::class.java,
                    "helmet.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                )
                    .build()
                    .also { instance = it }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_assets` (
                        `assetId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `byteSize` INTEGER NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `durationMillis` INTEGER,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `relatedEventId` TEXT,
                        `transferState` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `lastAttemptAtEpochMillis` INTEGER,
                        `deliveredAtEpochMillis` INTEGER,
                        `lastError` TEXT,
                        PRIMARY KEY(`assetId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_assets` ADD COLUMN `personId` TEXT")
                db.execSQL("ALTER TABLE `media_assets` ADD COLUMN `latitude` REAL")
                db.execSQL("ALTER TABLE `media_assets` ADD COLUMN `longitude` REAL")
                db.execSQL("ALTER TABLE `media_assets` ADD COLUMN `horizontalAccuracyMeters` REAL")
                db.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `locationFixType` TEXT NOT NULL DEFAULT 'NO_FIX'",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `track_points` (
                        `messageId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `sequence` INTEGER NOT NULL,
                        `fixId` TEXT NOT NULL,
                        `occurredAtEpochMillis` INTEGER NOT NULL,
                        `elapsedRealtimeNanos` INTEGER,
                        `source` TEXT NOT NULL,
                        `quality` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `altitudeMeters` REAL,
                        `horizontalAccuracyMeters` REAL,
                        `verticalAccuracyMeters` REAL,
                        `speedMetersPerSecond` REAL,
                        `speedAccuracyMetersPerSecond` REAL,
                        `bearingDegrees` REAL,
                        `bearingAccuracyDegrees` REAL,
                        `satellitesUsed` INTEGER,
                        `satellitesVisible` INTEGER,
                        `pdop` REAL,
                        `hdop` REAL,
                        `vdop` REAL,
                        `correctionAgeSeconds` REAL,
                        `correctionStationId` TEXT,
                        `provider` TEXT,
                        `isMock` INTEGER NOT NULL,
                        `deliveryState` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `recordedAtEpochMillis` INTEGER NOT NULL,
                        `lastAttemptAtEpochMillis` INTEGER,
                        `deliveredAtEpochMillis` INTEGER,
                        `lastError` TEXT,
                        PRIMARY KEY(`messageId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_track_points_deviceId_sequence` ON `track_points` (`deviceId`, `sequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_track_points_deliveryState_sequence` ON `track_points` (`deliveryState`, `sequence`)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `call_sessions` (
                        `callId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `mediaMode` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `stateSequence` INTEGER NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        `relatedEventId` TEXT,
                        `simulated` INTEGER NOT NULL,
                        `lastReason` TEXT,
                        `deliveryState` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `lastAttemptAtEpochMillis` INTEGER,
                        `deliveredAtEpochMillis` INTEGER,
                        PRIMARY KEY(`callId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_sessions_deviceId_updatedAtEpochMillis` ON `call_sessions` (`deviceId`, `updatedAtEpochMillis`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `text_broadcasts` (
                        `broadcastId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `serverSequence` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `expiresAtEpochMillis` INTEGER,
                        `playbackState` TEXT NOT NULL,
                        `receivedAtEpochMillis` INTEGER NOT NULL,
                        `playingAtEpochMillis` INTEGER,
                        `playedAtEpochMillis` INTEGER,
                        `lastError` TEXT,
                        `receiptDeliveryState` TEXT NOT NULL,
                        `receiptAttemptCount` INTEGER NOT NULL,
                        `lastReceiptAttemptAtEpochMillis` INTEGER,
                        `receiptDeliveredAtEpochMillis` INTEGER,
                        PRIMARY KEY(`broadcastId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_text_broadcasts_deviceId_serverSequence` ON `text_broadcasts` (`deviceId`, `serverSequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_text_broadcasts_receiptDeliveryState_serverSequence` ON `text_broadcasts` (`receiptDeliveryState`, `serverSequence`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `device_commands` (
                        `commandId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `serverSequence` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `receivedAtEpochMillis` INTEGER NOT NULL,
                        `appliedAtEpochMillis` INTEGER,
                        `lastError` TEXT,
                        `ackDeliveryState` TEXT NOT NULL,
                        `ackAttemptCount` INTEGER NOT NULL,
                        `lastAckAttemptAtEpochMillis` INTEGER,
                        `ackDeliveredAtEpochMillis` INTEGER,
                        PRIMARY KEY(`commandId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_device_commands_deviceId_serverSequence` ON `device_commands` (`deviceId`, `serverSequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_device_commands_state_serverSequence` ON `device_commands` (`state`, `serverSequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_device_commands_ackDeliveryState_serverSequence` ON `device_commands` (`ackDeliveryState`, `serverSequence`)",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `safety_samples` (
                        `sampleId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `sampleReference` INTEGER NOT NULL,
                        `monotonicMillis` INTEGER NOT NULL,
                        `validFlags` INTEGER NOT NULL,
                        `accelerationXMilliG` INTEGER,
                        `accelerationYMilliG` INTEGER,
                        `accelerationZMilliG` INTEGER,
                        `gyroXMilliDegreesPerSecond` INTEGER,
                        `gyroYMilliDegreesPerSecond` INTEGER,
                        `gyroZMilliDegreesPerSecond` INTEGER,
                        `electricFieldMilliVolts` INTEGER,
                        `pressurePascals` INTEGER,
                        `temperatureCentiCelsius` INTEGER,
                        `altitudeMillimetres` INTEGER,
                        `simulated` INTEGER NOT NULL,
                        `recordedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`sampleId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_safety_samples_deviceId_sampleReference` ON `safety_samples` (`deviceId`, `sampleReference`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_safety_samples_deviceId_recordedAtEpochMillis` ON `safety_samples` (`deviceId`, `recordedAtEpochMillis`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `safety_alerts` (
                        `messageId` TEXT NOT NULL,
                        `alertId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `alarmType` TEXT NOT NULL,
                        `severity` TEXT NOT NULL,
                        `active` INTEGER NOT NULL,
                        `configVersion` INTEGER,
                        `sampleReference` INTEGER,
                        `monotonicMillis` INTEGER NOT NULL,
                        `occurredAtEpochMillis` INTEGER NOT NULL,
                        `localActions` INTEGER NOT NULL,
                        `sensorFaults` INTEGER NOT NULL,
                        `simulated` INTEGER NOT NULL,
                        `sensorSnapshotJson` TEXT NOT NULL,
                        `latitude` REAL,
                        `longitude` REAL,
                        `horizontalAccuracyMeters` REAL,
                        `locationFixType` TEXT NOT NULL,
                        `evidenceAssetId` TEXT,
                        `deliveryState` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `lastAttemptAtEpochMillis` INTEGER,
                        `deliveredAtEpochMillis` INTEGER,
                        `lastError` TEXT,
                        PRIMARY KEY(`messageId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_safety_alerts_alertId_occurredAtEpochMillis` ON `safety_alerts` (`alertId`, `occurredAtEpochMillis`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_safety_alerts_deliveryState_occurredAtEpochMillis` ON `safety_alerts` (`deliveryState`, `occurredAtEpochMillis`)",
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_assets` ADD COLUMN `voiceSenderId` TEXT")
                db.execSQL("ALTER TABLE `media_assets` ADD COLUMN `voiceSenderRole` TEXT")
                db.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `voiceAllowedRoles` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL("ALTER TABLE `media_assets` ADD COLUMN `voiceCallId` TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `helmet_events_new` (
                        `messageId` TEXT NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `severity` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `occurredAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`messageId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `helmet_events_new` (
                        `messageId`, `eventType`, `severity`, `payloadJson`, `occurredAtEpochMillis`
                    )
                    SELECT `messageId`, `eventType`, `severity`, `payloadJson`, `occurredAtEpochMillis`
                    FROM `helmet_events`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `helmet_events`")
                db.execSQL("ALTER TABLE `helmet_events_new` RENAME TO `helmet_events`")
            }
        }
    }
}
