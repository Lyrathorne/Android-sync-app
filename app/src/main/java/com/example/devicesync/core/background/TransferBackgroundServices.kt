package com.example.devicesync.core.background

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.devicesync.DeviceSyncApplication
import com.example.devicesync.MainActivity
import com.example.devicesync.R
import com.example.devicesync.core.transfer.FileTransferState
import com.example.devicesync.core.transfer.IncomingFileTransferState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class TransferForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
            }.collect { active -> if (observedActive && !active) stopSelf() }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { serviceScope.cancel(); super.onDestroy() }
    companion object {
        const val CHANNEL_ID = "devicesync_transfers"
        const val NOTIFICATION_ID = 1001
        fun start(context: Context) = androidx.core.content.ContextCompat.startForegroundService(
            context, Intent(context, TransferForegroundService::class.java),
        )
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
