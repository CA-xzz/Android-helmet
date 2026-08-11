package com.example.helmet.service.runtime

import com.example.helmet.feature.connectivity.ConnectivitySnapshot
import com.example.helmet.feature.connectivity.InternetState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallBandwidthPolicyTest {
    @Test
    fun unavailableMeteredRoamingAndConstrainedNetworksUseLowBandwidth() {
        assertTrue(shouldUseCallLowBandwidthMode(snapshot(InternetState.UNAVAILABLE)))
        assertTrue(shouldUseCallLowBandwidthMode(snapshot(metered = true)))
        assertTrue(shouldUseCallLowBandwidthMode(snapshot(roaming = true)))
        assertTrue(shouldUseCallLowBandwidthMode(snapshot(downstreamKbps = 999)))
        assertTrue(shouldUseCallLowBandwidthMode(snapshot(upstreamKbps = 499)))
        assertFalse(shouldUseCallLowBandwidthMode(snapshot(downstreamKbps = 1_000, upstreamKbps = 500)))
    }

    @Test
    fun policyRetainsLatestModeUntilAnEngineCanApplyIt() {
        val policy = CallBandwidthPolicy()
        var applied: Boolean? = null

        policy.update(true)
        assertTrue(policy.apply { enabled -> applied = enabled; true })
        assertTrue(applied == true)

        policy.update(false)
        assertTrue(policy.apply { enabled -> applied = enabled; true })
        assertFalse(applied == true)
    }

    private fun snapshot(
        state: InternetState = InternetState.VALIDATED,
        metered: Boolean = false,
        roaming: Boolean = false,
        downstreamKbps: Int? = null,
        upstreamKbps: Int? = null,
    ) = ConnectivitySnapshot(
        state = state,
        metered = metered,
        roaming = roaming,
        downstreamKbps = downstreamKbps,
        upstreamKbps = upstreamKbps,
    )
}
