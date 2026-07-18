package com.example.devicesync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.devicesync.core.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = LightPrimary, onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer, onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary, onSecondary = LightOnSecondary,
    background = LightBackground, onBackground = LightOnSurface,
    surface = LightSurface, onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant, onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline, error = LightError, onError = LightOnError,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary, onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer, onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary, onSecondary = DarkOnSecondary,
    background = DarkBackground, onBackground = DarkOnSurface,
    surface = DarkSurface, onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant, onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline, error = DarkError, onError = DarkOnError,
)

private val HighContrastColors = darkColorScheme(
    primary = HighContrastPrimary, onPrimary = HighContrastOnPrimary,
    primaryContainer = HighContrastPrimary, onPrimaryContainer = HighContrastOnPrimary,
    secondary = HighContrastOnSurface, onSecondary = HighContrastBackground,
    background = HighContrastBackground, onBackground = HighContrastOnSurface,
    surface = HighContrastSurface, onSurface = HighContrastOnSurface,
    surfaceVariant = HighContrastSurfaceVariant, onSurfaceVariant = HighContrastOnSurface,
    outline = HighContrastOutline, error = HighContrastError, onError = HighContrastBackground,
)

object DeviceSyncThemeTokens {
    val spacing: DeviceSyncSpacing
        @Composable get() = LocalDeviceSyncSpacing.current
    val status: DeviceSyncStatusColors
        @Composable get() = LocalDeviceSyncStatusColors.current
}

@Composable
fun DeviceSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: ThemeMode = if (darkTheme) ThemeMode.DARK else ThemeMode.LIGHT,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val statusColors = when {
        highContrast -> DeviceSyncStatusColors(
            connected = androidx.compose.ui.graphics.Color(0xFF00FF66),
            syncing = androidx.compose.ui.graphics.Color.Yellow,
            offline = androidx.compose.ui.graphics.Color.White,
            error = HighContrastError,
            attention = androidx.compose.ui.graphics.Color.Yellow,
        )
        useDarkTheme -> DeviceSyncStatusColors(
            DarkStatusConnected, DarkStatusSyncing, DarkStatusOffline, DarkStatusError, DarkStatusAttention
        )
        else -> DeviceSyncStatusColors(StatusConnected, StatusSyncing, StatusOffline, StatusError, StatusAttention)
    }

    CompositionLocalProvider(LocalDeviceSyncStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = when {
                highContrast -> HighContrastColors
                useDarkTheme -> DarkColors
                else -> LightColors
            },
            typography = Typography,
            shapes = DeviceSyncShapes,
            content = content,
        )
    }
}
