package com.example.devicesync.keyboard.engine

class KeyboardLayoutRepository {
    fun layout(state: KeyboardState, numberRow: Boolean = false): KeyboardLayout = when (state.mode) {
        KeyboardMode.LETTERS -> letters(state.language, numberRow)
        KeyboardMode.SYMBOLS_PRIMARY -> symbolsPrimary()
        KeyboardMode.SYMBOLS_SECONDARY -> symbolsSecondary()
        KeyboardMode.NUMERIC -> numeric()
        KeyboardMode.EMOJI, KeyboardMode.CLIPBOARD -> letters(state.language, numberRow)
    }

    private fun letters(language: KeyboardLanguage, numberRow: Boolean): KeyboardLayout {
        val rows = when (language) {
            KeyboardLanguage.RUSSIAN -> listOf(
                listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х"),
                listOf("ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э"),
                listOf("я", "ч", "с", "м", "и", "т", "ь", "б", "ю"),
            )
            KeyboardLanguage.ENGLISH -> listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("z", "x", "c", "v", "b", "n", "m"),
            )
        }
        val keyRows = listOf(rows[0].map(::textKey), rows[1].map(::textKey),
                listOf(KeyboardKey.Shift) + rows[2].map(::textKey) + KeyboardKey.Backspace,
                listOf(KeyboardKey.Symbols, KeyboardKey.Language, textKey(","), KeyboardKey.Space, textKey("."), KeyboardKey.Enter))
        val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map(::textKey)
        return KeyboardLayout(if (numberRow) listOf(digits) + keyRows else keyRows)
    }

    private fun textKey(value: String): KeyboardKey.Text {
        val alternate = when (value) {
            "е" -> "ё"
            "ь" -> "ъ"
            "e" -> "é"
            else -> null
        }
        return KeyboardKey.Text(value, alternate)
    }

    private fun symbolsPrimary() = KeyboardLayout(listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map(::textKey),
        listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/").map(::textKey),
        listOf(KeyboardKey.MoreSymbols) + listOf("*", "\"", "'", ":", ";", "!", "?").map(::textKey) + KeyboardKey.Backspace,
        listOf(KeyboardKey.Letters, KeyboardKey.Language, textKey(","), KeyboardKey.Space, textKey("."), KeyboardKey.Enter),
    ))

    private fun symbolsSecondary() = KeyboardLayout(listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map(::textKey),
        listOf("€", "£", "¥", "₽", "^", "°", "=", "{", "}", "\\").map(::textKey),
        listOf(KeyboardKey.Symbols) + listOf("%", "©", "®", "™", "[", "]", "<", ">").map(::textKey) + KeyboardKey.Backspace,
        listOf(KeyboardKey.Letters, KeyboardKey.Language, textKey(","), KeyboardKey.Space, textKey("."), KeyboardKey.Enter),
    ))

    private fun numeric() = KeyboardLayout(listOf(
        listOf("1", "2", "3").map(::textKey),
        listOf("4", "5", "6").map(::textKey),
        listOf("7", "8", "9").map(::textKey),
        listOf(textKey("+"), textKey("0"), textKey("."), textKey(","), KeyboardKey.Backspace, KeyboardKey.Enter),
    ))
}
