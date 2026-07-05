package com.example.devicesync.feature.devices

import com.example.devicesync.MainDispatcherRule
import com.example.devicesync.core.model.ConnectionStatus
import com.example.devicesync.core.model.InMemoryDeviceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class DevicesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initialState_containsSampleDevices() {
        val viewModel = DevicesViewModel()

        val state = viewModel.uiState.value

        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(2, state.devices.size)
    }

    @Test
    fun initialState_displaysExpectedSampleDeviceList() {
        val viewModel = DevicesViewModel()

        val devices = viewModel.uiState.value.devices

        assertEquals("Gleb-PC", devices[0].name)
        assertEquals(ConnectionStatus.CONNECTED, devices[0].connectionStatus)
        assertEquals("Work-PC", devices[1].name)
        assertEquals(ConnectionStatus.OFFLINE, devices[1].connectionStatus)
    }

    @Test
    fun removeDevice_removesDeviceFromState() {
        val store = InMemoryDeviceStore()
        val viewModel = DevicesViewModel(store)

        viewModel.removeDevice("work-pc")

        val devices = viewModel.uiState.value.devices
        assertEquals(1, devices.size)
        assertEquals("gleb-pc", devices.first().id)
    }
}
