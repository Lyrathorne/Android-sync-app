package com.example.devicesync.core.notifications

import android.app.Notification
import android.os.Build
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.example.devicesync.DeviceSyncApplication
import com.example.devicesync.core.network.SharingMessageListener
import com.example.devicesync.core.network.SharingTransport
import com.example.devicesync.core.protocol.NotificationActionInvokePayload
import com.example.devicesync.core.protocol.NotificationActionPayload
import com.example.devicesync.core.protocol.NotificationActionResultPayload
import com.example.devicesync.core.protocol.NotificationPostedPayload
import com.example.devicesync.core.protocol.NotificationRemovedPayload
import com.example.devicesync.core.protocol.NotificationUpdatedPayload
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.ProtocolSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

data class ForwardableApp(val packageName: String, val displayName: String)

class NotificationForwardingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("notification_forwarding", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }

    var includePrivateNotifications: Boolean
        get() = preferences.getBoolean("include_private", false)
        set(value) { preferences.edit().putBoolean("include_private", value).apply() }

    var allowedPackages: Set<String>
        get() = preferences.getStringSet("allowed_packages", emptySet()).orEmpty().toSet()
        set(value) { preferences.edit().putStringSet("allowed_packages", value.toSet()).apply() }

    var deniedPackages: Set<String>
        get() = preferences.getStringSet("denied_packages", emptySet()).orEmpty().toSet()
        set(value) { preferences.edit().putStringSet("denied_packages", value.toSet()).apply() }

    val knownApps: List<ForwardableApp>
        get() = preferences.getStringSet("known_apps", emptySet()).orEmpty()
            .mapNotNull { encoded ->
                val separator = encoded.indexOf('|')
                if (separator <= 0) return@mapNotNull null
                ForwardableApp(
                    packageName = Uri.decode(encoded.substring(0, separator)),
                    displayName = Uri.decode(encoded.substring(separator + 1)),
                )
            }
            .distinctBy(ForwardableApp::packageName)
            .sortedBy { it.displayName.lowercase() }

    fun rememberApp(packageName: String, displayName: String) {
        val apps = knownApps.associateBy(ForwardableApp::packageName).toMutableMap()
        apps[packageName] = ForwardableApp(packageName, displayName)
        preferences.edit().putStringSet(
            "known_apps",
            apps.values.map { "${Uri.encode(it.packageName)}|${Uri.encode(it.displayName)}" }.toSet(),
        ).apply()
    }

    fun setPackageAllowed(packageName: String, allowed: Boolean) {
        if (allowed) {
            allowedPackages = allowedPackages + packageName
            deniedPackages = deniedPackages - packageName
        } else {
            allowedPackages = allowedPackages - packageName
            deniedPackages = deniedPackages + packageName
        }
    }

    fun setPackageDenied(packageName: String, denied: Boolean) {
        deniedPackages = if (denied) deniedPackages + packageName else deniedPackages - packageName
        if (denied) allowedPackages = allowedPackages - packageName
    }

    fun shouldForward(packageName: String): Boolean =
        enabled && packageName in allowedPackages && packageName !in deniedPackages
}

internal data class NotificationPolicyInput(
    val packageName: String,
    val isSecret: Boolean,
    val isPrivate: Boolean,
    val isOngoing: Boolean,
    val isGroupSummary: Boolean,
    val category: String?,
    val hasProgress: Boolean,
    val hasText: Boolean,
)

internal object NotificationForwardingPolicy {
    fun shouldForward(input: NotificationPolicyInput, includePrivate: Boolean): Boolean {
        if (input.isSecret || input.isGroupSummary || !input.hasText) return false
        if (input.isPrivate && !includePrivate) return false
        if (input.category == Notification.CATEGORY_TRANSPORT || input.hasProgress) return false
        if (input.isOngoing && input.category !in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_MESSAGE)) {
            return false
        }
        return true
    }
}

private class NotificationRateLimiter(
    private val perPackageLimit: Int = 20,
    private val globalLimit: Int = 100,
    private val windowMillis: Long = 60_000,
) {
    private val global = ArrayDeque<Long>()
    private val packages = mutableMapOf<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(packageName: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        trim(global, nowMillis)
        val perPackage = packages.getOrPut(packageName) { ArrayDeque() }
        trim(perPackage, nowMillis)
        if (global.size >= globalLimit || perPackage.size >= perPackageLimit) return false
        global.addLast(nowMillis)
        perPackage.addLast(nowMillis)
        return true
    }

    private fun trim(queue: ArrayDeque<Long>, nowMillis: Long) {
        while (queue.isNotEmpty() && nowMillis - queue.first() >= windowMillis) queue.removeFirst()
    }
}

