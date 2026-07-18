package com.example.devicesync.feature.home

import com.example.devicesync.core.network.ConnectionState
import com.example.devicesync.ui.designsystem.DeviceSyncStatus
import com.example.devicesync.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeConnectionMapperTest {
    @Test
    fun connectedState_exposesComputerWithoutTechnicalAddress() {
        val result = ConnectionState.Connected(
            deviceId = "computer-1",
            deviceName = "Work laptop",
            host = "192.168.1.10",
            port = 48123,
            acceptedProtocolVersion = 1,
            capabilities = emptyList(),
        ).toHomeConnectionUi()

        assertEquals(DeviceSyncStatus.Connected, result.status)
        assertEquals("Work laptop", result.computerName)
        assertEquals(R.string.status_connected_detail, result.detailRes)
    }

    @Test
    fun identityChange_isActionableWithoutRawDetails() {
        val result = ConnectionState.IdentityChanged("computer-1").toHomeConnectionUi()
        assertEquals(DeviceSyncStatus.Attention, result.status)
        assertEquals(R.string.status_pairing_title, result.titleRes)
    }
}
