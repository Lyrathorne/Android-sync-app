package com.example.devicesync.feature.send_file

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.devicesync.core.network.ConnectionManager
import com.example.devicesync.core.network.ConnectionState
import com.example.devicesync.core.transfer.FileMetadataSource
import com.example.devicesync.core.transfer.FileTransferManager
import com.example.devicesync.core.transfer.FileTransferState
import com.example.devicesync.core.transfer.OutgoingFileTransferController
import com.example.devicesync.core.transfer.PersistentOutgoingTransferQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SendFileUiState(
    val selectedUri: String? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long? = null,
    val mimeType: String? = null,
    val targetName: String = "No connected computer",
    val status: String = "Choose a file",
    val sentBytes: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val progress: Float = 0f,
    val canSend: Boolean = false,
    val canCancel: Boolean = false,
    val error: String? = null,
)

class SendFileViewModel(
    private val fileTransferManager: OutgoingFileTransferController,
    private val metadataSource: FileMetadataSource,
    private val connectionState: StateFlow<ConnectionState>,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SendFileUiState())
    val uiState: StateFlow<SendFileUiState> = _uiState.asStateFlow()

    init {
        connectionState.onEach { connection ->
            val connected = connection as? ConnectionState.Connected
            _uiState.update {
                it.copy(
                    targetName = connected?.deviceName ?: "No connected computer",
                    canSend = connected != null && it.selectedUri != null && !it.canCancel,
                )
            }
        }.launchIn(viewModelScope)

        fileTransferManager.state.onEach(::applyTransferState).launchIn(viewModelScope)
    }

    fun selectFile(uri: String) {
        viewModelScope.launch {
            runCatching { metadataSource.read(uri) }
                .onSuccess { metadata ->
                    _uiState.update { current ->
                        current.copy(
                            selectedUri = uri,
                            fileName = metadata.displayName,
                            fileSizeBytes = metadata.sizeBytes,
                            mimeType = metadata.mimeType,
                            totalBytes = metadata.sizeBytes,
                            status = "Ready to send",
                            error = null,
                            canSend = connectionState.value is ConnectionState.Connected,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(status = "Cannot read file", error = error.message, canSend = false) }
                }
        }
    }

    fun send() {
        val uri = _uiState.value.selectedUri ?: return
        runCatching { fileTransferManager.start(uri) }
            .onFailure { error -> _uiState.update { it.copy(error = error.message, canSend = false) } }
    }

    fun cancel() {
        viewModelScope.launch { fileTransferManager.cancel() }
    }

    private fun applyTransferState(state: FileTransferState) {
        _uiState.update { current ->
            when (state) {
                FileTransferState.Idle -> current
                FileTransferState.ReadingMetadata -> current.copy(status = "Reading metadata", canSend = false, canCancel = true, error = null)
                FileTransferState.Hashing -> current.copy(status = "Calculating SHA-256", canSend = false, canCancel = true)
                FileTransferState.WaitingForAcceptance -> current.copy(status = "Waiting for Windows approval", canSend = false, canCancel = true)
                is FileTransferState.Transferring -> current.copy(
                    status = "Sending",
                    sentBytes = state.sentBytes,
                    totalBytes = state.totalBytes,
                    bytesPerSecond = state.bytesPerSecond,
                    progress = if (state.totalBytes == 0L) 1f else state.sentBytes.toFloat() / state.totalBytes,
                    canSend = false,
                    canCancel = true,
                )
                FileTransferState.WaitingForReceipt -> current.copy(status = "Verifying on Windows", canSend = false, canCancel = true, progress = 1f)
                is FileTransferState.Completed -> current.copy(status = "Saved as ${state.savedFileName}", canSend = true, canCancel = false, progress = 1f)
                is FileTransferState.Rejected -> current.copy(status = "Rejected", error = state.message ?: state.code, canSend = true, canCancel = false)
                FileTransferState.Cancelled -> current.copy(status = "Cancelled", canSend = true, canCancel = false)
                is FileTransferState.Failed -> current.copy(status = "Failed", error = state.message, canSend = true, canCancel = false)
            }
        }
    }

    class Factory(
        private val fileTransferManager: PersistentOutgoingTransferQueue,
        private val metadataSource: FileMetadataSource,
        private val connectionManager: ConnectionManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SendFileViewModel(fileTransferManager, metadataSource, connectionManager.state) as T
    }
}
