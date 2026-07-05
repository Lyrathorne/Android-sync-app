package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable

@Serializable
data class PingPayload(
    val sequence: Long,
    val sentAtUtc: String,
)
