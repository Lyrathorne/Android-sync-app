package com.example.devicesync.core.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class AckConfig(
    val timeout: Duration = 5.seconds,
    val maxAttempts: Int = 3,
)
