package com.example.devicesync.keyboard.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.example.devicesync.keyboard.engine.KeyboardHeightMetrics

internal data class KeyboardUiMetrics(
    val keyHeightDp: Int,
    val keyHorizontalGapDp: Int = 2,
    val keyVerticalGapDp: Int = 3,
    val keyCornerRadiusDp: Float = 8f,
    val panelCornerRadiusDp: Float = 12f,
    val toolbarHeightDp: Int = 48,
    val suggestionHeightDp: Int = 48,
    val minimumTouchDp: Int = 48,
    val emojiMinimumCellDp: Int = 48,
    val emojiSpacingDp: Int = 4,
    val alternateLongPressMillis: Long = 400L,
)

internal data class KeyboardColorPalette(
    val background: Int,
    val key: Int,
    val functionalKey: Int,
    val enterKey: Int,
    val panel: Int,
    val selected: Int,
    val text: Int,
    val secondaryText: Int,
    val accentText: Int,
    val ripple: Int,
)

internal object KeyboardUiStyle {
    fun metrics(preferences: KeyboardPreferences): KeyboardUiMetrics {
        val height = if (preferences.compactMode) KeyboardHeightMetrics.Compact else KeyboardHeightMetrics.Normal
        return KeyboardUiMetrics(
            keyHeightDp = if (preferences.compactMode) height.keyDp else preferences.keyHeightDp,
            keyHorizontalGapDp = 2,
            keyVerticalGapDp = height.verticalMarginDp,
            keyCornerRadiusDp = 7f,
            panelCornerRadiusDp = 9f,
            toolbarHeightDp = height.toolbarDp,
            suggestionHeightDp = height.suggestionsDp,
            minimumTouchDp = 48,
            emojiMinimumCellDp = 44,
            emojiSpacingDp = 4,
        )
    }

    fun palette(context: Context, preferences: KeyboardPreferences): KeyboardColorPalette {
        val dark = when (preferences.themeMode) {
            KeyboardThemeMode.DARK -> true
            KeyboardThemeMode.LIGHT -> false
            KeyboardThemeMode.SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }
        return if (dark) darkPalette(preferences.colorScheme) else lightPalette(preferences.colorScheme)
    }

    private fun darkPalette(scheme: KeyboardColorScheme): KeyboardColorPalette {
        val colors = when (scheme) {
            KeyboardColorScheme.NEUTRAL -> intArrayOf(20, 20, 21, 43, 43, 45, 70, 70, 72, 0, 102, 55, 54, 54, 57, 0, 82, 47, 126, 211, 157)
            KeyboardColorScheme.OCEAN -> intArrayOf(12, 25, 34, 28, 49, 62, 42, 70, 86, 0, 105, 148, 31, 57, 71, 0, 78, 111, 119, 211, 242)
            KeyboardColorScheme.VIOLET -> intArrayOf(25, 20, 35, 49, 41, 63, 71, 58, 88, 112, 70, 190, 59, 49, 75, 86, 55, 151, 211, 176, 255)
            KeyboardColorScheme.SUNSET -> intArrayOf(34, 20, 20, 61, 40, 40, 87, 57, 53, 168, 70, 52, 73, 47, 45, 128, 53, 43, 255, 183, 145)
        }
        return KeyboardColorPalette(
            background = Color.rgb(colors[0], colors[1], colors[2]),
            key = Color.rgb(colors[3], colors[4], colors[5]),
            functionalKey = Color.rgb(colors[6], colors[7], colors[8]),
            enterKey = Color.rgb(colors[9], colors[10], colors[11]),
            panel = Color.rgb(colors[12], colors[13], colors[14]),
            selected = Color.rgb(colors[15], colors[16], colors[17]),
            text = Color.rgb(244, 244, 247),
            secondaryText = Color.rgb(184, 184, 191),
            accentText = Color.rgb(colors[18], colors[19], colors[20]),
            ripple = Color.argb(65, 255, 255, 255),
        )
    }

    private fun lightPalette(scheme: KeyboardColorScheme): KeyboardColorPalette {
        val colors = when (scheme) {
            KeyboardColorScheme.NEUTRAL -> intArrayOf(232, 235, 238, 248, 249, 250, 205, 210, 214, 173, 230, 196, 224, 228, 232, 190, 228, 207, 0, 105, 55)
            KeyboardColorScheme.OCEAN -> intArrayOf(224, 239, 247, 247, 252, 255, 190, 218, 232, 143, 214, 241, 210, 231, 241, 170, 217, 239, 0, 102, 145)
            KeyboardColorScheme.VIOLET -> intArrayOf(239, 232, 248, 252, 249, 255, 217, 204, 232, 215, 187, 246, 229, 219, 240, 222, 200, 242, 104, 65, 157)
            KeyboardColorScheme.SUNSET -> intArrayOf(248, 234, 230, 255, 251, 249, 235, 207, 198, 247, 180, 151, 242, 220, 212, 246, 204, 185, 155, 69, 40)
        }
        return KeyboardColorPalette(
            background = Color.rgb(colors[0], colors[1], colors[2]),
            key = Color.rgb(colors[3], colors[4], colors[5]),
            functionalKey = Color.rgb(colors[6], colors[7], colors[8]),
            enterKey = Color.rgb(colors[9], colors[10], colors[11]),
            panel = Color.rgb(colors[12], colors[13], colors[14]),
            selected = Color.rgb(colors[15], colors[16], colors[17]),
            text = Color.rgb(28, 30, 33),
            secondaryText = Color.rgb(88, 91, 98),
            accentText = Color.rgb(colors[18], colors[19], colors[20]),
            ripple = Color.argb(45, 0, 0, 0),
        )
    }
}
