package com.example.devicesync.keyboard.ime

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.TextUtils
import android.util.TypedValue
import android.util.Log
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.PopupWindow
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.DateFormat
import java.util.Date
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.example.devicesync.keyboard.engine.*
import com.example.devicesync.keyboard.engine.suggestions.BootstrapSuggestionEngine
import com.example.devicesync.keyboard.engine.suggestions.KeyboardLanguageDetector
import com.example.devicesync.keyboard.engine.suggestions.SuggestionContext

class DeviceSyncInputMethodService : InputMethodService() {
    private val controller = KeyboardController()
    private val layouts = KeyboardLayoutRepository()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var rootContentHeightPx = 0
    private var rootSystemBottomInsetPx = 0
    private lateinit var smartStrip: LinearLayout
    private var smartStripState: SmartStripState? = null
    private lateinit var clipboard: ClipboardManager
    private lateinit var preferences: KeyboardPreferences
    private lateinit var haptics: KeyboardHaptics
    private var fieldContext = InputFieldContext(InputFieldKind.TEXT)
    private var backspaceRepeater: Runnable? = null
    private var integration: KeyboardIntegration? = null
    private lateinit var suggestionEngine: BootstrapSuggestionEngine
    private lateinit var userDictionary: KeyboardUserDictionary
    private lateinit var emojiRepository: KeyboardEmojiRepository
    private val suggestionButtons = mutableListOf<TextView>()
    private var lastCorrection: Pair<String, String>? = null
    private val dictionaryExecutor = Executors.newSingleThreadExecutor()
    private val suggestionExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val suggestionGeneration = SuggestionGeneration()
    private var pendingSuggestionRequest: Runnable? = null
    private var latestCorrectionWord = ""
    private var latestCorrection: String? = null
    private var typedWordBuffer = ""
    private val letterKeyButtons = mutableListOf<Pair<KeyboardKey.Text, TextView>>()
    private var keySoundEnabled = false
    private lateinit var preferenceObserver: SharedPreferences.OnSharedPreferenceChangeListener
    private var keyTimingCount = 0L
    private var keyTimingTotalNanos = 0L
    private var keyTimingMaxNanos = 0L
    private var keyDownToCommitCount = 0L
    private var keyDownToCommitTotalNanos = 0L
    private var keyDownToCommitMaxNanos = 0L
    private var keyDownToDrawCount = 0L
    private var keyDownToDrawTotalNanos = 0L
    private var suggestionTimingCount = 0L
    private var suggestionTimingTotalNanos = 0L
    private var suggestionTimingMaxNanos = 0L
    private var droppedSuggestionResults = 0L
    private var rebuildCount = 0L
    private val keyDownStartedNanos = WeakHashMap<View, Long>()
    private val keyDownToDrawStartedNanos = WeakHashMap<View, Long>()
    private val panelController = KeyboardPanelController()
    private var emojiCategory = EmojiCategory.SMILEYS
    private var emojiSearchQuery = ""
    private var confirmClearClipboard = false

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val privateContext = fieldContext.isSensitive || preferences.incognitoMode || isClipboardMarkedSensitive()
        if (privateContext) return@OnPrimaryClipChangedListener
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            ?.takeIf { it.isNotBlank() } ?: return@OnPrimaryClipChangedListener
        integration?.onLocalClipboardChanged(text, saveToHistory = preferences.clipboardHistory, privateContext = false)
    }

    @SuppressLint("InlinedApi")
    private fun isClipboardMarkedSensitive(): Boolean =
        clipboard.primaryClipDescription?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true

    override fun onCreate() {
        super.onCreate()
        clipboard = getSystemService(ClipboardManager::class.java)
        preferences = KeyboardPreferences(this)
        keySoundEnabled = preferences.keySound
        preferenceObserver = preferences.observe { keySoundEnabled = preferences.keySound }
        haptics = KeyboardHaptics(this, preferences)
        userDictionary = KeyboardUserDictionary(this)
        emojiRepository = KeyboardEmojiRepository(this)
        suggestionEngine = BootstrapSuggestionEngine(userDictionary::words)
        loadFrequencyDictionaries()
        integration = (applicationContext as? KeyboardHost)?.keyboardIntegration
        clipboard.addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipboardListener)
        stopBackspaceRepeat()
        pendingSuggestionRequest?.let(handler::removeCallbacks)
        suggestionGeneration.invalidate()
        dictionaryExecutor.shutdownNow()
        suggestionExecutor.shutdownNow()
        preferences.removeObserver(preferenceObserver)
        haptics.close()
        integration = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        window?.window?.let { imeWindow ->
            imeWindow.navigationBarColor = keyboardBackgroundColor()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                imeWindow.isNavigationBarContrastEnforced = false
            }
        }
        val baseBottomPadding = dp(2)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(keyboardBackgroundColor())
            setPadding(dp(1), dp(2), dp(1), baseBottomPadding)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val mandatoryGesture = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
            val requiredBottom = KeyboardSystemInsetPolicy.bottomPadding(
                baseBottomPadding,
                navigation,
                mandatoryGesture,
            )
            rootSystemBottomInsetPx = (requiredBottom - baseBottomPadding).coerceAtLeast(0)
            if (view.paddingBottom != requiredBottom) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, requiredBottom)
            }
            updateRootHeight()
            insets
        }
        ViewCompat.requestApplyInsets(root)
        rebuildKeyboard()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        fieldContext = EditorInfoPolicy.context(attribute).copy(isIncognito = preferences.incognitoMode)
        typedWordBuffer = ""
        panelController.close()
        confirmClearClipboard = false
        controller.setMode(when (fieldContext.kind) {
            InputFieldKind.NUMBER, InputFieldKind.DECIMAL, InputFieldKind.PHONE -> KeyboardMode.NUMERIC
            else -> KeyboardMode.LETTERS
        })
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        fieldContext = EditorInfoPolicy.context(info).copy(isIncognito = preferences.incognitoMode)
        panelController.enforceFieldPolicy(fieldContext.isSensitive)
        val beforeCursor = if (fieldContext.isSensitive) "" else {
            currentInputConnection?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        }
        typedWordBuffer = beforeCursor.takeLastWhile { it.isLetter() || it == '\'' || it == '-' }
        updateAutomaticShift(info)
        if (::root.isInitialized) rebuildKeyboard()
    }

    override fun onFinishInput() {
        stopBackspaceRepeat()
        panelController.close()
        fieldContext = InputFieldContext(InputFieldKind.TEXT)
        typedWordBuffer = ""
        super.onFinishInput()
    }

    private fun rebuildKeyboard() {
        rebuildCount++
        if (rebuildCount % 20L == 0L) debugMetric("IME_REBUILD_COUNT", rebuildCount)
        suggestionGeneration.invalidate()
        pendingSuggestionRequest?.let(handler::removeCallbacks)
        root.setBackgroundColor(keyboardBackgroundColor())
        window?.window?.navigationBarColor = keyboardBackgroundColor()
        setRootContentHeightDp(currentKeyboardHeightDp())
        root.removeAllViews()
        suggestionButtons.clear()
        letterKeyButtons.clear()
        root.addView(createSmartStrip())
        layouts.layout(controller.state, preferences.numberRow).rows.forEach { row ->
            val metrics = KeyboardUiStyle.metrics(preferences)
            root.addView(KeyboardRowLayout(
                this,
                dp(metrics.keyHeightDp),
                dp(metrics.keyHorizontalGapDp),
                dp(metrics.keyVerticalGapDp),
            ).apply {
                row.forEach { addKey(createKey(it), weightFor(it)) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(metrics.keyHeightDp + metrics.keyVerticalGapDp * 2)))
        }
    }

    private fun createSmartStrip(): View = LinearLayout(this).also { strip ->
        smartStrip = strip
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER
        strip.weightSum = 3f
        strip.setPadding(dp(3), 0, dp(3), 0)
        strip.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(KeyboardUiStyle.metrics(preferences).toolbarHeightDp),
        )
        smartStripState = null
        renderSmartStrip(force = true)
    }

    private fun desiredSmartStripState(): SmartStripState = SmartStripPolicy.resolve(
        hasTypedWord = typedWordBuffer.isNotBlank(),
        suggestionsAllowed = fieldContext.allowsSuggestions && preferences.showSuggestions && controller.state.mode == KeyboardMode.LETTERS,
        activePanel = panelController.current,
    )

    private fun renderSmartStrip(force: Boolean = false) {
        if (!::smartStrip.isInitialized) return
        val desired = desiredSmartStripState()
        if (!force && desired == smartStripState) return
        if (smartStripState?.mode == SmartStripMode.SUGGESTIONS && desired.mode != SmartStripMode.SUGGESTIONS) {
            suggestionGeneration.invalidate()
            pendingSuggestionRequest?.let(handler::removeCallbacks)
        }
        smartStripState = desired
        smartStrip.removeAllViews()
        suggestionButtons.clear()
        when (desired.mode) {
            SmartStripMode.TOOLS -> populateToolStrip(smartStrip)
            SmartStripMode.SUGGESTIONS -> populateSuggestionStrip(smartStrip)
            SmartStripMode.PANEL_HEADER -> populatePanelHeader(smartStrip, desired.activePanel)
        }
    }

    private fun populateToolStrip(strip: LinearLayout) = with(strip) {
        addView(toolbarIconButton(R.drawable.ic_keyboard_settings, "Настройки клавиатуры") { openKeyboardSettings() }, smartStripItemParams())
        val clipboardEnabled = fieldContext.allowsClipboardHistory && preferences.clipboardHistory &&
            !fieldContext.isSensitive && !fieldContext.isIncognito
        addView(toolbarIconButton(
            if (clipboardEnabled) R.drawable.ic_keyboard_clipboard else R.drawable.ic_keyboard_private,
            if (clipboardEnabled) "Буфер обмена" else "Буфер обмена недоступен",
            panelController.current == KeyboardPanel.CLIPBOARD,
            clipboardEnabled,
        ) { togglePanel(KeyboardPanel.CLIPBOARD) }, smartStripItemParams())
        addView(toolbarIconButton(
            R.drawable.ic_keyboard_emoji,
            "Emoji",
            panelController.current == KeyboardPanel.EMOJI,
        ) { togglePanel(KeyboardPanel.EMOJI) }, smartStripItemParams())
    }

    private fun populatePanelHeader(strip: LinearLayout, panel: KeyboardPanel?) = with(strip) {
        addView(toolbarIconButton(R.drawable.ic_keyboard_close, "Вернуться к клавиатуре") { closePanel() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(TextView(this@DeviceSyncInputMethodService).apply {
            text = when (panel) {
                KeyboardPanel.CLIPBOARD -> "Буфер обмена"
                KeyboardPanel.EMOJI -> "Emoji"
                else -> "DeviceSync Keyboard"
            }
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setClampedTextSize(14f, 12f, 15f)
            setTextColor(primaryTextColor())
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(View(this@DeviceSyncInputMethodService), LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    private fun openKeyboardSettings() {
        haptics.perform(root, KeyboardHapticKind.MODE_CHANGE)
        panelController.close()
        requestHideSelf(0)
        handler.postDelayed({
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("devicesync://keyboard-settings?section=preferences")).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }, 120L)
    }

    private fun togglePanel(panel: KeyboardPanel) {
        haptics.perform(root, KeyboardHapticKind.MODE_CHANGE)
        panelController.toggle(panel)
        renderCurrentPanel()
    }

    private fun closePanel() {
        panelController.close()
        rebuildKeyboard()
    }

    private fun renderCurrentPanel() {
        when (panelController.current) {
            KeyboardPanel.KEYBOARD -> rebuildKeyboard()
            KeyboardPanel.CLIPBOARD -> showClipboardPanel()
            KeyboardPanel.EMOJI -> showEmojiPanel()
        }
    }

    private fun populateSuggestionStrip(strip: LinearLayout) = with(strip) {
        repeat(3) {
            addView(TextView(this@DeviceSyncInputMethodService).apply {
                gravity = Gravity.CENTER
                setTextColor(primaryTextColor())
                setClampedTextSize(15f, 13f, 16f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                background = suggestionBackground()
                setOnClickListener {
                    text?.toString()?.takeIf(String::isNotBlank)?.let {
                        replaceCurrentWord(it, learn = true)
                        commitText(" ")
                        typedWordBuffer = ""
                        renderSmartStrip()
                    }
                }
                setOnLongClickListener {
                    text?.toString()?.takeIf(String::isNotBlank)?.let { blocked ->
                        dictionaryExecutor.execute {
                            userDictionary.block(blocked)
                            suggestionEngine.invalidateCaches()
                            handler.post { scheduleSuggestions(immediate = true) }
                        }
                    }
                    true
                }
                suggestionButtons += this
            }, LinearLayout.LayoutParams(0, dp(KeyboardUiStyle.metrics(preferences).toolbarHeightDp), 1f).apply { setMargins(dp(1), 0, dp(1), 0) })
        }
        applySuggestionResults(typedWordBuffer, emptyList())
    }

    private fun createKey(key: KeyboardKey): TextView = KeyboardKeyTextView(this).apply {
        val pressState = KeyPressState()
        val commitOnTouchDown = KeyboardInputPolicy.commitOnTouchDown(key)
        var suppressNextClickActivation = false
        val activateKey = {
            val started = SystemClock.elapsedRealtimeNanos()
            handleKey(key)
            val completed = SystemClock.elapsedRealtimeNanos()
            recordKeyTiming(completed - started)
            keyDownStartedNanos.remove(this)?.let { recordKeyDownToCommit(completed - it) }
            if (keySoundEnabled) {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEY_CLICK, 0.25f)
            }
        }
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        setPadding(0, 0, 0, 0)
        if (key == KeyboardKey.Space) {
            setClampedTextSize(keyTextSize(key), 10f, 13f)
        } else {
            setClampedTextSize(keyTextSize(key), 12f, 24f)
        }
        typeface = Typeface.create(
            if (key is KeyboardKey.Text || key == KeyboardKey.Space) "sans-serif" else "sans-serif-medium",
            Typeface.NORMAL,
        )
        setTextColor(keyTextColor(key))
        background = keyBackground(key)
        elevation = 0f
        val icon = keyIcon(key)
        text = if (icon == null) label(key) else ""
        icon?.let { setCenteredIcon(this, it, keyTextColor(key), keyIconSizeDp(key)) }
        contentDescription = description(key)
        if (key is KeyboardKey.Text) letterKeyButtons += key to this
        setOnClickListener {
            if (suppressNextClickActivation) {
                suppressNextClickActivation = false
            } else {
                activateKey()
            }
        }
        val alternate = (key as? KeyboardKey.Text)?.alternate
        val alternateTracker = alternate?.let {
            AlternateKeyPressTracker(KeyboardUiStyle.metrics(preferences).alternateLongPressMillis)
        }
        var alternatePopup: PopupWindow? = null
        var alternateRunnable: Runnable? = null
        var committedPrimaryText: String? = null
        var alternateTextForPress: String? = null
        if (key == KeyboardKey.Language) {
            setOnLongClickListener { haptics.perform(this, KeyboardHapticKind.LONG_PRESS); switchKeyboard(); true }
        }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressState.onDown(event.getPointerId(event.actionIndex))
                    view.isPressed = pressState.isPressed
                    val down = SystemClock.elapsedRealtimeNanos()
                    keyDownStartedNanos[view] = down
                    keyDownToDrawStartedNanos[view] = down
                    alternateTextForPress = alternate?.let(::displayAlternate)
                    if (alternate != null && alternateTracker != null) {
                        alternateTracker.onDown(SystemClock.uptimeMillis())
                        alternateRunnable = Runnable {
                            if (alternateTracker.onThreshold(SystemClock.uptimeMillis()) && view.isPressed) {
                                haptics.perform(view, KeyboardHapticKind.LONG_PRESS)
                                val primary = committedPrimaryText
                                val alternateText = alternateTextForPress
                                if (primary != null && alternateText != null) {
                                    replaceCommittedPrimaryWithAlternate(primary, alternateText)
                                    committedPrimaryText = null
                                }
                                alternatePopup?.dismiss()
                                alternatePopup = showAlternatePopup(view, alternateText ?: displayAlternate(alternate))
                            }
                        }.also { handler.postDelayed(it, alternateTracker.thresholdMillis) }
                    }
                    if (commitOnTouchDown) {
                        committedPrimaryText = (key as? KeyboardKey.Text)?.let(controller::displayText)
                        suppressNextClickActivation = true
                        activateKey()
                        if (key == KeyboardKey.Backspace) startBackspaceRepeat()
                    }
                    haptics.perform(view, if (key is KeyboardKey.Text || key == KeyboardKey.Space || key == KeyboardKey.Backspace) {
                        KeyboardHapticKind.KEY
                    } else KeyboardHapticKind.MODE_CHANGE)
                    view.postOnAnimation {
                        keyDownToDrawStartedNanos.remove(view)?.let {
                            recordKeyDownToDraw(SystemClock.elapsedRealtimeNanos() - it)
                        }
                    }
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    pressState.onDown(event.getPointerId(event.actionIndex))
                    view.isPressed = pressState.isPressed
                }
                android.view.MotionEvent.ACTION_POINTER_UP -> {
                    pressState.onUp(event.getPointerId(event.actionIndex))
                    view.isPressed = pressState.isPressed
                }
                android.view.MotionEvent.ACTION_UP -> {
                    pressState.onUp(event.getPointerId(event.actionIndex))
                    view.isPressed = pressState.isPressed
                    if (key == KeyboardKey.Backspace) stopBackspaceRepeat()
                    alternateRunnable?.let(handler::removeCallbacks)
                    alternateRunnable = null
                    alternatePopup?.dismiss()
                    alternatePopup = null
                    if (alternateTracker != null && alternate != null && key is KeyboardKey.Text) {
                        val result = alternateTracker.onUp(SystemClock.uptimeMillis())
                        if (!commitOnTouchDown) {
                            when (result) {
                                AlternateKeyResult.PRIMARY -> activateKey()
                                AlternateKeyResult.ALTERNATE -> handleTextCommit(key, displayAlternate(alternate))
                                AlternateKeyResult.NONE -> Unit
                            }
                        }
                    }
                    if (commitOnTouchDown) view.performClick()
                    committedPrimaryText = null
                    alternateTextForPress = null
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    pressState.onCancel()
                    suppressNextClickActivation = false
                    if (key == KeyboardKey.Backspace) stopBackspaceRepeat()
                    alternateRunnable?.let(handler::removeCallbacks)
                    alternateRunnable = null
                    alternatePopup?.dismiss()
                    alternatePopup = null
                    alternateTracker?.cancel()
                    committedPrimaryText = null
                    alternateTextForPress = null
                    keyDownStartedNanos.remove(view)
                    keyDownToDrawStartedNanos.remove(view)
                    view.isPressed = false
                }
            }
            commitOnTouchDown
        }
    }

    private fun handleKey(key: KeyboardKey) {
        when (key) {
            is KeyboardKey.Text -> {
                handleTextCommit(key, controller.displayText(key))
            }
            KeyboardKey.Shift -> { controller.onShift(SystemClock.uptimeMillis()); rebuildKeyboard() }
            KeyboardKey.Backspace -> handleBackspace()
            KeyboardKey.Space -> commitSpaceWithAutoCorrection()
            KeyboardKey.Enter -> { typedWordBuffer = ""; performEnterAction(); renderSmartStrip() }
            KeyboardKey.Language -> { typedWordBuffer = ""; controller.toggleLanguage(); rebuildKeyboard() }
            KeyboardKey.Symbols -> { typedWordBuffer = ""; controller.setMode(KeyboardMode.SYMBOLS_PRIMARY); rebuildKeyboard() }
            KeyboardKey.MoreSymbols -> { controller.setMode(KeyboardMode.SYMBOLS_SECONDARY); rebuildKeyboard() }
            KeyboardKey.Letters -> { controller.setMode(KeyboardMode.LETTERS); rebuildKeyboard() }
            KeyboardKey.NextKeyboard -> switchKeyboard()
        }
    }

    private fun displayAlternate(value: String): String = when (controller.state.shift) {
        ShiftState.LOWERCASE -> value
        ShiftState.SHIFT_ONCE, ShiftState.CAPS_LOCK -> value.uppercase()
    }

    private fun handleTextCommit(key: KeyboardKey.Text, committed: String) {
        lastCorrection = null
        val wasShifted = controller.state.shift == ShiftState.SHIFT_ONCE
        commitText(committed)
        typedWordBuffer = if (committed.all { it.isLetter() || it == '\'' || it == '-' }) {
            typedWordBuffer + committed
        } else ""
        controller.onLetterCommitted()
        if (wasShifted) refreshLetterLabels()
        renderSmartStrip()
        scheduleSuggestions()
    }

    private fun replaceCommittedPrimaryWithAlternate(primary: String, alternate: String) {
        currentInputConnection?.deleteSurroundingTextInCodePoints(primary.codePointCount(0, primary.length), 0)
        commitText(alternate)
        typedWordBuffer = if (typedWordBuffer.endsWith(primary)) {
            typedWordBuffer.dropLast(primary.length) + alternate
        } else {
            typedWordBuffer + alternate
        }
        lastCorrection = null
        renderSmartStrip()
        scheduleSuggestions(immediate = true)
    }

    private fun showAlternatePopup(anchor: View, value: String): PopupWindow {
        val content = TextView(this).apply {
            text = value
            gravity = Gravity.CENTER
            includeFontPadding = false
            setClampedTextSize(24f, 20f, 28f)
            setTextColor(primaryTextColor())
            background = roundedDrawable(KeyboardUiStyle.palette(this@DeviceSyncInputMethodService, preferences).panel, 12f)
            elevation = dpF(8f)
        }
        return PopupWindow(content, dp(52), dp(58), false).apply {
            isClippingEnabled = false
            elevation = dpF(8f)
            showAsDropDown(anchor, (anchor.width - dp(52)) / 2, -(anchor.height + dp(64)))
        }
    }

    private fun commitText(text: String) { currentInputConnection?.commitText(text, 1) }

    private fun deleteOneCodePoint() {
        val input = currentInputConnection ?: return
        input.deleteSurroundingTextInCodePoints(1, 0)
    }

    private fun handleBackspace() {
        if (fieldContext.isSensitive) {
            lastCorrection = null
            deleteOneCodePoint()
            typedWordBuffer = ""
            renderSmartStrip()
            return
        }
        val correction = lastCorrection
        val before = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        if (correction != null && before.endsWith(correction.second + " ")) {
            currentInputConnection?.deleteSurroundingTextInCodePoints(correction.second.codePointCount(0, correction.second.length) + 1, 0)
            currentInputConnection?.commitText(correction.first, 1)
            typedWordBuffer = correction.first
            learnWord(correction.first)
            lastCorrection = null
            renderSmartStrip()
            scheduleSuggestions(immediate = true)
            return
        }
        deleteOneCodePoint()
        typedWordBuffer = typedWordBuffer.dropLast(1)
        if (!before.endsWith(" ")) lastCorrection = null
        renderSmartStrip()
        if (typedWordBuffer.isNotBlank()) scheduleSuggestions()
    }

    private fun commitSpaceWithAutoCorrection() {
        if (fieldContext.isSensitive) {
            lastCorrection = null
            commitText(" ")
            typedWordBuffer = ""
            renderSmartStrip()
            return
        }
        val word = typedWordBuffer
        if (word.isEmpty() && preferences.doubleSpacePeriod && replaceDoubleSpaceWithPeriod()) {
            typedWordBuffer = ""
            updateAutomaticShift(currentInputEditorInfo)
            renderSmartStrip()
            return
        }
        val correction = latestCorrection.takeIf {
            fieldContext.allowsSuggestions && preferences.autoCorrection && latestCorrectionWord.equals(word, ignoreCase = true)
        }
        if (correction != null) {
            replaceCurrentWord(correction, learn = false)
            lastCorrection = word to correction
            learnWord(correction)
        } else {
            lastCorrection = null
            learnWord(word)
        }
        commitText(" ")
        typedWordBuffer = ""
        renderSmartStrip()
        updateAutomaticShift(currentInputEditorInfo)
    }

    private fun replaceDoubleSpaceWithPeriod(): Boolean {
        val before = currentInputConnection?.getTextBeforeCursor(3, 0)?.toString().orEmpty()
        if (before.length < 2 || !before.endsWith(' ') || before.dropLast(1).lastOrNull()?.isWhitespace() == true) return false
        currentInputConnection?.deleteSurroundingText(1, 0)
        commitText(". ")
        return true
    }

    private fun updateAutomaticShift(info: EditorInfo?) {
        val shouldCapitalize = preferences.autoCapitalization &&
            EditorInfoPolicy.allowsAutomaticCapitalization(info, fieldContext) &&
            (currentInputConnection?.getCursorCapsMode(info?.inputType ?: 0) ?: 0) != 0
        if (shouldCapitalize) controller.requestShiftOnce() else controller.clearAutomaticShift()
        if (::root.isInitialized) refreshLetterLabels()
    }

    private fun replaceCurrentWord(replacement: String, learn: Boolean = false) {
        val word = typedWordBuffer
        if (word.isNotEmpty()) {
            currentInputConnection?.deleteSurroundingTextInCodePoints(word.codePointCount(0, word.length), 0)
        }
        val adjusted = if (word.firstOrNull()?.isUpperCase() == true) replacement.replaceFirstChar(Char::uppercase) else replacement
        commitText(adjusted)
        typedWordBuffer = adjusted
        if (learn) learnWord(adjusted)
    }

    private fun learnWord(word: String) {
        if (preferences.learnWords && fieldContext.allowsSuggestions && word.isNotBlank()) {
            val previousWord = previousWordsBeforeCurrent().lastOrNull()
            dictionaryExecutor.execute {
                userDictionary.recordUsage(word, previousWord)
                suggestionEngine.invalidateCaches()
            }
        }
    }

    private fun scheduleSuggestions(immediate: Boolean = false) {
        if (!fieldContext.allowsSuggestions || suggestionButtons.isEmpty()) return
        val typed = typedWordBuffer
        val language = KeyboardLanguageDetector.detect(typed, controller.state.language)
        val previousWords = previousWordsBeforeCurrent()
        val generation = suggestionGeneration.next()
        pendingSuggestionRequest?.let(handler::removeCallbacks)
        pendingSuggestionRequest = Runnable {
            suggestionExecutor.execute {
                val started = SystemClock.elapsedRealtimeNanos()
                val personalization = if (fieldContext.isSensitive || fieldContext.isIncognito) {
                    KeyboardPersonalizationSnapshot()
                } else {
                    userDictionary.snapshot()
                }
                val context = SuggestionContext(
                    previousWords = previousWords,
                    userFrequencies = personalization.wordFrequencies,
                    contextFrequencies = personalization.contextFrequencies,
                    blockedWords = personalization.blockedWords,
                    grammaticalGender = preferences.grammaticalGender,
                )
                val rawValues = suggestionEngine.suggest(typed, language, 5, context)
                val prefixElapsed = SystemClock.elapsedRealtimeNanos() - started
                handler.post {
                    if (!suggestionGeneration.isCurrent(generation) || smartStripState?.mode != SmartStripMode.SUGGESTIONS) {
                        droppedSuggestionResults++
                        return@post
                    }
                    applySuggestionResults(typed, rawValues.map { it.text })
                    debugMetric("IME_SUGGESTION_DURATION_MS", prefixElapsed / 1_000_000)
                }
                val hasExactWord = rawValues.any { it.text.equals(typed, ignoreCase = true) }
                val correction = if (preferences.autoCorrection && typed.length >= 3 && !hasExactWord) {
                    suggestionEngine.bestCorrection(typed, language, personalization.blockedWords)
                        ?.takeIf { isCorrectionAllowed(typed, it) }
                } else null
                val elapsed = SystemClock.elapsedRealtimeNanos() - started
                handler.post {
                    if (!suggestionGeneration.isCurrent(generation) || smartStripState?.mode != SmartStripMode.SUGGESTIONS) {
                        droppedSuggestionResults++
                        return@post
                    }
                    latestCorrectionWord = typed
                    latestCorrection = correction
                    applySuggestionResults(typed, (listOfNotNull(correction) + rawValues.map { it.text }).distinct())
                    recordSuggestionTiming(elapsed)
                }
            }
        }.also { handler.postDelayed(it, if (immediate) 0L else 8L) }
    }

    private fun previousWordsBeforeCurrent(): List<String> {
        if (fieldContext.isSensitive || fieldContext.isIncognito) return emptyList()
        val beforeCursor = currentInputConnection?.getTextBeforeCursor(192, 0)?.toString().orEmpty()
        val completedText = if (
            typedWordBuffer.isNotEmpty() &&
            beforeCursor.endsWith(typedWordBuffer, ignoreCase = true)
        ) {
            beforeCursor.dropLast(typedWordBuffer.length)
        } else {
            beforeCursor
        }
        return completedText
            .split(Regex("[^\\p{L}'-]+"))
            .asSequence()
            .filter(String::isNotBlank)
            .toList()
            .takeLast(2)
    }

    private fun applySuggestionResults(typed: String, rawValues: List<String>) {
        val values = if (typed.isNotBlank()) {
            val displayedTyped = rawValues.firstOrNull { it.equals(typed, ignoreCase = true) } ?: typed
            val alternatives = rawValues.filterNot { it.equals(typed, ignoreCase = true) }
            listOf(alternatives.getOrNull(0).orEmpty(), displayedTyped, alternatives.getOrNull(1).orEmpty())
        } else rawValues.take(3)
        suggestionButtons.forEachIndexed { index, view ->
            val value = values.getOrNull(index).orEmpty()
            view.text = value
            view.background = if (value.isBlank()) null else suggestionBackground()
            view.contentDescription = value.takeIf(String::isNotBlank)?.let { "Подсказка $it" }
            view.setTextColor(if (typed.isNotBlank() && index == 1) accentTextColor() else primaryTextColor())
        }
    }

    private fun isCorrectionAllowed(typed: String, correction: String): Boolean {
        val distance = editDistance(typed.lowercase(), correction.lowercase())
        return when (preferences.correctionLevel) {
            KeyboardCorrectionLevel.CONSERVATIVE -> typed.length >= 4 && distance <= 1
            KeyboardCorrectionLevel.BALANCED -> distance <= if (typed.length <= 5) 1 else 2
            KeyboardCorrectionLevel.AGGRESSIVE -> distance <= 2
        }
    }

    private fun editDistance(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun startBackspaceRepeat(): Boolean {
        stopBackspaceRepeat()
        backspaceRepeater = object : Runnable {
            override fun run() { handleBackspace(); handler.postDelayed(this, 55) }
        }.also { handler.postDelayed(it, 350) }
        return true
    }

    private fun stopBackspaceRepeat() { backspaceRepeater?.let(handler::removeCallbacks); backspaceRepeater = null }

    private fun loadFrequencyDictionaries() {
        dictionaryExecutor.execute {
            val loader = FrequencyDictionaryLoader(assets)
            listOf(KeyboardLanguage.RUSSIAN, KeyboardLanguage.ENGLISH).forEach { language ->
                runCatching { loader.load(language) }
                    .onSuccess { words ->
                        suggestionEngine.installDictionary(language, words)
                        suggestionEngine.invalidateCaches()
                        handler.post { if (::root.isInitialized) scheduleSuggestions(immediate = true) }
                    }
            }
        }
    }

    private fun refreshLetterLabels() {
        letterKeyButtons.forEach { (key, view) -> view.text = controller.displayText(key) }
    }

    private fun recordKeyTiming(durationNanos: Long) {
        keyTimingCount++
        keyTimingTotalNanos += durationNanos
        keyTimingMaxNanos = maxOf(keyTimingMaxNanos, durationNanos)
        if (keyTimingCount % 50L == 0L) {
            debugMetric("IME_KEY_COMMIT_DURATION_MS_AVG", keyTimingTotalNanos / keyTimingCount / 1_000_000)
            debugMetric("IME_KEY_COMMIT_DURATION_MS_MAX", keyTimingMaxNanos / 1_000_000)
            keyTimingCount = 0; keyTimingTotalNanos = 0; keyTimingMaxNanos = 0
        }
    }

    private fun recordKeyDownToCommit(durationNanos: Long) {
        keyDownToCommitCount++
        keyDownToCommitTotalNanos += durationNanos
        keyDownToCommitMaxNanos = maxOf(keyDownToCommitMaxNanos, durationNanos)
        if (durationNanos > 8_000_000L) debugMetric("IME_MAIN_THREAD_LONG_TASK", durationNanos / 1_000_000)
        if (keyDownToCommitCount % 50L == 0L) {
            debugMetric("IME_KEY_DOWN_TO_COMMIT_MS", keyDownToCommitTotalNanos / keyDownToCommitCount / 1_000_000)
            debugMetric("IME_KEY_DOWN_TO_COMMIT_MS_MAX", keyDownToCommitMaxNanos / 1_000_000)
            keyDownToCommitCount = 0
            keyDownToCommitTotalNanos = 0
            keyDownToCommitMaxNanos = 0
        }
    }

    private fun recordKeyDownToDraw(durationNanos: Long) {
        keyDownToDrawCount++
        keyDownToDrawTotalNanos += durationNanos
        if (keyDownToDrawCount % 50L == 0L) {
            debugMetric("IME_KEY_DOWN_TO_DRAW_MS", keyDownToDrawTotalNanos / keyDownToDrawCount / 1_000_000)
            keyDownToDrawCount = 0
            keyDownToDrawTotalNanos = 0
        }
    }

    private fun debugMetric(name: String, value: Long) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d("DeviceSyncImePerf", "$name=$value")
    }

    private fun recordSuggestionTiming(durationNanos: Long) {
        suggestionTimingCount++
        suggestionTimingTotalNanos += durationNanos
        suggestionTimingMaxNanos = maxOf(suggestionTimingMaxNanos, durationNanos)
        if (suggestionTimingCount % 25L == 0L) {
            debugMetric("IME_SUGGESTION_REQUEST_DURATION_MS_AVG", suggestionTimingTotalNanos / suggestionTimingCount / 1_000_000)
            debugMetric("IME_SUGGESTION_REQUEST_DURATION_MS_MAX", suggestionTimingMaxNanos / 1_000_000)
            debugMetric("IME_SUGGESTION_DROPPED_COUNT", droppedSuggestionResults)
            suggestionTimingCount = 0; suggestionTimingTotalNanos = 0; suggestionTimingMaxNanos = 0; droppedSuggestionResults = 0
        }
    }

    private fun performEnterAction() {
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
    }

    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            window.window?.attributes?.token?.let { imm.switchToNextInputMethod(it, false) }
        }
    }

    private fun showClipboardPanel() {
        if (!fieldContext.allowsClipboardHistory || !preferences.clipboardHistory) return
        panelController.show(KeyboardPanel.CLIPBOARD)
        val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val entries = integration?.clipboardHistory().orEmpty()
        setRootContentHeightDp(currentKeyboardHeightDp())
        root.removeAllViews()
        root.addView(createSmartStrip())
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(LinearLayout(this@DeviceSyncInputMethodService).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@DeviceSyncInputMethodService).apply {
                    text = if (entries.isEmpty()) "Текущий буфер обмена" else "История буфера • ${entries.size}"
                    setTextColor(secondaryTextColor())
                    setClampedTextSize(13f, 11f, 14f)
                    setPadding(dp(10), dp(5), dp(10), dp(1))
                })
                if (entries.isEmpty()) addView(TextView(this@DeviceSyncInputMethodService).apply {
                    text = if (value.isBlank()) "Буфер обмена пуст" else value.take(500)
                    setTextColor(primaryTextColor()); textSize = 14f; setPadding(dp(8), dp(6), dp(8), dp(6))
                    minHeight = dp(58)
                    maxLines = 5
                    ellipsize = TextUtils.TruncateAt.END
                    background = actionBackground()
                    setOnClickListener { if (value.isNotBlank()) commitText(value) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(6), dp(4), dp(6), dp(4))
                })
                entries.forEach { entry ->
                    addView(LinearLayout(this@DeviceSyncInputMethodService).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(7), dp(4), dp(7), dp(4))
                        background = actionBackground()
                        addView(TextView(this@DeviceSyncInputMethodService).apply {
                            text = entry.text.take(500)
                            setTextColor(primaryTextColor()); textSize = 14f
                            minHeight = dp(52)
                            maxLines = 4
                            ellipsize = TextUtils.TruncateAt.END
                            setOnClickListener { commitText(entry.text) }
                            contentDescription = "Вставить элемент буфера обмена"
                        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        addView(LinearLayout(this@DeviceSyncInputMethodService).apply {
                            orientation = LinearLayout.HORIZONTAL
                            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.timestampMillis))
                            addView(TextView(this@DeviceSyncInputMethodService).apply {
                                text = "${if (entry.pinned) "Закреплено • " else ""}${entry.source} • $time"
                                setTextColor(secondaryTextColor()); gravity = Gravity.CENTER_VERTICAL
                            }, LinearLayout.LayoutParams(0, dp(40), 1f))
                            addView(smallIconAction(R.drawable.ic_keyboard_insert, "Вставить") { commitText(entry.text) }, smallActionParams())
                            addView(smallIconAction(R.drawable.ic_keyboard_send, "Отправить в Windows") { integration?.sendClipboardNow(entry.text) }, smallActionParams())
                            addView(smallIconAction(R.drawable.ic_keyboard_pin, if (entry.pinned) "Открепить" else "Закрепить", entry.pinned) {
                                integration?.toggleClipboardPinned(entry.id); showClipboardPanel()
                            }, smallActionParams())
                            addView(smallIconAction(R.drawable.ic_keyboard_delete, "Удалить") {
                                integration?.removeClipboardItem(entry.id); showClipboardPanel()
                            }, smallActionParams())
                        })
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(dp(6), dp(4), dp(6), dp(4))
                    })
                }
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (confirmClearClipboard) {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@DeviceSyncInputMethodService).apply {
                    text = "Очистить незакреплённые элементы?"
                    setTextColor(primaryTextColor()); textSize = 14f; gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(toolbarButton("Отмена") { confirmClearClipboard = false; showClipboardPanel() })
                addView(toolbarButton("Очистить") {
                    integration?.clearClipboardHistory(); confirmClearClipboard = false; showClipboardPanel()
                })
            })
        } else {
            root.addView(toolbarButton("Очистить историю") { confirmClearClipboard = true; showClipboardPanel() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { setMargins(dp(4), dp(1), dp(4), dp(1)) })
        }
    }

    private fun showEmojiPanel() {
        panelController.show(KeyboardPanel.EMOJI)
        setRootContentHeightDp(
            EmojiPanelMetrics.totalHeightDp(
                screenHeightDp = resources.configuration.screenHeightDp,
                landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
                keyboardHeightDp = currentKeyboardHeightDp(),
            ),
        )
        val recent = emojiRepository.recent()
        val favorites = emojiRepository.favorites()
        root.removeAllViews(); root.addView(createSmartStrip())
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@DeviceSyncInputMethodService).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(3), 0, dp(3), 0)
                EmojiCategory.entries.forEach { category ->
                    addView(panelTab(category.shortLabel, category == emojiCategory) {
                        emojiCategory = category
                        if (category != EmojiCategory.SEARCH) emojiSearchQuery = ""
                        showEmojiPanel()
                    }.apply { contentDescription = category.description }, LinearLayout.LayoutParams(dp(48), dp(40)).apply {
                        setMargins(dp(1), 0, dp(1), 0)
                    })
                }
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@DeviceSyncInputMethodService).apply {
                text = when {
                    emojiCategory == EmojiCategory.SEARCH && emojiSearchQuery.isNotBlank() -> "Поиск: $emojiSearchQuery"
                    emojiCategory == EmojiCategory.SEARCH -> "Быстрый поиск эмодзи"
                    else -> "${emojiCategory.description} • удерживайте для избранного"
                }
                setTextColor(secondaryTextColor())
                setClampedTextSize(13f, 11f, 14f)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(4), 0)
            }, LinearLayout.LayoutParams(0, dp(34), 1f))
            if (emojiCategory == EmojiCategory.SEARCH && emojiSearchQuery.isNotBlank()) {
                addView(toolbarButton("Сбросить") {
                    emojiSearchQuery = ""
                    showEmojiPanel()
                }, LinearLayout.LayoutParams(dp(90), dp(34)).apply { setMargins(dp(2), 0, dp(4), 0) })
            }
        })

        if (emojiCategory == EmojiCategory.SEARCH && emojiSearchQuery.isBlank()) {
            root.addView(GridLayout(this).apply {
                columnCount = 2
                setPadding(dp(5), dp(2), dp(5), dp(4))
                KeyboardEmojiCatalog.searchOptions().forEachIndexed { index, option ->
                    addView(toolbarButton(option) {
                        emojiSearchQuery = option
                        showEmojiPanel()
                    }, GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(42)
                        rowSpec = GridLayout.spec(index / 2)
                        columnSpec = GridLayout.spec(index % 2, 1f)
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }

        val availablePixels = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val availableWidthDp = (availablePixels / resources.displayMetrics.density).toInt() - 8
        val uiMetrics = KeyboardUiStyle.metrics(preferences)
        val columns = EmojiGridMetrics.columns(
            availableWidthDp,
            minimumCellDp = uiMetrics.emojiMinimumCellDp,
            spacingDp = uiMetrics.emojiSpacingDp,
        )
        val cellDp = EmojiGridMetrics.cellWidthDp(
            availableWidthDp,
            columns,
            spacingDp = uiMetrics.emojiSpacingDp,
        )
        val cellHeightDp = cellDp.coerceIn(uiMetrics.minimumTouchDp.coerceAtLeast(48), 56)
        val emojis = if (emojiCategory == EmojiCategory.SEARCH) {
            KeyboardEmojiCatalog.search(emojiSearchQuery)
        } else {
            KeyboardEmojiCatalog.items(emojiCategory, recent, favorites)
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            if (emojis.isEmpty()) {
                addView(TextView(this@DeviceSyncInputMethodService).apply {
                    text = when (emojiCategory) {
                        EmojiCategory.RECENT -> "Недавних эмодзи пока нет"
                        EmojiCategory.FAVORITES -> "Избранных пока нет. Удерживайте эмодзи в любой категории."
                        EmojiCategory.SEARCH -> "Ничего не найдено"
                        else -> "В этой категории пока нет эмодзи"
                    }
                    gravity = Gravity.CENTER
                    setTextColor(secondaryTextColor())
                    setPadding(dp(16), dp(20), dp(16), dp(20))
                })
            } else {
                addView(GridLayout(this@DeviceSyncInputMethodService).apply {
                    columnCount = columns
                    setPadding(dp(4), dp(2), dp(4), dp(4))
                    emojis.forEachIndexed { index, emoji ->
                        addView(TextView(this@DeviceSyncInputMethodService).apply {
                            text = emoji
                            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(28).toFloat())
                            gravity = Gravity.CENTER
                            includeFontPadding = false
                            val favorite = emojiRepository.isFavorite(emoji)
                            contentDescription = "Emoji $emoji${if (favorite) ", в избранном" else ""}"
                            background = toolbarRippleBackground(favorite)
                            setOnClickListener {
                                haptics.perform(this, KeyboardHapticKind.KEY)
                                commitText(emoji)
                                emojiRepository.record(emoji)
                            }
                            setOnLongClickListener {
                                haptics.perform(this, KeyboardHapticKind.LONG_PRESS)
                                emojiRepository.toggleFavorite(emoji)
                                showEmojiPanel()
                                true
                            }
                        }, GridLayout.LayoutParams().apply {
                            width = dp(cellDp)
                            height = dp(cellHeightDp)
                            rowSpec = GridLayout.spec(index / columns)
                            columnSpec = GridLayout.spec(index % columns)
                            setMargins(dp(2), dp(2), dp(2), dp(2))
                        })
                    }
                })
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun toolbarButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        setClampedTextSize(14f, 12f, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(primaryTextColor())
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = actionBackground()
        setOnClickListener { action() }
        minHeight = dp(40)
    }

    private fun panelTab(label: String, selected: Boolean, action: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setClampedTextSize(13f, 11f, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (selected) accentTextColor() else primaryTextColor())
        background = toolbarRippleBackground(selected)
        isSelected = selected
        contentDescription = label
        setOnClickListener {
            haptics.perform(this, KeyboardHapticKind.MODE_CHANGE)
            action()
        }
    }

    private fun smallIconAction(
        @DrawableRes icon: Int,
        description: String,
        selected: Boolean = false,
        enabled: Boolean = true,
        action: () -> Unit,
    ) = KeyboardKeyTextView(this).apply {
        contentDescription = description
        gravity = Gravity.CENTER
        includeFontPadding = false
        isSelected = selected
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.38f
        setCenteredIcon(this, icon, if (enabled) primaryTextColor() else secondaryTextColor())
        background = toolbarRippleBackground(selected)
        setOnClickListener {
            if (isEnabled) {
                haptics.perform(this, KeyboardHapticKind.KEY)
                action()
            }
        }
    }

    private fun smallActionParams() = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
        setMargins(dp(1), 0, dp(1), 0)
    }

    private fun createPanelFooter(beforeClose: (() -> Unit)? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), dp(4), dp(2))
        addView(toolbarIconButton(R.drawable.ic_keyboard_close, "Закрыть панель") {
            beforeClose?.invoke()
            closePanel()
        }, LinearLayout.LayoutParams(dp(44), dp(42)).apply { setMargins(dp(1), 0, dp(1), 0) })
        addView(toolbarButton("АБВ") {
            beforeClose?.invoke()
            closePanel()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(1), 0, dp(1), 0) })
    }

    private fun toolbarIconButton(
        @DrawableRes icon: Int,
        description: String,
        selected: Boolean = false,
        enabled: Boolean = true,
        action: () -> Unit,
    ) = KeyboardKeyTextView(this).apply {
        contentDescription = description
        gravity = Gravity.CENTER
        includeFontPadding = false
        isSelected = selected
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.38f
        setCenteredIcon(this, icon, if (enabled) primaryTextColor() else secondaryTextColor())
        background = toolbarRippleBackground(selected)
        setOnClickListener { if (isEnabled) action() }
    }

    private fun toolbarIndicator(@DrawableRes icon: Int, description: String) = KeyboardKeyTextView(this).apply {
        contentDescription = description
        gravity = Gravity.CENTER
        includeFontPadding = false
        isEnabled = false
        alpha = 0.7f
        setCenteredIcon(this, icon, secondaryTextColor())
        background = toolbarRippleBackground(false)
    }

    private fun label(key: KeyboardKey): String = when (key) {
        is KeyboardKey.Text -> controller.displayText(key)
        KeyboardKey.Shift -> ""
        KeyboardKey.Backspace -> ""
        KeyboardKey.Space -> if (controller.state.language == KeyboardLanguage.RUSSIAN) "Русский" else "English"
        KeyboardKey.Enter -> ""
        KeyboardKey.Language -> ""
        KeyboardKey.Symbols -> "#1?"
        KeyboardKey.Letters -> "АБВ"
        KeyboardKey.MoreSymbols -> "=\\<"
        KeyboardKey.NextKeyboard -> "⌨"
    }

    private fun description(key: KeyboardKey): String = when (key) {
        is KeyboardKey.Text -> key.value
        KeyboardKey.Shift -> "Shift или Caps Lock"
        KeyboardKey.Backspace -> "Удалить"
        KeyboardKey.Space -> "Пробел"
        KeyboardKey.Enter -> enterDescription()
        KeyboardKey.Language -> "Переключить русский и английский"
        KeyboardKey.Symbols -> "Символы"
        KeyboardKey.Letters -> "Буквы"
        KeyboardKey.MoreSymbols -> "Дополнительные символы"
        KeyboardKey.NextKeyboard -> "Следующая клавиатура"
    }

    private fun weightFor(key: KeyboardKey): Float = KeyboardRowMetrics.weight(key)

    @DrawableRes
    private fun keyIcon(key: KeyboardKey): Int? = when (key) {
        KeyboardKey.Shift -> R.drawable.ic_keyboard_shift
        KeyboardKey.Backspace -> R.drawable.ic_keyboard_backspace
        KeyboardKey.Language, KeyboardKey.NextKeyboard -> R.drawable.ic_keyboard_language
        KeyboardKey.Enter -> R.drawable.ic_keyboard_enter
        else -> null
    }

    private fun enterDescription(): String = when (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
        EditorInfo.IME_ACTION_DONE -> "Готово"
        EditorInfo.IME_ACTION_GO -> "Перейти"
        EditorInfo.IME_ACTION_NEXT -> "Далее"
        EditorInfo.IME_ACTION_SEARCH -> "Поиск"
        EditorInfo.IME_ACTION_SEND -> "Отправить"
        else -> "Новая строка"
    }

    private fun keyIconSizeDp(key: KeyboardKey): Int = when (key) {
        KeyboardKey.Enter, KeyboardKey.Backspace -> 22
        else -> 20
    }

    private fun setCenteredIcon(view: KeyboardKeyTextView, @DrawableRes icon: Int, color: Int, sizeDp: Int = 22) {
        val drawable = ContextCompat.getDrawable(this, icon)?.mutate() ?: return
        DrawableCompat.setTint(drawable, color)
        val size = dp(sizeDp)
        view.setCenteredIcon(drawable, size)
    }

    private fun TextView.setClampedTextSize(viewSizeSp: Float, minSp: Float, maxSp: Float) {
        val fontScale = resources.configuration.fontScale.coerceAtLeast(0.75f)
        val allowedScale = fontScale.coerceIn(0.85f, 1.25f)
        val compensatedSp = (viewSizeSp * allowedScale).coerceIn(minSp, maxSp) / fontScale
        setTextSize(TypedValue.COMPLEX_UNIT_SP, compensatedSp)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

    private fun smartStripItemParams(): LinearLayout.LayoutParams {
        val size = KeyboardUiStyle.metrics(preferences).toolbarHeightDp
        return LinearLayout.LayoutParams(0, dp(size), 1f)
    }

    private fun currentKeyboardHeightDp(): Int {
        val metrics = if (preferences.compactMode) KeyboardHeightMetrics.Compact else KeyboardHeightMetrics.Normal
        val rowCount = layouts.layout(controller.state, preferences.numberRow).rows.size
        val suggestionsVisible = fieldContext.allowsSuggestions && preferences.showSuggestions && controller.state.mode == KeyboardMode.LETTERS
        return KeyboardHeightMetrics.totalDp(rowCount, suggestionsVisible, metrics)
    }

    private fun setRootContentHeightDp(heightDp: Int) {
        rootContentHeightPx = dp(heightDp)
        updateRootHeight()
    }

    private fun updateRootHeight() {
        val heightPx = rootContentHeightPx + rootSystemBottomInsetPx
        root.minimumHeight = heightPx
        root.layoutParams = (root.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            heightPx,
        )).also {
            it.width = ViewGroup.LayoutParams.MATCH_PARENT
            it.height = heightPx
        }
    }

    private fun keyTextSize(key: KeyboardKey): Float = (when (key) {
        is KeyboardKey.Text -> if (key.value.length > 2) 15f else if (resources.configuration.screenWidthDp < 380) 19f else 20f
        KeyboardKey.Space -> 12f
        KeyboardKey.Enter, KeyboardKey.Shift, KeyboardKey.Backspace -> 20f
        else -> 14f
    } * preferences.keyLabelScalePercent / 100f).coerceIn(12f, 24f)

    private fun keyTextColor(key: KeyboardKey): Int = when {
        key == KeyboardKey.Enter && isDarkTheme() -> Color.rgb(225, 255, 238)
        else -> primaryTextColor()
    }

    private fun keyBackground(key: KeyboardKey): StateListDrawable {
        val palette = KeyboardUiStyle.palette(this, preferences)
        val isEnter = key == KeyboardKey.Enter
        val isSpecial = key !is KeyboardKey.Text && key != KeyboardKey.Space
        val fill = when {
            isEnter -> palette.enterKey
            isSpecial -> palette.functionalKey
            else -> palette.key
        }
        val pressed = ColorUtils.blendARGB(fill, palette.text, if (isDarkTheme()) 0.16f else 0.09f)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedDrawable(pressed, KeyboardUiStyle.metrics(preferences).keyCornerRadiusDp))
            addState(intArrayOf(), roundedDrawable(fill, KeyboardUiStyle.metrics(preferences).keyCornerRadiusDp))
        }
    }

    private fun suggestionBackground(): StateListDrawable = fastStateBackground(
        KeyboardUiStyle.palette(this, preferences).background,
        9f,
    )

    private fun toolbarRippleBackground(selected: Boolean = false): StateListDrawable = fastStateBackground(
        if (selected) KeyboardUiStyle.palette(this, preferences).selected else Color.TRANSPARENT,
        10f,
    )

    private fun actionBackground(): StateListDrawable = fastStateBackground(
        KeyboardUiStyle.palette(this, preferences).panel,
        KeyboardUiStyle.metrics(preferences).panelCornerRadiusDp,
    )

    private fun fastStateBackground(normalColor: Int, radiusDp: Float): StateListDrawable {
        val pressedColor = if (normalColor == Color.TRANSPARENT) {
            if (isDarkTheme()) Color.argb(42, 255, 255, 255) else Color.argb(28, 0, 0, 0)
        } else {
            ColorUtils.blendARGB(normalColor, primaryTextColor(), if (isDarkTheme()) 0.14f else 0.08f)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedDrawable(pressedColor, radiusDp))
            addState(intArrayOf(), roundedDrawable(normalColor, radiusDp))
        }
    }

    private fun roundedDrawable(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(radiusDp)
        setColor(color)
    }

    private fun isDarkTheme(): Boolean = when (preferences.themeMode) {
        KeyboardThemeMode.DARK -> true
        KeyboardThemeMode.LIGHT -> false
        KeyboardThemeMode.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun keyboardBackgroundColor(): Int = KeyboardUiStyle.palette(this, preferences).background
    private fun primaryTextColor(): Int = KeyboardUiStyle.palette(this, preferences).text
    private fun secondaryTextColor(): Int = KeyboardUiStyle.palette(this, preferences).secondaryText
    private fun accentTextColor(): Int = KeyboardUiStyle.palette(this, preferences).accentText
}
