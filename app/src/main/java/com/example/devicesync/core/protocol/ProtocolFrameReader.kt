package com.example.devicesync.core.protocol

import com.example.devicesync.core.network.ConnectionException
import com.example.devicesync.core.network.MAX_JSON_MESSAGE_SIZE
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

class ProtocolFrameReader(
    private val inputStream: InputStream,
) {
    fun read(): ProtocolMessage {
        val header = inputStream.readExactly(Int.SIZE_BYTES)
        val payloadSize = ByteBuffer.wrap(header).int
        if (payloadSize <= 0 || payloadSize > MAX_JSON_MESSAGE_SIZE) {
            throw ConnectionException.InvalidFrame()
        }

        val payload = inputStream.readExactly(payloadSize)
        return ProtocolSerializer.deserialize(payload.toString(Charsets.UTF_8))
    }
}

fun InputStream.readExactly(byteCount: Int): ByteArray {
    val buffer = ByteArray(byteCount)
    var offset = 0
    while (offset < byteCount) {
        // A single read() may return only part of the frame; keep reading until it is complete.
        val read = read(buffer, offset, byteCount - offset)
        if (read == -1) {
            throw ConnectionException.ConnectionClosed(EOFException())
        }
        offset += read
    }
    return buffer
}