private data class RegisteredAction(
    val packageName: String,
    val pendingIntent: PendingIntent,
    val destructive: Boolean,
)

class NotificationForwarder(
    private val transport: SharingTransport,
    private val scope: CoroutineScope,
) : SharingMessageListener {
    @Volatile
    private var actionExecutor: (suspend (NotificationActionInvokePayload) -> NotificationActionResultPayload)? = null

    init {
        transport.addSharingListener(this)
    }

    fun setActionExecutor(executor: (suspend (NotificationActionInvokePayload) -> NotificationActionResultPayload)?) {
        actionExecutor = executor
    }

    fun posted(payload: NotificationPostedPayload) = scope.launch {
        transport.sendSharingMessage(ProtocolMessageType.NOTIFICATION_POSTED.value, ProtocolSerializer.payloadToJson(payload))
    }

    fun updated(payload: NotificationUpdatedPayload) = scope.launch {
        transport.sendSharingMessage(ProtocolMessageType.NOTIFICATION_UPDATED.value, ProtocolSerializer.payloadToJson(payload))
    }

    fun removed(payload: NotificationRemovedPayload) = scope.launch {
        transport.sendSharingMessage(ProtocolMessageType.NOTIFICATION_REMOVED.value, ProtocolSerializer.payloadToJson(payload))
    }

    override suspend fun onSharingMessage(message: ProtocolMessage) {
        if (message.type != ProtocolMessageType.NOTIFICATION_ACTION_INVOKE.value) return
        val invocation = ProtocolSerializer.decodePayload<NotificationActionInvokePayload>(message.payload)
        val result = actionExecutor?.invoke(invocation) ?: NotificationActionResultPayload(
            invocationId = invocation.invocationId,
            notificationId = invocation.notificationId,
            actionId = invocation.actionId,
            status = "not_found",
            message = "The notification action is no longer available.",
        )
        transport.sendSharingMessage(
            ProtocolMessageType.NOTIFICATION_ACTION_RESULT.value,
            ProtocolSerializer.payloadToJson(result),
        )
    }
}

class DeviceSyncNotificationListenerService : NotificationListenerService() {
    private val container get() = (application as DeviceSyncApplication).container
    private val limiter = NotificationRateLimiter()
    private val fingerprints = ConcurrentHashMap<String, String>()
    private val revisions = ConcurrentHashMap<String, Long>()
    private val activePackages = ConcurrentHashMap<String, String>()
    private val actions = ConcurrentHashMap<String, RegisteredAction>()

    override fun onCreate() {
        super.onCreate()
        container.notificationForwarder.setActionExecutor(::executeAction)
    }

    override fun onDestroy() {
        container.notificationForwarder.setActionExecutor(null)
        fingerprints.clear()
        revisions.clear()
        activePackages.clear()
        actions.clear()
        super.onDestroy()
    }

