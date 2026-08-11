package com.example.helmet.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.core.model.RtkRuntimeConfig
import com.example.helmet.core.model.LocalIntercomRuntimeConfig
import com.example.helmet.core.model.CircleGeofence
import com.example.helmet.core.model.SafetyThresholdConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeConfigStoreInstrumentedTest {
    @Test
    fun configPersistsAcrossStoreInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RuntimeConfigStore(context)
        val original = store.load()
        val expected = RuntimeConfig(
            revision = 42,
            simulatorEnabled = false,
            hardwareDevicePath = "/dev/ttyAS4",
            hardwareBaudRate = 921_600,
            backendBaseUrl = "https://example.invalid",
            backendBearerToken = "test-token",
            mqttBrokerUri = "ssl://example.invalid:8883",
            mqttClientCertificateAlias = "helmet-test-certificate",
            rtk = RtkRuntimeConfig(
                enabled = true,
                ntripUrl = "https://ntrip.example.invalid/MOUNT",
                username = "helmet-42",
                password = "ntrip-secret",
            ),
            localIntercom = LocalIntercomRuntimeConfig(
                enabled = true,
                fallbackWhenInternetUnavailable = true,
                groupId = 42,
                channel = 7,
                keySlot = 3,
            ),
            geofences = listOf(CircleGeofence("yard", 31.2, 121.4, 100.0, 10.0, 3)),
            safetyThresholds = SafetyThresholdConfig(
                version = 7,
                heightThresholdMillimetres = 3_000,
                heightHysteresisMillimetres = 300,
            ),
        )

        try {
            store.save(expected)
            assertEquals(expected, RuntimeConfigStore(context).load())
            assertFalse(
                context.getSharedPreferences(
                    BackendCredentialStore.LEGACY_PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ).contains(BackendCredentialStore.LEGACY_TOKEN_KEY),
            )
            val encryptedPreferences = context.getSharedPreferences(
                BackendCredentialStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            assertTrue(encryptedPreferences.all.isNotEmpty())
            assertFalse(encryptedPreferences.all.values.any { value -> value.toString().contains("test-token") })
            val encryptedRtkPreferences = context.getSharedPreferences(
                RtkCredentialStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            assertTrue(encryptedRtkPreferences.all.isNotEmpty())
            assertFalse(encryptedRtkPreferences.all.values.any { value ->
                value.toString().contains("ntrip-secret")
            })
            assertFalse(
                context.getSharedPreferences(
                    BackendCredentialStore.LEGACY_PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ).all.values.any { value -> value.toString().contains("ntrip-secret") },
            )
        } finally {
            store.save(original)
        }
    }

    @Test
    fun plaintextCredentialIsMigratedOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RuntimeConfigStore(context)
        val original = store.load()
        val legacyPreferences = context.getSharedPreferences(
            BackendCredentialStore.LEGACY_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        try {
            store.save(original.copy(backendBearerToken = ""))
            assertTrue(
                legacyPreferences.edit()
                    .putString(BackendCredentialStore.LEGACY_TOKEN_KEY, "legacy-test-token")
                    .commit(),
            )
            assertEquals("legacy-test-token", RuntimeConfigStore(context).load().backendBearerToken)
            assertFalse(legacyPreferences.contains(BackendCredentialStore.LEGACY_TOKEN_KEY))
            assertEquals("legacy-test-token", RuntimeConfigStore(context).load().backendBearerToken)
        } finally {
            store.save(original)
        }
    }
}
