package com.example.devicesync.core.protocol

import com.example.devicesync.core.network.ConnectionException
import com.example.devicesync.core.network.MAX_JSON_MESSAGE_SIZE
import java.io.OutputStream
import java.nio.ByteBuffer

class ProtocolFrameWriter(
    private val outputStream: OutputStream,
) {
    fun write(message: ProtocolMessage) {
        val payload = ProtocolSerializer.serialize(message).toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_JSON_MESSAGE_SIZE) {
            throw ConnectionException.InvalidFrame("Сообщение превышает максимальный размер")
        }

        // TCP is a byte stream, so every JSON message is framed with its byte length.
        val header = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(payload.size).array()
        outputStream.write(header)
        outputStream.write(payload)
        outputStream.flush()
    }
}