    override fun onListenerDisconnected() {
        actions.clear()
        requestRebind(ComponentName(this, DeviceSyncNotificationListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val preferences = container.notificationPreferences
        val appName = applicationLabel(sbn.packageName)
        preferences.rememberApp(sbn.packageName, appName)
        if (!preferences.shouldForward(sbn.packageName)) return

        val notification = sbn.notification
        val title = sanitize(notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(), 256)
        val text = sanitize(
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            8192,
        )
        val isPrivate = notification.visibility == Notification.VISIBILITY_PRIVATE
        val hasProgress = notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
        val policyInput = NotificationPolicyInput(
            packageName = sbn.packageName,
            isSecret = notification.visibility == Notification.VISIBILITY_SECRET,
            isPrivate = isPrivate,
            isOngoing = sbn.isOngoing,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            category = notification.category,
            hasProgress = hasProgress,
            hasText = title.isNotBlank() || text.isNotBlank(),
        )
        if (!NotificationForwardingPolicy.shouldForward(policyInput, preferences.includePrivateNotifications)) return

        val notificationId = stableToken(sbn.key)
        val exposedActions = registerActions(notificationId, sbn.packageName, notification)
        val fingerprint = stableToken(
            listOf(title, text, notification.category.orEmpty(), notification.group.orEmpty(),
                notification.flags.toString(), exposedActions.joinToString { it.actionId }).joinToString("\u001f"),
        )
        if (fingerprints[notificationId] == fingerprint || !limiter.allow(sbn.packageName)) return

        val previous = fingerprints.put(notificationId, fingerprint)
        val revision = revisions.merge(notificationId, 1L, Long::plus) ?: 1L
        activePackages[notificationId] = sbn.packageName
        val postedAt = Instant.ofEpochMilli(sbn.postTime).toString()
        val common = NotificationPostedPayload(
            notificationId = notificationId,
            packageName = sbn.packageName.take(256),
            appName = appName.take(256),
            title = title,
            text = text,
            postedAtUtc = postedAt,
            category = sanitize(notification.category, 64),
            groupKey = sanitize(notification.group, 256).ifBlank { null },
            isSilent = notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            isSensitive = isPrivate,
            iconToken = "app:${sbn.packageName}".take(256),
            revision = revision,
            actions = exposedActions,
        )
        if (previous == null) {
            container.notificationForwarder.posted(common)
        } else {
            container.notificationForwarder.updated(
                NotificationUpdatedPayload(
                    notificationId = common.notificationId,
                    packageName = common.packageName,
                    appName = common.appName,
                    title = common.title,
                    text = common.text,
                    postedAtUtc = common.postedAtUtc,
                    updatedAtUtc = Instant.now().toString(),
                    category = common.category,
                    groupKey = common.groupKey,
                    isSilent = common.isSilent,
                    isSensitive = common.isSensitive,
                    iconToken = common.iconToken,
                    revision = common.revision,
                    actions = common.actions,
                ),
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val notificationId = stableToken(sbn.key)
        val packageName = activePackages.remove(notificationId) ?: return
        fingerprints.remove(notificationId)
        actions.keys.removeAll { it.startsWith("$notificationId:") }
        val revision = revisions.merge(notificationId, 1L, Long::plus) ?: 1L
        container.notificationForwarder.removed(
            NotificationRemovedPayload(
                notificationId = notificationId,
                packageName = packageName,
                removedAtUtc = Instant.now().toString(),
                revision = revision,
            ),
        )
    }

    private fun registerActions(
        notificationId: String,
        packageName: String,
        notification: Notification,
    ): List<NotificationActionPayload> {
        actions.keys.removeAll { it.startsWith("$notificationId:") }
        return notification.actions.orEmpty().asSequence()
            .filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() }
            .mapIndexedNotNull { index, action ->
                val semanticAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    action.semanticAction
                } else {
                    Notification.Action.SEMANTIC_ACTION_NONE
                }
                val destructive = semanticAction in setOf(
                    Notification.Action.SEMANTIC_ACTION_DELETE,
                    Notification.Action.SEMANTIC_ACTION_ARCHIVE,
                )
                if (destructive) return@mapIndexedNotNull null
                val title = sanitize(action.title?.toString(), 128)
                if (title.isBlank()) return@mapIndexedNotNull null
                val actionId = stableToken("$notificationId:$index:$title")
                actions["$notificationId:$actionId"] = RegisteredAction(packageName, action.actionIntent, false)
                NotificationActionPayload(
                    actionId = actionId,
                    title = title,
                    semantic = when (semanticAction) {
                        Notification.Action.SEMANTIC_ACTION_REPLY -> "reply"
                        else -> "open"
                    },
                    requiresConfirmation = true,
                    isDestructive = false,
                )
            }
            .take(3)
            .toList()
    }

    private suspend fun executeAction(invocation: NotificationActionInvokePayload): NotificationActionResultPayload {
        val registered = actions["${invocation.notificationId}:${invocation.actionId}"]
        val status = when {
            !invocation.confirmedByUser -> "rejected"
            registered == null || registered.packageName != invocation.packageName -> "not_found"
            registered.destructive -> "rejected"
            else -> runCatching {
                registered.pendingIntent.send()
                "completed"
            }.getOrDefault("failed")
        }
        return NotificationActionResultPayload(
            invocationId = invocation.invocationId,
            notificationId = invocation.notificationId,
            actionId = invocation.actionId,
            status = status,
            message = if (status == "completed") null else "The requested action could not be completed.",
        )
    }

    private fun applicationLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun sanitize(value: String?, maximumLength: Int): String = value.orEmpty()
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maximumLength)

    private fun stableToken(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
