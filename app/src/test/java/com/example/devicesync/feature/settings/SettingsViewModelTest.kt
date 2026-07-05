package com.example.devicesync.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsViewModelTest {
    @Test
    fun settingsCanBeChanged() {
        val viewModel = SettingsViewModel()

        viewModel.setAutoConnectTrustedComputers(false)
        viewModel.setShowConnectionNotification(false)
        viewModel.setAllowBackgroundWork(true)
        viewModel.setUseDarkTheme(true)

        val state = viewModel.uiState.value
        assertFalse(state.autoConnectTrustedComputers)
        assertFalse(state.showConnectionNotification)
        assertTrue(state.allowBackgroundWork)
        assertTrue(state.useDarkTheme)
    }
}
