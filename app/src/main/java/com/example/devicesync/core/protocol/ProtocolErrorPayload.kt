package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ProtocolErrorPayload(
    val code: String,
    val message: String? = null,
    val fatal: Boolean = true,
)
