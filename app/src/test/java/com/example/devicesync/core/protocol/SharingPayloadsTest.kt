package com.example.devicesync.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SharingPayloadsTest {
    @Test
    fun sharedClipboardVectorUsesStableCrossPlatformFieldsAndIgnoresUnknownFields() {
        val raw = checkNotNull(javaClass.classLoader?.getResource("clipboard-update-v1.json")).readText()
        val message = ProtocolSerializer.deserialize(raw)
        val payload = ProtocolSerializer.decodePayload<ClipboardUpdatePayload>(message.payload)

        assertEquals(ProtocolMessageType.CLIPBOARD_UPDATE.value, message.type)
        assertEquals("android-vector", message.senderDeviceId)
        assertEquals("018f-vector-revision", payload.revisionId)
        assertEquals("android-vector", payload.originDeviceId)
        assertEquals("text/plain", payload.contentType)
        assertEquals("Hello, общий буфер!", payload.text)
        assertFalse(payload.isManual)
    }

    @Test
    fun notificationVectorUsesStableCrossPlatformFieldsAndIgnoresUnknownFields() {
        val raw = checkNotNull(javaClass.classLoader?.getResource("notification-posted-v1.json")).readText()
        val message = ProtocolSerializer.deserialize(raw)
        val payload = ProtocolSerializer.decodePayload<NotificationPostedPayload>(message.payload)

        assertEquals(ProtocolMessageType.NOTIFICATION_POSTED.value, message.type)
        assertEquals("mOrzZP0Tei8zz-vector", payload.notificationId)
        assertEquals("org.example.chat", payload.packageName)
        assertEquals("msg", payload.category)
        assertEquals(1L, payload.revision)
        assertEquals(1, payload.actions.size)
        assertEquals(true, payload.actions.single().requiresConfirmation)
        assertFalse(payload.actions.single().isDestructive)
    }
}
