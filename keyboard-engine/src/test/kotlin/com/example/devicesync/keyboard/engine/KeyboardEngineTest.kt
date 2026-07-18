package com.example.devicesync.keyboard.engine

import org.junit.Assert.*
import org.junit.Test
import com.example.devicesync.keyboard.engine.suggestions.BootstrapSuggestionEngine
import com.example.devicesync.keyboard.engine.suggestions.DictionaryWord
import com.example.devicesync.keyboard.engine.suggestions.GrammaticalGender
import com.example.devicesync.keyboard.engine.suggestions.KeyboardLanguageDetector
import com.example.devicesync.keyboard.engine.suggestions.SuggestionContext
import kotlin.system.measureNanoTime

class KeyboardEngineTest {
    @Test fun russianAndEnglishLayoutsContainExpectedKeys() {
        val repository = KeyboardLayoutRepository()
        val russian = repository.layout(KeyboardState()).rows.flatten()
        val english = repository.layout(KeyboardState(language = KeyboardLanguage.ENGLISH)).rows.flatten()
        assertTrue(russian.contains(KeyboardKey.Text("й")))
        assertTrue(russian.contains(KeyboardKey.Text("е", "ё")))
        assertTrue(russian.contains(KeyboardKey.Text("ь", "ъ")))
        assertFalse(russian.contains(KeyboardKey.Text("ъ")))
        assertTrue(english.contains(KeyboardKey.Text("q")))
    }

    @Test fun russianLetterRowsUseStandardOrderWithoutStandaloneHardSign() {
        val rows = KeyboardLayoutRepository().layout(KeyboardState()).rows
        assertEquals("йцукенгшщзх", rows[0].filterIsInstance<KeyboardKey.Text>().joinToString("") { it.value })
        assertEquals("фывапролджэ", rows[1].filterIsInstance<KeyboardKey.Text>().joinToString("") { it.value })
        assertEquals("ячсмитьбю", rows[2].filterIsInstance<KeyboardKey.Text>().joinToString("") { it.value })
    }

    @Test fun shiftOnceResetsAfterLetter() {
        val controller = KeyboardController()
        controller.onShift(100)
        assertEquals(ShiftState.SHIFT_ONCE, controller.state.shift)
        controller.onLetterCommitted()
        assertEquals(ShiftState.LOWERCASE, controller.state.shift)
    }

    @Test fun doubleShiftEnablesCapsLock() {
        val controller = KeyboardController()
        controller.onShift(100)
        controller.onShift(300)
        assertEquals(ShiftState.CAPS_LOCK, controller.state.shift)
    }

    @Test fun automaticShiftCanBeClearedWithoutDisablingCapsLock() {
        val once = KeyboardController().apply { requestShiftOnce() }
        once.clearAutomaticShift()
        assertEquals(ShiftState.LOWERCASE, once.state.shift)

        val caps = KeyboardController().apply {
            onShift(100)
            onShift(300)
        }
        caps.clearAutomaticShift()
        assertEquals(ShiftState.CAPS_LOCK, caps.state.shift)
    }

    @Test fun passwordDisablesPersonalizedFeatures() {
        val context = InputFieldContext(InputFieldKind.PASSWORD)
        assertTrue(context.isSensitive)
        assertFalse(context.allowsSuggestions)
        assertFalse(context.allowsClipboardHistory)
    }

    @Test fun suggestionsRankExactPrefixAndCorrectSmallTypo() {
        val engine = BootstrapSuggestionEngine()
        assertEquals("привет", engine.suggest("при", KeyboardLanguage.RUSSIAN).first().text)
        assertEquals("hello", engine.bestCorrection("helo", KeyboardLanguage.ENGLISH))
    }

    @Test fun optionalNumberRowIsPrependedToLetters() {
        val repository = KeyboardLayoutRepository()
        val regular = repository.layout(KeyboardState())
        val withNumbers = repository.layout(KeyboardState(), numberRow = true)
        assertEquals(regular.rows.size + 1, withNumbers.rows.size)
        assertEquals(KeyboardKey.Text("1"), withNumbers.rows.first().first())
    }

    @Test fun frequencyDictionaryRanksCommonPrefixAndKeepsKnownWord() {
        val engine = BootstrapSuggestionEngine()
        engine.installDictionary(KeyboardLanguage.RUSSIAN, listOf(
            DictionaryWord("привет", 20_000),
            DictionaryWord("привычка", 4_000),
            DictionaryWord("пример", 15_000),
        ))
        assertEquals("привет", engine.suggest("прив", KeyboardLanguage.RUSSIAN).first().text)
        assertNull(engine.bestCorrection("привет", KeyboardLanguage.RUSSIAN))
    }

    @Test fun frequencyDictionaryCorrectsOneEditTypo() {
        val engine = BootstrapSuggestionEngine()
        engine.installDictionary(KeyboardLanguage.RUSSIAN, listOf(
            DictionaryWord("привет", 20_000),
            DictionaryWord("привёл", 3_000),
            DictionaryWord("ответ", 18_000),
        ))
        assertEquals("привет", engine.bestCorrection("превет", KeyboardLanguage.RUSSIAN))
    }

    @Test fun personalDictionarySnapshotIsNotRebuiltForEveryLetter() {
        var reads = 0
        val engine = BootstrapSuggestionEngine {
            reads++
            listOf("кодексик", "кодировщик")
        }

        engine.suggest("к", KeyboardLanguage.RUSSIAN)
        engine.suggest("ко", KeyboardLanguage.RUSSIAN)
        engine.bestCorrection("кодексик", KeyboardLanguage.RUSSIAN)
        assertEquals(1, reads)

        engine.invalidateCaches()
        engine.suggest("код", KeyboardLanguage.RUSSIAN)
        assertEquals(2, reads)
    }

