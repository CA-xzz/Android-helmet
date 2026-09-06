package com.example.helmet.service.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistentPreferencesSnapshotFixtureInstrumentedTest {
    @Test
    fun snapshotRoundTripRestoresExactTypedState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "recovery_snapshot_test_${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            assertTrue(
                preferences.edit()
                    .putString("ciphertext", "opaque-ciphertext")
                    .putLong("revision", 9L)
                    .putBoolean("enabled", true)
                    .putStringSet("modes", setOf("UART", "USB"))
                    .commit(),
            )
            val serialized = capturePreferenceFilesForRecoveryTest(context, listOf(name))
            assertFalse(serialized.contains("backend-bearer-secret"))

            assertTrue(preferences.edit().clear().putInt("replacement", 3).commit())
            restorePreferenceFilesForRecoveryTest(context, serialized)

            assertEquals("opaque-ciphertext", preferences.getString("ciphertext", null))
            assertEquals(9L, preferences.getLong("revision", -1L))
            assertTrue(preferences.getBoolean("enabled", false))
            assertEquals(setOf("UART", "USB"), preferences.getStringSet("modes", emptySet()))
            assertFalse(preferences.contains("replacement"))
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }

    @Test
    fun snapshotRejectsPlaintextCredentialKeys() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "recovery_snapshot_test_${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            assertTrue(preferences.edit().putString("backend_bearer_token", "backend-bearer-secret").commit())
            val rejected = runCatching {
                capturePreferenceFilesForRecoveryTest(context, listOf(name))
            }.exceptionOrNull()

            assertTrue(rejected is IllegalArgumentException)
            assertFalse(requireNotNull(rejected).message.orEmpty().contains("backend-bearer-secret"))
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }
}
