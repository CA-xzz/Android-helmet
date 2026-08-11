package com.example.helmet.feature.location

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

data class LocationCapabilities(
    val providers: List<String>,
    val enabledProviders: List<String>,
    val hasGnssProvider: Boolean,
    val hasFusedProvider: Boolean,
    val hasNetworkProvider: Boolean,
    val hasFinePermission: Boolean,
    val hasCoarsePermission: Boolean,
) {
    val canRequestLocation: Boolean
        get() = enabledProviders.isNotEmpty() && (hasFinePermission || hasCoarsePermission)
}

class LocationCapabilityInspector(context: Context) {
    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(LocationManager::class.java)

    fun inspect(): LocationCapabilities {
        val providers = runCatching { manager.allProviders.sorted() }.getOrDefault(emptyList())
        val enabled = providers.filter { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
        return LocationCapabilities(
            providers = providers,
            enabledProviders = enabled,
            hasGnssProvider = LocationManager.GPS_PROVIDER in providers,
            hasFusedProvider = LocationManager.FUSED_PROVIDER in providers,
            hasNetworkProvider = LocationManager.NETWORK_PROVIDER in providers,
            hasFinePermission = hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION),
            hasCoarsePermission = hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, permission) == PackageManager.PERMISSION_GRANTED
}
