package com.pulsa.player.ui.settings

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanOptions
import com.pulsa.player.R
import com.pulsa.player.SettingsActivity
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.sync.Handoff
import com.pulsa.player.sync.MirrorQr
import com.pulsa.player.sync.MirrorSync
import com.pulsa.player.sync.Net
import com.pulsa.player.sync.RemoteControl
import com.pulsa.player.sync.RemoteProtocol
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Sincronização — ouvir juntos (sessão/QR), controle remoto e servidor.
 */

internal fun SettingsActivity.mirrorMenu() {
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
                activeCode.isBlank() || which == 1 -> chooseJoinMethod()
                else -> leaveSession()
            }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.createSession() {
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
            showSessionQr(code)
        }
    }
}

/** Host mostra o QR + o código; o convidado pode escanear em vez de digitar. */
internal fun SettingsActivity.showSessionQr(code: String) {
    val density = resources.displayMetrics.density
    val pad = (20 * density).toInt()
    val px = (248 * density).toInt().coerceAtLeast(248)
    val qr = MirrorQr.bitmap(MirrorQr.encode(code), px)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        setPadding(pad, pad, pad, 0)
    }
    if (qr != null) {
        box.addView(ImageView(this).apply {
            setImageBitmap(qr)
            contentDescription = getString(R.string.mirror_qr_title)
        })
    }
    box.addView(TextView(this).apply {
        text = getString(R.string.mirror_qr_hint)
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        setPadding(0, (12 * density).toInt(), 0, (6 * density).toInt())
    })
    box.addView(TextView(this).apply {
        text = code
        textSize = 30f
        letterSpacing = 0.35f
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    })
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.mirror_qr_title)
        .setView(box)
        .setPositiveButton(R.string.mirror_share_title) { _, _ -> shareCode(code) }
        .setNegativeButton(R.string.close, null)
        .show()
}

