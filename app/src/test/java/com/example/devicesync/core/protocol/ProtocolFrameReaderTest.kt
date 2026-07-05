package com.example.devicesync.core.protocol

import com.example.devicesync.core.network.ConnectionException
import com.example.devicesync.core.network.MAX_JSON_MESSAGE_SIZE
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

class ProtocolFrameReaderTest {
    @Test
    fun read_readsCompleteMessage() {
        val bytes = frameBytes(helloAckMessage(correlationId = "hello-id"))

        val message = ProtocolFrameReader(ByteArrayInputStream(bytes)).read()

        assertEquals(ProtocolMessageType.CONNECTION_HELLO_ACK.value, message.type)
        assertEquals("hello-id", message.correlationId)
    }

    @Test
    fun read_handlesPartialReads() {
        val bytes = frameBytes(helloAckMessage())

        val message = ProtocolFrameReader(ChunkedInputStream(bytes, chunkSize = 2)).read()

        assertEquals("windows-device-id", message.senderDeviceId)
    }

    @Test(expected = ConnectionException.InvalidFrame::class)
    fun read_rejectsNegativeLength() {
        val bytes = ByteBuffer.allocate(4).putInt(-1).array()

        ProtocolFrameReader(ByteArrayInputStream(bytes)).read()
    }

    @Test(expected = ConnectionException.InvalidFrame::class)
    fun read_rejectsLengthAboveLimit() {
        val bytes = ByteBuffer.allocate(4).putInt(MAX_JSON_MESSAGE_SIZE + 1).array()

        ProtocolFrameReader(ByteArrayInputStream(bytes)).read()
    }

    @Test(expected = ConnectionException.ConnectionClosed::class)
    fun read_detectsClosedStreamBeforeFullPayload() {
        val payload = "{}".toByteArray(Charsets.UTF_8)
        val bytes = ByteBuffer.allocate(4).putInt(payload.size + 10).array() + payload

        ProtocolFrameReader(ByteArrayInputStream(bytes)).read()
    }

    private fun frameBytes(message: ProtocolMessage): ByteArray {
        val output = ByteArrayOutputStream()
        ProtocolFrameWriter(output).write(message)
        return output.toByteArray()
    }
}

private class ChunkedInputStream(
    private val bytes: ByteArray,
    private val chunkSize: Int,
) : InputStream() {
    private var offset = 0

    override fun read(): Int {
        if (offset >= bytes.size) return -1
        return bytes[offset++].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (offset >= bytes.size) return -1
        val count = minOf(len, chunkSize, bytes.size - offset)
        bytes.copyInto(buffer, off, offset, offset + count)
        offset += count
        return count
    }
}
