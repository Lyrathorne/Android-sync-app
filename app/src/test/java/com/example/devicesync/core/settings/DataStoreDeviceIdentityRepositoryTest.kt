package com.example.devicesync.core.settings

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreDeviceIdentityRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun getOrCreateDeviceId_createsAndroidPrefixedId() = runTest {
        val repository = DataStoreDeviceIdentityRepository(testDataStore("identity-create"))

        val deviceId = repository.getOrCreateDeviceId()

        assertTrue(deviceId.startsWith("android-"))
    }

    @Test
    fun getOrCreateDeviceId_returnsSameIdOnRepeatedCall() = runTest {
        val repository = DataStoreDeviceIdentityRepository(testDataStore("identity-repeat"))

        val first = repository.getOrCreateDeviceId()
        val second = repository.getOrCreateDeviceId()

        assertEquals(first, second)
    }

    @Test
    fun getOrCreateDeviceId_survivesRepositoryRecreation() = runTest {
        val dataStore = testDataStore("identity-recreate")
        val firstRepository = DataStoreDeviceIdentityRepository(dataStore)
        val first = firstRepository.getOrCreateDeviceId()
        val secondRepository = DataStoreDeviceIdentityRepository(dataStore)

        val second = secondRepository.getOrCreateDeviceId()

        assertEquals(first, second)
    }

    @Test
    fun getOrCreateDeviceId_replacesBrokenValue() = runTest {
        val dataStore = testDataStore("identity-broken")
        dataStore.edit { it[stringPreferencesKey("device_id")] = "" }
        val repository = DataStoreDeviceIdentityRepository(dataStore)

        val fixed = repository.getOrCreateDeviceId()

        assertTrue(fixed.startsWith("android-"))
        assertNotEquals("", fixed)
    }

    private fun testDataStore(name: String): androidx.datastore.core.DataStore<Preferences> {
        return PreferenceDataStoreFactory.create {
            temporaryFolder.newFile("$name.preferences_pb")
        }
    }
}
