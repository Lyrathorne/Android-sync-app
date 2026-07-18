package com.example.devicesync.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import com.example.devicesync.R
import com.example.devicesync.core.network.ConnectionState
import com.example.devicesync.core.settings.AppSettings
import com.example.devicesync.core.settings.AppSettingsRepository
import com.example.devicesync.core.sharing.SharedTextItem
import com.example.devicesync.core.sharing.SharingManager
import com.example.devicesync.core.transfer.TransferHistoryEntry
import com.example.devicesync.core.transfer.TransferHistoryRepository
import com.example.devicesync.ui.designsystem.DeviceSyncStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeConnectionUi(
    val status: DeviceSyncStatus,
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int,
    val computerName: String? = null,
)

data class HomeUiState(
    val connection: HomeConnectionUi = ConnectionState.Disconnected.toHomeConnectionUi(),
    val transfers: List<TransferHistoryEntry> = emptyList(),
    val sharedItems: List<SharedTextItem> = emptyList(),
    val backgroundWorkEnabled: Boolean = true,
)

fun ConnectionState.toHomeConnectionUi(): HomeConnectionUi = when (this) {
    is ConnectionState.Connected -> HomeConnectionUi(
        DeviceSyncStatus.Connected,
        R.string.status_connected_title,
        R.string.status_connected_detail,
        deviceName,
    )
    is ConnectionState.Authenticated -> HomeConnectionUi(
        DeviceSyncStatus.Syncing,
        R.string.status_finishing_title,
        R.string.status_finishing_detail,
        deviceName,
    )
    is ConnectionState.Connecting,
    is ConnectionState.Handshaking,
    is ConnectionState.AuthenticatingWindows,
    is ConnectionState.ProvingAndroidIdentity -> HomeConnectionUi(
        DeviceSyncStatus.Syncing,
        R.string.status_connecting_title,
        R.string.status_connecting_detail,
    )
    is ConnectionState.Reconnecting -> HomeConnectionUi(
        DeviceSyncStatus.Syncing,
        R.string.status_reconnecting_title,
        R.string.status_reconnecting_detail,
    )
    ConnectionState.NetworkUnavailable -> HomeConnectionUi(
        DeviceSyncStatus.Offline,
        R.string.status_no_network_title,
        R.string.status_no_network_detail,
    )
    is ConnectionState.Failed,
    is ConnectionState.AuthenticationFailed -> HomeConnectionUi(
        DeviceSyncStatus.Error,
        R.string.status_problem_title,
        R.string.status_problem_detail,
    )
    is ConnectionState.IdentityChanged,
    ConnectionState.PairingRequired,
    ConnectionState.TrustRevoked -> HomeConnectionUi(
        DeviceSyncStatus.Attention,
        R.string.status_pairing_title,
        R.string.status_pairing_detail,
    )
    ConnectionState.Disconnected -> HomeConnectionUi(
        DeviceSyncStatus.Offline,
        R.string.status_disconnected_title,
        R.string.status_disconnected_detail,
    )
}

class HomeViewModel(
    connectionState: StateFlow<ConnectionState>,
    sharingManager: SharingManager,
    historyRepository: TransferHistoryRepository,
    settingsRepository: AppSettingsRepository,
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = combine(
        connectionState,
        sharingManager.history,
        historyRepository.entries,
        settingsRepository.settings,
    ) { connection, sharedItems, transfers, settings: AppSettings ->
        HomeUiState(
            connection = connection.toHomeConnectionUi(),
            transfers = transfers,
            sharedItems = sharedItems,
            backgroundWorkEnabled = settings.backgroundWorkEnabled,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    class Factory(
        private val connectionState: StateFlow<ConnectionState>,
        private val sharingManager: SharingManager,
        private val historyRepository: TransferHistoryRepository,
        private val settingsRepository: AppSettingsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(connectionState, sharingManager, historyRepository, settingsRepository) as T
    }
}
