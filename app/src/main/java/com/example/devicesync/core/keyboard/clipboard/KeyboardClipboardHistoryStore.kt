package com.example.devicesync.core.keyboard.clipboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.devicesync.keyboard.ime.KeyboardClipboardEntry
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyboardClipboardHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()
    private var entries: List<KeyboardClipboardEntry> = load()

    fun snapshot(): List<KeyboardClipboardEntry> = synchronized(lock) { entries.toList() }

    fun add(text: String, source: String) = synchronized(lock) {
        if (text.isBlank() || text.encodeToByteArray().size > MAX_BYTES) return@synchronized
        val existing = entries.firstOrNull { it.text == text }
        val fresh = KeyboardClipboardEntry(
            id = existing?.id ?: UUID.randomUUID().toString(),
            text = text,
            source = source,
            timestampMillis = System.currentTimeMillis(),
            pinned = existing?.pinned == true,
        )
        entries = (listOf(fresh) + entries.filterNot { it.text == text })
            .let { list -> list.filter { it.pinned } + list.filterNot { it.pinned } }
            .take(MAX_ITEMS)
        persist()
    }

    fun remove(id: String) = synchronized(lock) {
        entries = entries.filterNot { it.id == id }
        persist()
    }

    fun clear() = synchronized(lock) {
        entries = entries.filter { it.pinned }
        persist()
    }

    fun togglePinned(id: String) = synchronized(lock) {
        entries = entries.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }
            .let { list -> list.filter { it.pinned } + list.filterNot { it.pinned } }
        persist()
    }

    private fun persist() {
        val json = JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject().apply {
                    put("id", entry.id)
                    put("text", entry.text)
                    put("source", entry.source)
                    put("timestamp", entry.timestampMillis)
                    put("pinned", entry.pinned)
                })
            }
        }.toString().encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val value = cipher.iv + cipher.doFinal(json)
        preferences.edit().putString(KEY_PAYLOAD, Base64.encodeToString(value, Base64.NO_WRAP)).apply()
    }

    private fun load(): List<KeyboardClipboardEntry> = runCatching {
        val encoded = preferences.getString(KEY_PAYLOAD, null) ?: return emptyList()
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, payload.copyOfRange(0, IV_BYTES)))
        val array = JSONArray(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)).decodeToString())
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(KeyboardClipboardEntry(
                    item.getString("id"), item.getString("text"), item.getString("source"),
                    item.getLong("timestamp"), item.optBoolean("pinned"),
                ))
            }
        }.take(MAX_ITEMS)
    }.getOrElse {
        preferences.edit().remove(KEY_PAYLOAD).apply()
        emptyList()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "keyboard_clipboard_history"
        const val KEY_PAYLOAD = "encrypted_entries"
        const val KEY_ALIAS = "devicesync_keyboard_clipboard_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val MAX_ITEMS = 50
        const val MAX_BYTES = 256 * 1024
    }
}
