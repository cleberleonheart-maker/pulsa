package com.pulsa.player

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.dj.DjSession
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.playback.PlaybackService
import com.pulsa.player.audio.Ambient
import com.pulsa.player.core.Settings

class DjActivity : AppCompatActivity(), Playback.Listener {

    private lateinit var artA: ImageView
    private lateinit var titleA: TextView
    private lateinit var artistA: TextView
    private lateinit var artB: ImageView
    private lateinit var titleB: TextView
    private lateinit var artistB: TextView
    private lateinit var crossfader: SeekBar
    private lateinit var playBtn: MaterialButton
    private lateinit var voiceBtn: MaterialButton
    private lateinit var micBtn: MaterialButton
    private lateinit var configHint: TextView
    private var avatarView: android.view.View? = null
    private var avatarBob: ValueAnimator? = null

    private var bound = false
    private var triggered = false

    private val session by lazy {
        DjSession(
            this,
            DjSession.Launchers(deleteLauncher, duplicatesLauncher, pendriveTreeLauncher),
            object : DjSession.Host {
                override fun render() = this@DjActivity.render()
                override fun resetCrossfader() = this@DjActivity.resetCrossfader()
                override fun refreshVoiceLabel() = this@DjActivity.refreshVoiceLabel()
                override fun refreshMicUi(micOn: Boolean, recognizing: Boolean) =
                    this@DjActivity.refreshMicUi(micOn, recognizing)
                override fun setMixBusy(busy: Boolean) {
                    findViewById<MaterialButton>(R.id.dj_btn_mix)?.isEnabled = !busy
                }
                override fun refreshConfigLabels() = this@DjActivity.refreshConfigLabels()
            }
        )
    }

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

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Playback.service = (service as PlaybackService.LocalBinder).service
            bound = true
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Playback.service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(Settings.accentStyle(this))
        setContentView(R.layout.activity_dj)

        val toolbar = findViewById<MaterialToolbar>(R.id.dj_toolbar)
        toolbar.title = getString(R.string.dj_title) + " — " + getString(R.string.dj_voice_name)
        toolbar.setNavigationOnClickListener { finish() }

