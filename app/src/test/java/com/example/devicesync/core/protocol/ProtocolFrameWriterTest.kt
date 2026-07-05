package com.example.devicesync.core.protocol

import com.example.devicesync.core.network.ConnectionException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ProtocolFrameWriterTest {
    @Test
    fun write_writesPayloadLengthAsBigEndianInt() {
        val output = ByteArrayOutputStream()
        val message = helloMessage()

        ProtocolFrameWriter(output).write(message)

        val bytes = output.toByteArray()
        val size = ByteBuffer.wrap(bytes.take(4).toByteArray()).int
        assertEquals(bytes.size - 4, size)
    }

    @Test
    fun write_writesUtf8Payload() {
        val output = ByteArrayOutputStream()
        val message = helloAckMessage(deviceName = "Рабочий-ПК")

        ProtocolFrameWriter(output).write(message)

        val bytes = output.toByteArray()
        val payload = bytes.drop(4).toByteArray().toString(Charsets.UTF_8)
        assertTrue(payload.contains("Рабочий-ПК"))
    }

    @Test(expected = ConnectionException.InvalidFrame::class)
    fun write_rejectsMessageLargerThanLimit() {
        val output = ByteArrayOutputStream()
        val message = helloMessage().copy(
            payload = JsonObject(mapOf("value" to JsonPrimitive("x".repeat(1_048_577))))
        )

        ProtocolFrameWriter(output).write(message)
    }
}
