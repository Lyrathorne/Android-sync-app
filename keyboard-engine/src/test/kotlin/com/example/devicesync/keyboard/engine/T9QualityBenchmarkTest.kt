package com.example.devicesync.keyboard.engine

import com.example.devicesync.keyboard.engine.suggestions.BootstrapSuggestionEngine
import com.example.devicesync.keyboard.engine.suggestions.DictionaryWord
import com.example.devicesync.keyboard.engine.suggestions.SuggestionContext
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.system.measureNanoTime

class T9QualityBenchmarkTest {
    @Test
    fun localAnonymousCorpusMeetsQualityThresholds() {
        val cases = loadCorpus()
        val engine = engineFor(cases)
        var top1 = 0
        var top3 = 0
        var correctionHits = 0
        var correctionAttempts = 0
        var savedKeystrokes = 0
        var targetCharacters = 0

        cases.forEach { case ->
            val suggestions = engine.suggest(
                case.prefix,
                case.language,
                limit = 3,
                context = SuggestionContext(previousWords = listOf(case.previous)),
            ).map { it.text.lowercase() }
            if (suggestions.firstOrNull() == case.target) top1++
            if (case.target in suggestions) top3++
            targetCharacters += case.target.length
            if (case.target in suggestions) savedKeystrokes += (case.target.length - case.prefix.length).coerceAtLeast(0)

            if (case.typo != case.target) {
                correctionAttempts++
                if (engine.bestCorrection(case.typo, case.language)?.lowercase() == case.target) {
                    correctionHits++
                }
            }
        }

        val top1Accuracy = top1.toDouble() / cases.size
        val top3Accuracy = top3.toDouble() / cases.size
        val correctionRecall = correctionHits.toDouble() / correctionAttempts
        val keystrokeSavings = savedKeystrokes.toDouble() / targetCharacters
        println(
            "T9_QUALITY top1=$top1Accuracy top3=$top3Accuracy " +
                "correctionRecall=$correctionRecall keystrokeSavings=$keystrokeSavings",
        )

        assertTrue("Top-1 accuracy regressed: $top1Accuracy", top1Accuracy >= 0.70)
        assertTrue("Top-3 accuracy regressed: $top3Accuracy", top3Accuracy >= 0.95)
        assertTrue("Correction recall regressed: $correctionRecall", correctionRecall >= 0.75)
        assertTrue("Keystroke savings regressed: $keystrokeSavings", keystrokeSavings >= 0.25)
    }

    @Test
    fun knownWordsDoNotProduceFalseCorrections() {
        val cases = loadCorpus()
        val engine = engineFor(cases)
        val falseCorrections = cases.count { case ->
            engine.bestCorrection(case.target, case.language) != null
        }
        val falseCorrectionRate = falseCorrections.toDouble() / cases.size
        println("T9_FALSE_CORRECTION_RATE=$falseCorrectionRate")
        assertTrue("Known-word false correction rate regressed", falseCorrectionRate == 0.0)
    }

    @Test
    fun warmSuggestionP95StaysBelowCiThreshold() {
        val engine = BootstrapSuggestionEngine()
        val dictionary = generatedDictionary(20_000)
        engine.installDictionary(KeyboardLanguage.ENGLISH, dictionary)
        engine.suggest("per", KeyboardLanguage.ENGLISH, 5)

        val samples = LongArray(250) { index ->
            val prefix = if (index % 2 == 0) "per" else "tes"
            measureNanoTime { engine.suggest(prefix, KeyboardLanguage.ENGLISH, 5) }
        }.sorted()
        val p50Ms = percentile(samples, 0.50) / 1_000_000.0
        val p95Ms = percentile(samples, 0.95) / 1_000_000.0
        val p99Ms = percentile(samples, 0.99) / 1_000_000.0
        println("T9_WARM_LATENCY_MS p50=$p50Ms p95=$p95Ms p99=$p99Ms")
        assertTrue("Warm suggestion p95 exceeds 50 ms: $p95Ms", p95Ms <= 50.0)
    }

    @Test
    fun coldIndexBuildHasBoundedTimeAndHeapGrowth() {
        val dictionary = generatedDictionary(20_000)
        forceGc()
        val before = usedHeap()
        lateinit var engine: BootstrapSuggestionEngine
        val coldNanos = measureNanoTime {
            engine = BootstrapSuggestionEngine()
            engine.installDictionary(KeyboardLanguage.ENGLISH, dictionary)
        }
        engine.suggest("per", KeyboardLanguage.ENGLISH, 5)
        val heapGrowthMb = (usedHeap() - before).coerceAtLeast(0) / (1024.0 * 1024.0)
        val coldMs = coldNanos / 1_000_000.0
        println("T9_COLD_INDEX_MS=$coldMs T9_APPROX_HEAP_GROWTH_MB=$heapGrowthMb")
        assertTrue("Cold index build is unexpectedly slow: $coldMs ms", coldMs <= 2_500.0)
        assertTrue("T9 heap growth is unexpectedly high: $heapGrowthMb MB", heapGrowthMb <= 96.0)
    }

    private fun engineFor(cases: List<CorpusCase>): BootstrapSuggestionEngine =
        BootstrapSuggestionEngine().apply {
            cases.groupBy(CorpusCase::language).forEach { (language, languageCases) ->
                val words = languageCases.map(CorpusCase::target).distinct().mapIndexed { index, word ->
                    DictionaryWord(word, 50_000 - index * 250)
                }
                installDictionary(language, words)
            }
        }

    private fun loadCorpus(): List<CorpusCase> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/t9_quality_corpus.tsv"))
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                val columns = line.split('\t')
                CorpusCase(
                    language = if (columns[0] == "ru") KeyboardLanguage.RUSSIAN else KeyboardLanguage.ENGLISH,
                    previous = columns[1].lowercase(),
                    prefix = columns[2].lowercase(),
                    target = columns[3].lowercase(),
                    typo = columns[4].lowercase(),
                )
            }.toList()
        }
    }

    private fun generatedDictionary(size: Int): List<DictionaryWord> =
        (0 until size).map { index ->
            val prefix = when (index % 4) {
                0 -> "performance"
                1 -> "personal"
                2 -> "testing"
                else -> "text"
            }
            DictionaryWord(prefix + alphabeticSuffix(index), size - index + 1)
        }

    private fun alphabeticSuffix(number: Int): String {
        var value = number
        return buildString {
            repeat(4) {
                append(('a'.code + value % 26).toChar())
                value /= 26
            }
        }
    }

    private fun percentile(sorted: List<Long>, percentile: Double): Long =
        sorted[(ceil(sorted.size * percentile).toInt() - 1).coerceIn(0, sorted.lastIndex)]

    private fun usedHeap(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    private fun forceGc() {
        System.gc()
        System.runFinalization()
    }

    private data class CorpusCase(
        val language: KeyboardLanguage,
        val previous: String,
        val prefix: String,
        val target: String,
        val typo: String,
    )
}
