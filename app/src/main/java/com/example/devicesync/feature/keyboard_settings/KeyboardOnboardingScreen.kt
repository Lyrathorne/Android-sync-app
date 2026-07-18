package com.example.devicesync.feature.keyboard_settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.devicesync.keyboard.ime.DeviceSyncInputMethodService
import com.example.devicesync.keyboard.ime.KeyboardPreferences
import com.example.devicesync.keyboard.ime.KeyboardUserDictionary
import com.example.devicesync.keyboard.engine.KeyboardHapticIntensity
import com.example.devicesync.keyboard.engine.KeyboardHapticMode
import com.example.devicesync.keyboard.engine.suggestions.GrammaticalGender
import com.example.devicesync.keyboard.ime.KeyboardHaptics
import com.example.devicesync.keyboard.ime.KeyboardThemeMode
import com.example.devicesync.keyboard.ime.KeyboardColorScheme
import com.example.devicesync.keyboard.ime.KeyboardCorrectionLevel
import com.example.devicesync.keyboard.ime.KeyboardHapticOutcome

@Composable
fun KeyboardOnboardingScreen(
    onBackClick: () -> Unit,
    onOpenSourceLicensesClick: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settingsView = LocalView.current
    val preferences = remember { KeyboardPreferences(context) }
    val userDictionary = remember { KeyboardUserDictionary(context) }
    var enabled by remember { mutableStateOf(isKeyboardEnabled(context)) }
    var suggestions by remember { mutableStateOf(preferences.showSuggestions) }
    var autocorrection by remember { mutableStateOf(preferences.autoCorrection) }
    var learnWords by remember { mutableStateOf(preferences.learnWords) }
    var grammaticalGender by remember { mutableStateOf(preferences.grammaticalGender) }
    var clipboardHistory by remember { mutableStateOf(preferences.clipboardHistory) }
    var hapticMode by remember { mutableStateOf(preferences.hapticMode) }
    var hapticIntensity by remember { mutableStateOf(preferences.hapticIntensity) }
    val hapticTester = remember { KeyboardHaptics(context, preferences) }
    var hapticCapabilities by remember { mutableStateOf(hapticTester.diagnostics()) }
    var numberRow by remember { mutableStateOf(preferences.numberRow) }
    var compactMode by remember { mutableStateOf(preferences.compactMode) }
    var keySound by remember { mutableStateOf(preferences.keySound) }
    var incognito by remember { mutableStateOf(preferences.incognitoMode) }
    var keyHeight by remember { mutableStateOf(preferences.keyHeightDp.toFloat()) }
    var labelScale by remember { mutableStateOf(preferences.keyLabelScalePercent.toFloat()) }
    var themeMode by remember { mutableStateOf(preferences.themeMode) }
    var colorScheme by remember { mutableStateOf(preferences.colorScheme) }
    var correctionLevel by remember { mutableStateOf(preferences.correctionLevel) }
    var autoCapitalization by remember { mutableStateOf(preferences.autoCapitalization) }
    var doubleSpacePeriod by remember { mutableStateOf(preferences.doubleSpacePeriod) }
    var hapticTestStatus by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = isKeyboardEnabled(context)
                hapticCapabilities = hapticTester.diagnostics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        enabled = isKeyboardEnabled(context)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            hapticTester.close()
        }
    }

    Scaffold { insets ->
        Column(
            modifier = Modifier
                .padding(insets)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(onClick = onBackClick) { Text("Назад") }
            Text("DeviceSync Keyboard", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (enabled) "Клавиатура включена в Android." else "Клавиатура пока не включена.",
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("1. Включить клавиатуру в Android") }
            Button(
                onClick = {
                    context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            ) { Text("2. Выбрать DeviceSync Keyboard") }
            OutlinedButton(
                onClick = { enabled = isKeyboardEnabled(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Проверить состояние") }
            Text("Настройки клавиатуры", style = MaterialTheme.typography.titleMedium)
            Text("Изменения применяются к клавиатуре сразу.", style = MaterialTheme.typography.bodySmall)
            Text(
                "При наборе текста кнопки инструментов заменяются подсказками. После завершения слова инструменты появляются снова.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Ввод", style = MaterialTheme.typography.titleMedium)
            KeyboardSettingSwitch("Автоматически начинать с заглавной", autoCapitalization) {
                autoCapitalization = it
                preferences.autoCapitalization = it
            }
            KeyboardSettingSwitch("Двойной пробел ставит точку", doubleSpacePeriod) {
                doubleSpacePeriod = it
                preferences.doubleSpacePeriod = it
            }
            KeyboardSettingSwitch("Подсказки", suggestions) { suggestions = it; preferences.showSuggestions = it }
            KeyboardSettingSwitch("Автокоррекция", autocorrection) { autocorrection = it; preferences.autoCorrection = it }
            if (autocorrection) {
                Text("Сила автокоррекции", style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(
                    values = KeyboardCorrectionLevel.entries,
                    selected = correctionLevel,
                    label = {
                        when (it) {
                            KeyboardCorrectionLevel.CONSERVATIVE -> "Мягкая"
                            KeyboardCorrectionLevel.BALANCED -> "Обычная"
                            KeyboardCorrectionLevel.AGGRESSIVE -> "Сильная"
                        }
                    },
                    onSelected = { correctionLevel = it; preferences.correctionLevel = it },
                )
            }
            KeyboardSettingSwitch("Запоминать мои слова", learnWords) { learnWords = it; preferences.learnWords = it }
            Text("Форма слов от первого лица", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Необязательно. Настройка хранится только на телефоне и лишь слегка меняет порядок подходящих форм.",
                style = MaterialTheme.typography.bodySmall,
            )
            ChoiceRow(
                values = GrammaticalGender.entries,
                selected = grammaticalGender,
                label = {
                    when (it) {
                        GrammaticalGender.NONE -> "Не задано"
                        GrammaticalGender.MASCULINE -> "Мужская"
                        GrammaticalGender.FEMININE -> "Женская"
                    }
                },
                onSelected = { grammaticalGender = it; preferences.grammaticalGender = it },
            )
            KeyboardSettingSwitch("История clipboard", clipboardHistory) { clipboardHistory = it; preferences.clipboardHistory = it }
            Text("Вибрация и звук", style = MaterialTheme.typography.titleMedium)
            Text("Режим вибрации", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KeyboardHapticMode.entries.forEach { mode ->
                    OutlinedButton(
                        onClick = { hapticMode = mode; preferences.hapticMode = mode },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(when (mode) {
                            KeyboardHapticMode.OFF -> "Выкл."
                            KeyboardHapticMode.SYSTEM -> "Системная"
                            KeyboardHapticMode.CUSTOM -> "Своя"
                        })
                    }
                }
            }
            Text(
                if (hapticMode == KeyboardHapticMode.SYSTEM) {
                    "Системный режим зависит от настройки вибрации касаний Android и прошивки устройства."
                } else {
                    "Режим «Своя» использует короткий импульс вибромотора, выбранный явно в DeviceSync."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Вибромотор доступен: ${if (hapticCapabilities.hasVibrator) "да" else "нет"}\n" +
                    "Управление амплитудой: ${if (hapticCapabilities.hasAmplitudeControl) "да" else "нет"}\n" +
                    "Android API: ${hapticCapabilities.androidApi}\n" +
                    "Системная вибрация касаний: ${if (hapticCapabilities.systemTouchFeedbackEnabled) "включена" else "выключена"}\n" +
                    "Текущий режим: ${when (hapticMode) {
                        KeyboardHapticMode.OFF -> "выключен"
                        KeyboardHapticMode.SYSTEM -> "системный"
                        KeyboardHapticMode.CUSTOM -> "свой"
                    }}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (hapticMode != KeyboardHapticMode.OFF) {
                Text("Сила вибрации", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KeyboardHapticIntensity.entries.forEach { intensity ->
                        OutlinedButton(
                            onClick = { hapticIntensity = intensity; preferences.hapticIntensity = intensity },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(when (intensity) {
                                KeyboardHapticIntensity.LIGHT -> "Слабая"
                                KeyboardHapticIntensity.MEDIUM -> "Средняя"
                                KeyboardHapticIntensity.STRONG -> "Сильная"
                            })
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val result = hapticTester.performTest(settingsView)
                        hapticCapabilities = hapticTester.diagnostics()
                        hapticTestStatus = when (result.outcome) {
                            KeyboardHapticOutcome.PERFORMED -> "Команда вибрации отправлена"
                            KeyboardHapticOutcome.DISABLED -> "Вибрация отключена в настройках клавиатуры"
                            KeyboardHapticOutcome.NO_VIBRATOR -> "Вибромотор не найден"
                            KeyboardHapticOutcome.SYSTEM_FEEDBACK_DISABLED -> "Системная вибрация касаний отключена"
                            KeyboardHapticOutcome.VIEW_REJECTED -> "Android отклонил системную вибрацию для этого элемента"
                            KeyboardHapticOutcome.ERROR -> "Ошибка вибрации: ${result.errorClass ?: "неизвестная ошибка"}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Проверить вибрацию") }
                hapticTestStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text("Внешний вид", style = MaterialTheme.typography.titleMedium)
            KeyboardSettingSwitch("Компактная клавиатура", compactMode) { compactMode = it; preferences.compactMode = it }
            Text("Высота клавиш: ${keyHeight.toInt()} dp", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = keyHeight,
                onValueChange = { keyHeight = it },
                onValueChangeFinished = { preferences.keyHeightDp = keyHeight.toInt() },
                valueRange = 46f..54f,
                steps = 9,
                enabled = !compactMode,
            )
            Text(
                if (compactMode) "Отключите компактный режим для ручной высоты." else "Высота применяется к буквенным и цифровым раскладкам.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Размер подписей: ${labelScale.toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = labelScale,
                onValueChange = { labelScale = it },
                onValueChangeFinished = { preferences.keyLabelScalePercent = labelScale.toInt() },
                valueRange = 85f..120f,
                steps = 6,
            )
            Text("Тема клавиатуры", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(
                values = KeyboardThemeMode.entries,
                selected = themeMode,
                label = {
                    when (it) {
                        KeyboardThemeMode.SYSTEM -> "Система"
                        KeyboardThemeMode.LIGHT -> "Светлая"
                        KeyboardThemeMode.DARK -> "Тёмная"
                    }
                },
                onSelected = { themeMode = it; preferences.themeMode = it },
            )
            Text("Цветовая гамма", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Гамма применяется ко всем клавишам, панели инструментов и клавише Enter.",
                style = MaterialTheme.typography.bodySmall,
            )
            KeyboardColorScheme.entries.chunked(2).forEach { row ->
                ChoiceRow(
                    values = row,
                    selected = colorScheme,
                    label = {
                        when (it) {
                            KeyboardColorScheme.NEUTRAL -> "Нейтральная"
                            KeyboardColorScheme.OCEAN -> "Океан"
                            KeyboardColorScheme.VIOLET -> "Фиолетовая"
                            KeyboardColorScheme.SUNSET -> "Тёплая"
                        }
                    },
                    onSelected = { colorScheme = it; preferences.colorScheme = it },
                )
            }
            KeyboardSettingSwitch("Постоянный цифровой ряд", numberRow) { numberRow = it; preferences.numberRow = it }
            KeyboardSettingSwitch("Звук клавиш", keySound) { keySound = it; preferences.keySound = it }
            KeyboardSettingSwitch("Incognito mode", incognito) { incognito = it; preferences.incognitoMode = it }
            OutlinedButton(
                onClick = userDictionary::clear,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Очистить пользовательский словарь") }
            Text("Приватность", style = MaterialTheme.typography.titleMedium)
            Text(
                "• В Password/PIN отключены подсказки, история и обучение.\n" +
                    "• Клавиши и clipboard не записываются в логи.\n" +
                    "• Камера и микрофон клавиатурой не используются."
            )
            OutlinedButton(
                onClick = onOpenSourceLicensesClick,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open-source licenses") }
        }
    }
}

@Composable
private fun KeyboardSettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            OutlinedButton(
                onClick = { onSelected(value) },
                modifier = Modifier.weight(1f),
                enabled = value != selected,
            ) {
                Text(label(value), maxLines = 1)
            }
        }
    }
}

private fun isKeyboardEnabled(context: Context): Boolean {
    val expected = DeviceSyncInputMethodService::class.java.name
    return context.getSystemService(InputMethodManager::class.java)
        .enabledInputMethodList
        .any { it.serviceName == expected || it.component.className == expected }
}
