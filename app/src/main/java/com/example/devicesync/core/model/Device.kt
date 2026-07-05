package com.example.devicesync.core.model

data class Device(
    val id: String,
    val name: String,
    val connectionStatus: ConnectionStatus,
    val lastConnectedText: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val acceptedProtocolVersion: Int? = null,
    val capabilities: List<String> = emptyList(),
)
