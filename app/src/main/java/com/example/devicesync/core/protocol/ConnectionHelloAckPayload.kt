package com.example.devicesync.core.protocol

import com.example.devicesync.core.network.MAX_JSON_MESSAGE_SIZE
import com.example.devicesync.core.network.MAX_JSON_PAYLOAD_SIZE
import kotlinx.serialization.Serializable

@Serializable
data class ConnectionHelloAckPayload(
    val deviceName: String,
    val deviceType: String,
    val acceptedProtocolVersion: Int,
    val protocolMin: Int = 1,
    val protocolMax: Int = 1,
    val maxFrameBytes: Int = MAX_JSON_MESSAGE_SIZE + 4,
    val maxPayloadBytes: Int = MAX_JSON_PAYLOAD_SIZE,
    val capabilities: List<String>,
)
