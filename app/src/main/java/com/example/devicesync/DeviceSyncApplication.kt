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
import com.example.devicesync.core.network.ConnectionManager
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
import java.util.concurrent.TimeUnit

private val Context.deviceSyncDataStore by preferencesDataStore(name = "device_sync_settings")

class DeviceSyncApplication : Application() {
    lateinit var container: DeviceSyncContainer
        private set

    override fun onCreate() {
        super.onCreate()
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
        scope = applicationScope,
    )
    val networkMonitor = NetworkMonitor(context)
    val discoveryService = AndroidNsdDiscoveryService(context)
    val connectionManager = ConnectionManager(
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
    val fileMetadataReader = FileMetadataReader(context.contentResolver)
    val fileTransferManager = FileTransferManager(
        metadataSource = fileMetadataReader,
        transport = connectionManager,
        scope = applicationScope,
    )
    val outgoingTransferQueue = PersistentOutgoingTransferQueue(
        manager = fileTransferManager,
        store = JsonAndroidOutgoingQueueStore(context),
        scope = applicationScope,
    )
    val folderSyncManager = FolderSyncManager(context, SafFolderManifestBuilder(context.contentResolver), connectionManager, outgoingTransferQueue)
    val incomingFileTransferManager = IncomingFileTransferManager(
        transport = connectionManager,
        destination = ContentResolverIncomingFileDestination(context.contentResolver),
        scope = applicationScope,
        checkpointStore = JsonIncomingTransferCheckpointStore(context),
        folderAuthorizer = folderSyncManager,
    )
    val sharingManager = SharingManager(
        transport = connectionManager,
        identityRepository = identityRepository,
        clipboard = context.getSystemService(ClipboardManager::class.java),
        preferences = SharingPreferences(context),
    )
    val notificationPreferences = NotificationForwardingPreferences(context)
    val notificationForwarder = NotificationForwarder(connectionManager, applicationScope)

    init {
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
        connectionManager.startStartupAutoConnect()
    }
}
