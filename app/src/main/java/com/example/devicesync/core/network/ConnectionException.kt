package com.example.devicesync.core.network

sealed class ConnectionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class InvalidAddress(cause: Throwable? = null) :
        ConnectionException("Некорректный адрес", cause)

    class Timeout(cause: Throwable? = null) :
        ConnectionException("Превышено время ожидания", cause)

    class ConnectionRefused(cause: Throwable? = null) :
        ConnectionException("Соединение отклонено", cause)

    class ConnectionClosed(cause: Throwable? = null) :
        ConnectionException("Компьютер закрыл соединение", cause)

    class InvalidFrame(message: String = "Некорректный сетевой пакет", cause: Throwable? = null) :
        ConnectionException(message, cause)

    class InvalidMessage(message: String = "Получен некорректный ответ", cause: Throwable? = null) :
        ConnectionException(message, cause)

    class UnsupportedProtocol :
        ConnectionException("Версия протокола не поддерживается")

    class Unknown(cause: Throwable? = null) :
        ConnectionException("Не удалось подключиться к компьютеру", cause)
}
