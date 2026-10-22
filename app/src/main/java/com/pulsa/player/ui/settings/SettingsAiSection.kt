package com.pulsa.player.ui.settings

import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.SettingsActivity
import com.pulsa.player.core.Settings
import com.pulsa.player.dj.DjSuggest

/**
 * DJ / IA — chave Gemini e token de reconhecimento.
 */

internal fun SettingsActivity.refreshGeminiKey() {
    val key = Settings.geminiKey(this)
    findViewById<TextView>(R.id.gemini_key_value)?.text =
        if (key.isBlank()) getString(R.string.gemini_key_missing)
        else getString(R.string.gemini_key_saved)
}

internal fun SettingsActivity.promptGeminiKey() {
    val input = EditText(this).apply {
        setText(Settings.geminiKey(self))
        hint = getString(R.string.gemini_key_hint)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        maxLines = 1
        isSingleLine = true
        setPadding(48, 16, 48, 16)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.gemini_key_row)
        .setMessage(R.string.gemini_key_subtitle)
        .setView(input)
        .setPositiveButton(R.string.save) { _, _ ->
            val key = input.text.toString().trim()
            if (key != Settings.geminiKey(this)) DjSuggest.resetQuotaCooldown()
            Settings.setGeminiKey(this, key)
            Settings.setGeminiOn(this, key.isNotBlank())
            refreshGeminiKey()
            Toast.makeText(this, R.string.gemini_key_saved, Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.refreshRecToken() {
    val token = Settings.recToken(this)
    findViewById<TextView>(R.id.rec_token_value)?.text =
        if (token.isBlank()) getString(R.string.rec_token_missing)
        else getString(R.string.rec_token_saved)
}

internal fun SettingsActivity.promptRecToken() {
    val input = EditText(this).apply {
        setText(Settings.recToken(self))
        hint = getString(R.string.rec_token_hint)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        maxLines = 1
        isSingleLine = true
        setPadding(48, 16, 48, 16)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.rec_token)
        .setMessage(R.string.rec_token_subtitle)
        .setView(input)
        .setPositiveButton(R.string.save) { _, _ ->
            val token = input.text.toString().trim()
            Settings.setRecToken(this, token)
            refreshRecToken()
            Toast.makeText(this, R.string.rec_token_saved, Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

