package com.pulsa.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.pulsa.player.playback.Playback
import com.pulsa.player.util.Account
import com.pulsa.player.util.AnimatedBackground
import com.pulsa.player.util.Changelog
import com.pulsa.player.util.Helper
import com.pulsa.player.util.MusicDownloader
import com.pulsa.player.util.Settings
import com.pulsa.player.util.UpdateChecker

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.btn_settings_back).setOnClickListener { finish() }
        AnimatedBackground.apply(this)

        findViewById<MaterialSwitch>(R.id.bg_switch).apply {
            isChecked = Settings.animatedBg(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setAnimatedBg(this@SettingsActivity, checked)
                AnimatedBackground.apply(this@SettingsActivity)
            }
        }

        findViewById<MaterialSwitch>(R.id.stars_switch).apply {
            isChecked = Settings.starsOn(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setStarsOn(this@SettingsActivity, checked)
                AnimatedBackground.apply(this@SettingsActivity)
            }
        }

        findViewById<TextView>(R.id.accent_value).text = accentLabel()
        findViewById<View>(R.id.accent_row).setOnClickListener { pickAccent() }

        findViewById<TextView>(R.id.language_value).text = languageLabel()
        findViewById<View>(R.id.language_row).setOnClickListener { pickLanguage() }

        findViewById<TextView>(R.id.quality_value).text = qualityLabel()
        findViewById<View>(R.id.quality_row).setOnClickListener { pickQuality() }

        findViewById<TextView>(R.id.crossfade_value).text = crossfadeLabel()
        findViewById<View>(R.id.crossfade_row).setOnClickListener { pickCrossfade() }

        findViewById<MaterialSwitch>(R.id.eq_switch).apply {
            isChecked = Settings.equalizerOn(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setEqualizerOn(this@SettingsActivity, checked)
                Playback.service?.refreshFx()
            }
        }

        findViewById<MaterialSwitch>(R.id.audio_8d_switch).apply {
            isChecked = Settings.audio8d(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setAudio8d(this@SettingsActivity, checked)
                Playback.service?.refreshFx()
            }
        }

        findViewById<MaterialSwitch>(R.id.dj_radio_switch).apply {
            isChecked = Settings.djRadio(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setDjRadio(this@SettingsActivity, checked)
            }
        }

        refreshRecToken()
        findViewById<View>(R.id.rec_token_row).setOnClickListener { promptRecToken() }

        findViewById<MaterialButton>(R.id.btn_download).setOnClickListener { startDownload() }

        findViewById<MaterialButton>(R.id.btn_update_site).setOnClickListener {
            UpdateChecker.downloadFromSite(this)
        }

        val cacheValue = findViewById<TextView>(R.id.cache_value)
        updateCacheLabel(cacheValue)
        findViewById<View>(R.id.cache_row).setOnClickListener { clearCache(cacheValue) }

        findViewById<View>(R.id.logout_row).setOnClickListener { confirmLogout() }

        findViewById<TextView>(R.id.settings_version).apply {
            text =
                getString(R.string.app_version, packageManager.getPackageInfo(packageName, 0).versionName)
            setOnClickListener { Changelog.show(this@SettingsActivity) }
        }
    }

    override fun onStart() {
        super.onStart()
        AnimatedBackground.apply(this)
    }

    override fun onStop() {
        AnimatedBackground.stop()
        super.onStop()
    }

    private fun accentLabel(): String {
        return getString(Settings.accentLabelRes(Settings.accent(this)))
    }

    private fun languageLabel(): String {
        return getString(Settings.languageLabelRes(Settings.language(this)))
    }

    private fun qualityLabel(): String {
        return getString(Settings.qualityLabelRes(Settings.audioQuality(this)))
    }

    private fun crossfadeLabel(): String {
        return getString(Settings.crossfadeLabelRes(Settings.crossfade(this)))
    }

    private fun pickCrossfade() {
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
                findViewById<TextView>(R.id.crossfade_value).text = crossfadeLabel()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout)
            .setMessage(R.string.logout_confirm)
            .setPositiveButton(R.string.logout) { d, _ ->
                d.dismiss()
                Account.setLoggedIn(this, false)
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickLanguage() {
        val keys = arrayOf(
            Settings.LANG_PT,
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

    private fun pickAccent() {
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

    private fun pickQuality() {
        val keys = arrayOf(
            Settings.QUALITY_AUTO,
            Settings.QUALITY_DEFAULT,
            Settings.QUALITY_BASS,
            Settings.QUALITY_VOICES,
            Settings.QUALITY_TREBLE,
            Settings.QUALITY_ROCK,
            Settings.QUALITY_DANCE,
            Settings.QUALITY_POP,
            Settings.QUALITY_JAZZ,
            Settings.QUALITY_ACOUSTIC,
            Settings.QUALITY_CLASSICAL,
            Settings.QUALITY_LOUDNESS
        )
        val names = keys.map { getString(Settings.qualityLabelRes(it)) }.toTypedArray()
        val current = keys.indexOf(Settings.audioQuality(this))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_quality)
            .setSingleChoiceItems(names, current) { dialog, which ->
                Settings.setAudioQuality(this, keys[which])
                Playback.service?.refreshFx()
                Playback.service?.applyDanceParamsForRefresh()
                findViewById<TextView>(R.id.quality_value).text = qualityLabel()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startDownload() {
        val url = findViewById<EditText>(R.id.download_url).text.toString().trim()
        val name = findViewById<EditText>(R.id.download_name).text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, R.string.download_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        if (urlLooksLikePage(url)) {
            Toast.makeText(this, R.string.download_direct_only, Toast.LENGTH_LONG).show()
            return
        }
        val finalName = name.ifEmpty { guessName(url) }
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        MusicDownloader.download(this, url, finalName) { _, message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun guessName(url: String): String {
        val segment = url.substringBefore('?').substringAfterLast('/')
        return segment.substringBeforeLast('.', segment).ifEmpty { "música" }
    }

    private fun urlLooksLikePage(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("youtube.com") || lower.contains("youtu.be") ||
            lower.contains("spotify.com") || lower.contains("deezer.com") ||
            lower.contains("soundcloud.com") || lower.contains("tidal.com") ||
            lower.contains("apple.com") || lower.contains("music.youtube.com")
        ) return true
        val path = lower.substringBefore('?').substringAfter("://").substringAfter('/')
        if (path.startsWith("watch") || path.startsWith("track") || path.startsWith("album") ||
            path.startsWith("playlist") || path.startsWith("shorts") || path.startsWith("embed")
        ) return true
        return false
    }

    private fun refreshRecToken() {
        val token = Settings.recToken(this)
        findViewById<TextView>(R.id.rec_token_value).text =
            if (token.isBlank()) getString(R.string.rec_token_missing)
            else getString(R.string.rec_token_saved)
    }

    private fun promptRecToken() {
        val input = EditText(this).apply {
            setText(Settings.recToken(this@SettingsActivity))
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

    private fun updateCacheLabel(view: TextView) {
        view.text = getString(R.string.cache_size, Helper.formatBytes(Settings.cacheSize(this)))
    }

    private fun clearCache(valueView: TextView) {
        val size = Settings.cacheSize(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cache_title)
            .setMessage(getString(R.string.cache_confirm, Helper.formatBytes(size)))
            .setPositiveButton(R.string.delete) { d, _ ->
                d.dismiss()
                val freed = Settings.clearCache(this)
                Toast.makeText(
                    this,
                    getString(R.string.cache_cleared, Helper.formatBytes(freed)),
                    Toast.LENGTH_SHORT
                ).show()
                updateCacheLabel(valueView)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}