package com.example.devicesync

import android.os.Bundle
import android.content.Intent
import android.content.ClipboardManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.devicesync.core.settings.AppSettings

class MainActivity : ComponentActivity() {
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val container = (application as DeviceSyncApplication).container
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isNotEmpty()) lifecycleScope.launch { runCatching { container.sharingManager.onLocalClipboardChanged(text) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        setContent {
            val container = (application as DeviceSyncApplication).container
            val settings = container.settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings(),
            )
            DeviceSyncApp(themeMode = settings.value.themeMode)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onStop() {
        getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipboardListener)
        super.onStop()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        (application as DeviceSyncApplication).container.sharingManager.submitAndroidShare(text)
    }
}
