package com.example.devicesync.keyboard.ime

import android.content.Context
import android.content.SharedPreferences
import com.example.devicesync.keyboard.engine.KeyboardHapticIntensity
import com.example.devicesync.keyboard.engine.KeyboardHapticMode
import com.example.devicesync.keyboard.engine.suggestions.GrammaticalGender

enum class KeyboardThemeMode { SYSTEM, LIGHT, DARK }
enum class KeyboardColorScheme { NEUTRAL, OCEAN, VIOLET, SUNSET }
enum class KeyboardCorrectionLevel { CONSERVATIVE, BALANCED, AGGRESSIVE }

class KeyboardPreferences(context: Context) {
    private val values = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var showSuggestions: Boolean
        get() = values.getBoolean(SUGGESTIONS, true)
        set(value) { values.edit().putBoolean(SUGGESTIONS, value).apply() }
    var autoCorrection: Boolean
        get() = values.getBoolean(AUTOCORRECTION, true)
        set(value) { values.edit().putBoolean(AUTOCORRECTION, value).apply() }
    var learnWords: Boolean
        get() = values.getBoolean(LEARN_WORDS, true)
        set(value) { values.edit().putBoolean(LEARN_WORDS, value).apply() }
    var grammaticalGender: GrammaticalGender
        get() = runCatching {
            GrammaticalGender.valueOf(values.getString(GRAMMATICAL_GENDER, GrammaticalGender.NONE.name).orEmpty())
        }.getOrDefault(GrammaticalGender.NONE)
        set(value) { values.edit().putString(GRAMMATICAL_GENDER, value.name).apply() }
    var clipboardHistory: Boolean
        get() = values.getBoolean(CLIPBOARD_HISTORY, true)
        set(value) { values.edit().putBoolean(CLIPBOARD_HISTORY, value).apply() }
    var hapticFeedback: Boolean
        get() = hapticMode != KeyboardHapticMode.OFF
        set(value) { hapticMode = if (value) KeyboardHapticMode.CUSTOM else KeyboardHapticMode.OFF }
    var hapticMode: KeyboardHapticMode
        get() = runCatching {
            values.getString(HAPTIC_MODE, null)?.let(KeyboardHapticMode::valueOf)
        }.getOrNull() ?: if (values.getBoolean(HAPTIC, true)) KeyboardHapticMode.CUSTOM else KeyboardHapticMode.OFF
        set(value) { values.edit().putString(HAPTIC_MODE, value.name).putBoolean(HAPTIC, value != KeyboardHapticMode.OFF).apply() }
    var hapticIntensity: KeyboardHapticIntensity
        get() = runCatching {
            KeyboardHapticIntensity.valueOf(values.getString(HAPTIC_INTENSITY, KeyboardHapticIntensity.MEDIUM.name).orEmpty())
        }.getOrDefault(KeyboardHapticIntensity.MEDIUM)
        set(value) { values.edit().putString(HAPTIC_INTENSITY, value.name).apply() }
    var numberRow: Boolean
        get() = values.getBoolean(NUMBER_ROW, false)
        set(value) { values.edit().putBoolean(NUMBER_ROW, value).apply() }
    var keySound: Boolean
        get() = values.getBoolean(KEY_SOUND, false)
        set(value) { values.edit().putBoolean(KEY_SOUND, value).apply() }
    var incognitoMode: Boolean
        get() = values.getBoolean(INCOGNITO, false)
        set(value) { values.edit().putBoolean(INCOGNITO, value).apply() }
    var keyHeightDp: Int
        get() = values.getInt(KEY_HEIGHT, 48).coerceIn(46, 54)
        set(value) { values.edit().putInt(KEY_HEIGHT, value.coerceIn(46, 54)).apply() }
    var compactMode: Boolean
        get() = values.getBoolean(COMPACT_MODE, false)
        set(value) { values.edit().putBoolean(COMPACT_MODE, value).apply() }
    var themeMode: KeyboardThemeMode
        get() = runCatching {
            KeyboardThemeMode.valueOf(values.getString(THEME_MODE, KeyboardThemeMode.SYSTEM.name).orEmpty())
        }.getOrDefault(KeyboardThemeMode.SYSTEM)
        set(value) { values.edit().putString(THEME_MODE, value.name).apply() }
    var colorScheme: KeyboardColorScheme
        get() = runCatching {
            KeyboardColorScheme.valueOf(values.getString(COLOR_SCHEME, KeyboardColorScheme.NEUTRAL.name).orEmpty())
        }.getOrDefault(KeyboardColorScheme.NEUTRAL)
        set(value) { values.edit().putString(COLOR_SCHEME, value.name).apply() }
    var keyLabelScalePercent: Int
        get() = values.getInt(KEY_LABEL_SCALE, 100).coerceIn(85, 120)
        set(value) { values.edit().putInt(KEY_LABEL_SCALE, value.coerceIn(85, 120)).apply() }
    var correctionLevel: KeyboardCorrectionLevel
        get() = runCatching {
            KeyboardCorrectionLevel.valueOf(values.getString(CORRECTION_LEVEL, KeyboardCorrectionLevel.BALANCED.name).orEmpty())
        }.getOrDefault(KeyboardCorrectionLevel.BALANCED)
        set(value) { values.edit().putString(CORRECTION_LEVEL, value.name).apply() }
    var autoCapitalization: Boolean
        get() = values.getBoolean(AUTO_CAPITALIZATION, false)
        set(value) { values.edit().putBoolean(AUTO_CAPITALIZATION, value).apply() }
    var doubleSpacePeriod: Boolean
        get() = values.getBoolean(DOUBLE_SPACE_PERIOD, true)
        set(value) { values.edit().putBoolean(DOUBLE_SPACE_PERIOD, value).apply() }
    var keyPreview: Boolean
        get() = values.getBoolean(KEY_PREVIEW, false)
        set(value) { values.edit().putBoolean(KEY_PREVIEW, value).apply() }

    fun observe(onChanged: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChanged() }.also(values::registerOnSharedPreferenceChangeListener)

    fun removeObserver(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        values.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val FILE = "device_sync_keyboard_preferences"
        const val SUGGESTIONS = "suggestions"
        const val AUTOCORRECTION = "autocorrection"
        const val LEARN_WORDS = "learn_words"
        const val GRAMMATICAL_GENDER = "grammatical_gender"
        const val CLIPBOARD_HISTORY = "clipboard_history"
        const val HAPTIC = "haptic_feedback"
        const val HAPTIC_INTENSITY = "haptic_intensity"
        const val HAPTIC_MODE = "haptic_mode"
        const val NUMBER_ROW = "number_row"
        const val KEY_SOUND = "key_sound"
        const val INCOGNITO = "incognito"
        const val KEY_HEIGHT = "key_height"
        const val COMPACT_MODE = "compact_mode"
        const val THEME_MODE = "theme_mode"
        const val COLOR_SCHEME = "color_scheme"
        const val KEY_LABEL_SCALE = "key_label_scale"
        const val CORRECTION_LEVEL = "correction_level"
        const val AUTO_CAPITALIZATION = "auto_capitalization"
        const val DOUBLE_SPACE_PERIOD = "double_space_period"
        const val KEY_PREVIEW = "key_preview"
    }
}
