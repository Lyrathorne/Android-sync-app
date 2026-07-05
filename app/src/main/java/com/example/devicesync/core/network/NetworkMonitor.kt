package com.example.devicesync.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface NetworkState {
    data object Available : NetworkState
    data object Unavailable : NetworkState
}

class NetworkMonitor(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkState = MutableStateFlow(currentState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkState.value = NetworkState.Available
        }

        override fun onLost(network: Network) {
            _networkState.value = currentState()
        }

        override fun onUnavailable() {
            _networkState.value = NetworkState.Unavailable
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    private fun currentState(): NetworkState {
        return if (connectivityManager.activeNetwork == null) {
            NetworkState.Unavailable
        } else {
            NetworkState.Available
        }
    }
}
