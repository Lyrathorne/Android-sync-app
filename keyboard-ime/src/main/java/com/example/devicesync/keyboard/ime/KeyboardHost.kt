package com.example.devicesync.keyboard.ime

/** Implemented by the host Application. The IME never owns or reads the network socket. */
interface KeyboardHost {
    val keyboardIntegration: KeyboardIntegration
}

interface KeyboardIntegration {
    fun isAutomaticClipboardSyncEnabled(): Boolean
    fun onLocalClipboardChanged(text: String, saveToHistory: Boolean, privateContext: Boolean = false)
    fun clipboardHistory(): List<KeyboardClipboardEntry>
    fun sendClipboardNow(text: String)
    fun removeClipboardItem(id: String)
    fun clearClipboardHistory()
    fun toggleClipboardPinned(id: String)
}

data class KeyboardClipboardEntry(
    val id: String,
    val text: String,
    val source: String,
    val timestampMillis: Long,
    val pinned: Boolean,
)
