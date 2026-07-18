package com.example.devicesync.core.protocol

import com.example.devicesync.core.network.MAX_JSON_MESSAGE_SIZE
import com.example.devicesync.core.network.MAX_JSON_PAYLOAD_SIZE
import kotlinx.serialization.Serializable

@Serializable
data class ConnectionHelloPayload(
    val deviceName: String,
    val deviceType: String = "android",
    val appVersion: String,
    val protocolVersion: Int,
    val protocolMin: Int? = null,
    val protocolMax: Int? = null,
    val maxFrameBytes: Int = MAX_JSON_MESSAGE_SIZE + 4,
    val maxPayloadBytes: Int = MAX_JSON_PAYLOAD_SIZE,
    val capabilities: List<String>,
    val identityFingerprint: String? = null,
    val clientNonce: String? = null,
    val authVersion: Int = 0,
)
