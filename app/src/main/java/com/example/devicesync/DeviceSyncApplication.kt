package com.example.devicesync

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.example.devicesync.core.data.RoomDeviceRepository
import com.example.devicesync.core.data.RoomOutgoingMessageQueue
import com.example.devicesync.core.data.RoomProcessedMessageRepository
import com.example.devicesync.core.database.DeviceSyncDatabase
import com.example.devicesync.core.database.DeviceSyncMigrations
import com.example.devicesync.core.discovery.AndroidNsdDiscoveryService
import com.example.devicesync.core.discovery.HybridDeviceDiscoveryService
import com.example.devicesync.core.discovery.UdpBeaconDiscoveryService
import com.example.devicesync.core.network.ConnectionManager
import com.example.devicesync.core.network.BluetoothRfcommDeviceConnection
import com.example.devicesync.core.network.BluetoothFallbackManager
import android.bluetooth.BluetoothManager
import com.example.devicesync.core.network.NetworkMonitor
import com.example.devicesync.core.security.AndroidKeystoreDeviceIdentityKeyProvider
import com.example.devicesync.core.security.DefaultPairingCoordinator
import com.example.devicesync.core.security.RoomTrustedDeviceRepository
import com.example.devicesync.core.settings.DataStoreAppSettingsRepository
import com.example.devicesync.core.settings.DataStoreDeviceIdentityRepository
import com.example.devicesync.core.transfer.FileMetadataReader
import com.example.devicesync.core.transfer.FileTransferManager
import com.example.devicesync.core.transfer.ContentResolverIncomingFileDestination
import com.example.devicesync.core.transfer.IncomingFileTransferManager
import com.example.devicesync.core.transfer.JsonIncomingTransferCheckpointStore
import com.example.devicesync.core.transfer.JsonAndroidOutgoingQueueStore
import com.example.devicesync.core.transfer.PersistentOutgoingTransferQueue
import com.example.devicesync.core.transfer.TransferHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.content.ClipboardManager
import com.example.devicesync.core.sharing.SharingManager
import com.example.devicesync.core.sharing.SharingPreferences
import com.example.devicesync.core.notifications.NotificationForwarder
import com.example.devicesync.core.notifications.NotificationForwardingPreferences
import com.example.devicesync.core.foldersync.FolderSyncManager
import com.example.devicesync.core.foldersync.SafFolderManifestBuilder
import androidx.work.*
import com.example.devicesync.core.background.TransferRecoveryWorker
import com.example.devicesync.core.background.ConnectionRecoveryScheduler
import com.example.devicesync.core.background.ConnectionForegroundService
import java.util.concurrent.TimeUnit
import com.example.devicesync.keyboard.ime.KeyboardHost
import com.example.devicesync.keyboard.ime.KeyboardIntegration
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import com.example.devicesync.core.network.ConnectionState
import com.example.devicesync.core.keyboard.clipboard.KeyboardClipboardHistoryStore
import com.example.devicesync.keyboard.ime.KeyboardPreferences
import com.example.devicesync.core.catalog.AndroidMediaCatalogSource
import com.example.devicesync.core.catalog.CatalogAccessStore
import com.example.devicesync.core.catalog.MediaCatalogManager
import com.example.devicesync.core.catalog.CatalogSourceException
import com.example.devicesync.core.transfer.FileTransferState
import com.example.devicesync.core.diagnostics.StructuredDiagnosticLog

private val Context.deviceSyncDataStore by preferencesDataStore(name = "device_sync_settings")

class DeviceSyncApplication : Application(), KeyboardHost {
    lateinit var container: DeviceSyncContainer
        private set

    override val keyboardIntegration: KeyboardIntegration
        get() = container.keyboardIntegration

