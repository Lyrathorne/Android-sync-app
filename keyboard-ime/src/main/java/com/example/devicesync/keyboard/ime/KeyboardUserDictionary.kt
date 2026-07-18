package com.example.devicesync.keyboard.ime

import android.content.Context

class KeyboardUserDictionary(context: Context) {
    private val values = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    @Volatile private var cachedSnapshot: KeyboardPersonalizationSnapshot? = null

    fun words(): List<String> = snapshot().wordFrequencies.keys.toList()

    fun snapshot(): KeyboardPersonalizationSnapshot = cachedSnapshot ?: synchronized(this) {
        cachedSnapshot ?: loadSnapshot().also { cachedSnapshot = it }
    }

    private fun loadSnapshot(): KeyboardPersonalizationSnapshot {
        migrateLegacyWords()
        val frequencies = values.all.asSequence()
            .filter { (key, value) -> key.startsWith(WORD_PREFIX) && value is Int }
            .sortedByDescending { (_, value) -> value as Int }
            .map { (key, value) -> key.removePrefix(WORD_PREFIX) to (value as Int) }
            .take(MAX_WORDS)
            .toMap(LinkedHashMap())
        val contexts = values.all.asSequence()
            .filter { (key, value) -> key.startsWith(CONTEXT_PREFIX) && value is Int }
            .sortedByDescending { (_, value) -> value as Int }
            .take(MAX_CONTEXTS)
            .associate { (key, value) -> key.removePrefix(CONTEXT_PREFIX) to (value as Int) }
        val blocked = values.getStringSet(BLOCKED_WORDS, emptySet()).orEmpty().toSet()
        return KeyboardPersonalizationSnapshot(frequencies, contexts, blocked)
    }

    fun add(word: String) {
        recordUsage(word)
    }

    fun recordUsage(word: String, previousWord: String? = null) {
        val normalized = normalize(word) ?: return
        val key = WORD_PREFIX + normalized
        val editor = values.edit()
            .putInt(key, (values.getInt(key, 0) + 1).coerceAtMost(Int.MAX_VALUE))
        normalizeContext(previousWord.orEmpty())?.let { previous ->
            val contextKey = "$previous\u001f$normalized"
            val storageKey = CONTEXT_PREFIX + contextKey
            editor.putInt(storageKey, (values.getInt(storageKey, 0) + 1).coerceAtMost(Int.MAX_VALUE))
        }
        editor.apply()
        synchronized(this) {
            cachedSnapshot = null
        }
    }

    fun block(word: String) {
        val normalized = normalize(word) ?: return
        values.edit().putStringSet(BLOCKED_WORDS, snapshot().blockedWords + normalized).apply()
        cachedSnapshot = null
    }

    fun unblock(word: String) {
        val normalized = normalize(word) ?: return
        values.edit().putStringSet(BLOCKED_WORDS, snapshot().blockedWords - normalized).apply()
        cachedSnapshot = null
    }

    fun clear() {
        values.edit().apply {
            values.all.keys.filter { it.startsWith(WORD_PREFIX) || it.startsWith(CONTEXT_PREFIX) }.forEach(::remove)
            remove(LEGACY_WORDS)
            remove(BLOCKED_WORDS)
        }.apply()
        cachedSnapshot = KeyboardPersonalizationSnapshot()
    }

    private fun normalize(word: String): String? = word.trim().lowercase()
        .takeIf { it.length in 2..48 && it.all { char -> char.isLetter() || char == '-' || char == '\'' } }
    private fun normalizeContext(word: String): String? = word.trim().lowercase()
        .takeIf { it.length in 1..48 && it.all { char -> char.isLetter() || char == '-' || char == '\'' } }

    private fun migrateLegacyWords() {
        val legacy = values.getStringSet(LEGACY_WORDS, emptySet()).orEmpty()
        if (legacy.isEmpty()) return
        values.edit().apply {
            legacy.mapNotNull(::normalize).forEach { putInt(WORD_PREFIX + it, 1) }
            remove(LEGACY_WORDS)
        }.apply()
    }

    private companion object {
        const val FILE = "device_sync_keyboard_user_dictionary"
        const val LEGACY_WORDS = "words"
        const val WORD_PREFIX = "word:"
        const val CONTEXT_PREFIX = "context:"
        const val BLOCKED_WORDS = "blocked_words"
        const val MAX_WORDS = 5_000
        const val MAX_CONTEXTS = 10_000
    }
}

data class KeyboardPersonalizationSnapshot(
    val wordFrequencies: Map<String, Int> = emptyMap(),
    val contextFrequencies: Map<String, Int> = emptyMap(),
    val blockedWords: Set<String> = emptySet(),
)
