package com.example.devicesync.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface NetworkState {
    data class Available(val networkHandle: Long) : NetworkState
    data object Unavailable : NetworkState
}

data class NetworkEnvironmentDiagnostics(
    val availableNetworkCount: Int = 0,
    val transportKinds: Set<String> = emptySet(),
    val hasVpn: Boolean = false,
    val hasCaptivePortal: Boolean = false,
    val isMetered: Boolean = false,
) {
    fun privacySafeSummary(): String =
        "Networks: $availableNetworkCount\n" +
            "Transports: ${transportKinds.sorted().joinToString().ifBlank { "none" }}\n" +
            "VPN excluded by default: $hasVpn\n" +
            "Captive portal detected: $hasCaptivePortal\n" +
            "Metered active network: $isMetered"
}

interface NetworkStateSource {
    val networkState: StateFlow<NetworkState>
}

class NetworkMonitor(context: Context) : NetworkStateSource, Closeable {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkState = MutableStateFlow(currentState())
    override val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()
    private val _diagnostics = MutableStateFlow(currentDiagnostics())
    val diagnostics: StateFlow<NetworkEnvironmentDiagnostics> = _diagnostics.asStateFlow()
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkState.value = NetworkState.Available(network.networkHandle)
            _diagnostics.value = currentDiagnostics()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _networkState.value = NetworkState.Available(network.networkHandle)
            _diagnostics.value = currentDiagnostics()
        }

        override fun onLost(network: Network) {
            _networkState.value = currentState()
            _diagnostics.value = currentDiagnostics()
        }

        override fun onUnavailable() {
            _networkState.value = NetworkState.Unavailable
            _diagnostics.value = currentDiagnostics()
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(callback)
        registered = true
    }

    private fun currentState(): NetworkState {
        return if (connectivityManager.activeNetwork == null) {
            NetworkState.Unavailable
        } else {
            NetworkState.Available(connectivityManager.activeNetwork!!.networkHandle)
        }
    }

    private fun currentDiagnostics(): NetworkEnvironmentDiagnostics {
        val networks = connectivityManager.allNetworks
        val capabilities = networks.mapNotNull(connectivityManager::getNetworkCapabilities)
        val kinds = buildSet {
            capabilities.forEach { value ->
                if (value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi/hotspot")
                if (value.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet/USB tethering")
                if (value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
                if (value.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
            }
        }
        return NetworkEnvironmentDiagnostics(
            availableNetworkCount = networks.size,
            transportKinds = kinds,
            hasVpn = capabilities.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
            hasCaptivePortal = capabilities.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) },
            isMetered = connectivityManager.isActiveNetworkMetered,
        )
    }

    override fun close() {
        if (!registered) return
        registered = false
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }
}

object NetworkTransitionPolicy {
    fun requiresFreshConnection(previous: NetworkState, current: NetworkState, connected: Boolean): Boolean {
        if (!connected) return false
        if (current is NetworkState.Unavailable) return true
        return previous is NetworkState.Available && current is NetworkState.Available &&
            previous.networkHandle != current.networkHandle
    }
}
