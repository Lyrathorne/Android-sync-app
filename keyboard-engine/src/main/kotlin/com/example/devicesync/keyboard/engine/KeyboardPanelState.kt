package com.example.devicesync.keyboard.engine

enum class KeyboardPanel { KEYBOARD, CLIPBOARD, EMOJI }

enum class SmartStripMode { TOOLS, SUGGESTIONS, PANEL_HEADER }

data class SmartStripState(
    val mode: SmartStripMode,
    val activePanel: KeyboardPanel? = null,
)

object SmartStripPolicy {
    fun resolve(
        hasTypedWord: Boolean,
        suggestionsAllowed: Boolean,
        activePanel: KeyboardPanel,
    ): SmartStripState = when {
        activePanel != KeyboardPanel.KEYBOARD -> SmartStripState(SmartStripMode.PANEL_HEADER, activePanel)
        hasTypedWord && suggestionsAllowed -> SmartStripState(SmartStripMode.SUGGESTIONS)
        else -> SmartStripState(SmartStripMode.TOOLS)
    }
}

class KeyPressState {
    private val activePointers = mutableSetOf<Int>()
    val isPressed: Boolean get() = activePointers.isNotEmpty()

    fun onDown(pointerId: Int) { activePointers += pointerId }
    fun onUp(pointerId: Int) { activePointers -= pointerId }
    fun onCancel() { activePointers.clear() }
}

object KeyboardInputPolicy {
    fun commitOnTouchDown(key: KeyboardKey): Boolean = when (key) {
        is KeyboardKey.Text -> true
        KeyboardKey.Space, KeyboardKey.Backspace, KeyboardKey.Enter -> true
        else -> false
    }
}

enum class AlternateKeyResult { NONE, PRIMARY, ALTERNATE }

/** Deterministic long-press state used by the IME and unit tests. */
class AlternateKeyPressTracker(
    val thresholdMillis: Long = 400L,
) {
    private var downAtMillis: Long? = null
    private var alternateReady = false

    fun onDown(nowMillis: Long) {
        downAtMillis = nowMillis
        alternateReady = false
    }

    fun onThreshold(nowMillis: Long): Boolean {
        val started = downAtMillis ?: return false
        if (nowMillis - started < thresholdMillis) return false
        alternateReady = true
        return true
    }

    fun onUp(nowMillis: Long): AlternateKeyResult {
        val started = downAtMillis ?: return AlternateKeyResult.NONE
        val result = if (alternateReady || nowMillis - started >= thresholdMillis) {
            AlternateKeyResult.ALTERNATE
        } else AlternateKeyResult.PRIMARY
        reset()
        return result
    }

    fun cancel(): AlternateKeyResult {
        reset()
        return AlternateKeyResult.NONE
    }

    private fun reset() {
        downAtMillis = null
        alternateReady = false
    }
}

class KeyboardPanelController {
    var current: KeyboardPanel = KeyboardPanel.KEYBOARD
        private set

    fun toggle(panel: KeyboardPanel): KeyboardPanel {
        current = if (current == panel) KeyboardPanel.KEYBOARD else panel
        return current
    }

    fun show(panel: KeyboardPanel) { current = panel }
    fun close() { current = KeyboardPanel.KEYBOARD }

    fun enforceFieldPolicy(sensitive: Boolean) {
        if (sensitive && current == KeyboardPanel.CLIPBOARD) close()
    }
}

enum class KeyboardHapticIntensity { LIGHT, MEDIUM, STRONG }
enum class KeyboardHapticKind { KEY, MODE_CHANGE, LONG_PRESS }
enum class KeyboardHapticMode { OFF, SYSTEM, CUSTOM }

data class KeyboardHapticPattern(val durationMillis: Long, val amplitude: Int)

