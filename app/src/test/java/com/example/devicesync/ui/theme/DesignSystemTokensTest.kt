package com.example.devicesync.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class DesignSystemTokensTest {
    @Test
    fun coreTextAndActionPairsMeetWcagAa() {
        val pairs = listOf(
            0xFF15201D to 0xFFFFFFFF,
            0xFF4B625B to 0xFFFFFFFF,
            0xFFFFFFFF to 0xFF006B5B,
            0xFFE4ECE8 to 0xFF18201D,
            0xFFB7C9C3 to 0xFF18201D,
            0xFF00382F to 0xFF8BD8C4,
            0xFFFFFFFF to 0xFF000000,
            0xFF000000 to 0xFF00FFFF,
        )

        pairs.forEach { (foreground, background) ->
            assertTrue("Contrast was ${contrast(foreground, background)}", contrast(foreground, background) >= 4.5)
        }
    }

    private fun contrast(first: Long, second: Long): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + .05) / (min(a, b) + .05)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xff).toDouble() / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
}
