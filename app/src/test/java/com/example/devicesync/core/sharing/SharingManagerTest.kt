package com.example.devicesync.core.sharing

import com.example.devicesync.core.network.SharingMessageListener
import com.example.devicesync.core.network.SharingTransport
import com.example.devicesync.core.protocol.ClipboardUpdatePayload
import com.example.devicesync.core.protocol.ProtocolMessage
import com.example.devicesync.core.protocol.ProtocolMessageType
import com.example.devicesync.core.protocol.ProtocolSerializer
import com.example.devicesync.core.settings.DeviceIdentityRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharingManagerTest {
    @Test
    fun automaticClipboardIsDebouncedAndCoalesced() = runTest {
        val fixture = fixture(this)
        fixture.preferences.setClipboardAllowed("windows", true)
        fixture.manager.clipboardEnabled = true

        fixture.manager.onLocalClipboardChanged("first")
        advanceTimeBy(100)
        fixture.manager.onLocalClipboardChanged("latest")
        advanceUntilIdle()

        assertEquals(1, fixture.transport.sent.size)
        val payload = ProtocolSerializer.decodePayload<ClipboardUpdatePayload>(fixture.transport.sent.single().second)
        assertEquals("latest", payload.text)
        assertEquals("android", payload.originDeviceId)
        assertEquals("text/plain", payload.contentType)
    }

    @Test
    fun duplicateRevisionAndEchoAreAppliedOnlyOnce() = runTest {
        val fixture = fixture(this)
        fixture.preferences.setClipboardAllowed("windows", true)
        fixture.manager.clipboardEnabled = true
        val message = clipboardMessage("revision-1", "same")

        fixture.manager.onSharingMessage(message)
        fixture.manager.onSharingMessage(message)
        fixture.manager.onLocalClipboardChanged("same")
        advanceUntilIdle()

        assertEquals(listOf("same"), fixture.applied)
        assertTrue(fixture.transport.sent.isEmpty())
    }

    @Test
    fun manualClipboardBypassesAutomaticOptInButStillValidatesOrigin() = runTest {
        val fixture = fixture(this)
        fixture.manager.onSharingMessage(clipboardMessage("manual", "hello", isManual = true))
        assertEquals(listOf("hello"), fixture.applied)

        assertSuspendFails<IllegalArgumentException> {
            fixture.manager.onSharingMessage(clipboardMessage("spoof", "bad", origin = "attacker"))
        }
    }

    @Test
    fun emptyOversizedAndPrivateClipboardAreNeverSent() = runTest {
        val fixture = fixture(this)
        fixture.preferences.setClipboardAllowed("windows", true)
        fixture.manager.clipboardEnabled = true

        fixture.manager.onLocalClipboardChanged("secret", privateContext = true)
        fixture.manager.onLocalClipboardChanged("   ")
        advanceUntilIdle()
        assertTrue(fixture.transport.sent.isEmpty())
        assertSuspendFails<IllegalArgumentException> {
            fixture.manager.sendClipboardNow("x".repeat(SharingManager.MAXIMUM_TEXT_BYTES + 1))
        }
    }

    @Test
    fun mostRecentClipboardIsFlushedAfterShortDisconnect() = runTest {
        var now = 1_000L
        val fixture = fixture(this, nowMillis = { now })
        fixture.preferences.setClipboardAllowed("windows", true)
        fixture.manager.clipboardEnabled = true
        fixture.transport.failSends = true

        fixture.manager.onLocalClipboardChanged("queued")
        advanceUntilIdle()
        fixture.transport.failSends = false
        now += 1_000
        fixture.manager.flushPendingClipboard()

        assertEquals(1, fixture.transport.sent.size)
        val payload = ProtocolSerializer.decodePayload<ClipboardUpdatePayload>(fixture.transport.sent.single().second)
        assertEquals("queued", payload.text)
    }

    private fun fixture(scope: kotlinx.coroutines.CoroutineScope, nowMillis: () -> Long = { 1_000L }): Fixture {
        val transport = FakeTransport()
        val preferences = FakePreferences()
        val applied = mutableListOf<String>()
        val manager = SharingManager(
            transport = transport,
            identityRepository = FakeIdentity(),
            preferences = preferences,
            scope = scope,
            currentRemoteDeviceId = { "windows" },
            applyClipboard = applied::add,
            nowMillis = nowMillis,
        )
        return Fixture(manager, transport, preferences, applied)
    }

    private fun clipboardMessage(
        revision: String,
        text: String,
        isManual: Boolean = false,
        origin: String = "windows",
    ) = ProtocolMessage(
        protocolVersion = 1,
        messageId = "message-$revision",
        type = ProtocolMessageType.CLIPBOARD_UPDATE.value,
        senderDeviceId = "windows",
        timestampUtc = "2026-07-15T00:00:00Z",
        payload = ProtocolSerializer.payloadToJson(
            ClipboardUpdatePayload(revision, origin, "text/plain", text, "2026-07-15T00:00:00Z", isManual)
        ),
    )

    private data class Fixture(
        val manager: SharingManager,
        val transport: FakeTransport,
        val preferences: FakePreferences,
        val applied: MutableList<String>,
    )

    private class FakeTransport : SharingTransport {
        val sent = mutableListOf<Pair<String, JsonElement>>()
        var failSends = false
        private val listeners = mutableSetOf<SharingMessageListener>()
        override suspend fun sendSharingMessage(type: String, payload: JsonElement) {
            if (failSends) error("disconnected")
            sent += type to payload
        }
        override fun addSharingListener(listener: SharingMessageListener) { listeners += listener }
        override fun removeSharingListener(listener: SharingMessageListener) { listeners -= listener }
    }

    private class FakePreferences : ClipboardSyncPreferences {
        override var clipboardEnabled = false
        private val allowed = mutableSetOf<String>()
        override fun isClipboardAllowed(deviceId: String?) = deviceId != null && deviceId in allowed
        override fun setClipboardAllowed(deviceId: String, allowed: Boolean) {
            if (allowed) this.allowed += deviceId else this.allowed -= deviceId
        }
    }

    private class FakeIdentity : DeviceIdentityRepository {
        override suspend fun getOrCreateDeviceId() = "android"
        override suspend fun getDeviceName() = "Phone"
        override suspend fun updateDeviceName(name: String) = Unit
    }

    private suspend inline fun <reified T : Throwable> assertSuspendFails(noinline block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, got $error", error is T)
    }
}
