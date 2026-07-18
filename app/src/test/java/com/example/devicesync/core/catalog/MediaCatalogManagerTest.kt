package com.example.devicesync.core.catalog

import com.example.devicesync.core.network.ConnectionState
import com.example.devicesync.core.network.MediaCatalogMessageListener
import com.example.devicesync.core.network.MediaCatalogTransport
import com.example.devicesync.core.network.SupportedCapabilities
import com.example.devicesync.core.protocol.CatalogCancelPayload
import com.example.devicesync.core.protocol.CatalogFileDownloadRequestPayload
import com.example.devicesync.core.protocol.CatalogItemPayload
import com.example.devicesync.core.protocol.CatalogPagePayload
import com.example.devicesync.core.protocol.CatalogQueryPayload
import com.example.devicesync.core.protocol.CatalogThumbnailRequestPayload
import com.example.devicesync.core.protocol.CatalogThumbnailResponsePayload
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.ProtocolSerializer
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaCatalogManagerTest {
    @Test
    fun query_returnsOnePagedResponse() = runTest {
        val fixture = fixture(this)
        fixture.manager.onMediaCatalogMessage(message(
            ProtocolMessageType.CATALOG_QUERY,
            CatalogQueryPayload("query-1", pageSize = 25),
        ))
        advanceUntilIdle()

        val sent = fixture.transport.sent.single { it.first == ProtocolMessageType.CATALOG_PAGE.value }
        val page = ProtocolSerializer.decodePayload<CatalogPagePayload>(sent.second)
        assertEquals("query-1", page.queryId)
        assertEquals("photo.jpg", page.items.single().displayName)
        assertFalse(page.hasMore)
    }

    @Test
    fun thumbnail_isBoundedAndBase64Encoded() = runTest {
        val fixture = fixture(this)
        fixture.manager.onMediaCatalogMessage(message(
            ProtocolMessageType.CATALOG_THUMBNAIL_REQUEST,
            CatalogThumbnailRequestPayload("thumb-1", "item-1", "rev-1", 128, 128, "jpeg", 80),
        ))
        advanceUntilIdle()

        val response = ProtocolSerializer.decodePayload<CatalogThumbnailResponsePayload>(
            fixture.transport.sent.single { it.first == ProtocolMessageType.CATALOG_THUMBNAIL_RESPONSE.value }.second
        )
        assertEquals("AQID", response.data)
        assertEquals(3L, response.sizeBytes)
    }

    @Test
    fun invalidThumbnail_isRejectedWithoutCallingProvider() = runTest {
        val fixture = fixture(this)
        fixture.manager.onMediaCatalogMessage(message(
            ProtocolMessageType.CATALOG_THUMBNAIL_REQUEST,
            CatalogThumbnailRequestPayload("thumb-1", "item-1", maxWidth = 1024),
        ))
        advanceUntilIdle()

        assertFalse(fixture.source.thumbnailCalled)
        assertTrue(fixture.transport.sent.any { it.first == ProtocolMessageType.CATALOG_ERROR.value })
    }

    @Test
    fun download_resolvesOriginalOnlyAfterRequestAndPreservesTransferId() = runTest {
        val downloads = mutableListOf<Pair<String, String>>()
        val fixture = fixture(this) { uri, transferId -> downloads += uri to transferId }
        assertEquals(0, fixture.source.originalResolveCount)

        fixture.manager.onMediaCatalogMessage(message(
            ProtocolMessageType.CATALOG_FILE_DOWNLOAD_REQUEST,
            CatalogFileDownloadRequestPayload("download-1", "item-1", "rev-1", "transfer-1"),
        ))
        advanceUntilIdle()

        assertEquals(1, fixture.source.originalResolveCount)
        assertEquals(listOf("content://private/item-1" to "transfer-1"), downloads)
    }

    @Test
    fun cancel_stopsInFlightQueryWithoutLatePage() = runTest {
        val fixture = fixture(this)
        fixture.source.queryGate = CompletableDeferred()
        fixture.manager.onMediaCatalogMessage(message(
            ProtocolMessageType.CATALOG_QUERY,
            CatalogQueryPayload("query-1"),
        ))
        advanceUntilIdle()
        fixture.manager.onMediaCatalogMessage(message(
            ProtocolMessageType.CATALOG_CANCEL,
            CatalogCancelPayload("query-1", "view_closed"),
        ))
        fixture.source.queryGate?.complete(Unit)
        advanceUntilIdle()

        assertFalse(fixture.transport.sent.any { it.first == ProtocolMessageType.CATALOG_PAGE.value })
    }

    @Test
    fun deniedPermission_reportsPermissionAndError() = runTest {
        val fixture = fixture(this)
        fixture.source.permission = CatalogPermissionState("denied", emptyList(), true)
        fixture.manager.onMediaCatalogMessage(message(
            ProtocolMessageType.CATALOG_QUERY,
            CatalogQueryPayload("query-1"),
        ))
        advanceUntilIdle()

        assertTrue(fixture.transport.sent.any { it.first == ProtocolMessageType.CATALOG_PERMISSION.value })
        assertTrue(fixture.transport.sent.any { it.first == ProtocolMessageType.CATALOG_ERROR.value })
    }

    @Test
    fun observer_emitsCoalescibleRefreshNotification() = runTest {
        val fixture = fixture(this)
        fixture.source.changed?.invoke(8L)
        advanceUntilIdle()
        assertTrue(fixture.transport.sent.any { it.first == ProtocolMessageType.CATALOG_CHANGED.value })
    }

    private fun fixture(
        scope: TestScope,
        download: suspend (String, String) -> Unit = { _, _ -> },
    ): Fixture {
        val source = FakeSource()
        val transport = FakeTransport()
        return Fixture(source, transport, MediaCatalogManager(source, transport, download, scope))
    }

    private inline fun <reified T> message(type: ProtocolMessageType, payload: T) = ProtocolMessage(
        protocolVersion = 1,
        messageId = "message-1",
        type = type.value,
        senderDeviceId = "windows",
        timestampUtc = "2026-07-15T10:00:00Z",
        payload = ProtocolSerializer.payloadToJson(payload),
    )

    private data class Fixture(val source: FakeSource, val transport: FakeTransport, val manager: MediaCatalogManager)

    private class FakeTransport : MediaCatalogTransport {
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(
            "windows", "Windows", "127.0.0.1", 32145, 1,
            listOf(SupportedCapabilities.MEDIA_CATALOG_V1, SupportedCapabilities.THUMBNAILS_V1),
        ))
        val sent = mutableListOf<Pair<String, JsonElement>>()
        override suspend fun sendCatalogMessage(type: String, payload: JsonElement) { sent += type to payload }
        override fun addMediaCatalogListener(listener: MediaCatalogMessageListener) = Unit
        override fun removeMediaCatalogListener(listener: MediaCatalogMessageListener) = Unit
    }

    private class FakeSource : MediaCatalogSource {
        var permission = CatalogPermissionState("granted", listOf("image"), true)
        var thumbnailCalled = false
        var originalResolveCount = 0
        var queryGate: CompletableDeferred<Unit>? = null
        var changed: ((Long) -> Unit)? = null
        override fun permissionState() = permission
        override suspend fun query(query: CatalogQueryPayload): CatalogSourcePage {
            queryGate?.await()
            return CatalogSourcePage(listOf(item()), null, 7L)
        }
        override suspend fun thumbnail(itemId: String, expectedRevision: String?, maxWidth: Int, maxHeight: Int, format: String, quality: Int): CatalogThumbnail {
            thumbnailCalled = true
            return CatalogThumbnail(itemId, "rev-1", "image/jpeg", 64, 48, byteArrayOf(1, 2, 3))
        }
        override fun resolveOriginal(itemId: String, expectedRevision: String?): String {
            originalResolveCount++
            return "content://private/$itemId"
        }
        override fun openOriginal(itemId: String, expectedRevision: String?): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun startObserving(onChanged: (Long) -> Unit) { changed = onChanged }
        override fun stopObserving() = Unit
        override fun clearRevokedData() = Unit
        private fun item() = CatalogItemPayload(
            "item-1", "photo.jpg", "image/jpeg", 3L, null, "2026-07-15T10:00:00Z",
            "image", 64, 48, null, "album-1", "Camera", 7L, "rev-1", true,
        )
    }
}
