package com.example.devicesync.core.catalog

import com.example.devicesync.core.protocol.CatalogItemPayload
import com.example.devicesync.core.protocol.CatalogQueryPayload
import java.io.InputStream

data class CatalogSourcePage(
    val items: List<CatalogItemPayload>,
    val nextPageToken: String?,
    val snapshotGeneration: Long,
)

data class CatalogThumbnail(
    val itemId: String,
    val revision: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
)

data class CatalogPermissionState(
    val state: String,
    val grantedCategories: List<String>,
    val canRequest: Boolean,
    val reasonCode: String? = null,
)

interface MediaCatalogSource {
    fun permissionState(): CatalogPermissionState
    suspend fun query(query: CatalogQueryPayload): CatalogSourcePage
    suspend fun thumbnail(
        itemId: String,
        expectedRevision: String?,
        maxWidth: Int,
        maxHeight: Int,
        format: String,
        quality: Int,
    ): CatalogThumbnail
    fun resolveOriginal(itemId: String, expectedRevision: String?): String
    fun openOriginal(itemId: String, expectedRevision: String?): InputStream
    fun startObserving(onChanged: (generation: Long) -> Unit)
    fun stopObserving()
    fun clearRevokedData()
}

class CatalogSourceException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    val currentGeneration: Long? = null,
) : Exception(message)
