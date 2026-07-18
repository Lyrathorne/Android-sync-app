package com.example.devicesync.core.sharing

import android.content.Context
import com.example.devicesync.core.network.NetworkLogger
import com.example.devicesync.core.network.SharingMessageListener
import com.example.devicesync.core.network.SharingTransport
import com.example.devicesync.core.protocol.ClipboardUpdatePayload
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.ProtocolSerializer
import com.example.devicesync.core.protocol.TextSharePayload
import com.example.devicesync.core.settings.DeviceIdentityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.time.Instant
import java.util.UUID

data class SharedTextItem(val itemId: String, val kind: String, val text: String, val receivedAtUtc: String)

interface ClipboardSyncPreferences {
    var clipboardEnabled: Boolean
    fun isClipboardAllowed(deviceId: String?): Boolean
    fun setClipboardAllowed(deviceId: String, allowed: Boolean)
}

class SharingPreferences(context: Context) : ClipboardSyncPreferences {
    private val preferences = context.getSharedPreferences("sharing", Context.MODE_PRIVATE)

    override var clipboardEnabled: Boolean
        get() = preferences.getBoolean("clipboard_enabled", false)
        set(value) { preferences.edit().putBoolean("clipboard_enabled", value).apply() }

    val clipboardAllowedDeviceIds: Set<String>
        get() = preferences.getStringSet("clipboard_allowed_devices", emptySet()).orEmpty().toSet()

    override fun isClipboardAllowed(deviceId: String?): Boolean = deviceId != null && deviceId in clipboardAllowedDeviceIds

    override fun setClipboardAllowed(deviceId: String, allowed: Boolean) {
        val updated = clipboardAllowedDeviceIds.toMutableSet().apply {
            if (allowed) add(deviceId) else remove(deviceId)
        }
        preferences.edit().putStringSet("clipboard_allowed_devices", updated).apply()
    }
}

