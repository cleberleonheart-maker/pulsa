package com.pulsa.player.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

data class ImeEditorProfile(
    val sensitive: Boolean,
    val multiline: Boolean,
    val email: Boolean,
    val url: Boolean,
    val number: Boolean,
    val strictNumber: Boolean,
    val numberSigned: Boolean,
    val numberDecimal: Boolean,
    val noSuggestions: Boolean,
    val noEnterAction: Boolean,
    val typeNull: Boolean,
    val actionId: Int,
    val action: Int,
    val actionLabel: String?,
    val initialCapsMode: Int,
) {
    val hasCustomAction: Boolean get() = actionId != 0

    fun acceptsInput(text: String): Boolean = when {
        strictNumber -> text.all { character ->
            character.isDigit() ||
                (numberSigned && (character == '+' || character == '-')) ||
                (numberDecimal && character == '.')
        }
        number -> text.all { character -> character.isDigit() || character in "+#*() -" }
        else -> true
    }

    companion object {
        const val CAPS_MODE_NONE = 0
        const val CAPS_MODE_SENTENCES = 0x1000
        const val CAPS_MODE_WORDS = 0x2000
        const val CAPS_MODE_ALL = 0x3000

        fun from(info: EditorInfo?): ImeEditorProfile {
            val rawType = info?.inputType ?: InputType.TYPE_CLASS_TEXT
            val textClass = rawType and InputType.TYPE_MASK_CLASS
            val variation = rawType and InputType.TYPE_MASK_VARIATION
            val sensitive = when (textClass) {
                InputType.TYPE_CLASS_TEXT -> variation in setOf(
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                )
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
            val strictNumber = textClass == InputType.TYPE_CLASS_NUMBER
            return ImeEditorProfile(
                sensitive = sensitive,
                multiline = textClass == InputType.TYPE_CLASS_TEXT &&
                    rawType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0,
                email = textClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                ),
                url = textClass == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI,
                number = strictNumber || textClass == InputType.TYPE_CLASS_PHONE,
                strictNumber = strictNumber,
                numberSigned = strictNumber && rawType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0,
                numberDecimal = strictNumber && rawType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0,
                noSuggestions = rawType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0,
                noEnterAction = (info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0,
                typeNull = rawType == InputType.TYPE_NULL,
                actionId = info?.actionId ?: 0,
                action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                    ?: EditorInfo.IME_ACTION_UNSPECIFIED,
                actionLabel = info?.actionLabel?.toString()?.takeIf { it.isNotBlank() },
                initialCapsMode = info?.initialCapsMode ?: 0,
            )
        }

        fun wordBeforeCursor(text: CharSequence): String {
            val value = text.toString()
            var start = value.length
            while (start > 0) {
                val codePoint = value.codePointBefore(start)
                if (!isWordCodePoint(codePoint)) break
                start = value.offsetByCodePoints(start, -1)
            }
            return value.substring(start)
        }

        fun wordAfterCursor(text: CharSequence): String {
            val value = text.toString()
            var end = 0
            while (end < value.length) {
                val codePoint = value.codePointAt(end)
                if (!isWordCodePoint(codePoint)) break
                end += Character.charCount(codePoint)
            }
            return value.substring(0, end)
        }

        private fun isWordCodePoint(codePoint: Int): Boolean =
            Character.isLetterOrDigit(codePoint) ||
                codePoint == '\''.code || codePoint == '-'.code
    }
}
