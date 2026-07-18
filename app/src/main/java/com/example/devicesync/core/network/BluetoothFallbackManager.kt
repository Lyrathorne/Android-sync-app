package com.example.devicesync.core.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.devicesync.core.data.DeviceRepository
import kotlinx.coroutines.flow.first

data class BluetoothFallbackCandidate(
    val pairedDeviceId: String,
    val computerName: String,
    val bluetoothName: String,
    val bluetoothAddress: String,
)

class BluetoothFallbackManager(
    private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val connectionManager: ConnectionManager,
    private val adapterProvider: () -> BluetoothAdapter? = {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    },
) {
    fun isSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun candidates(): List<BluetoothFallbackCandidate> {
        if (!isSupported() || !hasPermission()) return emptyList()
        val pairedDevices = deviceRepository.observeDevices().first()
        val bonded = adapterProvider()?.bondedDevices.orEmpty()
        return pairedDevices.flatMap { logical ->
            val normalizedLogical = logical.name.normalizedName()
            val matching = bonded.filter { bluetooth ->
                val normalizedBluetooth = bluetooth.name.orEmpty().normalizedName()
                normalizedBluetooth.contains(normalizedLogical) ||
                    normalizedLogical.contains(normalizedBluetooth)
            }.ifEmpty { if (pairedDevices.size == 1) bonded.toList() else emptyList() }
            matching.map { bluetooth ->
                BluetoothFallbackCandidate(
                    pairedDeviceId = logical.id,
                    computerName = logical.name,
                    bluetoothName = bluetooth.name ?: "Bluetooth device",
                    bluetoothAddress = bluetooth.address,
                )
            }
        }.distinctBy { "${it.pairedDeviceId}|${it.bluetoothAddress}" }
    }

    suspend fun connect(candidate: BluetoothFallbackCandidate): ConnectionState.Connected =
        connectionManager.connectBluetooth(candidate.pairedDeviceId, candidate.bluetoothAddress)

    private fun String.normalizedName(): String =
        lowercase().filter(Char::isLetterOrDigit)
}
