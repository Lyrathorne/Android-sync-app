package com.example.devicesync.core.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class HeartbeatConfig(
    val pingInterval: Duration = 15.seconds,
    val pongTimeout: Duration = 10.seconds,
    val maxMissedPongs: Int = 3,
)
