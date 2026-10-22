package com.pulsa.player.ui.settings

import android.view.View
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.SettingsActivity
import com.pulsa.player.core.Settings

/**
 * Aparência — rótulos e diálogos de seleção (cor de destaque, idioma, crossfade).
 */

internal fun SettingsActivity.accentLabel(): String {
    return getString(Settings.accentLabelRes(Settings.accent(this)))
}

internal fun SettingsActivity.languageLabel(): String {
    return getString(Settings.languageLabelRes(Settings.language(this)))
}

internal fun SettingsActivity.crossfadeLabel(): String {
    return getString(Settings.crossfadeLabelRes(Settings.crossfade(this)))
}

internal fun SettingsActivity.pickCrossfade() {
    val keys = arrayOf(
        Settings.CROSSFADE_OFF,
        Settings.CROSSFADE_SHORT,
        Settings.CROSSFADE_MEDIUM,
        Settings.CROSSFADE_LONG
    )
    val names = keys.map { getString(Settings.crossfadeLabelRes(it)) }.toTypedArray()
    val current = keys.indexOf(Settings.crossfade(this))
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.pick_crossfade)
        .setSingleChoiceItems(names, current) { dialog, which ->
            Settings.setCrossfade(this, keys[which])
            findViewById<TextView>(R.id.crossfade_value)?.text = crossfadeLabel()
            dialog.dismiss()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.pickLanguage() {
    val keys = arrayOf(
        Settings.LANG_PT,
        Settings.LANG_PT_PT,
        Settings.LANG_EN,
        Settings.LANG_ES
    )
    val names = keys.map { getString(Settings.languageLabelRes(it)) }.toTypedArray()
    val current = keys.indexOf(Settings.language(this))
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.pick_language)
        .setSingleChoiceItems(names, current) { dialog, which ->
            dialog.dismiss()
            val chosen = keys[which]
            if (chosen == Settings.language(this)) return@setSingleChoiceItems
            Settings.setLanguage(this, chosen)
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(Settings.languageTag(chosen))
            )
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.pickAccent() {
    val keys = arrayOf(
        Settings.ACCENT_PURPLE,
        Settings.ACCENT_BLUE,
        Settings.ACCENT_GREEN,
        Settings.ACCENT_AMBER,
        Settings.ACCENT_RED,
        Settings.ACCENT_PINK,
        Settings.ACCENT_TEAL
    )
    val names = keys.map { getString(Settings.accentLabelRes(it)) }.toTypedArray()
    val current = keys.indexOf(Settings.accent(this))
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.pick_accent)
        .setSingleChoiceItems(names, current) { dialog, which ->
            Settings.setAccent(this, keys[which])
            dialog.dismiss()
            recreate()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

