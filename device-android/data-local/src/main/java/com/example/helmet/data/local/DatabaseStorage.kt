package com.example.helmet.data.local

internal object DatabaseStorage {
    private val auditedTables = listOf(
        "helmet_events",
        "media_assets",
        "track_points",
        "call_sessions",
        "text_broadcasts",
        "device_commands",
        "safety_samples",
        "safety_alerts",
        "call_media_recovery",
        "call_state_outbox",
        "hardware_key_actions",
        "safety_detection_checkpoints",
    )

    fun audit(database: HelmetDatabase): DatabaseStorageAudit {
        val handle = database.openHelper.readableDatabase
        val quickCheck = handle.query("PRAGMA quick_check").use { cursor ->
            cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
        }
        return DatabaseStorageAudit(
            quickCheck = quickCheck,
            userVersion = handle.version,
            tableCounts = auditedTables.associateWith { table ->
                handle.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    check(cursor.moveToFirst()) { "database count query returned no row" }
                    cursor.getLong(0)
                }
            },
        ).also { audit ->
            check(audit.quickCheck) { "database integrity audit failed" }
        }
    }
}

data class DatabaseStorageAudit(
    val quickCheck: Boolean,
    val userVersion: Int,
    val tableCounts: Map<String, Long>,
)