        artA = findViewById(R.id.dj_art_a)
        titleA = findViewById(R.id.dj_title_a)
        artistA = findViewById(R.id.dj_artist_a)
        artB = findViewById(R.id.dj_art_b)
        titleB = findViewById(R.id.dj_title_b)
        artistB = findViewById(R.id.dj_artist_b)
        configHint = findViewById(R.id.dj_config_hint)
        crossfader = findViewById(R.id.dj_crossfader)
        playBtn = findViewById(R.id.dj_btn_play)
        voiceBtn = findViewById(R.id.dj_btn_voice)
        micBtn = findViewById(R.id.dj_btn_mic)
        avatarView = findViewById(R.id.dj_avatar)
        stopAvatarBob()
        avatarView?.let { avatarBob = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { a ->
                it.translationY = -6f * (a.animatedValue as Float).coerceIn(0f, 1f)
            }
        }.also { it.start() } }

        crossfader.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || triggered) return
                if (progress >= 70) {
                    triggered = true
                    Playback.next()
                    resetCrossfader()
                } else if (progress <= 30) {
                    triggered = true
                    Playback.prev()
                    resetCrossfader()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<MaterialButton>(R.id.dj_btn_play).setOnClickListener {
            if (Playback.queue.isEmpty()) {
                session.startMix()
            } else {
                Playback.toggle()
                render()
            }
        }
        findViewById<MaterialButton>(R.id.dj_btn_next).setOnClickListener {
            if (Playback.queue.isEmpty()) {
                session.startMix()
            } else {
                Playback.next()
            }
        }
        findViewById<MaterialButton>(R.id.dj_btn_mix).setOnClickListener { session.startMix() }
        findViewById<MaterialButton>(R.id.dj_btn_source).setOnClickListener { session.chooseSource() }
        findViewById<MaterialButton>(R.id.dj_btn_intensity).setOnClickListener { session.chooseIntensity() }
        voiceBtn.setOnClickListener { session.toggleVoice() }
        micBtn.setOnClickListener { session.toggleMic() }
        micBtn.setOnLongClickListener {
            session.onMicLongPress()
            true
        }
        findViewById<MaterialButton>(R.id.dj_btn_scan).setOnClickListener { session.scanLibrary() }
        findViewById<MaterialButton>(R.id.dj_btn_suggest).setOnClickListener { session.suggestSong() }
        findViewById<MaterialButton>(R.id.dj_btn_commands).setOnClickListener { showCommandsDialog() }

        session.create(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        Playback.listener = this
        val playbackIntent = Intent(this, PlaybackService::class.java)
        runCatching { applicationContext.startService(playbackIntent) }
        applicationContext.bindService(
            playbackIntent,
            connection,
            Context.BIND_AUTO_CREATE
        )
        render()
    }

    override fun onStop() {
        session.stopForBackground()
        if (Playback.listener === this) Playback.listener = null
        if (bound) {
            bound = false
            runCatching { applicationContext.unbindService(connection) }
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopAvatarBob()
        session.destroy()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        render()
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

    private fun showCommandsDialog() {
        val commands = listOf(
            R.string.dj_commands_mix to R.drawable.ic_shuffle,
            R.string.dj_commands_sleep to R.drawable.ic_sleep,
            R.string.dj_commands_only to R.drawable.ic_music_note,
            R.string.dj_commands_mixwith to R.drawable.ic_queue_music,
            R.string.dj_commands_repeat to R.drawable.ic_repeat,
            R.string.dj_commands_next to R.drawable.ic_skip_next,
            R.string.dj_commands_prev to R.drawable.ic_skip_prev,
            R.string.dj_commands_skip to R.drawable.ic_skip_next,
            R.string.dj_commands_dislike to R.drawable.ic_heart,
            R.string.dj_commands_pause to R.drawable.ic_pause,
            R.string.dj_commands_play to R.drawable.ic_play,
            R.string.dj_commands_fav to R.drawable.ic_favorite,
            R.string.dj_commands_info to R.drawable.ic_album,
            R.string.dj_commands_scan to R.drawable.ic_search,
            R.string.dj_commands_recognize to R.drawable.ic_mic,
            R.string.dj_commands_delete to R.drawable.ic_delete,
            R.string.dj_commands_duplicates to R.drawable.ic_copy,
            R.string.dj_commands_pendrive to R.drawable.ic_download,
            R.string.dj_commands_suggest to R.drawable.ic_play_circle,
            R.string.dj_commands_visualizer to R.drawable.ic_dj,
            R.string.dj_commands_identity to R.drawable.virgin_avatar,
            R.string.dj_commands_thanks to R.drawable.ic_favorite,
            R.string.dj_commands_hello to R.drawable.ic_mic
        )
        val accent = ContextCompat.getColor(this, R.color.primary)
        val view = layoutInflater.inflate(R.layout.dialog_voice_commands, null)
        val container = view.findViewById<ViewGroup>(R.id.commands_container)
        for ((strRes, iconRes) in commands) {
            val row = layoutInflater.inflate(R.layout.item_command, container, false)
            val text = getString(strRes)
            val dash = text.indexOf(" — ")
            row.findViewById<TextView>(R.id.command_phrase).text =
                if (dash > 0) text.substring(0, dash).trim() else text
            row.findViewById<TextView>(R.id.command_desc).text =
                if (dash > 0) text.substring(dash + 3).trim() else ""
            val icon = row.findViewById<ImageView>(R.id.command_icon)
            icon.setImageResource(iconRes)
            if (iconRes != R.drawable.virgin_avatar) icon.setColorFilter(accent)
            container.addView(row)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        view.findViewById<MaterialButton>(R.id.commands_close).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun refreshConfigLabels() {
        val srcLabel = if (session.source == Settings.DJ_FAVORITES) R.string.dj_source_favorites else R.string.dj_source_all
        val intLabel = when (session.intensity) {
            Settings.DJ_CALM -> R.string.dj_intensity_calm
            Settings.DJ_WILD -> R.string.dj_intensity_wild
            else -> R.string.dj_intensity_balanced
        }
        configHint.setText(getString(R.string.dj_source) + ": " + getString(srcLabel) + "  •  " +
            getString(R.string.dj_intensity) + ": " + getString(intLabel))
    }

    private fun resetCrossfader() {
        crossfader.progress = 50
        crossfader.postDelayed({ triggered = false }, 600)
    }

    private fun refreshVoiceLabel() {
        val enabled = Settings.djVoice(this)
        voiceBtn.setText(
            getString(if (enabled) R.string.dj_voice_on else R.string.dj_voice_off)
        )
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
        micBtn.setTextColor(
            ContextCompat.getColor(
                this,
                if (micOn || recognizing) R.color.primary else R.color.text_secondary
            )
        )
    }

    private fun nextInQueue(): Song? {
        val queue = Playback.queue
        if (queue.isEmpty()) return null
        val i = Playback.index
        return queue.getOrNull(i + 1) ?: queue.firstOrNull()
    }

    private fun render() {
        if (!::artA.isInitialized) return
        val now = Playback.currentSong
        val next = nextInQueue()
        val playing = Playback.isPlaying
        playBtn.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.setIconTintResource(if (playing) R.color.primary else R.color.text_primary)
        bindDeck(now, titleA, artistA, artA)
        bindDeck(next, titleB, artistB, artB)
        animateAvatar(playing)
    }

    private fun stopAvatarBob() {
        avatarBob?.cancel()
        avatarBob = null
        avatarView?.translationY = 0f
    }

    private fun animateAvatar(playing: Boolean) {
        val v = avatarView ?: return
        v.animate().cancel()
        if (!playing) {
            v.scaleX = 1f
            v.scaleY = 1f
            return
        }
        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(450)
            .withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(450).start()
            }.start()
    }

    private fun bindDeck(song: Song?, titleView: TextView, artistView: TextView, artView: ImageView) {
        if (song == null) {
            titleView.text = "—"
            artistView.text = "—"
            artView.setImageResource(R.drawable.ic_music_note)
            return
        }
        titleView.text = song.title
        artistView.text = song.artist
        ArtLoader.load(song.albumId, song.path, artView)
    }
}