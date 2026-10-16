package com.pulsa.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.pulsa.player.audio.Ambient
import com.pulsa.player.core.Settings
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.dj.DjSession
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback

/**
 * Tela de dirigir: modo fullscreen enxuto, feito para ser usado no volante — capa gigante,
 * poucos botões enormes e toda a cabine por voz. É a mesma [DjSession] da cabine do DJ
 * (mixagem + microfone + comandos), então "tudo controlado por voz" reaproveita o cérebro
 * que já existe, sem duplicar nada.
 */
class DriveActivity : AppCompatActivity(), Playback.Listener {

    private lateinit var art: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var playBtn: MaterialButton
    private lateinit var micBtn: MaterialButton
    private lateinit var voiceBtn: MaterialButton

    private var playbackBind: Playback.Bind? = null

    private val deleteLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            session.onDeleteRequestResult(result.resultCode == RESULT_OK)
        }

    private val duplicatesLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            session.onDuplicatesRequestResult(result.resultCode == RESULT_OK)
        }

    private val pendriveTreeLauncher: ActivityResultLauncher<Uri?> =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            session.onPendriveTreeResult(uri)
        }

    private val session by lazy {
        DjSession(
            this,
            DjSession.Launchers(deleteLauncher, duplicatesLauncher, pendriveTreeLauncher),
            object : DjSession.Host {
                override fun render() = this@DriveActivity.render()
                override fun resetCrossfader() {}
                override fun refreshVoiceLabel() = this@DriveActivity.refreshVoiceLabel()
                override fun refreshMicUi(micOn: Boolean, recognizing: Boolean) =
                    this@DriveActivity.refreshMicUi(micOn, recognizing)
                override fun setMixBusy(busy: Boolean) {
                    playBtn.isEnabled = !busy
                }
                override fun refreshConfigLabels() {}
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(Settings.accentStyle(this))
        // No volante a tela não pode apagar sozinha.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_drive)

        art = findViewById(R.id.drive_art)
        titleView = findViewById(R.id.drive_title)
        artistView = findViewById(R.id.drive_artist)
        playBtn = findViewById(R.id.drive_btn_play)
        micBtn = findViewById(R.id.drive_btn_mic)
        voiceBtn = findViewById(R.id.drive_btn_voice)

        findViewById<MaterialButton>(R.id.drive_btn_close).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.drive_btn_prev).setOnClickListener { Playback.prev() }
        findViewById<MaterialButton>(R.id.drive_btn_next).setOnClickListener {
            if (Playback.queue.isEmpty()) session.startMix() else Playback.next()
        }
        playBtn.setOnClickListener {
            if (Playback.queue.isEmpty()) session.startMix() else {
                Playback.toggle()
                render()
            }
        }
        // Volume do ambiente (quando ligado) pelos botões físicos, igual ao volante do DJ.
        micBtn.setOnClickListener { session.toggleMic() }
        voiceBtn.setOnClickListener { session.toggleVoice() }

        session.create(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        Playback.listener = this
        playbackBind = Playback.connect(this) { render() }
        render()
    }

    override fun onStop() {
        session.stopForBackground()
        if (Playback.listener === this) Playback.listener = null
        playbackBind?.let { Playback.release(it) }
        playbackBind = null
        super.onStop()
    }

    override fun onDestroy() {
        session.destroy()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (Ambient.isOn()) {
            val step = 0.05f
            val vol = when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> Ambient.state().volume + step
                KeyEvent.KEYCODE_VOLUME_DOWN -> Ambient.state().volume - step
                else -> null
            }
            if (vol != null) {
                Ambient.setVolume(vol.coerceIn(0f, 1f))
                Toast.makeText(
                    this,
                    getString(R.string.ambient_volume, (Ambient.state().volume * 100).toInt()),
                    Toast.LENGTH_SHORT
                ).show()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        session.onPermissionResult(requestCode, permissions, grantResults)
    }

    override fun onSongChanged(song: Song?, index: Int) {
        session.onSongChanged(song, index)
        render()
    }

    override fun onPlayStateChanged(isPlaying: Boolean) = render()

    override fun onProgress(positionMs: Long, durationMs: Long) {
        session.onProgress(positionMs, durationMs)
    }

    private fun refreshVoiceLabel() {
        val enabled = Settings.djVoice(this)
        voiceBtn.setText(getString(if (enabled) R.string.dj_voice_on else R.string.dj_voice_off))
        voiceBtn.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.primary else R.color.text_secondary)
        )
    }

    private fun refreshMicUi(micOn: Boolean, recognizing: Boolean) {
        micBtn.setText(
            when {
                recognizing -> R.string.dj_recognizing
                micOn -> R.string.dj_mic_on
                else -> R.string.dj_mic_off
            }
        )
        val color = if (micOn || recognizing) R.color.primary else R.color.text_secondary
        micBtn.setTextColor(ContextCompat.getColor(this, color))
        micBtn.iconTint = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, color)
        )
    }

    private fun render() {
        if (!::art.isInitialized) return
        val now = Playback.currentSong
        playBtn.setIconResource(if (Playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        if (now == null) {
            titleView.text = "—"
            artistView.text = "—"
            art.setImageResource(R.drawable.ic_music_note)
        } else {
            titleView.text = now.title
            artistView.text = now.artist
            ArtLoader.load(now.albumId, now.path, art)
        }
    }
}
