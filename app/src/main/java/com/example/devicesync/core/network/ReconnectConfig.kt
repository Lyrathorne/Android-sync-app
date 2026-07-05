package com.example.devicesync.core.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ReconnectConfig(
    val initialDelay: Duration = 2.seconds,
    val maxDelay: Duration = 30.seconds,
    val maxAttempts: Int = 10,
    val jitterProvider: (Int) -> Duration = { 0.seconds },
) {
    fun delayForAttempt(attempt: Int): Duration {
        val multiplier = 1 shl (attempt - 1).coerceAtLeast(0).coerceAtMost(4)
        val base = initialDelay * multiplier
        return minOf(base, maxDelay) + jitterProvider(attempt)
    }
}
