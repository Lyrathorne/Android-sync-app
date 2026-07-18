package com.example.devicesync.core.background

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.example.devicesync.DeviceSyncApplication
import com.example.devicesync.MainActivity
import com.example.devicesync.R
import com.example.devicesync.core.transfer.FileTransferState
import com.example.devicesync.core.transfer.IncomingFileTransferState
import com.example.devicesync.core.network.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class ConnectionForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DeviceSync connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Keeps an explicitly enabled DeviceSync connection available in the background" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = buildConnectionNotification(getString(R.string.background_connection_starting))
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else 0,
        )
        val container = (application as DeviceSyncApplication).container
        serviceScope.launch {
            container.connectionManager.state.collect { state ->
                val text = when (state) {
                    is ConnectionState.Connected -> getString(R.string.background_connection_connected, state.deviceName)
                    is ConnectionState.Reconnecting -> getString(R.string.background_connection_reconnecting, state.attempt)
                    is ConnectionState.NetworkUnavailable -> getString(R.string.background_connection_waiting_network)
                    is ConnectionState.Failed -> getString(R.string.background_connection_retrying)
                    else -> getString(R.string.background_connection_starting)
                }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildConnectionNotification(text))
            }
        }
        acquireStartupWakeLock()
    }

    private fun buildConnectionNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("DeviceSync")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(openIntent)
                .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val explicitlyEnabled = intent?.getBooleanExtra(EXTRA_EXPLICITLY_ENABLED, false) == true
        val container = (application as DeviceSyncApplication).container
        serviceScope.launch {
            val settings = container.settingsRepository.settings.first()
            if (!BackgroundConnectionPolicy.shouldRun(explicitlyEnabled, settings.backgroundWorkEnabled)) {
                stopSelf()
                return@launch
            }
            container.connectionManager.startStartupAutoConnect()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // stopWithTask=false keeps this already-running foreground service alive. Starting
        // another service here causes reconnect storms on some OEM Android builds.
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        serviceScope.cancel()
        releaseRuntimeLocks()
        super.onDestroy()
    }

    private fun acquireStartupWakeLock() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeviceSync:connection")
            .apply {
                setReferenceCounted(false)
                acquire(STARTUP_WAKE_LOCK_MILLIS)
            }
        serviceScope.launch {
            delay(STARTUP_WAKE_LOCK_MILLIS)
            releaseRuntimeLocks()
        }
    }

    private fun releaseRuntimeLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val CHANNEL_ID = "devicesync_connection"
        const val NOTIFICATION_ID = 1000
        private const val EXTRA_EXPLICITLY_ENABLED = "explicitly_enabled"
        private const val STARTUP_WAKE_LOCK_MILLIS = 30_000L

        fun start(context: Context, explicitlyEnabled: Boolean = false) =
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, ConnectionForegroundService::class.java)
                    .putExtra(EXTRA_EXPLICITLY_ENABLED, explicitlyEnabled),
            )

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionForegroundService::class.java))
        }
    }
}

class DeviceSyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_USER_UNLOCKED
        ) {
            val pendingResult = goAsync()
            val application = context.applicationContext as DeviceSyncApplication
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val enabled = application.container.settingsRepository.settings.first().backgroundWorkEnabled
                    if (enabled) runCatching { ConnectionForegroundService.start(context) }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}

class TransferForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Active file transfers", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("DeviceSync file transfer")
            .setContentText("Transfer is active")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build())
        val container = (application as DeviceSyncApplication).container
        serviceScope.launch {
            var observedActive = false
            combine(container.fileTransferManager.state, container.incomingFileTransferManager.state) { outgoing, incoming ->
                val active = outgoing is FileTransferState.ReadingMetadata || outgoing is FileTransferState.Hashing ||
                    outgoing is FileTransferState.WaitingForAcceptance || outgoing is FileTransferState.Transferring ||
                    outgoing is FileTransferState.WaitingForReceipt || incoming is IncomingFileTransferState.Receiving ||
                    incoming is IncomingFileTransferState.Verifying
                observedActive = observedActive || active
                active
            }.collect { active ->
                if (active) acquireTransferLocks() else releaseTransferLocks()
                if (observedActive && !active) stopSelf()
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { serviceScope.cancel(); releaseTransferLocks(); super.onDestroy() }

    private fun acquireTransferLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeviceSync:transfer")
                .apply { setReferenceCounted(false); acquire(MAX_TRANSFER_LOCK_MILLIS) }
        }
        if (wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DeviceSync:transfer")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseTransferLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }
    companion object {
        const val CHANNEL_ID = "devicesync_transfers"
        const val NOTIFICATION_ID = 1001
        private const val MAX_TRANSFER_LOCK_MILLIS = 10 * 60 * 1000L
        fun start(context: Context) = androidx.core.content.ContextCompat.startForegroundService(
            context, Intent(context, TransferForegroundService::class.java),
        )
    }
}

object BackgroundConnectionPolicy {
    fun shouldRun(explicitStart: Boolean, backgroundWorkEnabled: Boolean): Boolean =
        explicitStart || backgroundWorkEnabled
}

object ConnectionRecoveryScheduler {
    private const val IMMEDIATE_WORK = "devicesync-connection-recovery"
    private const val PERIODIC_WORK = "devicesync-connection-watchdog"

    fun configure(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            workManager.cancelUniqueWork(IMMEDIATE_WORK)
            workManager.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val immediate = OneTimeWorkRequestBuilder<ConnectionWatchdogWorker>()
            .setConstraints(constraints)
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.KEEP, immediate)
        val periodic = PeriodicWorkRequestBuilder<ConnectionWatchdogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodic)
    }
}

class TransferRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as DeviceSyncApplication).container
        container.incomingFileTransferManager.cleanupStalePartials()
        container.outgoingTransferQueue.wake()
        return Result.success()
    }
}

class ConnectionWatchdogWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as DeviceSyncApplication).container
        val settings = container.settingsRepository.settings.first()
        if (!settings.backgroundWorkEnabled) return Result.success()

        return runCatching {
            ConnectionForegroundService.start(applicationContext)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
