package com.example.helmet.testfixture

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
class SharedPreferencesSnapshotFixtureInstrumentedTest {
    @Test
    fun serializedSnapshotRestoresExactStateWithoutPlaintextCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "app_snapshot_test_${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            assertTrue(
                preferences.edit()
                    .putString("ciphertext", "opaque-ciphertext")
                    .putString("iv", "opaque-iv")
                    .putLong("revision", 11L)
                    .commit(),
            )
            val serialized = capturePreferenceFilesForTest(context, listOf(name))
            assertTrue(preferences.edit().clear().putBoolean("changed", true).commit())

            restorePreferenceFilesForTest(context, serialized)

            assertEquals("opaque-ciphertext", preferences.getString("ciphertext", null))
            assertEquals("opaque-iv", preferences.getString("iv", null))
            assertEquals(11L, preferences.getLong("revision", -1L))
            assertFalse(preferences.contains("changed"))
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }

    @Test
    fun serializedSnapshotRejectsPlaintextTokenKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "app_snapshot_test_${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            assertTrue(preferences.edit().putString("authorization_token", "original-plain-token").commit())
            val rejected = runCatching {
                capturePreferenceFilesForTest(context, listOf(name))
            }.exceptionOrNull()

            assertTrue(rejected is IllegalArgumentException)
            assertFalse(requireNotNull(rejected).message.orEmpty().contains("original-plain-token"))
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }

    @Test
    fun persistentGuardRecoversAnInterruptedPreviousTestAndClearsEvidence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceName = "app_snapshot_test_${UUID.randomUUID()}"
        val evidenceName = "app_snapshot_evidence_${UUID.randomUUID()}"
        val source = context.getSharedPreferences(sourceName, Context.MODE_PRIVATE)
        val evidence = context.getSharedPreferences(evidenceName, Context.MODE_PRIVATE)
        try {
            assertTrue(source.edit().putString("mode", "ORIGINAL").commit())
            PersistentPreferencesTestGuard.restoreStale(context, evidenceName)
            PersistentPreferencesTestGuard.capture(context, evidenceName, listOf(sourceName))
            assertTrue(source.edit().clear().putString("mode", "TEST").commit())

            PersistentPreferencesTestGuard.restoreStale(context, evidenceName)

            assertEquals("ORIGINAL", source.getString("mode", null))
            assertTrue(evidence.all.isEmpty())
        } finally {
            assertTrue(source.edit().clear().commit())
            assertTrue(evidence.edit().clear().commit())
        }
    }
}
