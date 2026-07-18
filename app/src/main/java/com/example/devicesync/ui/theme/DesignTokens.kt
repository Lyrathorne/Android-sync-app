package com.example.devicesync.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class DeviceSyncSpacing(
    val xxs: androidx.compose.ui.unit.Dp = 4.dp,
    val xs: androidx.compose.ui.unit.Dp = 8.dp,
    val sm: androidx.compose.ui.unit.Dp = 12.dp,
    val md: androidx.compose.ui.unit.Dp = 16.dp,
    val lg: androidx.compose.ui.unit.Dp = 24.dp,
    val xl: androidx.compose.ui.unit.Dp = 32.dp,
    val xxl: androidx.compose.ui.unit.Dp = 48.dp,
)

@Immutable
data class DeviceSyncStatusColors(
    val connected: Color,
    val syncing: Color,
    val offline: Color,
    val error: Color,
    val attention: Color,
)

val LocalDeviceSyncSpacing = staticCompositionLocalOf { DeviceSyncSpacing() }
val LocalDeviceSyncStatusColors = staticCompositionLocalOf {
    DeviceSyncStatusColors(StatusConnected, StatusSyncing, StatusOffline, StatusError, StatusAttention)
}

val DeviceSyncShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