internal fun SettingsActivity.chooseJoinMethod() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.mirror_join)
        .setItems(
            arrayOf(getString(R.string.mirror_join_type), getString(R.string.mirror_join_scan))
        ) { _, which ->
            if (which == 0) joinByTypingDialog() else startScan()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.startScan() {
    val options = ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt(getString(R.string.mirror_scan_prompt))
        .setBeepEnabled(false)
        .setOrientationLocked(false)
    scanLauncher.launch(options)
}

internal fun SettingsActivity.joinByTypingDialog() {
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
            val code = MirrorQr.codeFrom(input.text.toString())
            if (code == null) {
                Toast.makeText(this, R.string.mirror_code_hint, Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            joinWithCode(code)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.joinWithCode(code: String) {
    ThreadPool.postNetwork {
        val res = sessionGet(code)
        val ok = runCatching { JSONObject(res) }.getOrNull()?.optBoolean("ok", false) == true
        runOnUiThread {
            if (!ok) {
                Toast.makeText(this, R.string.mirror_error, Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            Settings.setMirrorCode(this, code)
            // Item 289 · modo 1: com o toggle ligado, entrar na sessão é assumir, não
            // espelhar. Este aparelho vira o dono local (mirrorHost=true, para o
            // MirrorSync não nos puxar de volta) e o servidor troca o dono (Handoff.assume
            // pausa o antigo). Com o toggle desligado, o "ouvir juntos" clássico do item
            // 133 continua igual.
            if (Settings.handoffAutoAssume(this)) {
                Settings.setMirrorHost(this, true)
                Handoff.assume(this, code)
                refreshMirrorLabel()
                Toast.makeText(this, getString(R.string.mirror_joined, code), Toast.LENGTH_SHORT).show()
            } else {
                Settings.setMirrorHost(this, false)
                MirrorSync.start(this)
                refreshMirrorLabel()
                Toast.makeText(this, getString(R.string.mirror_joined, code), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

internal fun SettingsActivity.leaveSession() {
    ThreadPool.postNetwork {
        sessionCall("""{"leave":true}""")
        Settings.setMirrorCode(self, "")
        Settings.setMirrorHost(self, false)
        MirrorSync.stop()
        runOnUiThread {
            refreshMirrorLabel()
            Toast.makeText(self, R.string.mirror_left, Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun SettingsActivity.shareCode(code: String) {
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

internal fun SettingsActivity.refreshMirrorLabel() {
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

internal fun SettingsActivity.refreshRemoteLabel() {
    val label = viewOrNull<TextView>(R.id.remote_value)
    val code = Settings.remoteCode(this)
    label?.text = if (code.isBlank()) getString(R.string.remote_subtitle)
    else getString(R.string.remote_active, code)
}

internal fun SettingsActivity.remoteControlDialog() {
    val view = layoutInflater.inflate(R.layout.dialog_remote_control, null)
    val codeInput = view.findViewById<EditText>(R.id.remote_code_input)
    val status = view.findViewById<TextView>(R.id.remote_status)
    val now = view.findViewById<TextView>(R.id.remote_now)
    val playBtn = view.findViewById<Button>(R.id.remote_play)
    val prevBtn = view.findViewById<Button>(R.id.remote_prev)
    val nextBtn = view.findViewById<Button>(R.id.remote_next)
    val seek = view.findViewById<SeekBar>(R.id.remote_seek)
    val position = view.findViewById<TextView>(R.id.remote_position)
    val vol = view.findViewById<SeekBar>(R.id.remote_vol)
    val assumeBtn = view.findViewById<Button>(R.id.remote_assume)

    val startCode = Settings.remoteCode(this).ifBlank { Settings.mirrorCode(this) }
    codeInput.setText(startCode)

    var dragging = false

    fun apply(snap: RemoteProtocol.Snap) {
        if (!snap.online) {
            status.text = getString(R.string.remote_offline)
            now.text = ""
            playBtn.text = "▶"
            return
        }
        status.text = getString(R.string.remote_online, snap.code)
        if (snap.hasTrack) {
            val album = if (snap.album.isNotEmpty()) " · ${snap.album}" else ""
            now.text = "${snap.title} — ${snap.artist}$album"
            playBtn.text = if (snap.playing) "⏸" else "▶"
            val dur = snap.durationMs.coerceAtLeast(0L).toInt()
            seek.max = dur
            if (!dragging) seek.progress = snap.positionMs.coerceIn(0L, dur.toLong()).toInt()
            position.text = "${fmtMs(snap.positionMs)} / ${fmtMs(snap.durationMs)}"
            if (snap.volume >= 0) vol.progress = snap.volume
        } else {
            now.text = getString(R.string.remote_nothing)
            playBtn.text = "▶"
        }
    }

    RemoteControl.setListener(object : RemoteControl.Listener {
        override fun onState(snap: RemoteProtocol.Snap) = apply(snap)
    })

    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.remote_title)
        .setView(view)
        .setPositiveButton(R.string.remote_connect, null)
        .setNegativeButton(R.string.close, null)
        .setOnDismissListener { RemoteControl.stop(); RemoteControl.setListener(null) }
        .create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val code = codeInput.text.toString().trim().uppercase()
            if (code.length < 3) {
                Toast.makeText(this, R.string.remote_code_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Settings.setRemoteCode(this, code)
            refreshRemoteLabel()
            status.text = getString(R.string.remote_waiting)
            RemoteControl.start(this, code)
        }
    }

    playBtn.setOnClickListener { RemoteControl.send("toggle") }
    prevBtn.setOnClickListener { RemoteControl.send("prev") }
    nextBtn.setOnClickListener { RemoteControl.send("next") }
    // Item 289 (handoff): assumir a sessão deste código. Vira o dono e o aparelho
    // antigo pausa (handoff_release). O feedback vem pelo listener do próprio Handoff.
    assumeBtn.setOnClickListener {
        val code = codeInput.text.toString().trim().uppercase()
        if (code.length < 3) {
            Toast.makeText(this, R.string.remote_code_hint, Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        assumeBtn.isEnabled = false
        Handoff.assume(this, code, object : Handoff.Listener {
            override fun onResult(ok: Boolean, message: String) {
                assumeBtn.isEnabled = true
                Toast.makeText(self, message, Toast.LENGTH_SHORT).show()
                // Reconecta o controle pra refletir o novo dono (estado volta do servidor).
                Settings.setRemoteCode(self, code)
                RemoteControl.start(self, code)
            }
        })
    }
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
        override fun onStopTrackingTouch(sb: SeekBar) {
            dragging = false
            RemoteControl.send("seek", sb.progress.toLong())
        }
    })
    vol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {}
        override fun onStopTrackingTouch(sb: SeekBar) {
            RemoteControl.send("volume", sb.progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar) {}
    })

    dialog.show()
    if (startCode.length >= 3) RemoteControl.start(this, startCode) else status.text = getString(R.string.remote_subtitle)
}

internal fun SettingsActivity.fmtMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

internal fun SettingsActivity.serverDialog() {
    refreshServerLabel()
    val mode = Settings.serverMode(this)
    val input = EditText(this).apply {
        hint = getString(R.string.server_hint)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true)
        if (mode == "custom") setText(Settings.serverBase(self))
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

internal fun SettingsActivity.refreshServerLabel() {
    val label = viewOrNull<TextView>(R.id.server_value)
    label?.text = when (Settings.serverMode(this)) {
        "online" -> getString(R.string.server_online)
        "custom" -> Settings.serverBase(this)
        else -> getString(R.string.server_default)
    }
}

internal fun SettingsActivity.refreshServerToken() {
    val label = viewOrNull<TextView>(R.id.server_token_value)
    label?.text = if (Settings.hasCustomTelemetryToken(this)) {
        getString(R.string.server_token_saved)
    } else {
        getString(R.string.server_token_missing)
    }
}

internal fun SettingsActivity.promptServerToken() {
    val input = EditText(this).apply {
        if (Settings.hasCustomTelemetryToken(self)) {
            setText(Settings.telemetryToken(self))
        }
        hint = getString(R.string.server_token_hint)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        maxLines = 1
        isSingleLine = true
        setPadding(48, 16, 48, 16)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.server_token)
        .setMessage(R.string.server_token_subtitle)
        .setView(input)
        .setPositiveButton(R.string.save) { _, _ ->
            Settings.setTelemetryToken(this, input.text.toString().trim())
            refreshServerToken()
            Toast.makeText(this, R.string.server_token_saved, Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.sessionCall(body: String): String {
    val device = Settings.deviceId(this)
    for (base in sessionHosts()) {
        try {
            val conn = URL("$base/session").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 2000
            conn.readTimeout = 2500
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Pulsa-Device", device)
            conn.setRequestProperty("X-Pulsa-Token", Settings.telemetryToken(this))
            Net.user(this, conn)
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.toByteArray().size)
            conn.outputStream.use { it.write(body.toByteArray()) }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            Net.close(conn)
            return text
        } catch (t: Throwable) {
        }
    }
    return ""
}

internal fun SettingsActivity.sessionGet(code: String): String {
    val device = Settings.deviceId(this)
    for (base in sessionHosts()) {
        try {
            val conn = URL("$base/session?code=${java.net.URLEncoder.encode(code, "UTF-8")}")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2000
            conn.readTimeout = 2500
            conn.setRequestProperty("X-Pulsa-Device", device)
            conn.setRequestProperty("X-Pulsa-Token", Settings.telemetryToken(this))
            Net.user(this, conn)
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            Net.close(conn)
            return text
        } catch (t: Throwable) {
        }
    }
    return ""
}

internal fun SettingsActivity.sessionHosts(): List<String> = Settings.serverCandidates(this)

