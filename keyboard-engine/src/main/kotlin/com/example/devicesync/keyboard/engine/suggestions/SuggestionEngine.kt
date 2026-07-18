package com.example.devicesync.keyboard.engine.suggestions

import com.example.devicesync.keyboard.engine.KeyboardLanguage
import java.util.Locale
import kotlin.math.max

data class Suggestion(val text: String, val score: Int)
data class DictionaryWord(val text: String, val frequency: Int)
enum class GrammaticalGender { NONE, MASCULINE, FEMININE }

data class SuggestionContext(
    val previousWords: List<String> = emptyList(),
    val userFrequencies: Map<String, Int> = emptyMap(),
    val contextFrequencies: Map<String, Int> = emptyMap(),
    val blockedWords: Set<String> = emptySet(),
    val grammaticalGender: GrammaticalGender = GrammaticalGender.NONE,
)

interface SuggestionEngine {
    fun suggest(
        prefix: String,
        language: KeyboardLanguage,
        limit: Int = 3,
        context: SuggestionContext = SuggestionContext(),
    ): List<Suggestion>
    fun bestCorrection(
        word: String,
        language: KeyboardLanguage,
        blockedWords: Set<String> = emptySet(),
    ): String?
}

object KeyboardLanguageDetector {
    fun detect(text: String, fallback: KeyboardLanguage): KeyboardLanguage {
        var cyrillic = 0
        var latin = 0
        text.forEach { character ->
            when {
                character in '\u0400'..'\u04ff' -> cyrillic++
                character in 'a'..'z' || character in 'A'..'Z' -> latin++
            }
        }
        return when {
            cyrillic > latin -> KeyboardLanguage.RUSSIAN
            latin > cyrillic -> KeyboardLanguage.ENGLISH
            else -> fallback
        }
    }
}

/**
 * Local frequency-based T9 engine. Dictionaries are installed asynchronously by the Android layer.
 * Prefix buckets preserve corpus frequency order; a BK-tree handles one/two-edit corrections.
 */
