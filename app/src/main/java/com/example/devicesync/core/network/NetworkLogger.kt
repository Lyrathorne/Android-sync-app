package com.example.devicesync.core.network

import android.util.Log

object NetworkLogger {
    private const val Tag = "DeviceSyncNetwork"

    fun info(message: String) {
        runCatching { Log.i(Tag, message) }
    }

    fun error(message: String, throwable: Throwable? = null) {
        runCatching { Log.e(Tag, message, throwable) }
    }
}
