package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ConnectionHelloPayload(
    val deviceName: String,
    val deviceType: String = "android",
    val appVersion: String,
    val protocolVersion: Int,
    val capabilities: List<String>,
    val identityFingerprint: String? = null,
    val clientNonce: String? = null,
    val authVersion: Int = 0,
)