class SharingManager(
    private val transport: SharingTransport,
    private val identityRepository: DeviceIdentityRepository,
    private val preferences: ClipboardSyncPreferences,
    private val scope: CoroutineScope,
    private val currentRemoteDeviceId: () -> String?,
    private val applyClipboard: (String) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onClipboardReceived: (text: String, source: String) -> Unit = { _, _ -> },
) : SharingMessageListener {
    companion object {
        const val MAXIMUM_TEXT_BYTES = 256 * 1024
        const val DEBOUNCE_MILLIS = 300L
        const val PENDING_TTL_MILLIS = 2 * 60 * 1000L
        const val REVISION_TTL_MILLIS = 10 * 60 * 1000L
        const val MAXIMUM_SEEN_REVISIONS = 512
    }

    private data class PendingClipboard(val text: String, val createdAtMillis: Long)

    private val gate = Any()
    private val seenRevisions = linkedMapOf<String, Long>()
    private val _history = MutableStateFlow<List<SharedTextItem>>(emptyList())
    val history: StateFlow<List<SharedTextItem>> = _history.asStateFlow()
    private val _pendingShareText = MutableStateFlow<String?>(null)
    val pendingShareText: StateFlow<String?> = _pendingShareText.asStateFlow()
    private var suppressClipboardText: String? = null
    private var lastObservedLocalClipboardText: String? = null
    private var lastAppliedRemoteText: String? = null
    private var pendingClipboard: PendingClipboard? = null
    private var debounceJob: Job? = null
    private var lastRemoteDeviceId: String? = null

    var clipboardEnabled: Boolean
        get() = preferences.clipboardEnabled
        set(value) {
            preferences.clipboardEnabled = value
            if (!value) synchronized(gate) { pendingClipboard = null }
        }

    init { transport.addSharingListener(this) }

    fun submitAndroidShare(text: String?) { _pendingShareText.value = text?.takeIf { it.isNotBlank() } }
    fun consumeAndroidShare() { _pendingShareText.value = null }

    fun currentClipboardDeviceId(): String? = currentRemoteDeviceId()?.also { lastRemoteDeviceId = it } ?: lastRemoteDeviceId
    fun isClipboardAllowedForCurrentDevice(): Boolean = preferences.isClipboardAllowed(currentClipboardDeviceId())
    fun setClipboardAllowedForCurrentDevice(allowed: Boolean): Boolean {
        val deviceId = currentClipboardDeviceId() ?: return false
        preferences.setClipboardAllowed(deviceId, allowed)
        if (!allowed) synchronized(gate) { pendingClipboard = null }
        return true
    }

    suspend fun sendClipboard(text: String) {
        requireAutomaticPermission()
        sendClipboardInternal(text, isManual = false)
    }

    suspend fun sendClipboardNow(text: String) = sendClipboardInternal(text, isManual = true)

    private suspend fun sendClipboardInternal(text: String, isManual: Boolean) {
        val contentType = validate(text)
        val originDeviceId = identityRepository.getOrCreateDeviceId()
        val revision = UUID.randomUUID().toString()
        rememberRevision(revision)
        transport.sendSharingMessage(
            ProtocolMessageType.CLIPBOARD_UPDATE.value,
            ProtocolSerializer.payloadToJson(
                ClipboardUpdatePayload(
                    revision,
                    originDeviceId,
                    contentType,
                    text,
                    Instant.now().toString(),
                    isManual,
                )
            )
        )
        synchronized(gate) {
            lastObservedLocalClipboardText = text
            if (!isManual && pendingClipboard?.text == text) pendingClipboard = null
        }
        NetworkLogger.info("CLIPBOARD_UPDATE_SENT revisionId=$revision sizeBytes=${text.encodeToByteArray().size} manual=$isManual")
    }

    suspend fun onLocalClipboardChanged(text: String, privateContext: Boolean = false) {
        if (privateContext || text.isBlank() || !clipboardEnabled) {
            if (privateContext) NetworkLogger.info("CLIPBOARD_UPDATE_SKIPPED code=PRIVATE_CONTEXT")
            return
        }
        val deviceId = currentClipboardDeviceId()
        if (!preferences.isClipboardAllowed(deviceId)) return
        synchronized(gate) {
            if (suppressClipboardText == text) {
                suppressClipboardText = null
                lastObservedLocalClipboardText = text
                return
            }
            if (lastObservedLocalClipboardText == text || pendingClipboard?.text == text) return
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(DEBOUNCE_MILLIS)
                sendAutomaticOrQueue(text)
            }
        }
    }

    private suspend fun sendAutomaticOrQueue(text: String) {
        if (!clipboardEnabled || !preferences.isClipboardAllowed(currentClipboardDeviceId())) return
        try {
            sendClipboardInternal(text, isManual = false)
        } catch (_: Exception) {
            synchronized(gate) { pendingClipboard = PendingClipboard(text, nowMillis()) }
            NetworkLogger.info("CLIPBOARD_UPDATE_QUEUED sizeBytes=${text.encodeToByteArray().size}")
        }
    }

    suspend fun flushPendingClipboard() {
        if (!clipboardEnabled || !preferences.isClipboardAllowed(currentClipboardDeviceId())) return
        val pending = synchronized(gate) {
            pendingClipboard?.takeIf { nowMillis() - it.createdAtMillis <= PENDING_TTL_MILLIS }
                .also { if (it == null) pendingClipboard = null }
        } ?: return
        runCatching { sendClipboardInternal(pending.text, isManual = false) }
    }

    suspend fun sendText(text: String) {
        validate(text)
        transport.sendSharingMessage(
            ProtocolMessageType.TEXT_SHARE.value,
            ProtocolSerializer.payloadToJson(
                TextSharePayload(UUID.randomUUID().toString(), if (isSafeUrl(text)) "url" else "text", text, Instant.now().toString())
            )
        )
    }

    override suspend fun onSharingMessage(message: ProtocolMessage) {
        when (message.type) {
            ProtocolMessageType.CLIPBOARD_UPDATE.value -> handleClipboardUpdate(
                ProtocolSerializer.decodePayload(message.payload),
                message.senderDeviceId,
            )
            ProtocolMessageType.TEXT_SHARE.value -> {
                val payload = ProtocolSerializer.decodePayload<TextSharePayload>(message.payload)
                validate(payload.text)
                if (_history.value.any { it.itemId == payload.itemId }) return
                _history.value = (listOf(SharedTextItem(payload.itemId, payload.kind, payload.text, Instant.now().toString())) + _history.value).take(50)
                suppressClipboardText = payload.text
                lastObservedLocalClipboardText = payload.text
                onClipboardReceived(payload.text, "Windows")
                applyClipboard(payload.text)
            }
        }
    }

    private fun handleClipboardUpdate(payload: ClipboardUpdatePayload, senderDeviceId: String) {
        validate(payload.text)
        require(payload.revisionId.isNotBlank() && payload.originDeviceId.isNotBlank()) { "Invalid clipboard identity metadata." }
        require(payload.originDeviceId == senderDeviceId) { "Clipboard origin does not match authenticated sender." }
        require(payload.contentType == "text/plain" || payload.contentType == "text/uri-list") { "Unsupported clipboard content type." }
        require(payload.contentType != "text/uri-list" || isSafeUrl(payload.text)) { "Clipboard URL must use HTTP or HTTPS." }
        if (!rememberRevision(payload.revisionId)) return
        lastRemoteDeviceId = senderDeviceId
        if (!payload.isManual && (!clipboardEnabled || !preferences.isClipboardAllowed(senderDeviceId))) return
        synchronized(gate) {
            if (lastAppliedRemoteText == payload.text) return
            lastAppliedRemoteText = payload.text
            suppressClipboardText = payload.text
            lastObservedLocalClipboardText = payload.text
        }
        onClipboardReceived(payload.text, "Windows")
        applyClipboard(payload.text)
        NetworkLogger.info("CLIPBOARD_UPDATE_APPLIED revisionId=${payload.revisionId} sizeBytes=${payload.text.encodeToByteArray().size}")
    }

    private fun requireAutomaticPermission() {
        check(clipboardEnabled) { "Clipboard synchronization is disabled." }
        check(preferences.isClipboardAllowed(currentClipboardDeviceId())) { "Clipboard synchronization is not allowed for this computer." }
    }

    private fun rememberRevision(revisionId: String): Boolean = synchronized(gate) {
        val cutoff = nowMillis() - REVISION_TTL_MILLIS
        seenRevisions.entries.removeAll { it.value < cutoff }
        if (revisionId in seenRevisions) return@synchronized false
        seenRevisions[revisionId] = nowMillis()
        while (seenRevisions.size > MAXIMUM_SEEN_REVISIONS) seenRevisions.remove(seenRevisions.keys.first())
        true
    }

    private fun validate(text: String): String {
        require(text.isNotBlank()) { "Shared text is empty." }
        require(text.encodeToByteArray().size <= MAXIMUM_TEXT_BYTES) { "Shared text exceeds 256 KiB." }
        return if (isSafeUrl(text)) "text/uri-list" else "text/plain"
    }

    private fun isSafeUrl(text: String): Boolean = runCatching {
        val uri = URI(text)
        uri.scheme == "http" || uri.scheme == "https"
    }.getOrDefault(false)
}
