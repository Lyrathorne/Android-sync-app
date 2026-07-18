package com.example.devicesync.keyboard.engine

enum class KeyboardLanguage { RUSSIAN, ENGLISH }

enum class KeyboardMode {
    LETTERS,
    SYMBOLS_PRIMARY,
    SYMBOLS_SECONDARY,
    NUMERIC,
    EMOJI,
    CLIPBOARD,
}

enum class ShiftState { LOWERCASE, SHIFT_ONCE, CAPS_LOCK }

sealed interface KeyboardKey {
    data class Text(val value: String, val alternate: String? = null) : KeyboardKey
    data object Shift : KeyboardKey
    data object Backspace : KeyboardKey
    data object Space : KeyboardKey
    data object Enter : KeyboardKey
    data object Language : KeyboardKey
    data object Symbols : KeyboardKey
    data object Letters : KeyboardKey
    data object MoreSymbols : KeyboardKey
    data object NextKeyboard : KeyboardKey
}

data class KeyboardLayout(val rows: List<List<KeyboardKey>>)

data class KeyboardState(
    val language: KeyboardLanguage = KeyboardLanguage.RUSSIAN,
    val mode: KeyboardMode = KeyboardMode.LETTERS,
    val shift: ShiftState = ShiftState.LOWERCASE,
)

enum class InputFieldKind { TEXT, EMAIL, URI, PHONE, NUMBER, DECIMAL, PASSWORD }

data class InputFieldContext(
    val kind: InputFieldKind,
    val noPersonalizedLearning: Boolean = false,
    val isIncognito: Boolean = false,
) {
    val isSensitive: Boolean
        get() = kind == InputFieldKind.PASSWORD || noPersonalizedLearning || isIncognito

    val allowsSuggestions: Boolean get() = !isSensitive && kind == InputFieldKind.TEXT
    val allowsClipboardHistory: Boolean get() = !isSensitive
}
