package com.example.devicesync.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.example.devicesync.keyboard.engine.InputFieldContext
import com.example.devicesync.keyboard.engine.InputFieldKind

internal object EditorInfoPolicy {
    fun context(info: EditorInfo?): InputFieldContext {
        if (info == null) return InputFieldContext(InputFieldKind.TEXT)
        val inputType = info.inputType
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val kind = when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> if (
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            ) InputFieldKind.PASSWORD else if (
                inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0
            ) InputFieldKind.DECIMAL else InputFieldKind.NUMBER
            InputType.TYPE_CLASS_PHONE -> InputFieldKind.PHONE
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> InputFieldKind.PASSWORD
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> InputFieldKind.EMAIL
                InputType.TYPE_TEXT_VARIATION_URI -> InputFieldKind.URI
                else -> InputFieldKind.TEXT
            }
            else -> InputFieldKind.TEXT
        }
        val noLearning = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        return InputFieldContext(kind, noPersonalizedLearning = noLearning)
    }

    fun allowsAutomaticCapitalization(info: EditorInfo?, fieldContext: InputFieldContext): Boolean {
        if (info == null || fieldContext.kind != InputFieldKind.TEXT || fieldContext.isSensitive) return false
        return info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
    }
}
