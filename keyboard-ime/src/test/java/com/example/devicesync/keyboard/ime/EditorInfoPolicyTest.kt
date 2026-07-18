package com.example.devicesync.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.example.devicesync.keyboard.engine.InputFieldKind
import org.junit.Assert.*
import org.junit.Test

class EditorInfoPolicyTest {
    @Test fun textPasswordIsSensitive() {
        val context = policy(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(InputFieldKind.PASSWORD, context.kind)
        assertTrue(context.isSensitive)
    }

    @Test fun numericPasswordIsSensitive() {
        assertEquals(
            InputFieldKind.PASSWORD,
            policy(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD).kind,
        )
    }

    @Test fun emailAndUriUseDedicatedKinds() {
        assertEquals(InputFieldKind.EMAIL, policy(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).kind)
        assertEquals(InputFieldKind.URI, policy(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).kind)
    }

    @Test fun decimalUsesDedicatedKind() {
        assertEquals(InputFieldKind.DECIMAL, policy(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).kind)
    }

    @Test fun noPersonalizedLearningDisablesSuggestions() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val context = EditorInfoPolicy.context(info)
        assertTrue(context.isSensitive)
        assertFalse(context.allowsSuggestions)
    }

    @Test fun automaticCapitalizationIsAllowedOnlyForOrdinaryText() {
        val textInfo = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val textContext = EditorInfoPolicy.context(textInfo)
        assertTrue(EditorInfoPolicy.allowsAutomaticCapitalization(textInfo, textContext))

        val emailInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        assertFalse(EditorInfoPolicy.allowsAutomaticCapitalization(emailInfo, EditorInfoPolicy.context(emailInfo)))

        val passwordInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        assertFalse(EditorInfoPolicy.allowsAutomaticCapitalization(passwordInfo, EditorInfoPolicy.context(passwordInfo)))
    }

    private fun policy(inputType: Int) = EditorInfoPolicy.context(EditorInfo().apply { this.inputType = inputType })
}
