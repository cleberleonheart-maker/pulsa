package com.pulsa.player.ime

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.view.ContextThemeWrapper
import com.pulsa.player.R
import com.pulsa.player.core.DataStorePreferences
import com.pulsa.player.core.LegacyPreferencesFile
import com.pulsa.player.core.PreferenceValue
import com.pulsa.player.core.Settings as AppSettings
import java.util.Locale
import java.util.UUID

class PulsaImeService : InputMethodService(), ImeKeyboardView.Host {
    private lateinit var keyboard: ImeKeyboardView
    private var profile = ImeEditorProfile.from(null)
    private var language = ImeConfig.DEFAULT_LANGUAGE
    private var inputSessionId = 0L
    private var voiceToken = UUID.randomUUID().toString()
    private var voiceReceiverRegistered = false

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ImeVoiceActivity.ACTION_VOICE_RESULT) return
            if (intent.getStringExtra(ImeVoiceActivity.EXTRA_TOKEN) != voiceToken) return
            if (intent.getLongExtra(ImeVoiceActivity.EXTRA_SESSION_ID, -1L) != inputSessionId) return
            val text = intent.getStringExtra(ImeVoiceActivity.EXTRA_RESULT).orEmpty()
            if (text.isNotBlank()) inputText(text)
        }
    }

    override val editorProfile: ImeEditorProfile
        get() = profile

    override fun onCreate() {
        super.onCreate()
        language = ImeConfig.language(this)
        registerVoiceReceiver()
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        language = ImeConfig.language(this)
        val localized = localizedContext()
        val view = LayoutInflater.from(localized).inflate(R.layout.ime_keyboard, null) as ImeKeyboardView
        keyboard = view
        view.bind(this)
        view.setLanguage(language)
        view.applyEditorProfile(profile)
        return view
    }

    override fun onEvaluateInputViewShown(): Boolean =
        super.onEvaluateInputViewShown() && !profile.typeNull

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        inputSessionId++
        language = ImeConfig.language(this)
        profile = ImeEditorProfile.from(info)
        if (::keyboard.isInitialized) {
            keyboard.setLanguage(language)
            keyboard.onStartInput()
            keyboard.applyEditorProfile(profile)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::keyboard.isInitialized) keyboard.onShown()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        if (!::keyboard.isInitialized) return
        val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(256, 0)
        keyboard.onUpdateSelection(textBeforeCursor)
    }

    override fun onFinishInput() {
        if (::keyboard.isInitialized) keyboard.clearComposing()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (::keyboard.isInitialized) keyboard.onHidden()
        super.onFinishInputView(finishingInput)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        language = ImeConfig.language(this)
        if (::keyboard.isInitialized) keyboard.setLanguage(language)
    }

    override fun onDestroy() {
        unregisterVoiceReceiver()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun requestNextInputMethod() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (shouldOfferSwitchingToNextInputMethod()) {
                switchToNextInputMethod(false)
            } else {
                showInputMethodPicker()
            }
        } else {
            showInputMethodPicker()
        }
    }

    override fun showInputMethodPicker() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showInputMethodPicker()
    }

    override fun openKeyboardSettings() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    override fun startVoiceInput() {
        val intent = Intent(this, ImeVoiceActivity::class.java).apply {
            putExtra(ImeVoiceActivity.EXTRA_LANGUAGE, ImeConfig.languageTag(language))
            putExtra(ImeVoiceActivity.EXTRA_SESSION_ID, inputSessionId)
            putExtra(ImeVoiceActivity.EXTRA_TOKEN, voiceToken)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    override fun pasteClipboard() {
        if (profile.sensitive) return
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val description = clipboard.primaryClipDescription ?: return
            if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) return
            val clip = clipboard.primaryClip ?: return
            val text = clip.getItemAt(0).coerceToText(this).toString().take(10_000)
            if (text.isNotBlank()) inputText(text)
        } catch (_: SecurityException) {
        }
    }

    override fun inputText(text: String): Boolean {
        if (profile.typeNull || text.isEmpty() || !profile.acceptsInput(text)) return false
        val connection = currentInputConnection ?: return false
        connection.beginBatchEdit()
        val committed = connection.commitText(text, 1)
        connection.endBatchEdit()
        return committed
    }

    override fun replaceWord(value: String): Boolean {
        if (profile.typeNull || value.isEmpty() || !profile.acceptsInput(value)) return false
        val connection = currentInputConnection ?: return false
        val selected = connection.getSelectedText(0)
        val before = connection.getTextBeforeCursor(256, 0)?.toString().orEmpty()
        val after = connection.getTextAfterCursor(256, 0)?.toString().orEmpty()
        val prefix = ImeEditorProfile.wordBeforeCursor(before)
        val suffix = ImeEditorProfile.wordAfterCursor(after)
        connection.beginBatchEdit()
        val committed = if (!selected.isNullOrEmpty()) {
            connection.commitText(value, 1)
        } else {
            val deleted = if (prefix.isNotEmpty() || suffix.isNotEmpty()) {
                connection.deleteSurroundingText(prefix.length, suffix.length)
            } else {
                true
            }
            if (deleted) connection.commitText(value, 1) else false
        }
        connection.endBatchEdit()
        return committed
    }

    override fun replaceLastCharacter(value: String): Boolean {
        if (profile.typeNull || value.isEmpty() || !profile.acceptsInput(value)) return false
        val connection = currentInputConnection ?: return false
        val before = connection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (before.isEmpty()) return false
        val start = before.offsetByCodePoints(before.length, -1)
        val last = before.substring(start)
        connection.beginBatchEdit()
        val deleted = connection.deleteSurroundingText(last.length, 0)
        val committed = if (deleted) connection.commitText(value, 1) else false
        connection.endBatchEdit()
        return committed
    }

    override fun deleteBackward(): Boolean {
        val connection = currentInputConnection ?: return false
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) return connection.commitText("", 1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connection.deleteSurroundingTextInCodePoints(1, 0)
        } else {
            connection.deleteSurroundingText(1, 0)
        }
    }

    override fun enter() {
        val connection = currentInputConnection ?: return
        val action = if (profile.hasCustomAction) profile.actionId else profile.action
        val hasAction = action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (!profile.noEnterAction && hasAction && connection.performEditorAction(action)) return
        if (profile.multiline) {
            connection.beginBatchEdit()
            connection.commitText("\n", 1)
            connection.endBatchEdit()
            return
        }
        if (!profile.noEnterAction && action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun localizedContext(): Context {
        val tag = ImeConfig.languageTag(language)
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        val localized = createConfigurationContext(configuration)
        return ContextThemeWrapper(localized, ImeConfig.accentStyle(this))
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun registerVoiceReceiver() {
        if (voiceReceiverRegistered) return
        val filter = IntentFilter(ImeVoiceActivity.ACTION_VOICE_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(voiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(voiceReceiver, filter)
        }
        voiceReceiverRegistered = true
    }

    private fun unregisterVoiceReceiver() {
        if (!voiceReceiverRegistered) return
        try {
            unregisterReceiver(voiceReceiver)
        } catch (_: IllegalArgumentException) {
        }
        voiceReceiverRegistered = false
    }
}

internal object ImeConfig {
    const val DEFAULT_LANGUAGE = AppSettings.LANG_PT

    fun language(context: Context): String {
        val value = read(context, "language") ?: return DEFAULT_LANGUAGE
        return when (value) {
            AppSettings.LANG_EN, AppSettings.LANG_ES, AppSettings.LANG_PT, AppSettings.LANG_PT_PT -> value
            else -> DEFAULT_LANGUAGE
        }
    }

    fun languageTag(language: String): String = when (language) {
        AppSettings.LANG_EN -> "en"
        AppSettings.LANG_ES -> "es"
        AppSettings.LANG_PT_PT -> "pt-PT"
        else -> "pt"
    }

    fun accentStyle(context: Context): Int = when (read(context, "accent")) {
        AppSettings.ACCENT_BLUE -> R.style.Theme_Pulsa_Blue
        AppSettings.ACCENT_GREEN -> R.style.Theme_Pulsa_Green
        AppSettings.ACCENT_AMBER -> R.style.Theme_Pulsa_Amber
        AppSettings.ACCENT_RED -> R.style.Theme_Pulsa_Red
        AppSettings.ACCENT_PINK -> R.style.Theme_Pulsa_Pink
        AppSettings.ACCENT_TEAL -> R.style.Theme_Pulsa_Teal
        else -> R.style.Theme_Pulsa
    }

    private fun read(context: Context, key: String): String? = runCatching {
        val value = LegacyPreferencesFile.read(context, DataStorePreferences.SETTINGS_NAMESPACE)[key]
        (value as? PreferenceValue.Text)?.value
    }.getOrNull()
}
