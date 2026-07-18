package com.example.devicesync.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardUiPolicyTest {
    @Test fun tappingActivePanelReturnsToKeyboard() {
        val controller = KeyboardPanelController()

        assertEquals(KeyboardPanel.CLIPBOARD, controller.toggle(KeyboardPanel.CLIPBOARD))
        assertEquals(KeyboardPanel.KEYBOARD, controller.toggle(KeyboardPanel.CLIPBOARD))
    }

    @Test fun sensitiveFieldClosesPrivatePanels() {
        val controller = KeyboardPanelController()

        controller.show(KeyboardPanel.CLIPBOARD)
        controller.enforceFieldPolicy(sensitive = true)
        assertEquals(KeyboardPanel.KEYBOARD, controller.current)

        controller.show(KeyboardPanel.EMOJI)
        controller.enforceFieldPolicy(sensitive = true)
        assertEquals(KeyboardPanel.EMOJI, controller.current)
    }

    @Test fun hapticIntensityAndActionKindProduceDistinctPatterns() {
        val light = KeyboardHapticPolicy.pattern(KeyboardHapticIntensity.LIGHT, KeyboardHapticKind.KEY)
        val strong = KeyboardHapticPolicy.pattern(KeyboardHapticIntensity.STRONG, KeyboardHapticKind.KEY)
        val longPress = KeyboardHapticPolicy.pattern(KeyboardHapticIntensity.MEDIUM, KeyboardHapticKind.LONG_PRESS)
        val mediumKey = KeyboardHapticPolicy.pattern(KeyboardHapticIntensity.MEDIUM, KeyboardHapticKind.KEY)

        assertTrue(strong.amplitude > light.amplitude)
        assertTrue(strong.durationMillis > light.durationMillis)
        assertTrue(longPress.amplitude > mediumKey.amplitude)
    }

    @Test fun emojiGridStaysWithinSixToTenColumnsAndHasPositiveCells() {
        listOf(280, 360, 600, 1200).forEach { width ->
            val columns = EmojiGridMetrics.columns(width)
            assertTrue(columns in 6..10)
            assertTrue(EmojiGridMetrics.cellWidthDp(width, columns) > 0)
        }
        assertEquals(10, EmojiGridMetrics.columns(1200))
    }

    @Test fun emojiPanelIsCompactAndLandscapeAware() {
        assertEquals(232, EmojiPanelMetrics.totalHeightDp(800, landscape = false))
        assertEquals(232, EmojiPanelMetrics.totalHeightDp(600, landscape = false))
        assertEquals(232, EmojiPanelMetrics.totalHeightDp(480, landscape = true))
        assertEquals(176, EmojiPanelMetrics.totalHeightDp(320, landscape = true))
        assertEquals(278, EmojiPanelMetrics.totalHeightDp(800, landscape = false, keyboardHeightDp = 278))
    }

    @Test fun russianRowsHavePositiveWidthsOnNarrowScreens() {
        val rows = KeyboardLayoutRepository().layout(KeyboardState()).rows

        listOf(320, 360, 393, 411, 600).forEach { width ->
            rows.forEach { row ->
                val calculated = KeyboardRowMetrics.widthsDp(width, row)
                assertEquals(row.size, calculated.size)
                assertTrue(calculated.all { it > 0f })
                assertTrue(calculated.sum() <= width)
            }
        }
    }

    @Test fun punctuationKeysRemainUsableAndEnterGetsMoreSpace() {
        assertTrue(KeyboardRowMetrics.weight(KeyboardKey.Text(",")) >= 0.9f)
        assertTrue(KeyboardRowMetrics.weight(KeyboardKey.Enter) > KeyboardRowMetrics.weight(KeyboardKey.Text("а")))
    }

    @Test fun compactHeightStaysWithinDailyKeyboardBudget() {
        assertEquals(250, KeyboardHeightMetrics.totalDp(4, suggestionsVisible = true, KeyboardHeightMetrics.Compact))
        assertEquals(300, KeyboardHeightMetrics.totalDp(5, suggestionsVisible = true, KeyboardHeightMetrics.Compact))
        assertTrue(KeyboardHeightMetrics.totalDp(5, suggestionsVisible = true, KeyboardHeightMetrics.Normal) <= 315)
    }

    @Test fun smartStripSwitchesBetweenToolsSuggestionsAndPanelHeader() {
        assertEquals(
            SmartStripMode.TOOLS,
            SmartStripPolicy.resolve(false, true, KeyboardPanel.KEYBOARD).mode,
        )
        assertEquals(
            SmartStripMode.SUGGESTIONS,
            SmartStripPolicy.resolve(true, true, KeyboardPanel.KEYBOARD).mode,
        )
        assertEquals(
            SmartStripState(SmartStripMode.PANEL_HEADER, KeyboardPanel.CLIPBOARD),
            SmartStripPolicy.resolve(true, true, KeyboardPanel.CLIPBOARD),
        )
        assertEquals(
            SmartStripMode.TOOLS,
            SmartStripPolicy.resolve(true, false, KeyboardPanel.KEYBOARD).mode,
        )
    }

    @Test fun completingWordReturnsSmartStripToTools() {
        val typing = SmartStripPolicy.resolve(true, true, KeyboardPanel.KEYBOARD)
        val completed = SmartStripPolicy.resolve(false, true, KeyboardPanel.KEYBOARD)
        assertEquals(SmartStripMode.SUGGESTIONS, typing.mode)
        assertEquals(SmartStripMode.TOOLS, completed.mode)
    }

    @Test fun actionCancelAlwaysClearsPressedState() {
        val state = KeyPressState()
        state.onDown(0)
        state.onDown(1)
        assertTrue(state.isPressed)
        state.onCancel()
        assertTrue(!state.isPressed)
    }

    @Test fun commonInputCommitsOnTouchDownWithoutBreakingAlternateLongPress() {
        assertTrue(KeyboardInputPolicy.commitOnTouchDown(KeyboardKey.Text("a")))
        assertTrue(KeyboardInputPolicy.commitOnTouchDown(KeyboardKey.Space))
        assertTrue(KeyboardInputPolicy.commitOnTouchDown(KeyboardKey.Backspace))
        assertTrue(KeyboardInputPolicy.commitOnTouchDown(KeyboardKey.Text("e", "é")))
        assertFalse(KeyboardInputPolicy.commitOnTouchDown(KeyboardKey.Language))
    }

    @Test fun alternateKeyShortPressCommitsOnlyPrimaryCharacter() {
        val tracker = AlternateKeyPressTracker(400)
        tracker.onDown(1_000)
        assertEquals(AlternateKeyResult.PRIMARY, tracker.onUp(1_250))
        assertEquals(AlternateKeyResult.NONE, tracker.onUp(1_251))
    }

    @Test fun alternateKeyLongPressCommitsOnlyAlternateCharacter() {
        val tracker = AlternateKeyPressTracker(400)
        tracker.onDown(1_000)
        assertTrue(tracker.onThreshold(1_400))
        assertEquals(AlternateKeyResult.ALTERNATE, tracker.onUp(1_450))
        assertEquals(AlternateKeyResult.NONE, tracker.onUp(1_451))
    }

    @Test fun cancelledAlternatePressCommitsNothing() {
        val tracker = AlternateKeyPressTracker(400)
        tracker.onDown(1_000)
        assertEquals(AlternateKeyResult.NONE, tracker.cancel())
        assertEquals(AlternateKeyResult.NONE, tracker.onUp(1_500))
    }

    @Test fun suggestionGenerationRejectsObsoleteResults() {
        val generations = SuggestionGeneration()
        val first = generations.next()
        val second = generations.next()

        assertTrue(!generations.isCurrent(first))
        assertTrue(generations.isCurrent(second))
        generations.invalidate()
        assertTrue(!generations.isCurrent(second))
    }

    @Test fun allHapticModesAreExplicit() {
        assertEquals(
            setOf(KeyboardHapticMode.OFF, KeyboardHapticMode.SYSTEM, KeyboardHapticMode.CUSTOM),
            KeyboardHapticMode.entries.toSet(),
        )
    }

    @Test fun systemInsetUsesLargestBottomInsetExactlyOnce() {
        assertEquals(26, KeyboardSystemInsetPolicy.bottomPadding(2, 24, 18))
        assertEquals(34, KeyboardSystemInsetPolicy.bottomPadding(2, 0, 32))
        assertEquals(2, KeyboardSystemInsetPolicy.bottomPadding(2, 0, 0))
    }

    @Test fun negativeInsetsCannotRemoveBasePadding() {
        assertEquals(2, KeyboardSystemInsetPolicy.bottomPadding(2, -10, -20))
    }
}
