package com.example.devicesync.feature.notifications

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.devicesync.core.notifications.DeviceSyncNotificationListenerService
import com.example.devicesync.core.notifications.NotificationForwardingPreferences

@Composable
fun NotificationSettingsScreen(
    preferences: NotificationForwardingPreferences,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val component = ComponentName(context, DeviceSyncNotificationListenerService::class.java)
    val accessGranted = remember(refresh) {
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')
            ?.any { ComponentName.unflattenFromString(it) == component } == true
    }
    val enabled = preferences.enabled
    val includePrivate = preferences.includePrivateNotifications
    val allowedPackages = preferences.allowedPackages
    val knownApps = preferences.knownApps

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Уведомления Android",
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                "Выберите приложения, уведомления которых можно показывать на Windows.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (accessGranted) "Системный доступ предоставлен" else "Нужен системный доступ",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (accessGranted) {
                            "DeviceSync получает только выбранные ниже уведомления."
                        } else {
                            "Без разрешения Android не передаёт события Notification Listener."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    ) {
                        Text(if (accessGranted) "Открыть системные настройки" else "Предоставить доступ")
                    }
                }
            }
        }
        item {
            SettingSwitch(
                title = "Пересылать уведомления",
                detail = if (allowedPackages.isEmpty()) {
                    "Сначала разрешите хотя бы одно приложение."
                } else {
                    "Разрешено приложений: ${allowedPackages.size}"
                },
                checked = enabled,
                enabled = accessGranted,
                onCheckedChange = {
                    preferences.enabled = it
                    refresh++
                },
            )
        }
        item {
            SettingSwitch(
                title = "Разрешить приватные уведомления",
                detail = "Выключено по умолчанию. Секретные уведомления не пересылаются никогда.",
                checked = includePrivate,
                enabled = accessGranted,
                onCheckedChange = {
                    preferences.includePrivateNotifications = it
                    refresh++
                },
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text("Приложения", style = MaterialTheme.typography.titleLarge)
            Text(
                "Список пополняется, когда приложения публикуют уведомления. По умолчанию всё запрещено.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (knownApps.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Пока нет обнаруженных приложений. Предоставьте доступ и дождитесь нового уведомления.",
                        Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(knownApps, key = { it.packageName }) { app ->
                SettingSwitch(
                    title = app.displayName,
                    detail = app.packageName,
                    checked = app.packageName in allowedPackages,
                    enabled = accessGranted,
                    onCheckedChange = {
                        preferences.setPackageAllowed(app.packageName, it)
                        refresh++
                    },
                )
            }
        }
        item {
            Button(
                onClick = {
                    preferences.enabled = accessGranted && preferences.allowedPackages.isNotEmpty()
                    refresh++
                },
                enabled = accessGranted && allowedPackages.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Сохранить и включить")
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}
