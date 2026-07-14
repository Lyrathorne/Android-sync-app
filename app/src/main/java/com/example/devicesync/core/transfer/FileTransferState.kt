package com.example.devicesync.core.transfer

sealed interface FileTransferState {
    data object Idle : FileTransferState
    data object ReadingMetadata : FileTransferState
    data object Hashing : FileTransferState
    data object WaitingForAcceptance : FileTransferState
    data class Transferring(
        val sentBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) : FileTransferState
    data object WaitingForReceipt : FileTransferState
    data class Completed(val savedFileName: String) : FileTransferState
    data class Rejected(val code: String, val message: String?) : FileTransferState
    data object Cancelled : FileTransferState
    data class Failed(val code: String, val message: String) : FileTransferState
}
