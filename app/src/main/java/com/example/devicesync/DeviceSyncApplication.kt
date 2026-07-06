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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    init {
        connectionManager.startStartupAutoConnect()
    }
}
