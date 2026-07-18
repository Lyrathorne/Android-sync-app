package com.example.devicesync.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

class ReconnectConfigTest {
    @Test
    fun delayForAttempt_usesBackoffAndJitter() {
        val config = ReconnectConfig(
            initialDelay = 2.seconds,
            maxDelay = 30.seconds,
            jitterProvider = { 1.seconds },
        )

        assertEquals(3.seconds, config.delayForAttempt(1))
        assertEquals(5.seconds, config.delayForAttempt(2))
        assertEquals(9.seconds, config.delayForAttempt(3))
    }

    @Test
    fun delayForAttempt_doesNotExceedMaxDelayBeforeJitter() {
        val config = ReconnectConfig(
            initialDelay = 10.seconds,
            maxDelay = 30.seconds,
            jitterProvider = { 0.seconds },
        )

        assertEquals(30.seconds, config.delayForAttempt(6))
    }

    @Test
    fun defaultJitterIsBoundedAndBackoffRemainsCapped() {
        val config = ReconnectConfig()

        repeat(100) {
            val first = config.delayForAttempt(1)
            assertTrue(first >= 2.seconds && first < 2.seconds + 250.milliseconds)
            val capped = config.delayForAttempt(20)
            assertTrue(capped >= 30.seconds && capped < 32.seconds)
        }
    }
}
