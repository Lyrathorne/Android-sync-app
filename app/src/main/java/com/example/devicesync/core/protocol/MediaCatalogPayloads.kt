package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable

@Serializable
data class CatalogQueryPayload(
    val queryId: String,
    val pageSize: Int = 100,
    val pageToken: String? = null,
    val categories: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val albumId: String? = null,
    val search: String? = null,
    val sortBy: String = "modifiedAtUtc",
    val sortDirection: String = "desc",
    val modifiedAfterUtc: String? = null,
    val generationAfter: Long? = null,
)

@Serializable
data class CatalogItemPayload(
    val itemId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
    val createdAtUtc: String? = null,
    val modifiedAtUtc: String,
    val category: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: Long? = null,
    val albumId: String? = null,
    val albumDisplayName: String? = null,
    val generation: Long,
    val revision: String,
    val thumbnailAvailable: Boolean = false,
)

@Serializable
data class CatalogPagePayload(
    val queryId: String,
    val items: List<CatalogItemPayload>,
    val nextPageToken: String? = null,
    val snapshotGeneration: Long,
    val hasMore: Boolean,
)

@Serializable
data class CatalogChangePayload(
    val itemId: String,
    val changeType: String,
    val revision: String? = null,
)

@Serializable
data class CatalogChangedPayload(
    val generation: Long,
    val changes: List<CatalogChangePayload> = emptyList(),
    val requiresRefresh: Boolean = false,
)

@Serializable
data class CatalogThumbnailRequestPayload(
    val requestId: String,
    val itemId: String,
    val expectedRevision: String? = null,
    val maxWidth: Int = 512,
    val maxHeight: Int = 512,
    val format: String = "jpeg",
    val quality: Int = 80,
)

@Serializable
data class CatalogThumbnailResponsePayload(
    val requestId: String,
    val itemId: String,
    val revision: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val data: String,
)

@Serializable
data class CatalogFileDownloadRequestPayload(
    val requestId: String,
    val itemId: String,
    val expectedRevision: String? = null,
    val transferId: String,
)

@Serializable
data class CatalogPermissionPayload(
    val requestId: String? = null,
    val state: String,
    val grantedCategories: List<String> = emptyList(),
    val canRequest: Boolean = false,
    val reasonCode: String? = null,
)

@Serializable
data class CatalogErrorPayload(
    val requestId: String? = null,
    val code: String,
    val message: String? = null,
    val retryable: Boolean = false,
    val currentGeneration: Long? = null,
)

@Serializable
data class CatalogCancelPayload(
    val requestId: String,
    val reason: String,
)
