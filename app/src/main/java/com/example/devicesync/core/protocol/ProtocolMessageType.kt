package com.example.devicesync.core.protocol

enum class ProtocolMessageType(val value: String) {
    CONNECTION_HELLO("connection.hello"),
    CONNECTION_HELLO_ACK("connection.hello_ack"),
    CONNECTION_PING("connection.ping"),
    CONNECTION_PONG("connection.pong"),
    CONNECTION_CLOSE("connection.close"),
    MESSAGE_ACK("message.ack"),
    ERROR_PROTOCOL("error.protocol"),
}
