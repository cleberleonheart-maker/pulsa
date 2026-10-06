package com.pulsa.player
import com.pulsa.player.util.UpdateChecker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.pulsa.player.dj.DjLearn
import com.pulsa.player.dj.DjMemory
import com.pulsa.player.dj.Hotword
import com.pulsa.player.playback.Playback
import com.pulsa.player.core.Account
import com.pulsa.player.ui.AnimatedBackground
import com.pulsa.player.core.Changelog
import com.pulsa.player.core.Helper
import com.pulsa.player.sync.MirrorSync
import com.pulsa.player.work.PulsaWork
import com.pulsa.player.core.Settings
import com.pulsa.player.sync.Telemetry
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.PlaylistBackup
import com.pulsa.player.data.PlaylistDb
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private val backupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            // Fora da main: `PlaylistBackup.export` varre a biblioteca uma vez para traduzir os
            // ids das correções de nome em caminho, e isso é uma consulta ao MediaStore. Com a
            // biblioteca grande, fazer isso no callback do launcher trava a tela.
            ThreadPool.post {
                val json = runCatching { buildBackup().toString() }.getOrElse {
                    ThreadPool.onUi {
                        Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@post
                }
                ThreadPool.onUi {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    private val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            // Fora da main pelo mesmo motivo do backup: resolver caminho → faixa é MediaStore.
            // O restore inteiro vai junto, para o trabalho antigo (que era rápido e síncrono)
            // não ficar metade na main e metade fora — um `Playback.refreshFx()` disparado no
            // meio do restore mostraria o som antigo enquanto a biblioteca já mudou.
            ThreadPool.post {
                val applied = runCatching {
                    val text = contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: return@post
                    applyRestore(text)
                }.getOrNull() ?: return@post
                ThreadPool.onUi {
                    if (applied.settingsChanged) Playback.refreshFx()
                    showRestoreReport(applied.report)
                }
            }
        }

    // Mãos-livres precisa do microfone; se negado, o switch volta desligado.
    private val hotwordLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val sw = findViewById<MaterialSwitch>(R.id.hotword_switch)
            sw.isChecked = granted
            Settings.setHotword(this, granted)
            if (granted) Hotword.startIfNeeded(this) else Hotword.stopIfRunning(this)
            if (!granted) {
                Toast.makeText(this, R.string.hands_free_no_mic, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.btn_settings_back).setOnClickListener { finish() }

        findViewById<MaterialSwitch>(R.id.bg_switch).apply {
            isChecked = Settings.animatedBg(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setAnimatedBg(this@SettingsActivity, checked)
            }
        }

        findViewById<MaterialSwitch>(R.id.stars_switch).apply {
            isChecked = Settings.starsOn(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setStarsOn(this@SettingsActivity, checked)
            }
        }

        findViewById<TextView>(R.id.accent_value)?.text = accentLabel()
        findViewById<View>(R.id.accent_row).setOnClickListener { pickAccent() }

        findViewById<TextView>(R.id.language_value)?.text = languageLabel()
        findViewById<View>(R.id.language_row).setOnClickListener { pickLanguage() }

        findViewById<TextView>(R.id.quality_value)?.text = qualityLabel()
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

        findViewById<TextView>(R.id.crossfade_value)?.text = crossfadeLabel()
        findViewById<View>(R.id.crossfade_row).setOnClickListener { pickCrossfade() }

        findViewById<MaterialSwitch>(R.id.eq_switch).apply {
            isChecked = Settings.equalizerOn(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setEqualizerOn(this@SettingsActivity, checked)
                Playback.refreshFx()
            }
        }

        findViewById<MaterialSwitch>(R.id.audio_8d_switch).apply {
            isChecked = Settings.audio8d(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setAudio8d(this@SettingsActivity, checked)
                Playback.refreshFx()
            }
        }

        // 3D e surround: os dois processam o mesmo par (L, R) dentro do `SpatialAudio`, então
        // cada um tem o seu botão e o seu ajuste de intensidade. Os sliders só aparecem com o
        // efeito ligado — slider de efeito desligado é ajuste de nada.
        findViewById<MaterialSwitch>(R.id.audio_3d_switch).apply {
            isChecked = Settings.spatial3d(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setSpatial3d(this@SettingsActivity, checked)
                showSpatialRows()
                Playback.refreshFx()
            }
        }

        findViewById<MaterialSwitch>(R.id.surround_switch).apply {
            isChecked = Settings.surround(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setSurround(this@SettingsActivity, checked)
                showSpatialRows()
                Playback.refreshFx()
            }
        }

        findViewById<SeekBar>(R.id.audio_3d_depth_seek).apply {
            progress = Settings.spatial3dDepth(this@SettingsActivity)
            findViewById<TextView>(R.id.audio_3d_depth_value)?.text =
                getString(R.string.spatial_level, progress)
            setOnSeekBarChangeListener(sliderPersist(
                R.id.audio_3d_depth_value,
                onChanged = { Settings.setSpatial3dDepth(this@SettingsActivity, it) },
                onDone = { Playback.refreshFx() }
            ))
        }

        findViewById<SeekBar>(R.id.surround_intensity_seek).apply {
            progress = Settings.surroundIntensity(this@SettingsActivity)
            findViewById<TextView>(R.id.surround_intensity_value)?.text =
                getString(R.string.spatial_level, progress)
            setOnSeekBarChangeListener(sliderPersist(
                R.id.surround_intensity_value,
                onChanged = { Settings.setSurroundIntensity(this@SettingsActivity, it) },
                onDone = { Playback.refreshFx() }
            ))
        }

        showSpatialRows()

        findViewById<MaterialSwitch>(R.id.dj_radio_switch).apply {
            isChecked = Settings.djRadio(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setDjRadio(this@SettingsActivity, checked)
            }
        }

        // Gestos (shake/inclinacao): o interruptor nao existia, entao `Settings.gesturesOn`
        // ficava sempre false e o recurso era inalcancavel. Aqui so grava a escolha: quem
        // registra o sensor e a MainActivity, no onResume, via `attachIfEnabled` — assim o
        // callback do shake nao e sobrescrito por uma lambda vazia enquanto a tela de Ajustes
        // esta aberta.
        findViewById<MaterialSwitch>(R.id.gestures_switch).apply {
            isChecked = Settings.gesturesOn(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setGesturesOn(this@SettingsActivity, checked)
            }
        }

        findViewById<MaterialSwitch>(R.id.hotword_switch).apply {
            isChecked = Settings.hotword(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@SettingsActivity, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        hotwordLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        Settings.setHotword(this@SettingsActivity, true)
                        Hotword.startIfNeeded(this@SettingsActivity)
                    }
                } else {
                    Settings.setHotword(this@SettingsActivity, false)
                    Hotword.stopIfRunning(this@SettingsActivity)
                }
            }
        }

        refreshRecToken()
        findViewById<View>(R.id.rec_token_row).setOnClickListener { promptRecToken() }
        findViewById<View>(R.id.gemini_row).setOnClickListener { promptGeminiKey() }
        refreshGeminiKey()

        findViewById<MaterialButton>(R.id.btn_download).setOnClickListener { startDownload() }

        findViewById<MaterialButton>(R.id.btn_update_site).setOnClickListener {
            UpdateChecker.downloadFromSite(this)
        }

        val cacheValue = viewOrNull<TextView>(R.id.cache_value)
        updateCacheLabel(cacheValue)
        findViewById<View>(R.id.cache_row).setOnClickListener { clearCache(cacheValue) }

        findViewById<View>(R.id.logout_row).setOnClickListener { confirmLogout() }

        viewOrNull<TextView>(R.id.settings_version)?.apply {
            text =
                getString(R.string.app_version, packageManager.getPackageInfo(packageName, 0).versionName)
            setOnClickListener { Changelog.show(this@SettingsActivity) }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        window.decorView.setBackgroundResource(R.drawable.bg_aurora)
        AnimatedBackground.stop()
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

    /**
     * Busca segura: devolve `null` em vez de estourar quando o id some do layout.
     *
     * `findViewById` devolve tipo plataforma (`TextView!`) e o Kotlin só insere o check-null no
     * ponto do uso, então a falta do id estourava como NPE em `TextView.setText` — longe da
     * causa. Com o id no log dá para ver o que falta em vez de adivinhar pela linha do
     * stack trace.
     */
    private inline fun <reified T : View> viewOrNull(id: Int): T? {
        val found = findViewById<T>(id)
        if (found == null) {
            Log.e(TAG, "view ausente no layout: " + resources.getResourceEntryName(id))
        }
        return found
    }

    /**
     * Slider de intensidade: grava na hora, mas só avisa o playback no fim do arrasto.
     *
     * O `SpatialAudio` lê os parâmetros a cada buffer, então o efeito acompanha o dedo sem
     * precisar de nada do playback; o que precisa é persistir a escolha e reler os ajustes
     * quando o dedo sai. Gravar a cada pixel seria uma escrita por frame do dedo.
     */
    private fun sliderPersist(
        labelId: Int,
        onChanged: (Int) -> Unit,
        onDone: () -> Unit
    ): SeekBar.OnSeekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        private val label: TextView? by lazy { viewOrNull<TextView>(labelId) }

        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            onChanged(progress)
            label?.text = getString(R.string.spatial_level, progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}

        override fun onStopTrackingTouch(seekBar: SeekBar?) = onDone()
    }

    /** Esconde o slider de cada efeito desligado. */
    private fun showSpatialRows() {
        findViewById<View>(R.id.audio_3d_depth_row).visibility =
            if (Settings.spatial3d(this)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.surround_intensity_row).visibility =
            if (Settings.surround(this)) View.VISIBLE else View.GONE
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
                findViewById<TextView>(R.id.crossfade_value)?.text = crossfadeLabel()
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
        ThreadPool.postNetwork {
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
                ThreadPool.postNetwork {
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
        ThreadPool.postNetwork {
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
        val label = viewOrNull<TextView>(R.id.mirror_value)
        val code = Settings.mirrorCode(this)
        if (code.isBlank()) {
            label?.text = getString(R.string.mirror_subtitle)
        } else {
            val role = if (Settings.mirrorHost(this)) getString(R.string.mirror_role_host)
            else getString(R.string.mirror_role_guest)
            label?.text = getString(R.string.mirror_active, code, role)
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
        val label = viewOrNull<TextView>(R.id.server_value)
        label?.text = when (Settings.serverMode(this)) {
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
                Playback.refreshFx()
                Playback.reapplyDanceParams()
                findViewById<TextView>(R.id.quality_value)?.text = qualityLabel()
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
        // Virou job do WorkManager: espera a rede, e se a tela fechar no meio o download
        // continua -- antes era uma thread do pool que morria junto com o processo sem aviso.
        val job = PulsaWork.download(this, url, finalName)
        if (job == null) {
            Toast.makeText(
                this, getString(R.string.download_failed, finalName), Toast.LENGTH_SHORT
            ).show()
            return
        }
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        // O callback pode chegar depois desta tela fechar; o Toast usa o contexto de aplicacao
        // para nao prender uma Activity destruida.
        val app = applicationContext
        PulsaWork.watchDownload(this, job) { ok, _ ->
            val message = if (ok) {
                app.getString(R.string.download_done, finalName)
            } else {
                app.getString(R.string.download_failed, finalName)
            }
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
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

    private fun refreshGeminiKey() {
        val key = Settings.geminiKey(this)
        findViewById<TextView>(R.id.gemini_key_value)?.text =
            if (key.isBlank()) getString(R.string.gemini_key_missing)
            else getString(R.string.gemini_key_saved)
    }

    private fun promptGeminiKey() {
        val input = EditText(this).apply {
            setText(Settings.geminiKey(this@SettingsActivity))
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
                Settings.setGeminiKey(this, key)
                Settings.setGeminiOn(this, key.isNotBlank())
                refreshGeminiKey()
                Toast.makeText(this, R.string.gemini_key_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshRecToken() {
        val token = Settings.recToken(this)
        findViewById<TextView>(R.id.rec_token_value)?.text =
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

    private fun updateCacheLabel(view: TextView?) {
        view?.text = getString(R.string.cache_size, Helper.formatBytes(Settings.cacheSize(this)))
    }

    private fun clearCache(valueView: TextView?) {
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
        // O 3D e o surround vão juntos: um usuário que reinstalou o app perde o ajuste junto
        // com o botão, e sem isto o som volta diferente do que ele deixou.
        val spatial = JSONObject().apply {
            put("spatial_3d", p.getBoolean("spatial_3d", false))
            put("spatial_3d_depth", p.getInt("spatial_3d_depth", 60))
            put("spatial_surround", p.getBoolean("spatial_surround", false))
            put("spatial_surround_intensity", p.getInt("spatial_surround_intensity", 50))
        }
        return JSONObject().apply {
            put("app", "pulsa")
            // 2 = acrescenta o bloco de biblioteca (playlists, favoritas, correções de nome).
            // Um `pulsa-backup.json` da v1 continua restaurando normalmente: o `applyRestore`
            // lê o bloco novo com `optJSONObject`, que devolve `null` quando ele não existe.
            put("backupVersion", 2)
            put("learn", learn)
            put("memoryFacts", memoryFacts)
            put("eq", eq)
            put("spatial", spatial)
            put("library", PlaylistBackup.export(applicationContext, PlaylistDb.get(applicationContext)))
        }.toString()
    }

    /**
     * O que o restore devolve: mudou alguma coisa que afeta o áudio?
     *
     * Antes disto era um `Boolean` e o chamador fazia `if (applied) Playback.refreshFx()`.
     * Com o bloco de biblioteca no meio, um backup só de playlists marcaria `true` e forçaria
     * um `refreshFx()` à toa — inofensivo, mas é o tipo de coisa que finge ter efeito.
     */
    private data class RestoreOutcome(
        val settingsChanged: Boolean,
        val report: PlaylistBackup.Report
    )

    private fun applyRestore(text: String): RestoreOutcome {
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return RestoreOutcome(false, PlaylistBackup.Report())
        if (root.optString("app") != "pulsa") {
            return RestoreOutcome(false, PlaylistBackup.Report())
        }
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
        root.optJSONObject("spatial")?.let { spatial ->
            Settings.dataPrefs(applicationContext).edit()
                .putBoolean("spatial_3d", spatial.optBoolean("spatial_3d", false))
                .putInt("spatial_3d_depth", spatial.optInt("spatial_3d_depth", 60))
                .putBoolean("spatial_surround", spatial.optBoolean("spatial_surround", false))
                .putInt("spatial_surround_intensity", spatial.optInt("spatial_surround_intensity", 50))
                .apply()
            changed = true
        }
        // Por último, e separado do `changed` de propósito: as preferências de som acima dizem
        // se é preciso reaplicar o audio na hora, e playlist/favorita não tem audio nenhum
        // para reaplicar. Envolver isto no `changed` faria o `refreshFx()` disparar à toa.
        val report = PlaylistBackup.import(
            applicationContext,
            PlaylistDb.get(applicationContext),
            root.optJSONObject("library")
        )
        return RestoreOutcome(settingsChanged = changed, report = report)
    }

    /**
     * Mostra o que voltou e, principalmente, o que não voltou.
     *
     * A tela antiga era um toast de "pronto" ou "falhou" — dois estados para um restore que
     * tem quatro resultados possíveis: voltou tudo, voltou parte, não voltou nada porque o app
     * não tem permissão de mídia, ou o arquivo nem era do Pulsa. Quem restaurou no celular
     * novo precisa saber a diferença entre "minha playlist veio pela metade" e "o backup está
     * estragado" — e essa diferença está no diálogo, não num toast que some em dois segundos.
     */
    private fun showRestoreReport(report: PlaylistBackup.Report) {
        val title: Int
        val body: String
        if (report.mediaPermissionMissing) {
            title = R.string.restore_needs_permission_title
            body = getString(R.string.restore_needs_permission_body)
        } else if (!report.changed && report.missing == 0) {
            title = R.string.restore_done
            body = getString(R.string.restore_empty)
        } else {
            title = if (report.changed) R.string.restore_done else R.string.restore_failed
            body = restoreSummary(report)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun restoreSummary(r: PlaylistBackup.Report): String {
        val sb = StringBuilder()
        if (r.playlists > 0) sb.append("• ").append(getString(R.string.restore_n_playlists, r.playlists)).append('\n')
        if (r.songs > 0) sb.append("• ").append(getString(R.string.restore_n_songs, r.songs)).append('\n')
        if (r.favorites > 0) sb.append("• ").append(getString(R.string.restore_n_favorites, r.favorites)).append('\n')
        if (r.metaOverrides > 0) sb.append("• ").append(getString(R.string.restore_n_meta, r.metaOverrides)).append('\n')
        if (r.missing > 0) {
            sb.append('\n').append(getString(R.string.restore_missing_title, r.missing)).append('\n')
            r.missingExamples.forEach { sb.append("• ").append(it).append('\n') }
            sb.append(getString(R.string.restore_missing_hint))
        }
        return sb.toString().trim()
    }

    private companion object {
        const val TAG = "PulsaSettings"
    }
}
