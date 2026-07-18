package com.example.devicesync

import android.os.Bundle
import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipDescription
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.devicesync.core.settings.AppSettings

class MainActivity : ComponentActivity() {
    private val keyboardSettingsRequests = MutableStateFlow(0L)
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        forwardCurrentClipboard()
    }

    private fun forwardCurrentClipboard() {
        val container = (application as DeviceSyncApplication).container
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard.primaryClipDescription?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true) return
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isNotEmpty()) lifecycleScope.launch { runCatching { container.sharingManager.onLocalClipboardChanged(text) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        (application as DeviceSyncApplication).container.connectionManager.startStartupAutoConnect()
        handleShareIntent(intent)
        handleNavigationIntent(intent)
        setContent {
            val container = (application as DeviceSyncApplication).container
            val settings = container.settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings(),
            )
            DeviceSyncApp(
                themeMode = settings.value.themeMode,
                keyboardSettingsRequests = keyboardSettingsRequests,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleNavigationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipboardListener)
        forwardCurrentClipboard()
    }

    override fun onStop() {
        getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipboardListener)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) forwardCurrentClipboard()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        (application as DeviceSyncApplication).container.sharingManager.submitAndroidShare(text)
    }

    private fun handleNavigationIntent(intent: Intent?) {
        if (intent?.data?.scheme == "devicesync" && intent.data?.host == "keyboard-settings") {
            keyboardSettingsRequests.value += 1
        }
    }
}
