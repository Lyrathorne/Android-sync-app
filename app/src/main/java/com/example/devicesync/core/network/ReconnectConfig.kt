package com.example.devicesync.core.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.random.Random

data class ReconnectConfig(
    val initialDelay: Duration = 2.seconds,
    val maxDelay: Duration = 30.seconds,
    val maxAttempts: Int = Int.MAX_VALUE,
    val jitterProvider: (Int) -> Duration = { attempt ->
        Random.nextLong(0, (250L * attempt.coerceAtMost(8)).coerceAtLeast(1)).milliseconds
    },
) {
    fun delayForAttempt(attempt: Int): Duration {
        val multiplier = 1 shl (attempt - 1).coerceAtLeast(0).coerceAtMost(4)
        val base = initialDelay * multiplier
        return minOf(base, maxDelay) + jitterProvider(attempt)
    }
}
