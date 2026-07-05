package com.example.devicesync.core.model

object SampleDevices {
    val devices = listOf(
        Device(
            id = "gleb-pc",
            name = "Gleb-PC",
            connectionStatus = ConnectionStatus.CONNECTED,
        ),
        Device(
            id = "work-pc",
            name = "Work-PC",
            connectionStatus = ConnectionStatus.OFFLINE,
            lastConnectedText = "сегодня, 18:20",
        ),
    )

    fun findById(id: String): Device? = devices.firstOrNull { it.id == id }
}
