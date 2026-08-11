package com.example.helmet.feature.connectivity

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectivityClassifierTest {
    @Test
    fun distinguishesUnavailableLocalUnvalidatedAndValidatedNetworks() {
        assertEquals(InternetState.UNAVAILABLE, classify(active = false, internet = false, validated = false))
        assertEquals(InternetState.LOCAL_ONLY, classify(active = true, internet = false, validated = false))
        assertEquals(InternetState.CAPTIVE_OR_UNVALIDATED, classify(active = true, internet = true, validated = false))
        assertEquals(InternetState.VALIDATED, classify(active = true, internet = true, validated = true))
    }

    private fun classify(active: Boolean, internet: Boolean, validated: Boolean): InternetState =
        ConnectivityClassifier.classify(
            ConnectivitySignals(
                hasActiveNetwork = active,
                hasInternetCapability = internet,
                isValidated = validated,
                isMetered = false,
                isNotRoaming = true,
            ),
        )
}
