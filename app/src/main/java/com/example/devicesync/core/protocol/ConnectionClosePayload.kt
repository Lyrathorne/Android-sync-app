package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ConnectionClosePayload(
    val reason: String? = null,
    val allowReconnect: Boolean = true,
)
