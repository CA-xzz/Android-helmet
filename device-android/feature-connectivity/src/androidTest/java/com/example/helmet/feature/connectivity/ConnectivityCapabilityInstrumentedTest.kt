package com.example.helmet.feature.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectivityCapabilityInstrumentedTest {
    @Test
    fun snapshotMatchesAndroidActiveNetwork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val snapshot = AndroidNetworkMonitor(context).inspectCurrent()

        assertEquals(network != null && capabilities != null, snapshot.state != InternetState.UNAVAILABLE)
        assertEquals(
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            snapshot.hasValidatedInternet,
        )
    }
}
