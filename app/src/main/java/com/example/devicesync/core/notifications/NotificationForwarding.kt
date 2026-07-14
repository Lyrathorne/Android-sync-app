package com.example.devicesync.core.notifications

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.devicesync.DeviceSyncApplication
import com.example.devicesync.core.network.SharingTransport
import com.example.devicesync.core.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

class NotificationForwardingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("notification_forwarding", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }
    var allowedPackages: Set<String>
        get() = preferences.getStringSet("allowed_packages", emptySet()).orEmpty()
        set(value) { preferences.edit().putStringSet("allowed_packages", value).apply() }
    fun shouldForward(packageName: String): Boolean = enabled && packageName in allowedPackages
}

class NotificationForwarder(
    private val transport: SharingTransport,
    private val scope: CoroutineScope,
) {
    fun posted(payload: NotificationPostedPayload) = scope.launch {
        transport.sendSharingMessage(ProtocolMessageType.NOTIFICATION_POSTED.value, ProtocolSerializer.payloadToJson(payload))
    }
    fun removed(payload: NotificationRemovedPayload) = scope.launch {
        transport.sendSharingMessage(ProtocolMessageType.NOTIFICATION_REMOVED.value, ProtocolSerializer.payloadToJson(payload))
    }
}

class DeviceSyncNotificationListenerService : NotificationListenerService() {
    private val container get() = (application as DeviceSyncApplication).container

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!container.notificationPreferences.shouldForward(sbn.packageName)) return
        val notification = sbn.notification
        if (notification.visibility == Notification.VISIBILITY_SECRET) return
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(4096)
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().take(8192)
        if (title.isBlank() && text.isBlank()) return
        val appName = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)
        container.notificationForwarder.posted(NotificationPostedPayload(
            notificationId = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            postedAtUtc = Instant.ofEpochMilli(sbn.postTime).toString(),
        ))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!container.notificationPreferences.shouldForward(sbn.packageName)) return
        container.notificationForwarder.removed(NotificationRemovedPayload(sbn.key, sbn.packageName))
    }
}
