package com.example.devicesync.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.devicesync.core.settings.AppSettingsRepository
import com.example.devicesync.core.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: AppSettingsRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        settingsRepository?.settings
            ?.onEach { settings ->
                _uiState.update {
                    it.copy(
                        autoConnectTrustedComputers = settings.autoConnectEnabled,
                        restoreConnectionAfterDisconnect = settings.restoreConnectionEnabled,
                        allowBackgroundWork = settings.backgroundWorkEnabled,
                        useDarkTheme = settings.themeMode == ThemeMode.DARK,
                    )
                }
            }
            ?.launchIn(viewModelScope)
    }

    fun setAutoConnectTrustedComputers(enabled: Boolean) {
        _uiState.update { it.copy(autoConnectTrustedComputers = enabled) }
        viewModelScope.launch { settingsRepository?.setAutoConnectEnabled(enabled) }
    }

    fun setRestoreConnectionAfterDisconnect(enabled: Boolean) {
        _uiState.update { it.copy(restoreConnectionAfterDisconnect = enabled) }
        viewModelScope.launch { settingsRepository?.setRestoreConnectionEnabled(enabled) }
    }

    fun setAllowBackgroundWork(enabled: Boolean) {
        _uiState.update { it.copy(allowBackgroundWork = enabled) }
        viewModelScope.launch { settingsRepository?.setBackgroundWorkEnabled(enabled) }
    }

    fun setUseDarkTheme(enabled: Boolean) {
        _uiState.update { it.copy(useDarkTheme = enabled) }
        viewModelScope.launch {
            settingsRepository?.setThemeMode(if (enabled) ThemeMode.DARK else ThemeMode.LIGHT)
        }
    }

    fun showAboutDialog() {
        _uiState.update { it.copy(showAboutDialog = true) }
    }

    fun dismissAboutDialog() {
        _uiState.update { it.copy(showAboutDialog = false) }
    }

    class Factory(
        private val settingsRepository: AppSettingsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(settingsRepository) as T
        }
    }
}