object KeyboardHapticPolicy {
    fun pattern(intensity: KeyboardHapticIntensity, kind: KeyboardHapticKind): KeyboardHapticPattern {
        val base = when (intensity) {
            KeyboardHapticIntensity.LIGHT -> KeyboardHapticPattern(7, 100)
            KeyboardHapticIntensity.MEDIUM -> KeyboardHapticPattern(10, 160)
            KeyboardHapticIntensity.STRONG -> KeyboardHapticPattern(15, 220)
        }
        return when (kind) {
            KeyboardHapticKind.KEY -> base
            KeyboardHapticKind.MODE_CHANGE -> base.copy(
                durationMillis = (base.durationMillis + 2).coerceAtMost(18),
                amplitude = (base.amplitude + 15).coerceAtMost(255),
            )
            KeyboardHapticKind.LONG_PRESS -> base.copy(
                durationMillis = (base.durationMillis + 4).coerceAtMost(20),
                amplitude = (base.amplitude + 25).coerceAtMost(255),
            )
        }
    }
}

object EmojiGridMetrics {
    fun columns(availableWidthDp: Int, minimumCellDp: Int = 48, spacingDp: Int = 4): Int {
        if (availableWidthDp <= 0) return 6
        return ((availableWidthDp + spacingDp) / (minimumCellDp + spacingDp)).coerceIn(6, 10)
    }

    fun cellWidthDp(availableWidthDp: Int, columns: Int, spacingDp: Int = 4): Int {
        val safeColumns = columns.coerceAtLeast(1)
        return ((availableWidthDp - spacingDp * (safeColumns + 1)) / safeColumns).coerceAtLeast(1)
    }
}

object EmojiPanelMetrics {
    fun totalHeightDp(
        screenHeightDp: Int,
        landscape: Boolean,
        keyboardHeightDp: Int = 232,
    ): Int {
        val maximumFraction = if (landscape) 0.55f else 0.55f
        val availableMaximum = (screenHeightDp * maximumFraction).toInt().coerceAtLeast(160)
        return keyboardHeightDp.coerceAtLeast(160).coerceAtMost(availableMaximum)
    }
}

object KeyboardRowMetrics {
    fun weight(key: KeyboardKey): Float = when (key) {
        KeyboardKey.Space -> 4f
        KeyboardKey.Enter -> 1.55f
        KeyboardKey.Backspace, KeyboardKey.Shift -> 1.35f
        KeyboardKey.Symbols -> 1.3f
        KeyboardKey.Language -> 1.1f
        is KeyboardKey.Text -> if (key.value == "," || key.value == ".") 0.9f else 1f
        else -> 1f
    }

    fun widthsDp(availableWidthDp: Int, row: List<KeyboardKey>, horizontalMarginDp: Int = 2): List<Float> {
        if (row.isEmpty()) return emptyList()
        val contentWidth = (availableWidthDp - row.size * horizontalMarginDp * 2).coerceAtLeast(row.size)
        val weights = row.map(::weight)
        val unit = contentWidth / weights.sum()
        return weights.map { (it * unit).coerceAtLeast(1f) }
    }
}

data class KeyboardHeightSpec(
    val toolbarDp: Int,
    val suggestionsDp: Int,
    val keyDp: Int,
    val verticalMarginDp: Int,
    val outerPaddingDp: Int,
)

object KeyboardHeightMetrics {
    val Compact = KeyboardHeightSpec(44, 44, 46, 2, 3)
    val Normal = KeyboardHeightSpec(44, 44, 48, 2, 3)

    @Suppress("UNUSED_PARAMETER")
    fun totalDp(rowCount: Int, suggestionsVisible: Boolean, spec: KeyboardHeightSpec): Int =
        spec.outerPaddingDp * 2 + spec.toolbarDp +
            rowCount.coerceAtLeast(0) * (spec.keyDp + spec.verticalMarginDp * 2)
}

object KeyboardSystemInsetPolicy {
    fun bottomPadding(basePaddingPx: Int, navigationBarPx: Int, mandatoryGesturePx: Int): Int =
        basePaddingPx.coerceAtLeast(0) + maxOf(navigationBarPx, mandatoryGesturePx).coerceAtLeast(0)
}

class SuggestionGeneration {
    private var value = 0L

    @Synchronized fun next(): Long = ++value
    @Synchronized fun isCurrent(generation: Long): Boolean = generation == value
    @Synchronized fun invalidate() { value++ }
}