    override fun onCreate() {
        super.onCreate()
        StructuredDiagnosticLog.initialize(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            StructuredDiagnosticLog.record(
                com.example.devicesync.core.diagnostics.DiagnosticEvent(
                    domain = com.example.devicesync.core.diagnostics.DiagnosticDomain.APPLICATION,
                    name = "APPLICATION_CRASH",
                    level = com.example.devicesync.core.diagnostics.DiagnosticLevel.ERROR,
                    errorCode = error.javaClass.simpleName,
                    attributes = mapOf("thread" to thread.name),
                ),
            )
            previousHandler?.uncaughtException(thread, error)
        }
        container = DeviceSyncContainer(this)
    }
}

class DeviceSyncContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = Room.databaseBuilder(
        context,
        DeviceSyncDatabase::class.java,
        "devicesync.db",
    )
        .addMigrations(DeviceSyncMigrations.MIGRATION_1_2)
        .build()

    val identityRepository = DataStoreDeviceIdentityRepository(context.deviceSyncDataStore)
    val settingsRepository = DataStoreAppSettingsRepository(context.deviceSyncDataStore)
    val deviceRepository = RoomDeviceRepository(database.deviceDao())
    val outgoingMessageQueue = RoomOutgoingMessageQueue(database.pendingMessageDao())
    val processedMessageRepository = RoomProcessedMessageRepository(database.processedMessageDao())
    val trustedDeviceRepository = RoomTrustedDeviceRepository(database.trustedDeviceDao())
    val identityKeyProvider = AndroidKeystoreDeviceIdentityKeyProvider()
    val pairingCoordinator = DefaultPairingCoordinator(
        identityRepository = identityRepository,
        identityKeyProvider = identityKeyProvider,
        trustedDeviceRepository = trustedDeviceRepository,
        deviceRepository = deviceRepository,
        settingsRepository = settingsRepository,
        scope = applicationScope,
    )
    val networkMonitor = NetworkMonitor(context)
    val discoveryService = HybridDeviceDiscoveryService(
        AndroidNsdDiscoveryService(context),
        UdpBeaconDiscoveryService(applicationScope),
        applicationScope,
    )
    val connectionManager = ConnectionManager(
        bluetoothConnectionFactory = {
            BluetoothRfcommDeviceConnection {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            }
        },
        scope = applicationScope,
        identityRepository = identityRepository,
        deviceRepository = deviceRepository,
        outgoingMessageQueue = outgoingMessageQueue,
        processedMessageRepository = processedMessageRepository,
        settingsRepository = settingsRepository,
        networkMonitor = networkMonitor,
        identityKeyProvider = identityKeyProvider,
        trustedDeviceRepository = trustedDeviceRepository,
    )
    val bluetoothFallbackManager = BluetoothFallbackManager(context, deviceRepository, connectionManager)
    val fileMetadataReader = FileMetadataReader(context.contentResolver)
    val fileTransferManager = FileTransferManager(
        metadataSource = fileMetadataReader,
        transport = connectionManager,
        scope = applicationScope,
    )
    val catalogAccessStore = CatalogAccessStore(context)
    val mediaCatalogSource = AndroidMediaCatalogSource(context, accessStore = catalogAccessStore)
    val mediaCatalogManager = MediaCatalogManager(
        source = mediaCatalogSource,
        transport = connectionManager,
        startDownload = { uri, transferId ->
            fileTransferManager.transfer(uri = uri, requestedTransferId = transferId)
            when (val result = fileTransferManager.state.value) {
                is FileTransferState.Completed -> Unit
                is FileTransferState.Rejected ->
                    throw CatalogSourceException("ITEM_UNAVAILABLE", result.message ?: result.code, retryable = true)
                is FileTransferState.Failed ->
                    throw CatalogSourceException("ITEM_UNAVAILABLE", result.message, retryable = true)
                FileTransferState.Cancelled ->
                    throw CatalogSourceException("CANCELLED", "The download was cancelled.")
                else ->
                    throw CatalogSourceException("ITEM_UNAVAILABLE", "The original could not be transferred.", retryable = true)
            }
        },
        scope = applicationScope,
    )
    val transferHistoryRepository = TransferHistoryRepository(context)
    val outgoingTransferQueue = PersistentOutgoingTransferQueue(
        manager = fileTransferManager,
        store = JsonAndroidOutgoingQueueStore(context),
        scope = applicationScope,
        historyRepository = transferHistoryRepository,
    )
    val folderSyncManager = FolderSyncManager(context, SafFolderManifestBuilder(context.contentResolver), connectionManager, outgoingTransferQueue)
    val incomingFileTransferManager = IncomingFileTransferManager(
        transport = connectionManager,
        destination = ContentResolverIncomingFileDestination(context.contentResolver),
        scope = applicationScope,
        checkpointStore = JsonIncomingTransferCheckpointStore(context),
        folderAuthorizer = folderSyncManager,
        historyRepository = transferHistoryRepository,
    )
    val keyboardClipboardHistory = KeyboardClipboardHistoryStore(context)
    val keyboardPreferences = KeyboardPreferences(context)
    val sharingManager = SharingManager(
        transport = connectionManager,
        identityRepository = identityRepository,
        preferences = SharingPreferences(context),
        scope = applicationScope,
        currentRemoteDeviceId = {
            when (val state = connectionManager.state.value) {
                is ConnectionState.Connected -> state.deviceId
                is ConnectionState.Authenticated -> state.deviceId
                is ConnectionState.Reconnecting -> state.deviceId
                else -> null
            }
        },
        applyClipboard = { text ->
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("DeviceSync", text))
        },
        onClipboardReceived = { text, source ->
            if (keyboardPreferences.clipboardHistory) keyboardClipboardHistory.add(text, source)
        },
    )
    val keyboardIntegration = object : KeyboardIntegration {
        override fun isAutomaticClipboardSyncEnabled(): Boolean = sharingManager.clipboardEnabled

        override fun onLocalClipboardChanged(text: String, saveToHistory: Boolean, privateContext: Boolean) {
            if (privateContext) return
            if (saveToHistory) keyboardClipboardHistory.add(text, "Android")
            if (sharingManager.clipboardEnabled) {
                applicationScope.launch {
                    runCatching { sharingManager.onLocalClipboardChanged(text, privateContext) }
                }
            }
        }

        override fun clipboardHistory() = keyboardClipboardHistory.snapshot()

        override fun sendClipboardNow(text: String) {
            applicationScope.launch { runCatching { sharingManager.sendClipboardNow(text) } }
        }

        override fun removeClipboardItem(id: String) = keyboardClipboardHistory.remove(id)

        override fun clearClipboardHistory() = keyboardClipboardHistory.clear()

        override fun toggleClipboardPinned(id: String) = keyboardClipboardHistory.togglePinned(id)

    }
    val notificationPreferences = NotificationForwardingPreferences(context)
    val notificationForwarder = NotificationForwarder(connectionManager, applicationScope)

    init {
        applicationScope.launch {
            connectionManager.state.collect { state ->
                if (state is ConnectionState.Connected) sharingManager.flushPendingClipboard()
            }
        }
        incomingFileTransferManager.cleanupStalePartials()
        val recovery = OneTimeWorkRequestBuilder<TransferRecoveryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("devicesync-transfer-recovery", ExistingWorkPolicy.KEEP, recovery)
        val cleanup = PeriodicWorkRequestBuilder<TransferRecoveryWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("devicesync-transfer-cleanup", ExistingPeriodicWorkPolicy.UPDATE, cleanup)
        applicationScope.launch {
            val backgroundEnabled = settingsRepository.settings.first().backgroundWorkEnabled
            ConnectionRecoveryScheduler.configure(context, backgroundEnabled)
            if (backgroundEnabled) {
                runCatching { ConnectionForegroundService.start(context) }
            }
        }
    }
}
