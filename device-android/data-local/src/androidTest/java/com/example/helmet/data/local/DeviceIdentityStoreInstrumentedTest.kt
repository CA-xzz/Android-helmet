package com.example.helmet.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class DeviceIdentityStoreInstrumentedTest {
    @Test
    fun deviceIdPersistsAcrossStoreInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferencesName = "helmet_identity_test_${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

        try {
            val first = DeviceIdentityStore(context, preferencesName).getOrCreateDeviceId()
            val second = DeviceIdentityStore(context, preferencesName).getOrCreateDeviceId()

            assertEquals(first, second)
            assertTrue(first.isNotBlank())
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }

    @Test
    fun corruptOrInvalidStoredIdentityIsReplacedOnceInIsolatedPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferencesName = "helmet_identity_test_${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        try {
            assertTrue(preferences.edit().putInt(DeviceIdentityStore.KEY_DEVICE_ID, 7).commit())
            val recovered = DeviceIdentityStore(context, preferencesName).getOrCreateDeviceId()
            assertEquals(recovered, DeviceIdentityStore(context, preferencesName).getOrCreateDeviceId())
            assertTrue(recovered.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))

            assertTrue(
                preferences.edit().putString(DeviceIdentityStore.KEY_DEVICE_ID, "invalid\nidentity").commit(),
            )
            val replaced = DeviceIdentityStore(context, preferencesName).getOrCreateDeviceId()
            assertTrue(replaced != "invalid\nidentity")
            assertEquals(replaced, DeviceIdentityStore(context, preferencesName).getOrCreateDeviceId())
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }

    @Test
    fun concurrentCreationUsesOnePersistedIdentityInIsolatedPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferencesName = "helmet_identity_test_${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        try {
            val ids = (1..40).map {
                async(Dispatchers.IO) {
                    DeviceIdentityStore(context, preferencesName).getOrCreateDeviceId()
                }
            }.awaitAll()

            assertEquals(1, ids.toSet().size)
            assertEquals(ids.first(), preferences.getString(DeviceIdentityStore.KEY_DEVICE_ID, null))
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }
}
