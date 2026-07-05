package com.example.devicesync.core.database

import androidx.room.TypeConverter
import java.time.Instant

class DeviceSyncTypeConverters {
    @TypeConverter
    fun instantToString(value: Instant?): String? = value?.toString()

    @TypeConverter
    fun stringToInstant(value: String?): Instant? = value?.let(Instant::parse)

    @TypeConverter
    fun stringListToString(value: List<String>): String = value.joinToString(separator = "\n")

    @TypeConverter
    fun stringToStringList(value: String): List<String> {
        return value.split("\n").filter { it.isNotBlank() }
    }
}
