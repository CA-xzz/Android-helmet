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
        DeviceCommandCursorEntity::class,
        ActiveCommandStreamEntity::class,
        QuarantinedCommandStreamEntity::class,
        CommandStreamAdoptionEntity::class,
        CommandStreamAdoptionRowEntity::class,
        CommandStreamLineageEntity::class,
        SafetySampleEntity::class,
        SafetyAlertEntity::class,
        CallMediaRecoveryEntity::class,
        CallStateOutboxEntity::class,
        HardwareKeyActionEntity::class,
        SafetyDetectionCheckpointEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
abstract class HelmetDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun mediaDao(): MediaDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun callSessionDao(): CallSessionDao
    abstract fun textBroadcastDao(): TextBroadcastDao
    abstract fun deviceCommandDao(): DeviceCommandDao
    abstract fun activeCommandStreamDao(): ActiveCommandStreamDao
    abstract fun quarantinedCommandStreamDao(): QuarantinedCommandStreamDao
    abstract fun commandStreamAdoptionDao(): CommandStreamAdoptionDao
    abstract fun safetyDao(): SafetyDao
    abstract fun callMediaRecoveryDao(): CallMediaRecoveryDao
    abstract fun callStateOutboxDao(): CallStateOutboxDao
    abstract fun hardwareKeyActionDao(): HardwareKeyActionDao

    fun storageAudit(): DatabaseStorageAudit = DatabaseStorage.audit(this)

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
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
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

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `call_media_recovery` (
                        `callId` TEXT NOT NULL,
                        `generation` INTEGER NOT NULL,
                        `lastRemoteSequence` INTEGER NOT NULL,
                        `latestOfferSequence` INTEGER,
                        `latestAnsweredOfferSequence` INTEGER,
                        `restartAttemptCount` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        `lastError` TEXT,
                        PRIMARY KEY(`callId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `call_state_outbox` (
                        `callId` TEXT NOT NULL,
                        `stateSequence` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `actorId` TEXT NOT NULL,
                        `occurredAtEpochMillis` INTEGER NOT NULL,
                        `reason` TEXT,
                        `deliveryState` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `lastAttemptAtEpochMillis` INTEGER,
                        `deliveredAtEpochMillis` INTEGER,
                        `lastError` TEXT,
                        PRIMARY KEY(`callId`, `stateSequence`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_state_outbox_deliveryState_occurredAtEpochMillis` " +
                        "ON `call_state_outbox` (`deliveryState`, `occurredAtEpochMillis`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hardware_key_actions` (
                        `actionId` TEXT NOT NULL,
                        `input` TEXT NOT NULL,
                        `plannedAction` TEXT NOT NULL,
                        `rejectionReason` TEXT,
                        `targetCallId` TEXT,
                        `simulated` INTEGER NOT NULL,
                        `monotonicMillis` INTEGER NOT NULL,
                        `hardwareEventId` INTEGER,
                        `sequence` INTEGER,
                        `state` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `receivedAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`actionId`)
                    )
                    """.trimIndent(),
                )
                // v9 retained only the latest call state and accepted a response by call ID and
                // state name. Its DELIVERED flag therefore does not prove that the local sequence
                // equals the server sequence. Keep the original snapshot for diagnosis, but force
                // every non-initial outgoing state through online server reconciliation.
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO call_state_outbox
                    SELECT callId, 1, 'REQUESTED', deviceId, createdAtEpochMillis, NULL,
                        CASE
                            WHEN state = 'REQUESTED' THEN deliveryState
                            ELSE 'PENDING'
                        END,
                        CASE WHEN state = 'REQUESTED' THEN attemptCount ELSE 0 END,
                        CASE WHEN state = 'REQUESTED' THEN lastAttemptAtEpochMillis ELSE NULL END,
                        CASE WHEN state = 'REQUESTED' AND deliveryState = 'DELIVERED'
                            THEN deliveredAtEpochMillis ELSE NULL END,
                        NULL
                    FROM call_sessions WHERE direction = 'OUTGOING_DEVICE'
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO call_state_outbox
                    SELECT callId, stateSequence, state, deviceId, updatedAtEpochMillis, lastReason,
                        'PENDING', attemptCount, lastAttemptAtEpochMillis,
                        NULL, 'MIGRATED_V9_RECONCILIATION_REQUIRED'
                    FROM call_sessions
                    WHERE direction = 'OUTGOING_DEVICE' AND state != 'REQUESTED'
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `hardware_key_actions` ADD COLUMN `plannedArgument` TEXT")
                db.execSQL("ALTER TABLE `hardware_key_actions` ADD COLUMN `businessRequestId` INTEGER")
                // v10 persisted a relative toggle/volume instruction. Its target cannot be
                // reconstructed after a process death without risking the opposite side effect.
                db.execSQL(
                    """
                    UPDATE hardware_key_actions
                    SET state = 'FAILED',
                        attemptCount = attemptCount + 1,
                        lastError = 'UNSAFE_V10_RELATIVE_KEY_PLAN',
                        updatedAtEpochMillis = MAX(updatedAtEpochMillis, receivedAtEpochMillis)
                    WHERE state = 'RECEIVED' AND (
                        plannedAction = 'LOCAL_INTERCOM_TOGGLE' OR
                        input IN ('VOLUME_UP', 'VOLUME_DOWN')
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE hardware_key_actions
                    SET state = 'FAILED',
                        attemptCount = attemptCount + 1,
                        lastError = 'UNSAFE_V10_MISSING_CALL_ID',
                        updatedAtEpochMillis = MAX(updatedAtEpochMillis, receivedAtEpochMillis)
                    WHERE state = 'RECEIVED' AND
                        plannedAction IN ('CALL_START', 'SOS') AND targetCallId IS NULL
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Null deliberately means unknown legacy provenance. It must never be
                // replaced with the current threshold version during migration.
                db.execSQL("ALTER TABLE `safety_samples` ADD COLUMN `thresholdConfigVersion` INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `safety_detection_checkpoints` (
                        `deviceId` TEXT NOT NULL,
                        `schemaVersion` INTEGER NOT NULL,
                        `algorithmVersion` INTEGER NOT NULL,
                        `thresholdConfigVersion` INTEGER NOT NULL,
                        `lastSampleReference` INTEGER NOT NULL,
                        `lastMonotonicMillis` INTEGER NOT NULL,
                        `payload` TEXT NOT NULL,
                        `payloadSha256` TEXT NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`deviceId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v12 samples/checkpoints did not bind the complete threshold content.
                // Null keeps that provenance isolated instead of guessing a fingerprint.
                db.execSQL("ALTER TABLE `safety_samples` ADD COLUMN `thresholdConfigFingerprint` TEXT")
                db.execSQL(
                    "ALTER TABLE `safety_samples` ADD COLUMN `derivationCommitted` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("UPDATE `safety_samples` SET `thresholdConfigVersion` = NULL")
                db.execSQL(
                    "ALTER TABLE `safety_detection_checkpoints` ADD COLUMN `thresholdConfigFingerprint` TEXT",
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_safety_samples_deviceId_thresholdConfigVersion_thresholdConfigFingerprint_derivationCommitted`
                    ON `safety_samples` (
                        `deviceId`, `thresholdConfigVersion`,
                        `thresholdConfigFingerprint`, `derivationCommitted`
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `device_commands` ADD COLUMN `commandStreamId` TEXT NOT NULL " +
                        "DEFAULT '${DeviceCommandStore.LEGACY_COMMAND_STREAM_ID}'",
                )
                db.execSQL(
                    "ALTER TABLE `text_broadcasts` ADD COLUMN `commandStreamId` TEXT NOT NULL " +
                        "DEFAULT '${DeviceCommandStore.LEGACY_COMMAND_STREAM_ID}'",
                )
                db.execSQL("DROP INDEX IF EXISTS `index_text_broadcasts_deviceId_serverSequence`")
                db.execSQL("DROP INDEX IF EXISTS `index_text_broadcasts_receiptDeliveryState_serverSequence`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_text_broadcasts_commandStreamId_deviceId_serverSequence` " +
                        "ON `text_broadcasts` (`commandStreamId`, `deviceId`, `serverSequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_text_broadcasts_commandStreamId_deviceId_receiptDeliveryState_serverSequence` " +
                        "ON `text_broadcasts` " +
                        "(`commandStreamId`, `deviceId`, `receiptDeliveryState`, `serverSequence`)",
                )
                db.execSQL("DROP INDEX IF EXISTS `index_device_commands_deviceId_serverSequence`")
                db.execSQL("DROP INDEX IF EXISTS `index_device_commands_state_serverSequence`")
                db.execSQL("DROP INDEX IF EXISTS `index_device_commands_ackDeliveryState_serverSequence`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_device_commands_commandStreamId_deviceId_serverSequence` " +
                        "ON `device_commands` (`commandStreamId`, `deviceId`, `serverSequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_device_commands_commandStreamId_deviceId_state_serverSequence` " +
                        "ON `device_commands` (`commandStreamId`, `deviceId`, `state`, `serverSequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_device_commands_commandStreamId_deviceId_ackDeliveryState_serverSequence` " +
                        "ON `device_commands` (`commandStreamId`, `deviceId`, `ackDeliveryState`, `serverSequence`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `device_command_cursors` (
                        `commandStreamId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `afterSequence` INTEGER NOT NULL,
                        PRIMARY KEY(`commandStreamId`, `deviceId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO device_command_cursors (commandStreamId, deviceId, afterSequence)
                    SELECT '${DeviceCommandStore.LEGACY_COMMAND_STREAM_ID}', deviceId, MAX(serverSequence)
                    FROM device_commands
                    GROUP BY deviceId
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `active_command_streams` (
                        `deviceId` TEXT NOT NULL,
                        `commandStreamId` TEXT NOT NULL,
                        PRIMARY KEY(`deviceId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `quarantined_command_streams` (
                        `commandStreamId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `requiredHighWater` INTEGER NOT NULL,
                        `observedHighWater` INTEGER NOT NULL,
                        `reason` TEXT NOT NULL,
                        PRIMARY KEY(`commandStreamId`, `deviceId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "ALTER TABLE `device_command_cursors` RENAME TO `device_command_cursors_v14`",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `device_command_cursors` (
                        `commandStreamId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `afterSequence` INTEGER NOT NULL,
                        `maxObservedHighWater` INTEGER NOT NULL,
                        PRIMARY KEY(`commandStreamId`, `deviceId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `device_command_cursors` (
                        `commandStreamId`, `deviceId`, `afterSequence`, `maxObservedHighWater`
                    )
                    SELECT `commandStreamId`, `deviceId`, `afterSequence`, `afterSequence`
                    FROM `device_command_cursors_v14`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `device_command_cursors_v14`")
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `active_command_streams` (`deviceId`, `commandStreamId`)
                    SELECT `deviceId`, MIN(`commandStreamId`)
                    FROM `device_command_cursors`
                    GROUP BY `deviceId`
                    HAVING COUNT(*) = 1
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE `device_commands` RENAME TO `device_commands_v14`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `device_commands` (
                        `commandId` TEXT NOT NULL,
                        `commandStreamId` TEXT NOT NULL DEFAULT '${DeviceCommandStore.LEGACY_COMMAND_STREAM_ID}',
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
                        `ackLastError` TEXT,
                        PRIMARY KEY(`commandStreamId`, `commandId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `device_commands` (
                        `commandId`, `commandStreamId`, `deviceId`, `serverSequence`, `type`,
                        `payloadJson`, `createdAtEpochMillis`, `state`, `receivedAtEpochMillis`,
                        `appliedAtEpochMillis`, `lastError`, `ackDeliveryState`, `ackAttemptCount`,
                        `lastAckAttemptAtEpochMillis`, `ackDeliveredAtEpochMillis`, `ackLastError`
                    )
                    SELECT
                        `commandId`, `commandStreamId`, `deviceId`, `serverSequence`, `type`,
                        `payloadJson`, `createdAtEpochMillis`, `state`, `receivedAtEpochMillis`,
                        `appliedAtEpochMillis`,
                        CASE
                            WHEN `state` = 'FAILED' AND (
                                `ackDeliveryState` IN ('FAILED', 'REJECTED') OR
                                `ackAttemptCount` > 1 OR
                                (`ackDeliveryState` = 'PENDING' AND `ackAttemptCount` > 0)
                            ) THEN
                                'LEGACY_COMMAND_FAILURE_REASON_UNAVAILABLE'
                            WHEN `state` = 'FAILED' THEN `lastError`
                            ELSE NULL
                        END,
                        `ackDeliveryState`, `ackAttemptCount`,
                        `lastAckAttemptAtEpochMillis`, `ackDeliveredAtEpochMillis`,
                        CASE
                            WHEN `ackDeliveryState` IN ('FAILED', 'REJECTED') THEN `lastError`
                            ELSE NULL
                        END
                    FROM `device_commands_v14`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `device_commands_v14`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_device_commands_commandStreamId_deviceId_serverSequence` " +
                        "ON `device_commands` (`commandStreamId`, `deviceId`, `serverSequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_device_commands_commandStreamId_deviceId_state_serverSequence` " +
                        "ON `device_commands` (`commandStreamId`, `deviceId`, `state`, `serverSequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_device_commands_commandStreamId_deviceId_ackDeliveryState_serverSequence` " +
                        "ON `device_commands` " +
                        "(`commandStreamId`, `deviceId`, `ackDeliveryState`, `serverSequence`)",
                )

                db.execSQL("ALTER TABLE `text_broadcasts` RENAME TO `text_broadcasts_v14`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `text_broadcasts` (
                        `broadcastId` TEXT NOT NULL,
                        `commandStreamId` TEXT NOT NULL DEFAULT '${DeviceCommandStore.LEGACY_COMMAND_STREAM_ID}',
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
                        `receiptLastError` TEXT,
                        `receiptDeliveryState` TEXT NOT NULL,
                        `receiptAttemptCount` INTEGER NOT NULL,
                        `lastReceiptAttemptAtEpochMillis` INTEGER,
                        `receiptDeliveredAtEpochMillis` INTEGER,
                        PRIMARY KEY(`commandStreamId`, `broadcastId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `text_broadcasts` (
                        `broadcastId`, `commandStreamId`, `deviceId`, `serverSequence`, `text`,
                        `language`, `priority`, `expiresAtEpochMillis`, `playbackState`,
                        `receivedAtEpochMillis`, `playingAtEpochMillis`, `playedAtEpochMillis`,
                        `lastError`, `receiptLastError`, `receiptDeliveryState`, `receiptAttemptCount`,
                        `lastReceiptAttemptAtEpochMillis`, `receiptDeliveredAtEpochMillis`
                    )
                    SELECT
                        `broadcastId`, `commandStreamId`, `deviceId`, `serverSequence`, `text`,
                        `language`, `priority`, `expiresAtEpochMillis`, `playbackState`,
                        `receivedAtEpochMillis`, `playingAtEpochMillis`, `playedAtEpochMillis`,
                        CASE
                            WHEN `receiptDeliveryState` != 'REJECTED' THEN `lastError`
                            WHEN `playbackState` = 'FAILED' THEN
                                'LEGACY_BROADCAST_FAILURE_REASON_UNAVAILABLE'
                            ELSE NULL
                        END,
                        CASE
                            WHEN `receiptDeliveryState` = 'REJECTED' THEN `lastError`
                            ELSE NULL
                        END,
                        `receiptDeliveryState`, `receiptAttemptCount`,
                        `lastReceiptAttemptAtEpochMillis`, `receiptDeliveredAtEpochMillis`
                    FROM `text_broadcasts_v14`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `text_broadcasts_v14`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_text_broadcasts_commandStreamId_deviceId_serverSequence` " +
                        "ON `text_broadcasts` (`commandStreamId`, `deviceId`, `serverSequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_text_broadcasts_commandStreamId_deviceId_receiptDeliveryState_serverSequence` " +
                        "ON `text_broadcasts` " +
                        "(`commandStreamId`, `deviceId`, `receiptDeliveryState`, `serverSequence`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `command_stream_adoptions` (
                        `targetStreamId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `sourceStreamId` TEXT NOT NULL,
                        `sourceCursor` INTEGER NOT NULL,
                        `observedHighWater` INTEGER NOT NULL,
                        `adoptionThroughSequence` INTEGER NOT NULL,
                        `nextSequence` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `sourceRowCount` INTEGER NOT NULL,
                        `sourceFingerprint` TEXT NOT NULL,
                        PRIMARY KEY(`targetStreamId`, `deviceId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `command_stream_adoption_rows` (
                        `targetStreamId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `sequence` INTEGER NOT NULL,
                        `commandId` TEXT NOT NULL,
                        `immutableDigest` TEXT NOT NULL,
                        `serverAcknowledged` INTEGER NOT NULL,
                        PRIMARY KEY(`targetStreamId`, `deviceId`, `sequence`),
                        FOREIGN KEY(`targetStreamId`, `deviceId`)
                            REFERENCES `command_stream_adoptions`(`targetStreamId`, `deviceId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_command_stream_adoption_rows_targetStreamId_deviceId_commandId` " +
                        "ON `command_stream_adoption_rows` (`targetStreamId`, `deviceId`, `commandId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `command_stream_lineage` (
                        `targetStreamId` TEXT NOT NULL,
                        `sourceStreamId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        PRIMARY KEY(`targetStreamId`, `sourceStreamId`, `deviceId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_command_stream_lineage_targetStreamId_deviceId` " +
                        "ON `command_stream_lineage` (`targetStreamId`, `deviceId`)",
                )
            }
        }
    }
}
