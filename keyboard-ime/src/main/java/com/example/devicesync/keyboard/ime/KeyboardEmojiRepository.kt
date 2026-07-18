package com.example.devicesync.keyboard.ime

import android.content.Context

class KeyboardEmojiRepository(context: Context) {
    private val values = context.getSharedPreferences("device_sync_keyboard_emoji", Context.MODE_PRIVATE)
    @Volatile private var recentCache: List<String>? = null
    @Volatile private var favoritesCache: List<String>? = null

    fun recent(): List<String> = recentCache ?: values.getString("recent", "").orEmpty().split(' ')
        .filter(String::isNotBlank).take(32).also { recentCache = it }

    fun record(emoji: String) {
        val updated = (listOf(emoji) + recent().filterNot { it == emoji }).take(32)
        recentCache = updated
        values.edit().putString("recent", updated.joinToString(" ")).apply()
    }

    fun favorites(): List<String> = favoritesCache ?: values.getString("favorites", "").orEmpty()
        .split(SEPARATOR).filter(String::isNotBlank).take(MAX_FAVORITES).also { favoritesCache = it }

    fun isFavorite(emoji: String): Boolean = emoji in favorites()

    fun toggleFavorite(emoji: String): Boolean {
        val wasFavorite = isFavorite(emoji)
        val updated = if (wasFavorite) favorites().filterNot { it == emoji }
        else (listOf(emoji) + favorites().filterNot { it == emoji }).take(MAX_FAVORITES)
        favoritesCache = updated
        values.edit().putString("favorites", updated.joinToString(SEPARATOR.toString())).apply()
        return !wasFavorite
    }

    private companion object {
        const val SEPARATOR = '\u001F'
        const val MAX_FAVORITES = 96
    }
}
