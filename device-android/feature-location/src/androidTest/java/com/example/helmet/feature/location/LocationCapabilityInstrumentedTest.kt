package com.example.helmet.feature.location

import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationCapabilityInstrumentedTest {
    @Test
    fun capabilityReportMatchesLocationManager() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(LocationManager::class.java)
        val capabilities = LocationCapabilityInspector(context).inspect()

        assertEquals(manager.allProviders.sorted(), capabilities.providers)
        if (LocationManager.GPS_PROVIDER !in manager.allProviders) {
            assertFalse(capabilities.hasGnssProvider)
        }
    }
}
