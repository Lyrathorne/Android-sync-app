package com.example.devicesync.keyboard.ime

import android.content.res.AssetManager
import com.example.devicesync.keyboard.engine.KeyboardLanguage
import com.example.devicesync.keyboard.engine.suggestions.DictionaryWord
import java.io.BufferedReader
import java.io.InputStreamReader

internal class FrequencyDictionaryLoader(private val assets: AssetManager) {
    fun load(language: KeyboardLanguage): List<DictionaryWord> {
        val assetNames = when (language) {
            KeyboardLanguage.RUSSIAN -> listOf("dictionaries/ru_50k.txt", "dictionaries/ru_slang.txt")
            KeyboardLanguage.ENGLISH -> listOf("dictionaries/en_50k.txt", "dictionaries/en_slang.txt")
        }
        return assetNames.flatMap { assetName ->
            assets.open(assetName).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8), BUFFER_SIZE).useLines { lines ->
                    lines.mapNotNull(::parseLine).toList()
                }
            }
        }
    }

    private fun parseLine(line: String): DictionaryWord? {
        val separator = line.lastIndexOf(' ')
        if (separator <= 0 || separator == line.lastIndex) return null
        val word = line.substring(0, separator).trim()
        val frequency = line.substring(separator + 1).trim().toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            ?: return null
        return DictionaryWord(word, frequency)
    }

    private companion object { const val BUFFER_SIZE = 32 * 1024 }
}
