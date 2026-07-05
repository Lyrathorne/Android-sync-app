package com.example.devicesync.core.network

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data class Connecting(
        val host: String,
        val port: Int,
    ) : ConnectionState

    data class Handshaking(
        val host: String,
        val port: Int,
    ) : ConnectionState

    data class Connected(
        val deviceId: String,
        val deviceName: String,
        val host: String,
        val port: Int,
        val acceptedProtocolVersion: Int,
        val capabilities: List<String>,
        val reconnectAttempt: Int = 0,
        val lastPongAtUtc: String? = null,
        val missedPongs: Int = 0,
        val pendingMessageCount: Int = 0,
    ) : ConnectionState

    data class Reconnecting(
        val deviceId: String,
        val host: String,
        val port: Int,
        val attempt: Int,
        val nextRetryMessage: String,
    ) : ConnectionState

    data object NetworkUnavailable : ConnectionState

    data class Failed(
        val message: String,
    ) : ConnectionState
}
