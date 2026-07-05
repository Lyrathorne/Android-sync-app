package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ConnectionHelloAckPayload(
    val deviceName: String,
    val deviceType: String,
    val acceptedProtocolVersion: Int,
    val capabilities: List<String>,
)
