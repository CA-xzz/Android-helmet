package com.example.helmet.feature.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidNetworkMonitor(context: Context) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutableSnapshot = MutableStateFlow(inspectCurrent())
    private var registered = false

    val snapshot: StateFlow<ConnectivitySnapshot> = mutableSnapshot.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refresh()
    }

    fun start() {
        if (registered) return
        manager.registerDefaultNetworkCallback(callback)
        registered = true
        refresh()
    }

    fun inspectCurrent(): ConnectivitySnapshot {
        val network = manager.activeNetwork ?: return ConnectivitySnapshot(InternetState.UNAVAILABLE)
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return ConnectivitySnapshot(InternetState.UNAVAILABLE)
        val links = manager.getLinkProperties(network)
        val signals = ConnectivitySignals(
            hasActiveNetwork = true,
            hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            isNotRoaming = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
        )
        return ConnectivitySnapshot(
            state = ConnectivityClassifier.classify(signals),
            transports = buildSet {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkTransport.WIFI)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetworkTransport.CELLULAR)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetworkTransport.ETHERNET)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetworkTransport.VPN)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add(NetworkTransport.BLUETOOTH)
                if (isEmpty()) add(NetworkTransport.OTHER)
            },
            metered = signals.isMetered,
            roaming = !signals.isNotRoaming,
            interfaceName = links?.interfaceName,
            downstreamKbps = capabilities.linkDownstreamBandwidthKbps.takeIf { it > 0 },
            upstreamKbps = capabilities.linkUpstreamBandwidthKbps.takeIf { it > 0 },
            dnsServers = links?.dnsServers?.map { it.hostAddress.orEmpty() }?.filter(String::isNotBlank).orEmpty(),
        )
    }

    override fun close() {
        if (!registered) return
        runCatching { manager.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun refresh() {
        mutableSnapshot.value = inspectCurrent()
    }
}
