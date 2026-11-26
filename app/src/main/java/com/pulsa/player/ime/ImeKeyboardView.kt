package com.pulsa.player.ime

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.pulsa.player.R
import java.util.Locale

class ImeKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    interface Host {
        val editorProfile: ImeEditorProfile
        fun requestNextInputMethod()
        fun showInputMethodPicker()
        fun openKeyboardSettings()
        fun startVoiceInput()
        fun pasteClipboard()
        fun inputText(text: String): Boolean
        fun replaceWord(value: String): Boolean
        fun replaceLastCharacter(value: String): Boolean
        fun deleteBackward(): Boolean
        fun enter()
    }

    private var host: Host? = null
    private var layer = ImeLayer.LETTERS
    private var shift = ImeShiftState.OFF
    private var language = "pt"
    private var currentPrefix = ""
    private var backspaceRunnable: Runnable? = null
    private var accentPopup: PopupWindow? = null
    private var shiftKey: ImageView? = null
    private lateinit var rowContainer: LinearLayout
    private lateinit var suggestionContainer: LinearLayout
    private lateinit var toolbarContainer: LinearLayout
    private lateinit var emojiContainer: LinearLayout
    private var emojiVisible = false
    private val punctuation = setOf(".", ",", "?", "!", "'", ":", ";", "(", ")")
    private val textActions = setOf(
        ImeAction.SPACE,
        ImeAction.ENTER,
        ImeAction.LETTERS,
        ImeAction.NUMBERS,
        ImeAction.SYMBOLS,
    )

    init {
        setBackgroundResource(R.drawable.bg_ime_surface)
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        rowContainer = findViewById(R.id.ime_rows)
        suggestionContainer = findViewById(R.id.ime_suggestions)
        toolbarContainer = findViewById(R.id.ime_toolbar)
        emojiContainer = findViewById(R.id.ime_emoji_panel)
    }

    fun bind(host: Host) {
        this.host = host
        render()
    }

    fun setLanguage(value: String) {
        language = value
        render()
    }

    fun applyEditorProfile(profile: ImeEditorProfile) {
        layer = if (profile.number) ImeLayer.NUMBERS else ImeLayer.LETTERS
        shift = when (profile.initialCapsMode) {
            ImeEditorProfile.CAPS_MODE_ALL -> ImeShiftState.LOCKED
            ImeEditorProfile.CAPS_MODE_SENTENCES, ImeEditorProfile.CAPS_MODE_WORDS -> ImeShiftState.ONE_SHOT
            else -> ImeShiftState.OFF
        }
        currentPrefix = ""
        emojiVisible = false
        emojiContainer.visibility = View.GONE
        rowContainer.visibility = View.VISIBLE
        suggestionContainer.visibility = View.VISIBLE
        render()
    }

    fun onStartInput() {
        currentPrefix = ""
        cancelBackspace()
    }

    fun onUpdateSelection(textBeforeCursor: CharSequence?) {
        val prefix = ImeEditorProfile.wordBeforeCursor(textBeforeCursor ?: "")
        if (prefix != currentPrefix) {
            currentPrefix = prefix
            renderSuggestions(prefix)
        }
    }

    fun onShown() {
        render()
    }

    fun setLayer(newLayer: ImeLayer) {
        layer = newLayer
        render()
    }

    fun clearComposing() {
        currentPrefix = ""
    }

    fun onHidden() {
        cancelBackspace()
        dismissAccentPopup()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    override fun onDetachedFromWindow() {
        cancelBackspace()
        dismissAccentPopup()
        super.onDetachedFromWindow()
    }

    private fun render() {
        val activeHost = host ?: return
        if (!::rowContainer.isInitialized) return
        rowContainer.removeAllViews()
        val profile = activeHost.editorProfile
        val rows = ImeKeyboardLayout.rows(language, layer, profile.email, profile.url)
        rows.forEach { keys ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(44),
                )
            }
            keys.forEach { key -> row.addView(createKeyView(key)) }
            rowContainer.addView(row)
        }
        renderToolbar()
        renderSuggestions(currentPrefix)
    }

    private fun renderToolbar() {
        val activeHost = host ?: return
        toolbarContainer.removeAllViews()
        if (!activeHost.editorProfile.sensitive) {
            val clipboard = createIconButton(R.drawable.ic_copy, R.string.ime_paste) {
                activeHost.pasteClipboard()
            }
            toolbarContainer.addView(clipboard)
        }
        val spacer = View(context)
        toolbarContainer.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        val settings = createIconButton(R.drawable.ic_settings, R.string.ime_settings) {
            activeHost.openKeyboardSettings()
        }
        toolbarContainer.addView(settings)
    }

    private fun renderSuggestions(prefix: String) {
        val activeHost = host ?: return
        suggestionContainer.removeAllViews()
        if (activeHost.editorProfile.sensitive || activeHost.editorProfile.noSuggestions || prefix.isBlank()) {
            suggestionContainer.visibility = View.GONE
            return
        }
        val suggestions = ImeSuggestionEngine.suggestions(prefix, language)
        if (suggestions.isEmpty()) {
            suggestionContainer.visibility = View.GONE
            return
        }
        suggestionContainer.visibility = View.VISIBLE
        suggestions.forEach { suggestion ->
            val view = TextView(context).apply {
                text = suggestion
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 14f
                typeface = ResourcesCompat.getFont(context, R.font.outfit_medium)
                setPadding(dp(12), 0, dp(12), 0)
                background = ContextCompat.getDrawable(context, R.drawable.bg_ime_suggestion)
                contentDescription = context.getString(R.string.ime_suggestion, suggestion)
                setOnClickListener {
                    if (activeHost.replaceWord(suggestion)) {
                        currentPrefix = suggestion
                        renderSuggestions(currentPrefix)
                    }
                }
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            params.marginEnd = dp(6)
            suggestionContainer.addView(view, params)
        }
    }

    private fun createKeyView(key: ImeKey): View {
        val activeHost = host ?: return View(context)
        val textKey = key.character != null || key.action in textActions
        val view = if (textKey) {
            TextView(context).apply {
                typeface = ResourcesCompat.getFont(context, R.font.outfit_medium)
                textSize = if (key.character.orEmpty().length > 1) 13f else 20f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
        } else {
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
        }
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, key.weight).apply {
            val horizontal = dp(3)
            marginStart = horizontal
            marginEnd = horizontal
            topMargin = dp(2)
            bottomMargin = dp(2)
        }
        view.layoutParams = params
        view.background = ContextCompat.getDrawable(context, R.drawable.bg_ime_key)
        view.isFocusable = true
        view.isClickable = true
        view.isLongClickable = key.character != null
        when {
            key.character != null -> {
                val character = key.character
                (view as TextView).text = character
                view.contentDescription = character
                view.setOnClickListener {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (character in punctuation) inputPunctuation(character) else inputCharacter(character)
                }
                view.setOnLongClickListener {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showAccentPopup(view, character)
                    true
                }
            }
            key.action == ImeAction.BACKSPACE -> {
                (view as ImageView).setImageResource(R.drawable.ic_ime_backspace)
                view.contentDescription = context.getString(R.string.ime_backspace)
                view.setOnTouchListener { target, event ->
                    val consumed = handleBackspaceTouch(target, event)
                    if (event.actionMasked == MotionEvent.ACTION_UP) target.performClick()
                    consumed
                }
            }
            key.action == ImeAction.SHIFT -> {
                shiftKey = view as ImageView
                updateShiftKey()
                view.contentDescription = context.getString(R.string.ime_shift)
                view.setOnClickListener { cycleShift() }
            }
            key.action == ImeAction.SPACE -> {
                (view as TextView).text = context.getString(R.string.ime_space)
                (view as TextView).setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                view.contentDescription = context.getString(R.string.ime_space)
                view.setOnClickListener { inputSpace() }
            }
            key.action == ImeAction.ENTER -> {
                (view as TextView).text = enterLabel()
                (view as TextView).textSize = 13f
                view.contentDescription = enterLabel()
                view.setOnClickListener { activeHost.enter() }
            }
            key.action == ImeAction.LETTERS -> {
                (view as TextView).text = key.label
                (view as TextView).textSize = 13f
                view.contentDescription = context.getString(R.string.ime_letters)
                view.setOnClickListener { setLayer(ImeLayer.LETTERS) }
            }
            key.action == ImeAction.NUMBERS -> {
                (view as TextView).text = key.label
                (view as TextView).textSize = 13f
                view.contentDescription = context.getString(R.string.ime_numbers)
                view.setOnClickListener { setLayer(ImeLayer.NUMBERS) }
            }
            key.action == ImeAction.SYMBOLS -> {
                (view as TextView).text = key.label
                (view as TextView).textSize = 13f
                view.contentDescription = context.getString(R.string.ime_symbols)
                view.setOnClickListener { setLayer(ImeLayer.SYMBOLS) }
            }
            key.action == ImeAction.GLOBE -> {
                (view as ImageView).setImageResource(R.drawable.ic_ime_globe)
                view.contentDescription = context.getString(R.string.ime_switch_keyboard)
                view.setOnClickListener { activeHost.requestNextInputMethod() }
                view.setOnLongClickListener {
                    activeHost.showInputMethodPicker()
                    true
                }
            }
            key.action == ImeAction.EMOJI -> {
                (view as ImageView).setImageResource(R.drawable.ic_ime_emoji)
                view.contentDescription = context.getString(R.string.ime_emoji)
                view.setOnClickListener { toggleEmoji() }
            }
            key.action == ImeAction.VOICE -> {
                if (activeHost.editorProfile.sensitive) {
                    view.visibility = View.GONE
                } else {
                    (view as ImageView).setImageResource(R.drawable.ic_mic)
                    view.contentDescription = context.getString(R.string.ime_voice)
                    view.setOnClickListener { activeHost.startVoiceInput() }
                }
            }
        }
        view.setOnFocusChangeListener { _, focused ->
            view.alpha = if (focused) 0.78f else 1f
        }
        return view
    }

    private fun createIconButton(icon: Int, description: Int, onClick: () -> Unit): View =
        ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.text_secondary),
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = ContextCompat.getDrawable(context, R.drawable.bg_ime_toolbar)
            contentDescription = context.getString(description)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(36))
        }

    private fun inputCharacter(raw: String) {
        val activeHost = host ?: return
        val character = when (shift) {
            ImeShiftState.OFF -> raw
            else -> raw.uppercase(keyboardLocale())
        }
        if (!activeHost.inputText(character)) return
        if (shift == ImeShiftState.ONE_SHOT) shift = ImeShiftState.OFF
        updateShiftKey()
        currentPrefix += character
        renderSuggestions(currentPrefix)
    }

    private fun inputAccent(character: String) {
        val activeHost = host ?: return
        if (!activeHost.replaceLastCharacter(character)) return
        if (shift == ImeShiftState.ONE_SHOT) shift = ImeShiftState.OFF
        updateShiftKey()
        currentPrefix = currentPrefix.dropLastCodePoint() + character
        renderSuggestions(currentPrefix)
    }

    private fun inputSpace() {
        val activeHost = host ?: return
        if (!activeHost.inputText(" ")) return
        if (shift == ImeShiftState.ONE_SHOT) shift = ImeShiftState.OFF
        updateShiftKey()
        currentPrefix = ""
        renderSuggestions(currentPrefix)
    }

    private fun inputPunctuation(character: String) {
        val activeHost = host ?: return
        if (!activeHost.inputText(character)) return
        if (shift == ImeShiftState.ONE_SHOT) shift = ImeShiftState.OFF
        updateShiftKey()
        currentPrefix = ""
        renderSuggestions(currentPrefix)
    }

    private fun cycleShift() {
        shift = when (shift) {
            ImeShiftState.OFF -> ImeShiftState.ONE_SHOT
            ImeShiftState.ONE_SHOT -> ImeShiftState.LOCKED
            ImeShiftState.LOCKED -> ImeShiftState.OFF
        }
        render()
    }

    private fun updateShiftKey() {
        shiftKey?.setImageResource(
            if (shift == ImeShiftState.LOCKED) R.drawable.ic_ime_shift_locked else R.drawable.ic_ime_shift,
        )
    }

    private fun handleBackspaceTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                deleteBackward()
                scheduleBackspace()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelBackspace()
                return true
            }
        }
        return false
    }

    private fun scheduleBackspace() {
        cancelBackspace()
        val runnable = object : Runnable {
            override fun run() {
                deleteBackward()
                postDelayed(this, 55L)
            }
        }
        backspaceRunnable = runnable
        postDelayed(runnable, 420L)
    }

    private fun cancelBackspace() {
        backspaceRunnable?.let { removeCallbacks(it) }
        backspaceRunnable = null
    }

    private fun deleteBackward() {
        val activeHost = host ?: return
        if (!activeHost.deleteBackward()) return
        currentPrefix = currentPrefix.dropLastCodePoint()
        renderSuggestions(currentPrefix)
    }

    private fun showAccentPopup(anchor: View, character: String) {
        dismissAccentPopup()
        val alternatives = ImeKeyboardLayout.accentAlternatives(
            character,
            shift != ImeShiftState.OFF,
            language,
        )
        if (alternatives.isEmpty()) return
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        alternatives.forEach { alternative ->
            val option = TextView(context).apply {
                text = alternative
                gravity = Gravity.CENTER
                textSize = 20f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                typeface = ResourcesCompat.getFont(context, R.font.outfit_medium)
                background = ContextCompat.getDrawable(context, R.drawable.bg_ime_key)
                contentDescription = alternative
                setOnClickListener {
                    inputAccent(alternative)
                    dismissAccentPopup()
                }
            }
            row.addView(option, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val popup = PopupWindow(
            row,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = dp(12).toFloat()
            setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_ime_popup))
        }
        accentPopup = popup
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        popup.showAtLocation(this, Gravity.NO_GRAVITY, location[0], location[1] - dp(52))
    }

    private fun dismissAccentPopup() {
        accentPopup?.dismiss()
        accentPopup = null
    }

    private fun toggleEmoji() {
        emojiVisible = !emojiVisible
        emojiContainer.visibility = if (emojiVisible) View.VISIBLE else View.GONE
        rowContainer.visibility = if (emojiVisible) View.GONE else View.VISIBLE
        if (emojiVisible) {
            suggestionContainer.visibility = View.GONE
            renderEmoji()
        } else {
            emojiContainer.removeAllViews()
            renderSuggestions(currentPrefix)
        }
    }

    private fun renderEmoji() {
        val activeHost = host ?: return
        emojiContainer.removeAllViews()
        val emojis = listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🙂", "😉", "😊", "😍", "🥰",
            "😘", "😋", "😎", "🤩", "🥳", "🤔", "🤨", "😐", "😴", "😢", "😭", "😡",
            "👍", "👎", "👏", "🙌", "🤝", "🙏", "💪", "👋", "✌️", "🤞", "👌", "🤟",
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💔", "💯", "✨", "🔥",
            "🎉", "🎊", "🎁", "🎵", "🎶", "⭐", "🌟", "⚡", "☀️", "🌙", "☁️", "🌈",
        )
        emojis.chunked(12).forEach { rowEmojis ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(40),
                )
            }
            rowEmojis.forEach { emoji ->
                val view = TextView(context).apply {
                    text = emoji
                    gravity = Gravity.CENTER
                    textSize = 22f
                    background = ContextCompat.getDrawable(context, R.drawable.bg_ime_key)
                    contentDescription = emoji
                    setOnClickListener {
                        if (activeHost.inputText(emoji)) {
                            if (shift == ImeShiftState.ONE_SHOT) shift = ImeShiftState.OFF
                            updateShiftKey()
                            currentPrefix = ""
                            renderSuggestions(currentPrefix)
                        }
                    }
                }
                row.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            emojiContainer.addView(row)
        }
    }

    private fun enterLabel(): String {
        val activeHost = host ?: return context.getString(R.string.ime_enter_newline)
        val custom = activeHost.editorProfile.actionLabel
        if (!custom.isNullOrBlank()) return custom
        return context.getString(
            when (activeHost.editorProfile.action) {
                EditorInfo.IME_ACTION_SEARCH -> R.string.ime_enter_search
                EditorInfo.IME_ACTION_SEND -> R.string.ime_enter_send
                EditorInfo.IME_ACTION_GO -> R.string.ime_enter_go
                EditorInfo.IME_ACTION_NEXT -> R.string.ime_enter_next
                EditorInfo.IME_ACTION_PREVIOUS -> R.string.ime_enter_previous
                EditorInfo.IME_ACTION_DONE -> R.string.ime_enter_done
                else -> R.string.ime_enter_newline
            },
        )
    }

    private fun keyboardLocale(): Locale = when {
        language.startsWith("en", ignoreCase = true) -> Locale.ENGLISH
        language.startsWith("es", ignoreCase = true) -> Locale("es")
        language.equals("pt-PT", ignoreCase = true) -> Locale("pt", "PT")
        else -> Locale("pt", "BR")
    }

    private fun String.dropLastCodePoint(): String {
        if (isEmpty()) return this
        val start = offsetByCodePoints(length, -1)
        return substring(0, start)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
