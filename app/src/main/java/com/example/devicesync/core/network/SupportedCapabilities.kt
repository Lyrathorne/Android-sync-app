package com.example.devicesync.core.network

object SupportedCapabilities {
    const val FILE_TRANSFER_V1 = "file-transfer-v1"
    const val FILE_TRANSFER_V2 = "file-transfer-v2"
    const val CLIPBOARD_V1 = "clipboard-v1"
    const val TEXT_SHARE_V1 = "text-share-v1"
    const val NOTIFICATIONS_V1 = "notifications-v1"
    const val FOLDER_SYNC_V1 = "folder-sync-v1"
    const val CLIPBOARD_AUTO_V1 = "clipboard-auto-v1"
    const val FILE_AUTO_RECEIVE_V1 = "file-auto-receive-v1"
    const val MEDIA_CATALOG_V1 = "media-catalog-v1"
    const val THUMBNAILS_V1 = "thumbnails-v1"
    const val TRANSPORT_LAN_TLS_V1 = "transport-lan-tls-v1"
    const val TRANSPORT_HOTSPOT_V1 = "transport-hotspot-v1"
    const val TRANSPORT_USB_V1 = "transport-usb-v1"
    const val TRANSPORT_BLUETOOTH_V1 = "transport-bluetooth-v1"

    val values: List<String> = listOf(
        "heartbeat-v1",
        "ack-v1",
        "reconnect-v1",
        FILE_TRANSFER_V1,
        FILE_TRANSFER_V2,
        CLIPBOARD_V1,
        TEXT_SHARE_V1,
        NOTIFICATIONS_V1,
        FOLDER_SYNC_V1,
        MEDIA_CATALOG_V1,
        THUMBNAILS_V1,
        TRANSPORT_LAN_TLS_V1,
        TRANSPORT_HOTSPOT_V1,
        TRANSPORT_USB_V1,
        TRANSPORT_BLUETOOTH_V1,
    )
}
