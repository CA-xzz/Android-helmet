package com.example.helmet.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceIdentityStoreInstrumentedTest {
    @Test
    fun deviceIdPersistsAcrossStoreInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(DeviceIdentityStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        val first = DeviceIdentityStore(context).getOrCreateDeviceId()
        val second = DeviceIdentityStore(context).getOrCreateDeviceId()

        assertEquals(first, second)
        assertTrue(first.isNotBlank())
    }
}
