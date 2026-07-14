package com.example.devicesync.core.sharing

import android.content.ClipData
import android.content.ClipboardManager
import com.example.devicesync.core.network.SharingMessageListener
import com.example.devicesync.core.network.SharingTransport
import com.example.devicesync.core.protocol.*
import com.example.devicesync.core.settings.DeviceIdentityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID
import android.content.Context

data class SharedTextItem(val itemId: String, val kind: String, val text: String, val receivedAtUtc: String)

class SharingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("sharing", Context.MODE_PRIVATE)
    var clipboardEnabled: Boolean
        get() = preferences.getBoolean("clipboard_enabled", false)
        set(value) { preferences.edit().putBoolean("clipboard_enabled", value).apply() }
}

class SharingManager(
    private val transport: SharingTransport,
    private val identityRepository: DeviceIdentityRepository,
    private val clipboard: ClipboardManager,
    private val preferences: SharingPreferences,
) : SharingMessageListener {
    companion object { const val MAXIMUM_TEXT_BYTES = 256 * 1024 }
    private val seenRevisions = linkedSetOf<String>()
    private val _history = MutableStateFlow<List<SharedTextItem>>(emptyList())
    val history: StateFlow<List<SharedTextItem>> = _history.asStateFlow()
    private val _pendingShareText = MutableStateFlow<String?>(null)
    val pendingShareText: StateFlow<String?> = _pendingShareText.asStateFlow()
    private var suppressClipboardText: String? = null
    var clipboardEnabled: Boolean
        get() = preferences.clipboardEnabled
        set(value) { preferences.clipboardEnabled = value }

    init { transport.addSharingListener(this) }

    fun submitAndroidShare(text: String?) { _pendingShareText.value = text?.takeIf { it.isNotBlank() } }
    fun consumeAndroidShare() { _pendingShareText.value = null }

    suspend fun sendClipboard(text: String) {
        validate(text)
        check(clipboardEnabled) { "Clipboard synchronization is disabled." }
        val revision = UUID.randomUUID().toString()
        seenRevisions += revision
        transport.sendSharingMessage(ProtocolMessageType.CLIPBOARD_UPDATE.value, ProtocolSerializer.payloadToJson(
            ClipboardUpdatePayload(
                revision, identityRepository.getOrCreateDeviceId(),
                if (isSafeUrl(text)) "text/uri-list" else "text/plain", text, Instant.now().toString(),
            )
        ))
    }

    suspend fun onLocalClipboardChanged(text: String) {
        if (!clipboardEnabled) return
        if (suppressClipboardText == text) { suppressClipboardText = null; return }
        sendClipboard(text)
    }

    suspend fun sendText(text: String) {
        validate(text)
        transport.sendSharingMessage(ProtocolMessageType.TEXT_SHARE.value, ProtocolSerializer.payloadToJson(
            TextSharePayload(UUID.randomUUID().toString(), if (isSafeUrl(text)) "url" else "text", text, Instant.now().toString())
        ))
    }

    override suspend fun onSharingMessage(message: ProtocolMessage) {
        when (message.type) {
            ProtocolMessageType.CLIPBOARD_UPDATE.value -> {
                val payload = ProtocolSerializer.decodePayload<ClipboardUpdatePayload>(message.payload)
                validate(payload.text)
                if (!seenRevisions.add(payload.revisionId) || !clipboardEnabled) return
                suppressClipboardText = payload.text
                clipboard.setPrimaryClip(ClipData.newPlainText("DeviceSync", payload.text))
            }
            ProtocolMessageType.TEXT_SHARE.value -> {
                val payload = ProtocolSerializer.decodePayload<TextSharePayload>(message.payload)
                validate(payload.text)
                if (_history.value.any { it.itemId == payload.itemId }) return
                _history.value = (listOf(SharedTextItem(payload.itemId, payload.kind, payload.text, Instant.now().toString())) + _history.value).take(50)
            }
        }
    }

    private fun validate(text: String) {
        require(text.isNotEmpty() && text.encodeToByteArray().size <= MAXIMUM_TEXT_BYTES) { "Shared text is empty or exceeds 256 KiB." }
    }

    private fun isSafeUrl(text: String): Boolean = runCatching {
        val uri = java.net.URI(text)
        uri.scheme == "http" || uri.scheme == "https"
    }.getOrDefault(false)
}
