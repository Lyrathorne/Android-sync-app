package com.example.devicesync.core.protocol

import com.example.devicesync.core.network.ConnectionException
import com.example.devicesync.core.network.SupportedCapabilities
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCatalogPayloadsTest {
    @Test
    fun contract_usesExactMessageTypesAndProviderCapabilities() {
        assertEquals("catalog.query", ProtocolMessageType.CATALOG_QUERY.value)
        assertEquals("catalog.page", ProtocolMessageType.CATALOG_PAGE.value)
        assertEquals("catalog.changed", ProtocolMessageType.CATALOG_CHANGED.value)
        assertEquals("catalog.thumbnail.request", ProtocolMessageType.CATALOG_THUMBNAIL_REQUEST.value)
        assertEquals("catalog.thumbnail.response", ProtocolMessageType.CATALOG_THUMBNAIL_RESPONSE.value)
        assertEquals("catalog.file.download.request", ProtocolMessageType.CATALOG_FILE_DOWNLOAD_REQUEST.value)
        assertEquals("catalog.permission", ProtocolMessageType.CATALOG_PERMISSION.value)
        assertEquals("catalog.error", ProtocolMessageType.CATALOG_ERROR.value)
        assertEquals("catalog.cancel", ProtocolMessageType.CATALOG_CANCEL.value)
        assertEquals("media-catalog-v1", SupportedCapabilities.MEDIA_CATALOG_V1)
        assertEquals("thumbnails-v1", SupportedCapabilities.THUMBNAILS_V1)
        assertTrue(SupportedCapabilities.MEDIA_CATALOG_V1 in SupportedCapabilities.values)
        assertTrue(SupportedCapabilities.THUMBNAILS_V1 in SupportedCapabilities.values)
    }

    @Test
    fun sharedVectors_decodeEveryPayloadAndPreserveLong() {
        val query = ProtocolSerializer.decodePayload<CatalogQueryPayload>(message("01-query.json").payload)
        assertEquals(3_000_000_000L, query.generationAfter)

        val page = ProtocolSerializer.decodePayload<CatalogPagePayload>(message("02-page.json").payload)
        assertEquals(5_000_000_000L, page.items.single().sizeBytes)
        assertEquals("Лето.jpg", page.items.single().displayName)

        assertEquals(2, ProtocolSerializer.decodePayload<CatalogChangedPayload>(message("03-changed.json").payload).changes.size)
        assertEquals(256, ProtocolSerializer.decodePayload<CatalogThumbnailRequestPayload>(message("04-thumbnail-request.json").payload).maxWidth)
        assertEquals("aGVsbG8gd29ybGQ=", ProtocolSerializer.decodePayload<CatalogThumbnailResponsePayload>(message("05-thumbnail-response.json").payload).data)
        assertTrue(ProtocolSerializer.decodePayload<CatalogFileDownloadRequestPayload>(message("06-download-request.json").payload).transferId.isNotBlank())
        assertEquals("revoked", ProtocolSerializer.decodePayload<CatalogPermissionPayload>(message("07-permission-revoked.json").payload).state)
        assertEquals("ITEM_CHANGED", ProtocolSerializer.decodePayload<CatalogErrorPayload>(message("08-error.json").payload).code)
        assertEquals("view_closed", ProtocolSerializer.decodePayload<CatalogCancelPayload>(message("09-cancel.json").payload).reason)
    }

    @Test
    fun query_ignoresUnknownFieldsAndUsesCamelCase() {
        val payload = ProtocolSerializer.decodePayload<CatalogQueryPayload>(message("01-query.json").payload)
        val encoded = ProtocolSerializer.payloadToJson(payload).jsonObject

        assertEquals("summer", payload.search)
        assertTrue("queryId" in encoded)
        assertFalse("QueryId" in encoded)
    }

    @Test(expected = ConnectionException.InvalidMessage::class)
    fun item_rejectsMissingRequiredRevision() {
        val json = ProtocolSerializer.json.parseToJsonElement(
            """{"itemId":"item:1","displayName":"photo.jpg","mimeType":"image/jpeg","modifiedAtUtc":"2026-07-15T10:00:00Z","category":"image","generation":1}"""
        )
        ProtocolSerializer.decodePayload<CatalogItemPayload>(json)
    }

    @Test
    fun sharedVectors_neverExposeAndroidPathsOrUris() {
        (1..9).forEach { index ->
            val fileName = when (index) {
                1 -> "01-query.json"
                2 -> "02-page.json"
                3 -> "03-changed.json"
                4 -> "04-thumbnail-request.json"
                5 -> "05-thumbnail-response.json"
                6 -> "06-download-request.json"
                7 -> "07-permission-revoked.json"
                8 -> "08-error.json"
                else -> "09-cancel.json"
            }
            val raw = resource(fileName)
            assertFalse(raw.contains("content://", ignoreCase = true))
            assertFalse(raw.contains("filesystemPath", ignoreCase = true))
            assertFalse(raw.contains("relativePath", ignoreCase = true))
        }
    }

    private fun message(fileName: String): ProtocolMessage = ProtocolSerializer.deserialize(resource(fileName))

    private fun resource(fileName: String): String = checkNotNull(
        javaClass.classLoader?.getResource("protocol/test-vectors/media-catalog/$fileName")
    ).readText()
}
