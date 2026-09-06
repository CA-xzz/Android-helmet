package com.example.helmet.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseStorageInstrumentedTest {
    @Test
    fun roomDatabaseUsesPlainSQLiteAndPassesIntegrityAudit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DATABASE_NAME)
        val database = Room.databaseBuilder(context, HelmetDatabase::class.java, DATABASE_NAME).build()
        try {
            val audit = database.storageAudit()
            assertTrue(audit.quickCheck)
            assertEquals(DATABASE_VERSION, audit.userVersion)

            val header = context.getDatabasePath(DATABASE_NAME).inputStream().use { input ->
                ByteArray(SQLITE_HEADER.size).also { bytes ->
                    assertEquals(bytes.size, input.read(bytes))
                }
            }
            assertTrue(header.contentEquals(SQLITE_HEADER))
        } finally {
            database.close()
            context.deleteDatabase(DATABASE_NAME)
        }
    }

    companion object {
        private const val DATABASE_NAME = "helmet.db"
        private const val DATABASE_VERSION = 15
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
