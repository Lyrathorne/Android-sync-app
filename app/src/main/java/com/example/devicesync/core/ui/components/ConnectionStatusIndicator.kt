package com.example.devicesync.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.devicesync.core.model.ConnectionStatus
import com.example.devicesync.ui.theme.StatusConnected
import com.example.devicesync.ui.theme.StatusConnecting
import com.example.devicesync.ui.theme.StatusError
import com.example.devicesync.ui.theme.StatusOffline

@Composable
fun ConnectionStatusIndicator(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(status.color),
    )
}

private val ConnectionStatus.color: Color
    get() = when (this) {
        ConnectionStatus.CONNECTED -> StatusConnected
        ConnectionStatus.CONNECTING -> StatusConnecting
        ConnectionStatus.OFFLINE -> StatusOffline
        ConnectionStatus.ERROR -> StatusError
    }
