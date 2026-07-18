package com.example.devicesync.core.background

import com.example.devicesync.core.network.NetworkState
import com.example.devicesync.core.network.NetworkTransitionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundConnectionPolicyTest {
    @Test
    fun foregroundServiceRequiresExplicitStartOrPersistedOptIn() {
        assertFalse(BackgroundConnectionPolicy.shouldRun(explicitStart = false, backgroundWorkEnabled = false))
        assertTrue(BackgroundConnectionPolicy.shouldRun(explicitStart = true, backgroundWorkEnabled = false))
        assertTrue(BackgroundConnectionPolicy.shouldRun(explicitStart = false, backgroundWorkEnabled = true))
    }

    @Test
    fun connectedSessionRestartsOnNetworkLossOrNetworkSwitch() {
        val wifi = NetworkState.Available(1)
        val hotspot = NetworkState.Available(2)

        assertTrue(NetworkTransitionPolicy.requiresFreshConnection(wifi, NetworkState.Unavailable, connected = true))
        assertTrue(NetworkTransitionPolicy.requiresFreshConnection(wifi, hotspot, connected = true))
        assertFalse(NetworkTransitionPolicy.requiresFreshConnection(wifi, wifi, connected = true))
        assertFalse(NetworkTransitionPolicy.requiresFreshConnection(wifi, hotspot, connected = false))
        assertFalse(NetworkTransitionPolicy.requiresFreshConnection(NetworkState.Unavailable, hotspot, connected = false))
    }
}
