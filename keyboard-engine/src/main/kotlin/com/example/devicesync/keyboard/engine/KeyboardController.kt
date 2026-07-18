package com.example.devicesync.keyboard.engine

class KeyboardController(initialState: KeyboardState = KeyboardState()) {
    var state: KeyboardState = initialState
        private set

    private var lastShiftTapMillis: Long = Long.MIN_VALUE

    fun onShift(nowMillis: Long) {
        state = when (state.shift) {
            ShiftState.CAPS_LOCK -> state.copy(shift = ShiftState.LOWERCASE)
            ShiftState.SHIFT_ONCE -> {
                if (nowMillis - lastShiftTapMillis <= DOUBLE_TAP_MILLIS) state.copy(shift = ShiftState.CAPS_LOCK)
                else state.copy(shift = ShiftState.LOWERCASE)
            }
            ShiftState.LOWERCASE -> state.copy(shift = ShiftState.SHIFT_ONCE)
        }
        lastShiftTapMillis = nowMillis
    }

    fun onLetterCommitted() {
        if (state.shift == ShiftState.SHIFT_ONCE) state = state.copy(shift = ShiftState.LOWERCASE)
    }

    fun toggleLanguage() {
        state = state.copy(
            language = if (state.language == KeyboardLanguage.RUSSIAN) KeyboardLanguage.ENGLISH else KeyboardLanguage.RUSSIAN,
            mode = KeyboardMode.LETTERS,
        )
    }

    fun setMode(mode: KeyboardMode) { state = state.copy(mode = mode) }

    fun requestShiftOnce() {
        if (state.shift == ShiftState.LOWERCASE) state = state.copy(shift = ShiftState.SHIFT_ONCE)
    }

    fun clearAutomaticShift() {
        if (state.shift == ShiftState.SHIFT_ONCE) state = state.copy(shift = ShiftState.LOWERCASE)
    }

    fun displayText(key: KeyboardKey.Text): String = when (state.shift) {
        ShiftState.LOWERCASE -> key.value
        ShiftState.SHIFT_ONCE, ShiftState.CAPS_LOCK -> key.value.uppercase()
    }

    companion object { const val DOUBLE_TAP_MILLIS = 400L }
}
