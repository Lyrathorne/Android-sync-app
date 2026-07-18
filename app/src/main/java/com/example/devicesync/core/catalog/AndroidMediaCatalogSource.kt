package com.example.devicesync.core.catalog

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.util.Size
import com.example.devicesync.core.protocol.CatalogItemPayload
import com.example.devicesync.core.protocol.CatalogQueryPayload
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class AndroidMediaCatalogSource(
    private val context: Context,
    private val resolver: ContentResolver = context.contentResolver,
    private val accessStore: CatalogAccessStore = CatalogAccessStore(context),
    private val thumbnailCache: BoundedThumbnailCache = BoundedThumbnailCache(),
) : MediaCatalogSource {
    private val preferences = context.getSharedPreferences("media_catalog_index", Context.MODE_PRIVATE)
    private val referenceStore = ItemReferenceStore(preferences)
    private val collectionUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private var observer: ContentObserver? = null

    override fun permissionState(): CatalogPermissionState {
        if (!accessStore.isCatalogEnabled()) {
            return CatalogPermissionState("revoked", emptyList(), canRequest = true, reasonCode = "user_revoked")
        }
        val granted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            granted += listOf("image", "video", "audio")
            if (accessStore.isDocumentsAccessEnabled()) granted += listOf("document", "other")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) granted += "image"
            if (hasPermission(Manifest.permission.READ_MEDIA_VIDEO)) granted += "video"
            if (hasPermission(Manifest.permission.READ_MEDIA_AUDIO)) granted += "audio"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
                granted += listOf("image", "video")
            }
        } else if (hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            granted += listOf("image", "video", "audio", "document", "other")
        }
        if (accessStore.isDocumentsAccessEnabled() && accessStore.selectedTreeUris().isNotEmpty()) {
            granted += listOf("document", "other")
        }
        val distinct = granted.distinct()
        return when {
            distinct.isEmpty() -> CatalogPermissionState("denied", emptyList(), canRequest = true, reasonCode = "permission_required")
            distinct.size < 5 -> CatalogPermissionState("limited", distinct, canRequest = true)
            else -> CatalogPermissionState("granted", distinct, canRequest = true)
        }
    }

    override suspend fun query(query: CatalogQueryPayload): CatalogSourcePage = withContext(Dispatchers.IO) {
        validateQuery(query)
        val permission = permissionState()
        if (permission.state == "denied" || permission.state == "revoked") {
            throw CatalogSourceException("PERMISSION_REQUIRED", "Media access is not granted.")
        }
        val requested = query.categories.ifEmpty { permission.grantedCategories }
        if (requested.any { it !in permission.grantedCategories }) {
            throw CatalogSourceException("PERMISSION_REQUIRED", "One or more requested categories are not granted.")
        }
        val offset = decodePageToken(query.pageToken)
        val requestedAlbum = query.albumId?.let(referenceStore::resolveAlbum)
        val requestedSafTree = requestedAlbum?.takeIf { it.startsWith("saf:") }?.removePrefix("saf:")
        if (requestedSafTree != null ||
            (!hasAllFilesAccess() && requested.all { it == "document" || it == "other" } &&
                accessStore.selectedTreeUris().isNotEmpty())) {
            return@withContext querySaf(query, offset, requestedSafTree)
        }
        val selection = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        if (requested.isNotEmpty()) {
            val mediaTypes = requested.mapNotNull(::mediaTypeForCategory).distinct()
            if (mediaTypes.isNotEmpty()) {
                selection += "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${mediaTypes.joinToString { "?" }})"
                arguments += mediaTypes.map(Int::toString)
            }
        }
        if (query.mimeTypes.isNotEmpty()) {
            selection += "${MediaStore.MediaColumns.MIME_TYPE} IN (${query.mimeTypes.joinToString { "?" }})"
            arguments += query.mimeTypes
        }
        query.search?.takeIf(String::isNotBlank)?.let {
            selection += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
            arguments += "%${escapeLike(it)}%"
        }
        query.modifiedAfterUtc?.let {
            val epochSeconds = runCatching { Instant.parse(it).epochSecond }.getOrElse {
                throw CatalogSourceException("INVALID_QUERY", "modifiedAfterUtc is invalid.")
            }
            selection += "${MediaStore.MediaColumns.DATE_MODIFIED} > ?"
            arguments += epochSeconds.toString()
        }
        if (query.albumId != null) {
            val bucket = referenceStore.resolveAlbum(query.albumId)
                ?: throw CatalogSourceException("INVALID_QUERY", "Unknown albumId.")
            selection += "${MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME} = ?"
            arguments += bucket
        }

        val orderColumn = when (query.sortBy) {
            "modifiedAtUtc" -> MediaStore.MediaColumns.DATE_MODIFIED
            "createdAtUtc" -> MediaStore.MediaColumns.DATE_ADDED
            "displayName" -> MediaStore.MediaColumns.DISPLAY_NAME
            "sizeBytes" -> MediaStore.MediaColumns.SIZE
            else -> throw CatalogSourceException("INVALID_QUERY", "Unsupported sortBy.")
        }
        val direction = query.sortDirection.uppercase()
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection.takeIf { it.isNotEmpty() }?.joinToString(" AND "))
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arguments.toTypedArray())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "$orderColumn $direction, ${MediaStore.MediaColumns._ID} $direction")
            putInt(ContentResolver.QUERY_ARG_LIMIT, query.pageSize + 1)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        val items = mutableListOf<CatalogItemPayload>()
        resolver.query(collectionUri, projection(), bundle, null)?.use { cursor ->
            while (cursor.moveToNext() && items.size <= query.pageSize) {
                coroutineContext.ensureActive()
                runCatching { cursorToItem(cursor) }.getOrNull()
                    ?.takeIf { requested.isEmpty() || it.category in requested }
                    ?.let(items::add)
            }
        } ?: throw CatalogSourceException("ITEM_UNAVAILABLE", "The media provider returned no cursor.", retryable = true)
        val hasMore = items.size > query.pageSize
        if (hasMore) items.removeAt(items.lastIndex)
        val generation = currentGeneration()
        CatalogSourcePage(items, if (hasMore) encodePageToken(offset + items.size) else null, generation)
    }

    override suspend fun thumbnail(
        itemId: String,
        expectedRevision: String?,
        maxWidth: Int,
        maxHeight: Int,
        format: String,
        quality: Int,
    ): CatalogThumbnail = withContext(Dispatchers.IO) {
        val key = "$itemId|$expectedRevision|$maxWidth|$maxHeight|$format|$quality"
        thumbnailCache.get(key)?.let { return@withContext it }
        val reference = resolveCurrent(itemId, expectedRevision)
        coroutineContext.ensureActive()
        val signal = CancellationSignal()
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(reference.uri, Size(maxWidth, maxHeight), signal)
            } else {
                decodeSampled(reference.uri, maxWidth, maxHeight)
            }
        } catch (error: FileNotFoundException) {
            throw CatalogSourceException("ITEM_NOT_FOUND", "The item no longer exists.")
        } catch (error: SecurityException) {
            throw CatalogSourceException("PERMISSION_REVOKED", "Media permission was revoked.")
        }
        try {
            coroutineContext.ensureActive()
            val encoded = encodeBitmap(bitmap, format, quality)
            val result = CatalogThumbnail(itemId, reference.revision, mimeForFormat(format), bitmap.width, bitmap.height, encoded)
            thumbnailCache.put(key, result)
            result
        } finally {
            bitmap.recycle()
        }
    }

    override fun resolveOriginal(itemId: String, expectedRevision: String?): String =
        resolveCurrent(itemId, expectedRevision).uri.toString()

    override fun openOriginal(itemId: String, expectedRevision: String?): InputStream {
        val reference = resolveCurrent(itemId, expectedRevision)
        return try {
            resolver.openInputStream(reference.uri)
                ?: throw CatalogSourceException("ITEM_UNAVAILABLE", "The provider returned no stream.", retryable = true)
        } catch (error: FileNotFoundException) {
            throw CatalogSourceException("ITEM_NOT_FOUND", "The item no longer exists.")
        } catch (error: SecurityException) {
            throw CatalogSourceException("PERMISSION_REVOKED", "Media permission was revoked.")
        }
    }

    override fun startObserving(onChanged: (generation: Long) -> Unit) {
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChanged(incrementGeneration())
            }
        }.also { resolver.registerContentObserver(collectionUri, true, it) }
    }

    override fun stopObserving() {
        observer?.let(resolver::unregisterContentObserver)
        observer = null
    }

    override fun clearRevokedData() {
        thumbnailCache.clear()
        referenceStore.clear()
    }

    private fun resolveCurrent(itemId: String, expectedRevision: String?): ItemReference {
        val stored = referenceStore.resolve(itemId)
            ?: throw CatalogSourceException("ITEM_NOT_FOUND", "Unknown or expired itemId.")
        val current = readReference(stored.uri)
        if (expectedRevision != null && expectedRevision != current.revision) {
            throw CatalogSourceException("ITEM_CHANGED", "The item changed.", retryable = true, currentGeneration())
        }
        referenceStore.put(itemId, current)
        return current
    }

    private fun readReference(uri: Uri): ItemReference {
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                resolver.query(
                    uri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val modified = cursor.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED) ?: 0L
                        val size = cursor.longOrNull(DocumentsContract.Document.COLUMN_SIZE)
                        val name = cursor.stringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty()
                        return ItemReference(uri, revisionFor(uri, modified, size, name))
                    }
                }
                throw CatalogSourceException("ITEM_NOT_FOUND", "The document no longer exists.")
            }
            resolver.query(uri, projection(), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val modified = cursor.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L
                    val size = cursor.longOrNull(MediaStore.MediaColumns.SIZE)
                    val name = cursor.stringOrNull(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()
                    return ItemReference(uri, revisionFor(uri, modified, size, name))
                }
            }
        } catch (error: SecurityException) {
            throw CatalogSourceException("PERMISSION_REVOKED", "Media permission was revoked.")
        }
        throw CatalogSourceException("ITEM_NOT_FOUND", "The item no longer exists.")
    }

    private fun android.database.Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    private fun android.database.Cursor.intOrNull(column: String): Int? = longOrNull(column)?.toInt()
    private fun android.database.Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun cursorToItem(cursor: android.database.Cursor): CatalogItemPayload {
        val id = cursor.longOrNull(MediaStore.MediaColumns._ID) ?: error("Missing ID")
        val uri = ContentUris.withAppendedId(collectionUri, id)
        val name = cursor.stringOrNull(MediaStore.MediaColumns.DISPLAY_NAME)?.takeIf(String::isNotBlank) ?: "Unnamed item"
        val mime = cursor.stringOrNull(MediaStore.MediaColumns.MIME_TYPE) ?: "application/octet-stream"
        val modified = cursor.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L
        val created = cursor.longOrNull(MediaStore.MediaColumns.DATE_ADDED)
        val size = cursor.longOrNull(MediaStore.MediaColumns.SIZE)?.takeIf { it >= 0 }
        val category = categoryForMediaType(cursor.intOrNull(MediaStore.Files.FileColumns.MEDIA_TYPE), mime)
        val revision = revisionFor(uri, modified, size, name)
        val itemId = referenceStore.idFor(uri)
        referenceStore.put(itemId, ItemReference(uri, revision))
        val albumName = cursor.stringOrNull(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)?.takeIf(String::isNotBlank)
        val albumId = albumName?.let(referenceStore::idForAlbum)
        return CatalogItemPayload(
            itemId = itemId,
            displayName = name.take(255),
            mimeType = mime.lowercase(),
            sizeBytes = size,
            createdAtUtc = created?.let { Instant.ofEpochSecond(it).toString() },
            modifiedAtUtc = Instant.ofEpochSecond(modified).toString(),
            category = category,
            width = cursor.intOrNull(MediaStore.MediaColumns.WIDTH)?.takeIf { it > 0 },
            height = cursor.intOrNull(MediaStore.MediaColumns.HEIGHT)?.takeIf { it > 0 },
            durationMillis = cursor.longOrNull(MediaStore.MediaColumns.DURATION)?.takeIf { it >= 0 },
            albumId = albumId,
            albumDisplayName = albumName?.take(255),
            generation = currentGeneration(),
            revision = revision,
            thumbnailAvailable = category == "image" || category == "video",
        )
    }

    private fun projection(): Array<String> = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.DURATION,
        MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
    )

    private suspend fun querySaf(query: CatalogQueryPayload, offset: Int, requestedTree: String?): CatalogSourcePage {
        val candidates = mutableListOf<SafDocumentCandidate>()
        var scannedEntries = 0
        val modifiedAfterMillis = query.modifiedAfterUtc?.let { threshold ->
            runCatching { Instant.parse(threshold).toEpochMilli() }.getOrElse {
                throw CatalogSourceException("INVALID_QUERY", "modifiedAfterUtc is invalid.")
            }
        }
        val trees = if (accessStore.isDocumentsAccessEnabled()) {
            requestedTree?.let(::setOf) ?: accessStore.selectedTreeUris()
        } else emptySet()
        for (treeValue in trees.sorted()) {
            coroutineContext.ensureActive()
            val treeUri = Uri.parse(treeValue)
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
            val rootName = documentDisplayName(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)) ?: "Selected folder"
            val albumId = referenceStore.idForAlbum("saf:$treeValue")
            val pendingDirectories = java.util.ArrayDeque<String>().apply { add(rootId) }
            val visitedDirectories = mutableSetOf<String>()
            while (pendingDirectories.isNotEmpty() && scannedEntries < MAX_SAF_SCAN_ENTRIES) {
                coroutineContext.ensureActive()
                val directoryId = pendingDirectories.removeFirst()
                if (!visitedDirectories.add(directoryId)) continue
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId)
                resolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_FLAGS,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext() && scannedEntries < MAX_SAF_SCAN_ENTRIES) {
                        coroutineContext.ensureActive()
                        scannedEntries++
                        val documentId = cursor.stringOrNull(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: continue
                        val mime = cursor.stringOrNull(DocumentsContract.Document.COLUMN_MIME_TYPE)
                            ?: "application/octet-stream"
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pendingDirectories.addLast(documentId)
                            continue
                        }
                        val category = if (mime.startsWith("text/") || mime == "application/pdf" ||
                            mime.contains("document") || mime.contains("sheet") || mime.contains("presentation")) {
                            "document"
                        } else "other"
                        if (category !in query.categories && query.categories.isNotEmpty()) continue
                        if (query.mimeTypes.isNotEmpty() && mime !in query.mimeTypes) continue
                        val name = cursor.stringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            ?.takeIf(String::isNotBlank) ?: "Unnamed item"
                        if (query.search != null && !name.contains(query.search, ignoreCase = true)) continue
                        val modifiedMillis = cursor.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED) ?: 0L
                        if (modifiedAfterMillis != null && modifiedMillis <= modifiedAfterMillis) continue
                        candidates += SafDocumentCandidate(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                            name = name,
                            mime = mime,
                            size = cursor.longOrNull(DocumentsContract.Document.COLUMN_SIZE)?.takeIf { it >= 0 },
                            modifiedMillis = modifiedMillis,
                            flags = cursor.longOrNull(DocumentsContract.Document.COLUMN_FLAGS)?.toInt() ?: 0,
                            category = category,
                            albumId = albumId,
                            albumName = rootName,
                        )
                    }
                }
            }
        }
        val ascending = when (query.sortBy) {
            "displayName" -> compareBy<SafDocumentCandidate> { it.name.lowercase() }
            "sizeBytes" -> compareBy { it.size ?: -1L }
            "createdAtUtc", "modifiedAtUtc" -> compareBy { it.modifiedMillis }
            else -> throw CatalogSourceException("INVALID_QUERY", "Unsupported sortBy.")
        }.thenBy { it.name.lowercase() }
        val sorted = candidates.sortedWith(if (query.sortDirection == "desc") ascending.reversed() else ascending)
        val pageCandidates = sorted.drop(offset).take(query.pageSize + 1)
        val result = pageCandidates.map { candidate ->
            val revision = revisionFor(candidate.uri, candidate.modifiedMillis, candidate.size, candidate.name)
            val itemId = referenceStore.idFor(candidate.uri)
            referenceStore.put(itemId, ItemReference(candidate.uri, revision))
            CatalogItemPayload(
                itemId = itemId,
                displayName = candidate.name.take(255),
                mimeType = candidate.mime.lowercase(),
                sizeBytes = candidate.size,
                modifiedAtUtc = Instant.ofEpochMilli(candidate.modifiedMillis).toString(),
                category = candidate.category,
                albumId = candidate.albumId,
                albumDisplayName = candidate.albumName.take(255),
                generation = currentGeneration(),
                revision = revision,
                thumbnailAvailable = candidate.flags and DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL != 0,
            )
        }.toMutableList()
        val hasMore = result.size > query.pageSize
        if (hasMore) result.removeAt(result.lastIndex)
        return CatalogSourcePage(result, if (hasMore) encodePageToken(offset + result.size) else null, currentGeneration())
    }

    private data class SafDocumentCandidate(
        val uri: Uri,
        val name: String,
        val mime: String,
        val size: Long?,
        val modifiedMillis: Long,
        val flags: Int,
        val category: String,
        val albumId: String,
        val albumName: String,
    )

    private fun documentDisplayName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.stringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME) else null }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private fun decodeSampled(uri: Uri, width: Int, height: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw FileNotFoundException(uri.toString())
        var sample = 1
        while (bounds.outWidth / sample > width * 2 || bounds.outHeight / sample > height * 2) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw FileNotFoundException(uri.toString())
        if (decoded.width <= width && decoded.height <= height) return decoded
        val scale = minOf(width.toFloat() / decoded.width, height.toFloat() / decoded.height)
        return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    private fun encodeBitmap(bitmap: Bitmap, format: String, requestedQuality: Int): ByteArray {
        val compressFormat = when (format) {
            "jpeg" -> Bitmap.CompressFormat.JPEG
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            else -> throw CatalogSourceException("INVALID_REQUEST", "Unsupported thumbnail format.")
        }
        var quality = requestedQuality
        while (quality >= 40) {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(compressFormat, quality, output)) {
                throw CatalogSourceException("THUMBNAIL_UNAVAILABLE", "Thumbnail encoding failed.")
            }
            if (output.size() <= MAX_THUMBNAIL_BYTES) return output.toByteArray()
            quality -= 10
        }
        throw CatalogSourceException("THUMBNAIL_TOO_LARGE", "Encoded thumbnail exceeds 256 KiB.")
    }

    private fun validateQuery(query: CatalogQueryPayload) {
        if (query.pageSize !in 1..200 || query.sortDirection !in setOf("asc", "desc")) {
            throw CatalogSourceException("INVALID_QUERY", "Invalid page size or sort direction.")
        }
        if (query.categories.any { it !in CATEGORIES }) throw CatalogSourceException("INVALID_QUERY", "Unknown category.")
    }

    private fun mediaTypeForCategory(category: String): Int? = when (category) {
        "image" -> MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        "video" -> MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        "audio" -> MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
        "document", "other" -> MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
        else -> null
    }

    private fun categoryForMediaType(type: Int?, mime: String): String = when (type) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> "image"
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> "video"
        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> "audio"
        else -> if (mime.startsWith("text/") || mime == "application/pdf" || mime.contains("document")) "document" else "other"
    }

    private fun revisionFor(uri: Uri, modified: Long, size: Long?, name: String): String = digest("$uri|$modified|$size|$name").take(22)
    private fun currentGeneration(): Long = preferences.getLong(KEY_GENERATION, 1L)
    private fun incrementGeneration(): Long = synchronized(preferences) {
        (currentGeneration() + 1).also { preferences.edit().putLong(KEY_GENERATION, it).apply() }
    }
    private fun encodePageToken(offset: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString("v1:$offset".toByteArray())
    private fun decodePageToken(token: String?): Int {
        if (token == null) return 0
        return runCatching {
            String(Base64.getUrlDecoder().decode(token)).removePrefix("v1:").toInt().takeIf { it >= 0 } ?: error("negative")
        }.getOrElse { throw CatalogSourceException("INVALID_PAGE_TOKEN", "Page token is invalid.") }
    }
    private fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    private fun hasPermission(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun mimeForFormat(format: String) = if (format == "webp") "image/webp" else "image/jpeg"
    private fun digest(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private data class ItemReference(val uri: Uri, val revision: String)

    private inner class ItemReferenceStore(private val prefs: android.content.SharedPreferences) {
        private val secret: ByteArray by lazy {
            prefs.getString(KEY_SECRET, null)?.let(Base64.getDecoder()::decode) ?: ByteArray(32).also {
                SecureRandom().nextBytes(it)
                prefs.edit().putString(KEY_SECRET, Base64.getEncoder().encodeToString(it)).apply()
            }
        }
        fun idFor(uri: Uri) = hmac("item|$uri")
        fun idForAlbum(name: String): String = hmac("album|$name").also { prefs.edit().putString("album:$it", name).apply() }
        fun resolveAlbum(id: String): String? = prefs.getString("album:$id", null)
        fun put(id: String, reference: ItemReference) { prefs.edit().putString("item:$id", "${reference.uri}\n${reference.revision}").apply() }
        fun resolve(id: String): ItemReference? = prefs.getString("item:$id", null)?.split('\n', limit = 2)?.takeIf { it.size == 2 }?.let { ItemReference(Uri.parse(it[0]), it[1]) }
        fun clear() {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith("item:") || it.startsWith("album:") }.forEach(editor::remove)
            editor.apply()
        }
        private fun hmac(value: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))).take(32)
        }
    }

    private companion object {
        const val KEY_GENERATION = "generation"
        const val KEY_SECRET = "id_secret"
        const val MAX_THUMBNAIL_BYTES = 256 * 1024
        const val MAX_SAF_SCAN_ENTRIES = 10_000
        val CATEGORIES = setOf("image", "video", "audio", "document", "other")
    }
}
