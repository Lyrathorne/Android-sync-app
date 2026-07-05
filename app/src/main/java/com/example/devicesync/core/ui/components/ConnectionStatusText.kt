package com.example.devicesync.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.devicesync.R
import com.example.devicesync.core.model.ConnectionStatus

@Composable
fun connectionStatusText(status: ConnectionStatus): String {
    return stringResource(
        when (status) {
            ConnectionStatus.CONNECTED -> R.string.connection_connected
            ConnectionStatus.CONNECTING -> R.string.connection_connecting
            ConnectionStatus.OFFLINE -> R.string.connection_offline
            ConnectionStatus.ERROR -> R.string.connection_error
        }
    )
}
