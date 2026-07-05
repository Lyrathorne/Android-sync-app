package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable

@Serializable
data class PongPayload(
    val sequence: Long,
    val receivedAtUtc: String,
)
