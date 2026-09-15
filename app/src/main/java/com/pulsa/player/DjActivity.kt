package com.pulsa.player

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.playback.PlaybackService
import com.pulsa.player.util.DjCommandListener
import com.pulsa.player.util.DjCommander
import com.pulsa.player.util.DjEngine
import com.pulsa.player.util.DjFacts
import com.pulsa.player.util.DjIdentity
import com.pulsa.player.util.DjLearn
import com.pulsa.player.util.DjRecognizer
import com.pulsa.player.util.DjSuggest
import com.pulsa.player.util.DjVoice
import com.pulsa.player.util.GalleryScanner
import com.pulsa.player.util.MusicEditor
import com.pulsa.player.util.Permissions
import com.pulsa.player.util.Settings
import com.pulsa.player.util.Telemetry
import com.pulsa.player.util.ThreadPool

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

    private var source = Settings.DJ_ALL
    private var intensity = Settings.DJ_BALANCED
    private var bound = false
    private var triggered = false
    private var djActive = false
    private var micOn = false

    private var learnId: Long = -1L
    private var learnStartedAt: Long = 0L
    private var lastCompleted = false
    private var suppressNextLearnSkip = false

    private var micShouldResume = false
    private var scanInFlight = false

    private var djVoice: DjVoice? = null
    private var commandListener: DjCommandListener? = null
    private var pendingDelete: Song? = null
    private var recognizing = false
    private var pendingRecognize = false
    private var resumeAfterRecognize = false
    private var lastSpeechEndMs = 0L
    private val RESUME_LISTENER_DELAY_MS = 800L
    private val resumeListenerRunnable = Runnable { resumeListener() }
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var deleteLauncher: ActivityResultLauncher<IntentSenderRequest>

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

        deleteLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val song = pendingDelete
            pendingDelete = null
            uiHandler.removeCallbacksAndMessages(null)
            if (result.resultCode == RESULT_OK && song != null) {
                finalizeDelete(song, alreadyDeleted = true)
            } else if (song != null) {
                speak(getString(R.string.dj_voice_delete_cancel))
            }
        }

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
                startMix()
            } else {
                Playback.toggle()
                render()
            }
        }
        findViewById<MaterialButton>(R.id.dj_btn_next).setOnClickListener {
            if (Playback.queue.isEmpty()) {
                startMix()
            } else {
                Playback.next()
            }
        }
        findViewById<MaterialButton>(R.id.dj_btn_mix).setOnClickListener { startMix() }
        findViewById<MaterialButton>(R.id.dj_btn_source).setOnClickListener { chooseSource() }
        findViewById<MaterialButton>(R.id.dj_btn_intensity).setOnClickListener { chooseIntensity() }
        voiceBtn.setOnClickListener { toggleVoice() }
        micBtn.setOnClickListener { toggleMic() }
        micBtn.setOnLongClickListener {
            if (!recognizing) recognizeSong()
            true
        }
        findViewById<MaterialButton>(R.id.dj_btn_scan).setOnClickListener { scanLibrary() }
        findViewById<MaterialButton>(R.id.dj_btn_suggest).setOnClickListener { suggestSong() }

        source = Settings.djSource(this)
        intensity = Settings.djIntensity(this)
        refreshConfigLabels()
        refreshVoiceLabel()

        djVoice = DjVoice(this, Settings.languageTag(Settings.language(this))).also { vm ->
            vm.init { _ ->
                if (Settings.djVoice(this) && savedInstanceState == null) {
                    vm.speak(getString(R.string.dj_voice_hello))
                }
            }
        }
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
        if (micOn) stopMic()
        commandListener?.destroy()
        commandListener = null
        if (Playback.listener === this) Playback.listener = null
        if (bound) {
            bound = false
            runCatching { applicationContext.unbindService(connection) }
        }
        djVoice?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        djVoice?.shutdown()
        djVoice = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_MIC -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (pendingRecognize) {
                    pendingRecognize = false
                    if (granted) {
                        recognizeSong()
                    } else {
                        Toast.makeText(this, R.string.dj_mic_denied, Toast.LENGTH_LONG).show()
                    }
                } else if (granted) {
                    startMic()
                } else {
                    Toast.makeText(this, R.string.dj_mic_denied, Toast.LENGTH_LONG).show()
                }
            }
            REQ_STORAGE -> {
                val granted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    performScan()
                } else {
                    speak(getString(R.string.dj_voice_scan_denied))
                }
            }
        }
    }

    private fun startMix() {
        if (!Permissions.hasAccess(this)) {
            Toast.makeText(this, R.string.dj_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        val activeSource = source
        val activeIntensity = intensity
        findViewById<MaterialButton>(R.id.dj_btn_mix).isEnabled = false
        ThreadPool.post {
            val songs = Library.allSongs(this)
            val favIds = runCatching {
                PlaylistDb.get(applicationContext).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val learn = DjLearn.learn(applicationContext)
            val set = DjEngine.build(
                songs, favIds,
                if (activeSource == Settings.DJ_FAVORITES) DjEngine.Source.FAVORITES else DjEngine.Source.ALL,
                when (activeIntensity) {
                    Settings.DJ_CALM -> DjEngine.Intensity.CALM
                    Settings.DJ_WILD -> DjEngine.Intensity.WILD
                    else -> DjEngine.Intensity.BALANCED
                },
                learn
            )
            ThreadPool.onUi {
                findViewById<MaterialButton>(R.id.dj_btn_mix)?.isEnabled = true
                if (set.isEmpty()) {
                    Toast.makeText(this, R.string.dj_empty, Toast.LENGTH_LONG).show()
                    return@onUi
                }
                Telemetry.log(this, "DJ Virgin mix source=$activeSource style=$activeIntensity n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                djActive = true
                learnId = -1L
                lastCompleted = false
                suppressNextLearnSkip = false
                Playback.start(set, 0)
                resetCrossfader()
                render()
                speak(getString(R.string.dj_voice_start, set.size))
            }
        }
    }

    private fun toggleVoice() {
        val enabled = !Settings.djVoice(this)
        Settings.setDjVoice(this, enabled)
        refreshVoiceLabel()
        if (enabled) {
            speak(getString(R.string.dj_voice_hello))
        }
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

    private fun speak(text: String, holdEnabled: Boolean = false, onDone: (() -> Unit)? = null) {
        if (isFinishing || isDestroyed) return
        if (Settings.djVoice(this)) {
            pauseListener()
            djVoice?.speak(text) {
                lastSpeechEndMs = SystemClock.elapsedRealtime()
                ThreadPool.onUi {
                    if (!holdEnabled) uiHandler.postDelayed(resumeListenerRunnable, RESUME_LISTENER_DELAY_MS)
                    onDone?.invoke()
                }
            }
        } else {
            resumeListener()
            onDone?.invoke()
        }
    }

    private fun pauseListener() {
        uiHandler.removeCallbacks(resumeListenerRunnable)
        if (micOn && !micShouldResume) {
            micShouldResume = true
            commandListener?.stop()
        }
    }

    private fun resumeListener() {
        if (!micShouldResume) return
        if (!micOn || isFinishing || isDestroyed) {
            micShouldResume = false
            return
        }
        if (djVoice?.isSpeaking == true) return
        micShouldResume = false
        commandListener?.start()
    }

    private fun toggleMic() {
        if (micOn) {
            stopMic()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startMic()
    }

    private fun startMic() {
        micOn = true
        micBtn.setText(R.string.dj_mic_on)
        micBtn.setTextColor(ContextCompat.getColor(this, R.color.primary))
        commandListener?.destroy()
        commandListener = DjCommandListener(this) { handleCommand(it) }
        commandListener?.start()
        speak(getString(R.string.dj_mic_hint))
    }

    private fun stopMic() {
        micOn = false
        micShouldResume = false
        commandListener?.stop()
        micBtn.setText(R.string.dj_mic_off)
        micBtn.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        djVoice?.stop()
    }

    private fun handleCommand(text: String) {
        if (text == "__unsupported__") {
            Toast.makeText(this, R.string.dj_mic_denied, Toast.LENGTH_LONG).show()
            stopMic()
            return
        }
        ThreadPool.onUi { resolveVoiceCommand(text) }
    }

    private fun resolveVoiceCommand(text: String) {
        if (isFinishing || isDestroyed) return
        if (SystemClock.elapsedRealtime() - lastSpeechEndMs < 1500L) return
        val norm = DjCommander.norm(text)
        val hasWake = DjCommander.hasWake(norm)
        val action = DjCommander.action(norm)
        if (action == null) {
            if (hasWake) speak(getString(R.string.dj_voice_unknown))
            return
        }
        if (action !in setOf("confirm", "cancel", "delete")) {
            pendingDelete = null
            uiHandler.removeCallbacksAndMessages(null)
        }
        when (action) {
            "mix" -> {
                speak(getString(R.string.dj_voice_mix))
                startMix()
            }
            "skip" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    DjLearn.recordSkip(applicationContext, cur.id)
                    suppressNextLearnSkip = true
                }
                speak(getString(R.string.dj_voice_skip))
                if (Playback.queue.isNotEmpty()) Playback.next() else startMix()
            }
            "dislike" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    DjLearn.recordDislike(applicationContext, cur.id)
                    suppressNextLearnSkip = true
                }
                speak(getString(R.string.dj_voice_dislike))
                if (Playback.queue.isNotEmpty()) Playback.next() else startMix()
            }
            "next" -> {
                speak(getString(R.string.dj_voice_next))
                if (Playback.queue.isNotEmpty()) Playback.next() else startMix()
            }
            "prev" -> {
                speak(getString(R.string.dj_voice_prev))
                Playback.prev()
            }
            "pause" -> {
                djVoice?.stop()
                if (Playback.isPlaying) {
                    Playback.toggle()
                    speak(getString(R.string.dj_voice_pause))
                }
            }
            "play" -> {
                djVoice?.stop()
                if (!Playback.isPlaying && Playback.queue.isNotEmpty()) {
                    Playback.toggle()
                    speak(getString(R.string.dj_voice_play))
                }
            }
            "fav" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    val db = PlaylistDb.get(applicationContext)
                    val nextValue = !db.isFavorite(cur.id)
                    db.setFavorite(cur, nextValue)
                    if (nextValue) DjLearn.recordLiked(applicationContext, cur.id)
                    Toast.makeText(this, R.string.dj_voice_fav, Toast.LENGTH_SHORT).show()
                    speak(getString(
                        if (nextValue) R.string.dj_voice_learn_like else R.string.dj_voice_fav
                    ))
                }
            }
            "info" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    speak(getString(R.string.dj_voice_track, cur.artist, cur.title))
                } else {
                    speak(getString(R.string.dj_voice_unknown))
                }
            }
            "scan" -> scanLibrary()
            "recognize" -> {
                recognizeSong()
            }
            "delete" -> {
                val cur = Playback.currentSong
                if (cur == null) {
                    pendingDelete = null
                    uiHandler.removeCallbacksAndMessages(null)
                    speak(getString(R.string.dj_voice_delete_no_song))
                } else if (pendingDelete?.id == cur.id) {
                    pendingDelete = null
                    uiHandler.removeCallbacksAndMessages(null)
                    doDelete(cur)
                } else {
                    pendingDelete = cur
                    uiHandler.removeCallbacksAndMessages(null)
                    uiHandler.postDelayed({ pendingDelete = null }, 15000)
                    speak(getString(R.string.dj_voice_delete_confirm, cur.title))
                }
            }
            "confirm" -> {
                val song = pendingDelete
                pendingDelete = null
                uiHandler.removeCallbacksAndMessages(null)
                if (song != null) doDelete(song)
            }
            "cancel" -> {
                pendingDelete = null
                uiHandler.removeCallbacksAndMessages(null)
                speak(getString(R.string.dj_voice_delete_cancel))
            }
            "suggest" -> suggestSong()
            "identity" -> {
                speak(DjIdentity.introSpeech())
            }
            "thanks" -> speak(getString(R.string.dj_voice_thanks))
            "hello" -> speak(getString(R.string.dj_voice_hello))
        }
    }

    private fun suggestSong() {
        val songsHere = Library.allSongs(this)
        if (songsHere.isEmpty()) {
            if (isFinishing || isDestroyed) return
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dj_suggest_title)
                .setMessage(R.string.dj_suggest_needs_songs)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        if (!DjSuggest.isReady(this)) {
            offlineSuggest(songsHere)
            return
        }
        pauseListener()
        speak(getString(R.string.dj_suggest_thinking), holdEnabled = true)
        ThreadPool.post {
            val songs = Library.allSongs(this)
            val suggestion = if (songs.isEmpty()) null else DjSuggest.suggest(this, songs)
            if (suggestion == null) {
                presuggestOffline(songs)
                return@post
            }
            val freshSongs = Library.allSongs(applicationContext)
            val queue = run {
                val playing = freshSongs.firstOrNull { it.id == findId(suggestion, songs) }
                if (playing != null) listOf(playing) + freshSongs.filter { it.id != playing.id } else emptyList()
            }
            ThreadPool.onUi {
                if (isFinishing || isDestroyed) return@onUi
                resumeListener()
                if (queue.isEmpty()) {
                    speak(DjSuggest.toSpeech(suggestion) + " " + getString(R.string.dj_suggest_not_found))
                } else {
                    Telemetry.log(this, "DJ Virgin AI sugeriu: ${queue.first().title}")
                    speak(DjSuggest.toSpeech(suggestion)) {
                        Playback.start(queue, 0)
                    }
                }
            }
        }
    }

    private fun offlineSuggest(songs: List<Song>) {
        pauseListener()
        speak(getString(R.string.dj_suggest_thinking), holdEnabled = true)
        ThreadPool.post { presuggestOffline(songs) }
    }

    private fun presuggestOffline(songs: List<Song>) {
        val freshSongs = Library.allSongs(applicationContext)
        val suggestion = DjSuggest.offline(this, songs)
        val queue = run {
            val playing = freshSongs.firstOrNull { it.id == suggestion.let { s -> findId(s, songs) } }
            if (playing != null) listOf(playing) + freshSongs.filter { it.id != playing.id } else emptyList()
        }
        ThreadPool.onUi {
            if (isFinishing || isDestroyed) return@onUi
            resumeListener()
            if (suggestion.title.isBlank() || queue.isEmpty()) {
                speak(getString(R.string.dj_suggest_error))
                return@onUi
            }
            Telemetry.log(this, "DJ Virgin offline sugeriu: ${queue.first().title}")
            speak(DjSuggest.toSpeech(suggestion)) {
                Playback.start(queue, 0)
            }
        }
    }

    private fun findId(suggestion: com.pulsa.player.util.DjSuggest.Suggestion, songs: List<Song>): Long {
        return DjSuggest.findSong(songs, suggestion)?.id ?: -1L
    }

    private fun showGeminiSetup() {
        if (isFinishing || isDestroyed) return
        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        input.hint = getString(R.string.dj_suggest_key_hint)
        input.setText(Settings.geminiKey(this))
        input.setSingleLine(true)
        val box = android.widget.CheckBox(this)
        box.text = getString(R.string.dj_suggest_enable)
        box.isChecked = Settings.geminiOn(this)
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(48, 8, 48, 0)
        layout.addView(input)
        layout.addView(box)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dj_suggest_title)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                Settings.setGeminiKey(this, input.text.toString())
                Settings.setGeminiOn(this, box.isChecked)
                Telemetry.log(this, "DJ Virgin AI config atualizada")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun scanLibrary() {
        if (Build.VERSION.SDK_INT >= 33) {
            val hasAudio = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val hasVideo = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasAudio || !hasVideo) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO),
                    REQ_STORAGE
                )
                return
            }
        } else if (!Permissions.hasAccess(this)) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE
            )
            return
        }
        performScan()
    }

    private fun performScan() {
        if (scanInFlight) return
        scanInFlight = true
        speak(getString(R.string.dj_voice_scan), holdEnabled = true)
        GalleryScanner.scan(applicationContext) { songs, videos ->
            scanInFlight = false
            if (isFinishing || isDestroyed) {
                resumeListener()
                return@scan
            }
            speak(getString(R.string.dj_voice_scan_result, songs, videos))
            Toast.makeText(
                this, getString(R.string.dj_voice_scan_result, songs, videos),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun doDelete(song: Song) {
        if (Build.VERSION.SDK_INT >= 30) {
            val sender = runCatching {
                MediaStore.createDeleteRequest(
                    contentResolver,
                    listOf(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id))
                )
            }.getOrNull()
            if (sender == null) {
                speak(getString(R.string.dj_voice_delete_failed))
                return
            }
            pendingDelete = song
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            finalizeDelete(song, alreadyDeleted = false)
        }
    }

    private fun finalizeDelete(song: Song, alreadyDeleted: Boolean) {
        ThreadPool.post {
            val deleted = if (alreadyDeleted) {
                true
            } else {
                runCatching {
                    contentResolver.delete(
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id),
                        null, null
                    ) > 0
                }.getOrDefault(false)
            }
            ThreadPool.onUi {
                if (deleted) {
                    runCatching {
                        val db = PlaylistDb.get(applicationContext)
                        db.removeSongFromAll(song.id)
                        db.removeFavorite(song.id)
                    }
                    if (Playback.currentSong?.id == song.id) Playback.next()
                    speak(getString(R.string.dj_voice_delete_done, song.title))
                    Telemetry.log(this, "DJ Virgin delete vc ok id=${song.id}")
                } else {
                    speak(getString(R.string.dj_voice_delete_failed))
                }
            }
        }
    }

    private fun recognizeSong() {
        if (recognizing) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingRecognize = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (micOn) stopMic()
        recognizing = true
        resumeAfterRecognize = Playback.isPlaying
        if (resumeAfterRecognize) Playback.pause()
        micBtn.setText(R.string.dj_recognizing)
        micBtn.setTextColor(ContextCompat.getColor(this, R.color.primary))
        speak(getString(R.string.dj_voice_recognize)) {
            DjRecognizer.recognize(applicationContext) { result, error ->
                if (resumeAfterRecognize && !Playback.isPlaying && !isFinishing && !isDestroyed) {
                    Playback.toggle()
                }
                resumeAfterRecognize = false
                if (isFinishing || isDestroyed) return@recognize
            recognizing = false
            micBtn.setText(R.string.dj_mic_off)
            micBtn.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            when {
                error == "no_token" -> {
                    speak(getString(R.string.dj_voice_recognize_configured))
                    Toast.makeText(this, R.string.dj_voice_recognize_configured, Toast.LENGTH_LONG).show()
                }
                error == "network" || error?.startsWith("audd_error:") == true -> {
                    val msg = when {
                        error.contains("900") -> getString(R.string.dj_voice_recognize_token)
                        error.contains("901") || error.contains("902") -> getString(R.string.dj_voice_recognize_quota)
                        else -> getString(R.string.dj_voice_recognize_error)
                    }
                    speak(msg)
                    Telemetry.log(this, "REC erro: $error")
                }
                error != null -> speak(getString(R.string.dj_voice_recognize_error))
                result == null -> speak(getString(R.string.dj_voice_recognize_none))
                else -> {
                    speak(getString(R.string.dj_voice_recognize_recognized, result.title, result.artist))
                    val cur = Playback.currentSong
                    if (cur != null) {
                        MusicEditor.renameDetected(
                            applicationContext, cur,
                            result.title, result.artist, result.albumName
                        ) { ok ->
                            if (ok) {
                                speak(getString(R.string.dj_voice_recognize_renamed, result.title, result.artist))
                            }
                        }
                    }
                }
            }
        }
    }
}

    private fun chooseSource() {
        val options = arrayOf(
            getString(R.string.dj_source_all) to Settings.DJ_ALL,
            getString(R.string.dj_source_favorites) to Settings.DJ_FAVORITES
        )
        val labels = options.map { it.first }.toTypedArray()
        val current = options.indexOfFirst { it.second == source }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dj_source)
            .setSingleChoiceItems(labels, current) { d, which ->
                source = options[which].second
                Settings.setDjSource(this, source)
                d.dismiss()
                refreshConfigLabels()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseIntensity() {
        val options = listOf(
            getString(R.string.dj_intensity_calm) to Settings.DJ_CALM,
            getString(R.string.dj_intensity_balanced) to Settings.DJ_BALANCED,
            getString(R.string.dj_intensity_wild) to Settings.DJ_WILD
        )
        val labels = options.map { it.first }.toTypedArray()
        val current = options.indexOfFirst { it.second == intensity }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dj_intensity)
            .setSingleChoiceItems(labels, current) { d, which ->
                intensity = options[which].second
                Settings.setDjIntensity(this, intensity)
                d.dismiss()
                refreshConfigLabels()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshConfigLabels() {
        val srcLabel = if (source == Settings.DJ_FAVORITES) R.string.dj_source_favorites else R.string.dj_source_all
        val intLabel = when (intensity) {
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

    private fun nextInQueue(): Song? {
        val queue = Playback.queue
        if (queue.isEmpty()) return null
        val i = Playback.index
        return queue.getOrNull(i + 1) ?: queue.firstOrNull()
    }

    override fun onSongChanged(song: Song?, index: Int) {
        val app = applicationContext
        var announce: String? = null
        if (djActive) {
            val newId = song?.id ?: -1L
            if (newId != learnId) {
                if (learnId >= 0L) {
                    val ms = SystemClock.elapsedRealtime() - learnStartedAt
                    DjLearn.recordListenMs(app, learnId, ms)
                    if (!lastCompleted && !suppressNextLearnSkip) {
                        DjLearn.recordSkip(app, learnId)
                    }
                    announce = getString(R.string.dj_voice_track, song?.artist, song?.title)
                }
suppressNextLearnSkip = false
                                if (newId >= 0L) {
                                    DjLearn.recordPlay(app, newId)
                                }
                                learnId = newId
                                learnStartedAt = SystemClock.elapsedRealtime()
                                lastCompleted = false
                                if (Settings.djRadio(app)) {
                                    val fact = DjFacts.curiosityFor(song?.artist ?: "")
                                    if (fact != null) {
                                        announce = "${DjFacts.leadIn(intensity)} $fact " +
                                            getString(R.string.dj_voice_track, song?.artist, song?.title)
                                    }
                                }
            }
        }
        if (!djActive && announce == null && Settings.djRadio(this)) {
            val fact = DjFacts.curiosityFor(song?.artist ?: "")
            announce = if (fact != null) {
                "${DjFacts.leadIn(intensity)} $fact " +
                    getString(R.string.dj_voice_track, song?.artist, song?.title)
            } else {
                getString(R.string.dj_voice_track, song?.artist, song?.title)
            }
        }
        if (announce != null) speak(announce)
        render()
    }

    override fun onPlayStateChanged(isPlaying: Boolean) = render()

    override fun onProgress(positionMs: Long, durationMs: Long) {
        if (djActive && learnId >= 0L && durationMs > 0L && !lastCompleted &&
            positionMs >= 0.95 * durationMs.toDouble()
        ) {
            lastCompleted = true
        }
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

    companion object {
        private const val REQ_MIC = 1001
        private const val REQ_STORAGE = 1002
    }
}