class BootstrapSuggestionEngine(
    private val additionalWords: () -> Collection<String> = { emptyList() },
) : SuggestionEngine {
    @Volatile
    private var dictionaries: Map<KeyboardLanguage, LanguageDictionary> = emptyMap()
    private val resultCache = object : LinkedHashMap<String, List<Suggestion>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Suggestion>>?): Boolean = size > 256
    }
    @Volatile private var additionalWordsCache: List<String>? = null

    private val bootstrapRussian = listOf(
        "привет", "спасибо", "пожалуйста", "хорошо", "сегодня", "завтра", "можно", "нужно",
        "будет", "работает", "сообщение", "устройство", "компьютер", "телефон", "файл", "текст",
        "да", "нет", "как", "что", "это", "для", "когда", "почему", "отправить", "получить",
    )
    private val bootstrapEnglish = listOf(
        "hello", "thanks", "please", "good", "today", "tomorrow", "can", "need", "will", "works",
        "message", "device", "computer", "phone", "file", "text", "yes", "no", "how", "what",
        "this", "when", "why", "send", "receive", "devicesync",
    )

    fun installDictionary(language: KeyboardLanguage, words: List<DictionaryWord>) {
        if (words.isEmpty()) return
        val normalized = words.asSequence()
            .mapNotNull { entry ->
                normalize(entry.text).takeIf { it.isValidDictionaryWord() }
                    ?.let { DictionaryWord(it, entry.frequency.coerceAtLeast(1)) }
            }
            .distinctBy(DictionaryWord::text)
            .sortedByDescending(DictionaryWord::frequency)
            .toList()
        if (normalized.isEmpty()) return
        val built = LanguageDictionary(normalized)
        synchronized(this) {
            dictionaries = dictionaries + (language to built)
            resultCache.clear()
        }
    }

    @Synchronized
    fun invalidateCaches() {
        resultCache.clear()
        additionalWordsCache = null
    }

    fun isDictionaryReady(language: KeyboardLanguage): Boolean = dictionaries.containsKey(language)

    override fun suggest(
        prefix: String,
        language: KeyboardLanguage,
        limit: Int,
        context: SuggestionContext,
    ): List<Suggestion> {
        val normalized = normalize(prefix)
        val wanted = limit.coerceIn(1, 5)
        val normalizedPrevious = context.previousWords.takeLast(2).map(::normalize)
        val blocked = context.blockedWords.asSequence().map(::normalize).toHashSet()
        val cacheKey = buildString {
            append(language.name).append('|').append(normalized).append('|').append(wanted)
            append('|').append(normalizedPrevious.joinToString(","))
            append('|').append(context.grammaticalGender.name)
            append('|').append(System.identityHashCode(context.userFrequencies))
            append('|').append(System.identityHashCode(context.contextFrequencies))
            append('|').append(blocked.hashCode())
        }
        synchronized(this) { resultCache[cacheKey]?.let { return it } }
        if (normalized.isEmpty()) {
            val top = dictionaries[language]?.top(wanted * 4)
                ?.asSequence()
                ?.filterNot { it.text in blocked }
                ?.map { Suggestion(it.text, rankedScore(it, normalizedPrevious, context)) }
                ?.sortedByDescending(Suggestion::score)
                ?.take(wanted)
                ?.toList()
                ?: bootstrap(language).take(wanted).mapIndexed { index, word -> Suggestion(word, 100_000 - index * 100) }
            synchronized(this) { resultCache[cacheKey] = top }
            return top
        }
        val user = cachedAdditionalWords().asSequence()
            .filter { it.startsWith(normalized) && it.isValidDictionaryWord() && it !in blocked }
            .distinct()
            .mapIndexed { index, word ->
                Suggestion(
                    word,
                    USER_WORD_SCORE + personalizationBonus(word, normalizedPrevious, context) -
                        index * 1_000 - (word.length - normalized.length),
                )
            }
        val dictionary = dictionaries[language]
        val corpusEntries = dictionary?.prefixMatches(normalized, wanted * 3).orEmpty()
        val corpus = corpusEntries.asSequence()
            .filterNot { it.text in blocked }
            .map { entry ->
                val exactBonus = if (entry.text == normalized) EXACT_WORD_BONUS else 0
                Suggestion(
                    entry.text,
                    rankedScore(entry, normalizedPrevious, context) + exactBonus -
                        (entry.text.length - normalized.length),
                )
            }
        val fallback = if (dictionary == null) bootstrap(language).asSequence()
            .filter { it.startsWith(normalized) }
            .mapIndexed { index, word -> Suggestion(word, 100_000 - index * 100) }
        else emptySequence()

        val result = (user + corpus + fallback)
            .distinctBy { it.text }
            .sortedWith(compareByDescending<Suggestion>(Suggestion::score).thenBy { it.text.length }.thenBy(Suggestion::text))
            .take(wanted)
            .map { it.copy(text = restoreCase(prefix, it.text)) }
            .toList()
        synchronized(this) { resultCache[cacheKey] = result }
        return result
    }

    override fun bestCorrection(
        word: String,
        language: KeyboardLanguage,
        blockedWords: Set<String>,
    ): String? {
        val normalized = normalize(word)
        val blocked = blockedWords.asSequence().map(::normalize).toHashSet()
        if (normalized.length < 3) return null
        if (normalized in cachedAdditionalWords()) return null
        val dictionary = dictionaries[language]
        if (dictionary == null) {
            return bootstrap(language)
                .map { it to levenshtein(normalized, it) }
                .filter { (candidate, distance) -> distance <= 1 && candidate !in blocked }
                .minByOrNull { it.second }
                ?.first
                ?.let { restoreCase(word, it) }
        }
        if (dictionary.contains(normalized)) return null

        val maximumDistance = if (normalized.length <= 3) 1 else 2
        val best = dictionary.corrections(normalized, maximumDistance)
            .asSequence()
            .filter { candidate -> candidate.text.length >= 2 && candidate.text !in blocked }
            .maxByOrNull { candidate ->
                frequencyScore(candidate.frequency) - levenshtein(normalized, candidate.text) * EDIT_DISTANCE_PENALTY
            }
            ?: return null
        val distance = levenshtein(normalized, best.text)
        if (distance > maximumDistance) return null
        return restoreCase(word, best.text)
    }

    private fun rankedScore(
        entry: DictionaryWord,
        previousWords: List<String>,
        context: SuggestionContext,
    ): Int = frequencyScore(entry.frequency) + personalizationBonus(entry.text, previousWords, context)

    private fun personalizationBonus(
        candidate: String,
        previousWords: List<String>,
        context: SuggestionContext,
    ): Int {
        val userBonus = context.userFrequencies[candidate].orZero().coerceAtMost(100) * 12_000
        val previous = previousWords.lastOrNull()
        val learnedContext = previous?.let { context.contextFrequencies[contextKey(it, candidate)].orZero() }
            ?.coerceAtMost(100)?.times(18_000) ?: 0
        val builtInContext = CONTEXT_BIGRAMS[previous]?.indexOf(candidate)?.let { index ->
            (CONTEXT_BONUS - index * 30_000).coerceAtLeast(0)
        } ?: 0
        val genderBonus = when (context.grammaticalGender) {
            GrammaticalGender.NONE -> 0
            GrammaticalGender.MASCULINE -> if (candidate in MASCULINE_FORMS) GENDER_BONUS else 0
            GrammaticalGender.FEMININE -> if (candidate in FEMININE_FORMS) GENDER_BONUS else 0
        }
        return userBonus + learnedContext + builtInContext + genderBonus +
            morphologyBonus(previousWords, candidate)
    }

    private fun morphologyBonus(previousWords: List<String>, candidate: String): Int {
        val previous = previousWords.lastOrNull() ?: return 0
        val endings = when (previous) {
            "я" -> listOf("ю", "у", "ла", "л")
            "мы" -> listOf("ем", "им", "ли")
            "ты" -> listOf("ешь", "ишь", "ла", "л")
            "вы" -> listOf("ете", "ите", "ли")
            "они" -> listOf("ют", "ут", "ат", "ят", "ли")
            "без", "для", "от", "до" -> listOf("а", "я", "ы", "и")
            "к", "по" -> listOf("у", "ю", "е", "и")
            else -> return 0
        }
        val matchedIndex = endings.indexOfFirst(candidate::endsWith)
        return if (matchedIndex >= 0) MORPHOLOGY_BONUS - matchedIndex * 8_000 else 0
    }

    private fun contextKey(previous: String, candidate: String): String =
        "${normalize(previous)}\u001f${normalize(candidate)}"

    private fun Int?.orZero(): Int = this ?: 0

    internal fun levenshtein(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { index, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = index + 1
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun bootstrap(language: KeyboardLanguage): List<String> =
        if (language == KeyboardLanguage.RUSSIAN) bootstrapRussian else bootstrapEnglish

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
    private fun cachedAdditionalWords(): List<String> = additionalWordsCache ?: synchronized(this) {
        additionalWordsCache ?: additionalWords().asSequence()
            .map(::normalize)
            .filter { it.isValidDictionaryWord() }
            .distinct()
            .toList()
            .also { additionalWordsCache = it }
    }
    private fun String.isValidDictionaryWord(): Boolean =
        length in 1..40 && all { it.isLetter() || it == '-' || it == '\'' || it == 'ё' }

    private fun restoreCase(source: String, result: String): String = when {
        source.all(Char::isUpperCase) && source.any(Char::isLetter) -> result.uppercase(Locale.ROOT)
        source.firstOrNull()?.isUpperCase() == true -> result.replaceFirstChar(Char::uppercase)
        else -> result
    }

    private fun frequencyScore(frequency: Int): Int {
        var value = max(1, frequency)
        var digits = 0
        while (value > 0) { digits++; value /= 10 }
        return digits * 100_000 + frequency.coerceAtMost(99_999)
    }

    private inner class LanguageDictionary(words: List<DictionaryWord>) {
        private val rankedWords = words
        private val exact = words.asSequence().map(DictionaryWord::text).toHashSet()
        private val prefixTrie = PrefixTrie(words)
        private val tree = BkTree(words.take(MAX_CORRECTION_WORDS))

        fun contains(word: String): Boolean = word in exact
        fun top(limit: Int): List<DictionaryWord> = rankedWords.take(limit)

        fun prefixMatches(prefix: String, limit: Int): List<DictionaryWord> =
            prefixTrie.matches(prefix, limit)

        fun corrections(word: String, distance: Int): List<DictionaryWord> = tree.search(word, distance)
    }

    private class PrefixTrie(words: List<DictionaryWord>) {
        private val root = Node()

        init {
            words.forEach { entry ->
                var node = root
                entry.text.take(MAX_TRIE_DEPTH).forEach { character ->
                    node = node.childOrCreate(character)
                    node.addCandidate(entry)
                }
            }
        }

        fun matches(prefix: String, limit: Int): List<DictionaryWord> {
            var node = root
            prefix.take(MAX_TRIE_DEPTH).forEach { character ->
                node = node.child(character) ?: return emptyList()
            }
            return node.candidates().asSequence()
                .filter { it.text.startsWith(prefix) }
                .take(limit)
                .toList()
        }

        private class Node {
            private var children: HashMap<Char, Node>? = null
            private var best: ArrayList<DictionaryWord>? = null

            fun child(character: Char): Node? = children?.get(character)

            fun childOrCreate(character: Char): Node {
                val values = children ?: HashMap<Char, Node>(2).also { children = it }
                return values.getOrPut(character) { Node() }
            }

            fun addCandidate(entry: DictionaryWord) {
                val values = best ?: ArrayList<DictionaryWord>(MAX_PREFIX_RESULTS).also { best = it }
                if (values.size < MAX_PREFIX_RESULTS) values += entry
            }

            fun candidates(): List<DictionaryWord> = best.orEmpty()
        }
    }

    private inner class BkTree(words: List<DictionaryWord>) {
        private val root = words.firstOrNull()?.let(::Node)

        init { if (root != null) words.drop(1).forEach(root::add) }

        fun search(word: String, maximumDistance: Int): List<DictionaryWord> = buildList {
            root?.search(word, maximumDistance, this)
        }

        private inner class Node(val entry: DictionaryWord) {
            private val children = mutableMapOf<Int, Node>()

            fun add(candidate: DictionaryWord) {
                val distance = levenshtein(entry.text, candidate.text)
                children[distance]?.add(candidate) ?: run { children[distance] = Node(candidate) }
            }

            fun search(word: String, maximumDistance: Int, output: MutableList<DictionaryWord>) {
                val distance = levenshtein(entry.text, word)
                if (distance <= maximumDistance) output += entry
                val start = distance - maximumDistance
                val end = distance + maximumDistance
                children.forEach { (edge, child) -> if (edge in start..end) child.search(word, maximumDistance, output) }
            }
        }
    }

    private companion object {
        const val USER_WORD_SCORE = 2_000_000
        const val EXACT_WORD_BONUS = 1_000_000
        const val EDIT_DISTANCE_PENALTY = 300_000
        const val MAX_CORRECTION_WORDS = 30_000
        const val MAX_PREFIX_RESULTS = 24
        const val MAX_TRIE_DEPTH = 6
        const val CONTEXT_BONUS = 360_000
        // An explicit local preference should be strong enough to reorder a matching
        // first-person form above the exact stem, while autocorrection still keeps
        // exact dictionary input untouched.
        const val GENDER_BONUS = 1_100_000
        const val MORPHOLOGY_BONUS = 80_000

        val CONTEXT_BIGRAMS = mapOf(
            "я" to listOf("не", "тоже", "уже", "сейчас", "буду", "могу"),
            "не" to listOf("знаю", "могу", "буду", "хочу", "надо"),
            "как" to listOf("дела", "ты", "это", "раз", "будто"),
            "доброе" to listOf("утро"),
            "спокойной" to listOf("ночи"),
            "thank" to listOf("you"),
            "how" to listOf("are", "is", "do", "can"),
            "i" to listOf("am", "will", "can", "need", "think"),
            "see" to listOf("you"),
        )
        val MASCULINE_FORMS = setOf("готов", "сделал", "написал", "отправил", "пришёл")
        val FEMININE_FORMS = setOf("готова", "сделала", "написала", "отправила", "пришла")
    }
}
