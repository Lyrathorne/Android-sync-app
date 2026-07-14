package com.example.devicesync.core.protocol

import kotlinx.serialization.Serializable

@Serializable data class FileOfferPayload(
    val transferId: String, val fileName: String, val sizeBytes: Long, val mimeType: String,
    val sha256: String, val chunkSize: Int, val folderSyncId: String? = null,
    val relativePath: String? = null, val conflictCopy: Boolean = false,
)
@Serializable data class FileAcceptPayload(val transferId: String)
@Serializable data class FileRejectPayload(val transferId: String, val code: String, val message: String? = null)
@Serializable data class FileChunkPayload(val transferId: String, val index: Int, val offset: Long, val data: String, val chunkSha256: String? = null)
@Serializable data class FileChunkReceivedPayload(val transferId: String, val nextChunkIndex: Int, val offset: Long)
@Serializable data class FileResumeRequestPayload(
    val transferId: String, val fileName: String, val sizeBytes: Long, val sha256: String, val chunkSize: Int,
    val folderSyncId: String? = null, val relativePath: String? = null, val conflictCopy: Boolean = false,
)
@Serializable data class FileResumeAcceptedPayload(val transferId: String, val nextChunkIndex: Int, val offset: Long)
@Serializable data class FileCompletePayload(val transferId: String, val totalChunks: Int, val sizeBytes: Long)
@Serializable data class FileReceivedPayload(val transferId: String, val sizeBytes: Long, val sha256: String, val savedFileName: String)
@Serializable data class FileCancelPayload(val transferId: String, val reason: String)
@Serializable data class FileErrorPayload(val transferId: String, val code: String, val message: String? = null)
