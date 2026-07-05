package com.example.devicesync.core.network

sealed interface ConnectionEvent {
    data class Connected(val state: ConnectionState.Connected) : ConnectionEvent
    data class Failed(val message: String) : ConnectionEvent
    data object Disconnected : ConnectionEvent
}
