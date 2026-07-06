package com.example.devicesync.core.protocol

enum class ProtocolMessageType(val value: String) {
    CONNECTION_HELLO("connection.hello"),
    CONNECTION_HELLO_ACK("connection.hello_ack"),
    CONNECTION_PING("connection.ping"),
    CONNECTION_PONG("connection.pong"),
    CONNECTION_CLOSE("connection.close"),
    MESSAGE_ACK("message.ack"),
    ERROR_PROTOCOL("error.protocol"),
    PAIRING_REQUEST("pairing.request"),
    PAIRING_CHALLENGE("pairing.challenge"),
    PAIRING_CONFIRM("pairing.confirm"),
    PAIRING_ACCEPTED("pairing.accepted"),
    PAIRING_REJECTED("pairing.rejected"),
    PAIRING_CANCEL("pairing.cancel"),
    PAIRING_COMPLETE_ACK("pairing.complete_ack"),
    AUTH_CHALLENGE("auth.challenge"),
    AUTH_RESPONSE("auth.response"),
    AUTH_ACCEPTED("auth.accepted"),
    AUTH_REJECTED("auth.rejected"),
}
