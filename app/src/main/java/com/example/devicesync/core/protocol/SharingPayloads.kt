package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ClipboardUpdatePayload(
    val revisionId: String,
    val sourceDeviceId: String,
    val contentType: String,
    val text: String,
    val createdAtUtc: String,
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
)

@Serializable
data class NotificationRemovedPayload(
    val notificationId: String,
    val packageName: String,
)
