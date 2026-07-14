package com.example.devicesync.feature.send_file

import com.example.devicesync.MainDispatcherRule
import com.example.devicesync.core.network.ConnectionState
import com.example.devicesync.core.network.SupportedCapabilities
import com.example.devicesync.core.transfer.FileMetadata
import com.example.devicesync.core.transfer.FileMetadataSource
import com.example.devicesync.core.transfer.FileTransferState
import com.example.devicesync.core.transfer.OutgoingFileTransferController
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendFileViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun selectedFile_canBeSentToConnectedComputer() = runTest {
        val controller = FakeController()
        val connection = MutableStateFlow<ConnectionState>(connected())
        val viewModel = SendFileViewModel(controller, FakeMetadataSource(), connection)
        runCurrent()

        viewModel.selectFile("content://test/file")
        runCurrent()

        assertEquals("report.txt", viewModel.uiState.value.fileName)
        assertEquals("Windows PC", viewModel.uiState.value.targetName)
        assertTrue(viewModel.uiState.value.canSend)

        viewModel.send()
        assertEquals("content://test/file", controller.startedUri)
    }

    @Test
    fun progressRejectFailureAndCompletion_areRendered() = runTest {
        val controller = FakeController()
        val viewModel = SendFileViewModel(
            controller,
            FakeMetadataSource(),
            MutableStateFlow<ConnectionState>(connected()),
        )
        runCurrent()

        controller.mutableState.value = FileTransferState.WaitingForAcceptance
        runCurrent()
        assertEquals("Waiting for Windows approval", viewModel.uiState.value.status)
        assertTrue(viewModel.uiState.value.canCancel)

        controller.mutableState.value = FileTransferState.Transferring(50, 100, 25)
        runCurrent()
        assertEquals(0.5f, viewModel.uiState.value.progress)

        controller.mutableState.value = FileTransferState.Rejected("user_rejected", "Declined")
        runCurrent()
        assertEquals("Declined", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.canCancel)

        controller.mutableState.value = FileTransferState.Completed("report (1).txt")
        runCurrent()
        assertEquals("Saved as report (1).txt", viewModel.uiState.value.status)
    }

    @Test
    fun cancel_delegatesToTransferController() = runTest {
        val controller = FakeController()
        val viewModel = SendFileViewModel(
            controller,
            FakeMetadataSource(),
            MutableStateFlow<ConnectionState>(connected()),
        )
        runCurrent()

        viewModel.cancel()
        runCurrent()

        assertTrue(controller.cancelled)
    }

    private class FakeController : OutgoingFileTransferController {
        val mutableState = MutableStateFlow<FileTransferState>(FileTransferState.Idle)
        override val state = mutableState
        var startedUri: String? = null
        var cancelled = false

        override fun start(uri: String): Job {
            startedUri = uri
            return Job().apply { complete() }
        }

        override suspend fun cancel() {
            cancelled = true
        }
    }

    private class FakeMetadataSource : FileMetadataSource {
        override fun read(uri: String) = FileMetadata("report.txt", 100, "text/plain")
        override fun open(uri: String): InputStream = ByteArrayInputStream(ByteArray(100))
    }

    private fun connected() = ConnectionState.Connected(
        deviceId = "windows-test",
        deviceName = "Windows PC",
        host = "192.168.1.2",
        port = 54321,
        acceptedProtocolVersion = 1,
        capabilities = listOf(SupportedCapabilities.FILE_TRANSFER_V1),
    )
}
