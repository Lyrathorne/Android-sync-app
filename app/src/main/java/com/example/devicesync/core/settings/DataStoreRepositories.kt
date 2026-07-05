package com.example.devicesync.core.settings

import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

class DataStoreDeviceIdentityRepository(
    private val dataStore: DataStore<Preferences>,
) : DeviceIdentityRepository {
    override suspend fun getOrCreateDeviceId(): String {
        val savedId = dataStore.data.first()[Keys.DeviceId]
        if (savedId.isValidDeviceId()) {
            return savedId.orEmpty()
        }

        val newId = "android-${UUID.randomUUID()}"
        dataStore.edit { preferences ->
            preferences[Keys.DeviceId] = newId
        }
        return newId
    }

    override suspend fun getDeviceName(): String {
        val savedName = dataStore.data.first()[Keys.DeviceName]
        if (!savedName.isNullOrBlank()) {
            return savedName
        }
        return Build.MODEL.orEmpty().ifBlank { "Android device" }
    }

    override suspend fun updateDeviceName(name: String) {
        dataStore.edit { preferences ->
            preferences[Keys.DeviceName] = name.trim()
        }
    }
}

class DataStoreAppSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : AppSettingsRepository {
    override val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            autoConnectEnabled = preferences[Keys.AutoConnectEnabled] ?: true,
            restoreConnectionEnabled = preferences[Keys.RestoreConnectionEnabled] ?: true,
            lastSelectedDeviceId = preferences[Keys.LastSelectedDeviceId],
        )
    }

    override suspend fun setAutoConnectEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.AutoConnectEnabled] = enabled }
    }

    override suspend fun setRestoreConnectionEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.RestoreConnectionEnabled] = enabled }
    }

    override suspend fun setLastSelectedDeviceId(deviceId: String?) {
        dataStore.edit { preferences ->
            if (deviceId == null) {
                preferences.remove(Keys.LastSelectedDeviceId)
            } else {
                preferences[Keys.LastSelectedDeviceId] = deviceId
            }
        }
    }
}

private object Keys {
    val DeviceId = stringPreferencesKey("device_id")
    val DeviceName = stringPreferencesKey("device_name")
    val LastSelectedDeviceId = stringPreferencesKey("last_selected_device_id")
    val AutoConnectEnabled = booleanPreferencesKey("auto_connect_enabled")
    val RestoreConnectionEnabled = booleanPreferencesKey("restore_connection_enabled")
}

private fun String?.isValidDeviceId(): Boolean {
    return this?.startsWith("android-") == true && length > "android-".length
}
