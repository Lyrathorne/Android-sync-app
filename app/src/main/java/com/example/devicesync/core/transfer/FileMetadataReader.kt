package com.example.devicesync.core.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

data class FileMetadata(
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
)

interface FileMetadataSource {
    fun read(uri: String): FileMetadata
    fun open(uri: String): InputStream
}

class FileMetadataReader(
    private val contentResolver: ContentResolver,
) : FileMetadataSource {
    override fun read(uri: String): FileMetadata {
        val contentUri = Uri.parse(uri)
        var displayName: String? = null
        var sizeBytes: Long? = null
        contentResolver.query(
            contentUri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }

        val name = displayName?.takeIf { it.isNotBlank() }
            ?: throw FileMetadataException("missing_name", "The provider did not return a display name.")
        val size = sizeBytes?.takeIf { it >= 0 }
            ?: throw FileMetadataException("missing_size", "The provider did not return a file size.")
        val mimeType = contentResolver.getType(contentUri) ?: "application/octet-stream"
        return FileMetadata(name, size, mimeType)
    }

    override fun open(uri: String): InputStream {
        return contentResolver.openInputStream(Uri.parse(uri))
            ?: throw FileNotFoundException("The content provider returned no stream.")
    }
}

class FileMetadataException(val code: String, message: String) : Exception(message)
