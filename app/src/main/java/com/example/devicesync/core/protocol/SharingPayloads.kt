package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ClipboardUpdatePayload(
    val revisionId: String,
    val originDeviceId: String,
    val contentType: String,
    val text: String,
    val createdAtUtc: String,
    val isManual: Boolean = false,
)

@Serializable
data class TextSharePayload(
    val itemId: String,
    val kind: String,
    val text: String,
    val createdAtUtc: String,
)

@Serializable
data class NotificationPostedPayload(
    val notificationId: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAtUtc: String,
    val category: String = "",
    val groupKey: String? = null,
    val isSilent: Boolean = false,
    val isSensitive: Boolean = false,
    val iconToken: String? = null,
    val revision: Long = 1,
    val actions: List<NotificationActionPayload> = emptyList(),
)

@Serializable
data class NotificationUpdatedPayload(
    val notificationId: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAtUtc: String,
    val updatedAtUtc: String,
    val category: String = "",
    val groupKey: String? = null,
    val isSilent: Boolean = false,
    val isSensitive: Boolean = false,
    val iconToken: String? = null,
    val revision: Long = 1,
    val actions: List<NotificationActionPayload> = emptyList(),
)

@Serializable
data class NotificationRemovedPayload(
    val notificationId: String,
    val packageName: String,
    val removedAtUtc: String = "",
    val reason: String = "removed",
    val revision: Long = 0,
)

@Serializable
data class NotificationActionPayload(
    val actionId: String,
    val title: String,
    val semantic: String = "custom",
    val requiresConfirmation: Boolean = true,
    val isDestructive: Boolean = false,
)

@Serializable
data class NotificationActionInvokePayload(
    val invocationId: String,
    val notificationId: String,
    val packageName: String,
    val actionId: String,
    val confirmedByUser: Boolean,
)

@Serializable
data class NotificationActionResultPayload(
    val invocationId: String,
    val notificationId: String,
    val actionId: String,
    val status: String,
    val message: String? = null,
)
