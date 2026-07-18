package com.example.devicesync.keyboard.ime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import com.example.devicesync.keyboard.engine.KeyboardHapticKind
import com.example.devicesync.keyboard.engine.KeyboardHapticIntensity
import com.example.devicesync.keyboard.engine.KeyboardHapticMode
import com.example.devicesync.keyboard.engine.KeyboardHapticPolicy

data class KeyboardHapticDiagnostics(
    val hasVibrator: Boolean,
    val hasAmplitudeControl: Boolean,
    val mode: KeyboardHapticMode,
    val intensity: KeyboardHapticIntensity,
    val systemTouchFeedbackEnabled: Boolean,
    val androidApi: Int,
)

enum class KeyboardHapticOutcome {
    PERFORMED,
    DISABLED,
    NO_VIBRATOR,
    SYSTEM_FEEDBACK_DISABLED,
    VIEW_REJECTED,
    ERROR,
}

data class KeyboardHapticResult(
    val outcome: KeyboardHapticOutcome,
    val effect: String? = null,
    val errorClass: String? = null,
) {
    val performed: Boolean get() = outcome == KeyboardHapticOutcome.PERFORMED
}

class KeyboardHaptics(
    private val context: Context,
    private val preferences: KeyboardPreferences,
) {
    @Volatile private var mode = preferences.hapticMode
    @Volatile private var intensity = preferences.hapticIntensity
    private val preferenceObserver = preferences.observe {
        mode = preferences.hapticMode
        intensity = preferences.hapticIntensity
    }
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun perform(view: View, kind: KeyboardHapticKind = KeyboardHapticKind.KEY): KeyboardHapticResult {
        refreshPreferences()
        val result = when (mode) {
            KeyboardHapticMode.OFF -> KeyboardHapticResult(KeyboardHapticOutcome.DISABLED)
            KeyboardHapticMode.SYSTEM -> performSystem(view, kind)
            KeyboardHapticMode.CUSTOM -> performCustomWithFallback(view, kind)
        }
        logResult(result)
        return result
    }

    fun performTest(view: View): KeyboardHapticResult {
        refreshPreferences()
        // The test follows the selected mode, but uses a more noticeable CUSTOM pulse.
        val result = when (mode) {
            KeyboardHapticMode.OFF -> KeyboardHapticResult(KeyboardHapticOutcome.DISABLED)
            KeyboardHapticMode.SYSTEM -> performSystem(view, KeyboardHapticKind.MODE_CHANGE)
            KeyboardHapticMode.CUSTOM -> performCustom(KeyboardHapticKind.MODE_CHANGE, isTest = true)
        }
        logResult(result)
        return result
    }

    fun diagnostics(): KeyboardHapticDiagnostics = KeyboardHapticDiagnostics(
        hasVibrator = vibrator?.hasVibrator() == true,
        hasAmplitudeControl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator?.hasAmplitudeControl() == true,
        mode = mode,
        intensity = intensity,
        systemTouchFeedbackEnabled = isSystemTouchFeedbackEnabled(),
        androidApi = Build.VERSION.SDK_INT,
    )

    fun close() = preferences.removeObserver(preferenceObserver)

    private fun refreshPreferences() {
        mode = preferences.hapticMode
        intensity = preferences.hapticIntensity
    }

    private fun performSystem(view: View, kind: KeyboardHapticKind): KeyboardHapticResult {
        if (!isSystemTouchFeedbackEnabled()) {
            return KeyboardHapticResult(KeyboardHapticOutcome.SYSTEM_FEEDBACK_DISABLED)
        }
        view.isHapticFeedbackEnabled = true
        val constant = when (kind) {
            KeyboardHapticKind.KEY -> HapticFeedbackConstants.KEYBOARD_TAP
            KeyboardHapticKind.MODE_CHANGE -> HapticFeedbackConstants.CONTEXT_CLICK
            KeyboardHapticKind.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
        }
        val accepted = view.performHapticFeedback(constant)
        return KeyboardHapticResult(
            if (accepted) KeyboardHapticOutcome.PERFORMED else KeyboardHapticOutcome.VIEW_REJECTED,
            effect = "system:$constant",
        )
    }

    private fun performCustom(kind: KeyboardHapticKind, isTest: Boolean = false): KeyboardHapticResult {
        val target = vibrator ?: return KeyboardHapticResult(KeyboardHapticOutcome.NO_VIBRATOR)
        if (!target.hasVibrator()) return KeyboardHapticResult(KeyboardHapticOutcome.NO_VIBRATOR)
        return runCatching {
            val base = KeyboardHapticPolicy.pattern(intensity, kind)
            val duration = if (isTest) base.durationMillis.coerceAtLeast(28L) else base.durationMillis
            val requestedAmplitude = if (isTest) base.amplitude.coerceAtLeast(190) else base.amplitude
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude = if (target.hasAmplitudeControl()) {
                    requestedAmplitude.coerceIn(1, 255)
                } else {
                    VibrationEffect.DEFAULT_AMPLITUDE
                }
                val effect = VibrationEffect.createOneShot(duration, amplitude)
                // Do not mark CUSTOM as USAGE_TOUCH. XOS and some other OEM firmware
                // suppresses that usage when Android touch feedback is disabled.
                target.vibrate(effect)
                KeyboardHapticResult(
                    KeyboardHapticOutcome.PERFORMED,
                    effect = "custom:oneShot:${duration}ms:$amplitude",
                )
            } else {
                @Suppress("DEPRECATION")
                target.vibrate(duration)
                KeyboardHapticResult(
                    KeyboardHapticOutcome.PERFORMED,
                    effect = "custom:legacy:${duration}ms",
                )
            }
        }.getOrElse { error ->
            KeyboardHapticResult(
                KeyboardHapticOutcome.ERROR,
                errorClass = error.javaClass.simpleName,
            )
        }
    }

    private fun performCustomWithFallback(view: View, kind: KeyboardHapticKind): KeyboardHapticResult {
        val direct = performCustom(kind)
        if (direct.performed) return direct
        val fallback = performSystem(view, kind)
        return if (fallback.performed) fallback.copy(effect = "fallback:${fallback.effect}") else direct
    }

    private fun isSystemTouchFeedbackEnabled(): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    private fun logResult(result: KeyboardHapticResult) {
        Log.d(
            TAG,
            "mode=$mode vibrator=${vibrator?.hasVibrator() == true} " +
                "amplitude=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator?.hasAmplitudeControl() == true} " +
                "outcome=${result.outcome} effect=${result.effect} error=${result.errorClass}",
        )
    }

    private companion object { const val TAG = "DeviceSyncHaptics" }
}