    @Test fun repeatedPrefixUsesCachedResultAndLanguagesDoNotMix() {
        val engine = BootstrapSuggestionEngine()
        engine.installDictionary(KeyboardLanguage.RUSSIAN, listOf(DictionaryWord("привет", 20_000)))
        engine.installDictionary(KeyboardLanguage.ENGLISH, listOf(DictionaryWord("private", 20_000)))

        val first = engine.suggest("при", KeyboardLanguage.RUSSIAN)
        val cached = engine.suggest("при", KeyboardLanguage.RUSSIAN)

        assertSame(first, cached)
        assertEquals("привет", first.first().text)
        assertFalse(engine.suggest("при", KeyboardLanguage.ENGLISH).any { it.text == "привет" })
    }

    @Test fun userWordIsRankedWithoutCrossLanguageCorpusMutation() {
        val personal = mutableListOf("кодексик")
        val engine = BootstrapSuggestionEngine { personal }

        assertEquals("кодексик", engine.suggest("код", KeyboardLanguage.RUSSIAN).first().text)
        personal += "кодировщик"
        engine.invalidateCaches()
        assertTrue(engine.suggest("коди", KeyboardLanguage.RUSSIAN).any { it.text == "кодировщик" })
    }

    @Test fun contextAndPersonalFrequencyChangeRankingWithoutChangingCorpus() {
        val engine = BootstrapSuggestionEngine()
        engine.installDictionary(KeyboardLanguage.RUSSIAN, listOf(
            DictionaryWord("дело", 30_000),
            DictionaryWord("дела", 20_000),
        ))

        val neutral = engine.suggest("дел", KeyboardLanguage.RUSSIAN, context = SuggestionContext())
        val contextual = engine.suggest(
            "дел",
            KeyboardLanguage.RUSSIAN,
            context = SuggestionContext(
                previousWords = listOf("как"),
                userFrequencies = mapOf("дела" to 12),
            ),
        )

        assertEquals("дело", neutral.first().text)
        assertEquals("дела", contextual.first().text)
    }

    @Test fun blockedWordsNeverAppearOrBecomeCorrections() {
        val engine = BootstrapSuggestionEngine()
        engine.installDictionary(KeyboardLanguage.ENGLISH, listOf(
            DictionaryWord("hello", 30_000),
            DictionaryWord("help", 20_000),
        ))
        val blocked = setOf("hello")

        assertFalse(
            engine.suggest(
                "hel",
                KeyboardLanguage.ENGLISH,
                context = SuggestionContext(blockedWords = blocked),
            ).any { it.text == "hello" },
        )
        assertNotEquals("hello", engine.bestCorrection("helo", KeyboardLanguage.ENGLISH, blocked))
    }

    @Test fun languageDetectionUsesTypedScriptAndFallsBackForNeutralInput() {
        assertEquals(
            KeyboardLanguage.RUSSIAN,
            KeyboardLanguageDetector.detect("прив", KeyboardLanguage.ENGLISH),
        )
        assertEquals(
            KeyboardLanguage.ENGLISH,
            KeyboardLanguageDetector.detect("hello", KeyboardLanguage.RUSSIAN),
        )
        assertEquals(
            KeyboardLanguage.RUSSIAN,
            KeyboardLanguageDetector.detect("123", KeyboardLanguage.RUSSIAN),
        )
    }

    @Test fun explicitGenderOnlyBiasesMatchingFirstPersonForms() {
        val engine = BootstrapSuggestionEngine()
        engine.installDictionary(KeyboardLanguage.RUSSIAN, listOf(
            DictionaryWord("готов", 20_000),
            DictionaryWord("готова", 20_000),
        ))
        assertEquals(
            "готова",
            engine.suggest(
                "готов",
                KeyboardLanguage.RUSSIAN,
                context = SuggestionContext(grammaticalGender = GrammaticalGender.FEMININE),
            ).first().text,
        )
    }

    @Test fun lightweightMorphologyUsesPreviousPronoun() {
        val engine = BootstrapSuggestionEngine()
        engine.installDictionary(KeyboardLanguage.RUSSIAN, listOf(
            DictionaryWord("делает", 20_000),
            DictionaryWord("делаю", 20_000),
        ))
        assertEquals(
            "делаю",
            engine.suggest(
                "дела",
                KeyboardLanguage.RUSSIAN,
                context = SuggestionContext(previousWords = listOf("я")),
            ).first().text,
        )
    }

    @Test fun twentyRapidCachedSuggestionRequestsStayCheap() {
        val engine = BootstrapSuggestionEngine()
        val words = (0 until 5_000).map { index ->
            var value = index
            val suffix = buildString {
                repeat(4) {
                    append(('а'.code + value % 32).toChar())
                    value /= 32
                }
            }
            DictionaryWord("пр$suffix", 10_000 - index)
        }
        engine.installDictionary(KeyboardLanguage.RUSSIAN, words)
        engine.suggest("пр", KeyboardLanguage.RUSSIAN, 5)

        val samples = LongArray(20) {
            measureNanoTime { engine.suggest("пр", KeyboardLanguage.RUSSIAN, 5) }
        }
        val averageMicros = samples.average() / 1_000.0
        val maximumMicros = samples.max() / 1_000.0
        println("T9_CACHE_20_REQUESTS_AVG_US=$averageMicros MAX_US=$maximumMicros")
        assertTrue("Cached T9 lookup unexpectedly slow: max=${maximumMicros}us", maximumMicros < 50_000.0)
    }
}
