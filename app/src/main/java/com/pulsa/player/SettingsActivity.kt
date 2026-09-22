package com.pulsa.player
import com.pulsa.player.util.UpdateChecker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.pulsa.player.dj.DjLearn
import com.pulsa.player.dj.DjMemory
import com.pulsa.player.playback.Playback
import com.pulsa.player.core.Account
import com.pulsa.player.ui.AnimatedBackground
import com.pulsa.player.core.Changelog
import com.pulsa.player.core.Helper
import com.pulsa.player.sync.MirrorSync
import com.pulsa.player.media.MusicDownloader
import com.pulsa.player.core.Settings
import com.pulsa.player.sync.Telemetry
import com.pulsa.player.core.ThreadPool
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private val backupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(buildBackup().toByteArray()) }
                Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
            }
        }

    private val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                val text = contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: return@registerForActivityResult
                val applied = applyRestore(text)
                Toast.makeText(
                    this,
                    if (applied) R.string.restore_done else R.string.restore_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show()
            }
        }

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
        findViewById<View>(R.id.mirror_row).setOnClickListener { mirrorMenu() }
        refreshMirrorLabel()
        findViewById<View>(R.id.server_row).setOnClickListener { serverDialog() }
        refreshServerLabel()
        findViewById<View>(R.id.backup_row).setOnClickListener {
            backupLauncher.launch("pulsa-backup.json")
        }
        findViewById<View>(R.id.restore_row).setOnClickListener {
            restoreLauncher.launch(arrayOf("application/json"))
        }

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

    private fun mirrorMenu() {
        val activeCode = Settings.mirrorCode(this)
        val options = if (activeCode.isBlank()) {
            arrayOf(getString(R.string.mirror_create), getString(R.string.mirror_join))
        } else {
            arrayOf(
                getString(R.string.mirror_create),
                getString(R.string.mirror_join),
                getString(R.string.mirror_leave, activeCode)
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mirror_title)
            .setItems(options) { _, which ->
                when {
                    which == 0 -> createSession()
                    activeCode.isBlank() || which == 1 -> joinSessionDialog()
                    else -> leaveSession()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun createSession() {
        ThreadPool.post {
            val res = sessionCall("""{"create":true}""")
            runOnUiThread {
                val code = runCatching { JSONObject(res) }.getOrNull()?.optString("code")
                if (code.isNullOrBlank()) {
                    Toast.makeText(this, R.string.mirror_error, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Settings.setMirrorCode(this, code)
                Settings.setMirrorHost(this, true)
                MirrorSync.start(this)
                refreshMirrorLabel()
                shareCode(code)
            }
        }
    }

    private fun joinSessionDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.mirror_code_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mirror_join)
            .setView(input)
            .setPositiveButton(R.string.mirror_join) { _, _ ->
                val code = input.text.toString().trim().uppercase()
                if (code.length < 3) {
                    Toast.makeText(this, R.string.mirror_code_hint, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ThreadPool.post {
                    val res = sessionGet(code)
                    val ok = runCatching { JSONObject(res) }.getOrNull()?.optBoolean("ok", false) == true
                    runOnUiThread {
                        if (!ok) {
                            Toast.makeText(this, R.string.mirror_error, Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                        Settings.setMirrorCode(this, code)
                        Settings.setMirrorHost(this, true)
                        MirrorSync.start(this)
                        refreshMirrorLabel()
                        Toast.makeText(this, getString(R.string.mirror_joined, code), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun leaveSession() {
        ThreadPool.post {
            sessionCall("""{"leave":true}""")
            Settings.setMirrorCode(this@SettingsActivity, "")
            Settings.setMirrorHost(this@SettingsActivity, false)
            MirrorSync.stop()
            runOnUiThread {
                refreshMirrorLabel()
                Toast.makeText(this@SettingsActivity, R.string.mirror_left, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareCode(code: String) {
        val text = getString(R.string.mirror_share, code)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                getString(R.string.mirror_share_title)
            )
        )
    }

    private fun refreshMirrorLabel() {
        val label = findViewById<TextView>(R.id.mirror_value)
        val code = Settings.mirrorCode(this)
        if (code.isBlank()) {
            label.text = getString(R.string.mirror_subtitle)
        } else {
            val role = if (Settings.mirrorHost(this)) getString(R.string.mirror_role_host)
            else getString(R.string.mirror_role_guest)
            label.text = getString(R.string.mirror_active, code, role)
        }
    }

    private fun serverDialog() {
        refreshServerLabel()
        val mode = Settings.serverMode(this)
        val input = EditText(this).apply {
            hint = getString(R.string.server_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            if (mode == "custom") setText(Settings.serverBase(this@SettingsActivity))
        }
        val rbLan = RadioButton(this).apply { text = getString(R.string.server_opt_lan) }
        val rbOnline = RadioButton(this).apply { text = getString(R.string.server_opt_online) }
        val rbCustom = RadioButton(this).apply { text = getString(R.string.server_opt_custom) }
        val radio = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(rbLan)
            addView(rbOnline)
            addView(rbCustom)
        }
        when (mode) {
            "online" -> rbOnline.isChecked = true
            "custom" -> rbCustom.isChecked = true
            else -> rbLan.isChecked = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(radio)
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * resources.displayMetrics.density).toInt() }
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.server_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val chosen = when {
                    rbOnline.isChecked -> Settings.SERVER_ONLINE
                    rbCustom.isChecked -> input.text.toString().trim()
                    else -> Settings.SERVER_LAN
                }
                Settings.setServerBase(this, chosen)
                refreshServerLabel()
                Toast.makeText(this, R.string.server_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshServerLabel() {
        val label = findViewById<TextView>(R.id.server_value)
        label.text = when (Settings.serverMode(this)) {
            "online" -> getString(R.string.server_online)
            "custom" -> Settings.serverBase(this)
            else -> getString(R.string.server_default)
        }
    }

    private fun sessionCall(body: String): String {
        val device = Settings.deviceId(this)
        for (base in sessionHosts()) {
            try {
                val conn = URL("$base/session").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 2000
                conn.readTimeout = 2500
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("X-Pulsa-Device", device)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.toByteArray().size)
                conn.outputStream.use { it.write(body.toByteArray()) }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.inputStream.close()
                return text
            } catch (t: Throwable) {
            }
        }
        return ""
    }

    private fun sessionGet(code: String): String {
        val device = Settings.deviceId(this)
        for (base in sessionHosts()) {
            try {
                val conn = URL("$base/session?code=${java.net.URLEncoder.encode(code, "UTF-8")}")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 2000
                conn.readTimeout = 2500
                conn.setRequestProperty("X-Pulsa-Device", device)
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.inputStream.close()
                return text
            } catch (t: Throwable) {
            }
        }
        return ""
    }

    private fun sessionHosts(): List<String> = Settings.serverCandidates(this)

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

    private fun buildBackup(): String {
        val learn = DjLearn.snapshot(applicationContext)
        val memoryFacts = DjMemory.exportFacts(applicationContext)
        val p = Settings.dataPrefs(applicationContext)
        val eq = JSONObject().apply {
            put("equalizer_on", p.getBoolean("equalizer_on", false))
            put("custom_eq_on", p.getBoolean("custom_eq_on", false))
            put("custom_eq_bands", p.getString("custom_eq_bands", "0,0,0,0,0"))
        }
        return JSONObject().apply {
            put("app", "pulsa")
            put("backupVersion", 1)
            put("learn", learn)
            put("memoryFacts", memoryFacts)
            put("eq", eq)
        }.toString()
    }

    private fun applyRestore(text: String): Boolean {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (root.optString("app") != "pulsa") return false
        var changed = false
        root.optJSONObject("learn")?.let {
            changed = DjLearn.mergeRemote(applicationContext, it) || changed
        }
        val facts = root.optString("memoryFacts", "")
        if (facts.isNotBlank()) {
            changed = DjMemory.importFacts(applicationContext, facts) || changed
        }
        root.optJSONObject("eq")?.let { eq ->
            Settings.dataPrefs(applicationContext).edit()
                .putBoolean("equalizer_on", eq.optBoolean("equalizer_on", false))
                .putBoolean("custom_eq_on", eq.optBoolean("custom_eq_on", false))
                .putString("custom_eq_bands", eq.optString("custom_eq_bands", "0,0,0,0,0"))
                .apply()
            changed = true
        }
        return changed
    }
}
