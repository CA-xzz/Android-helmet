package com.example.helmet.feature.connectivity

enum class InternetState {
    UNAVAILABLE,
    LOCAL_ONLY,
    CAPTIVE_OR_UNVALIDATED,
    VALIDATED,
}

enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    BLUETOOTH,
    OTHER,
}

data class ConnectivitySnapshot(
    val state: InternetState,
    val transports: Set<NetworkTransport> = emptySet(),
    val metered: Boolean = false,
    val roaming: Boolean = false,
    val interfaceName: String? = null,
    val downstreamKbps: Int? = null,
    val upstreamKbps: Int? = null,
    val dnsServers: List<String> = emptyList(),
) {
    val hasValidatedInternet: Boolean
        get() = state == InternetState.VALIDATED
}

data class ConnectivitySignals(
    val hasActiveNetwork: Boolean,
    val hasInternetCapability: Boolean,
    val isValidated: Boolean,
    val isMetered: Boolean,
    val isNotRoaming: Boolean,
)

object ConnectivityClassifier {
    fun classify(signals: ConnectivitySignals): InternetState = when {
        !signals.hasActiveNetwork -> InternetState.UNAVAILABLE
        !signals.hasInternetCapability -> InternetState.LOCAL_ONLY
        !signals.isValidated -> InternetState.CAPTIVE_OR_UNVALIDATED
        else -> InternetState.VALIDATED
    }
}
