package com.example.devicesync.core.transfer

data class OutgoingFileTransfer(
    val transferId: String,
    val uri: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val sha256: String,
    val targetDeviceId: String,
    val startedAtMillis: Long,
    val sentBytes: Long = 0,
    val nextChunkIndex: Int = 0,
)
