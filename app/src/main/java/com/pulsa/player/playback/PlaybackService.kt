package com.pulsa.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.pulsa.player.MainActivity
import com.pulsa.player.R
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.model.Song
import com.pulsa.player.audio.AudioFx
import com.pulsa.player.dj.DjFacts
import com.pulsa.player.dj.DjVoice
import com.pulsa.player.sync.LastFm
import com.pulsa.player.widget.PulsaWidget
import com.pulsa.player.audio.MusicVisualizer
import com.pulsa.player.audio.SleepTimer
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import kotlin.random.Random

class PlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 10
        const val ACTION_TOGGLE = "com.pulsa.player.TOGGLE"
        const val ACTION_NEXT = "com.pulsa.player.NEXT"
        const val ACTION_PREV = "com.pulsa.player.PREV"
        const val FADE_STEPS = 10
        private const val PAN_STEP_MS = 150L
        private const val PAN_ANGLE_STEP = 0.105
    }

    var queue: List<Song> = emptyList()
        private set
    var index: Int = -1
        private set
    var shuffle: Boolean = false
        private set
    var repeatAll: Boolean = true
        private set
    var repeatOne: Boolean = false
        private set

    var abA: Long = -1L
        private set
    var abB: Long = -1L
        private set
    val abActive: Boolean get() = abA >= 0L && abB > abA

    var positionMs: Long = 0L
        private set

    private var mp: MediaPlayer? = null
    private lateinit var session: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private var largeIcon: android.graphics.Bitmap? = null
    private var audioManager: AudioManager? = null

    @Volatile
    private var ducked = false

    @Volatile
    private var pauseOnFocusLoss = false

    @Volatile
    private var micListening = false

    @Volatile
    private var micDucked = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fadeHandler = Handler(Looper.getMainLooper())
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var fadeOutRunnable: Runnable? = null
    private var fadeInRunnable: Runnable? = null
    private var sleepMix = false
    private var sleepFadeRunnable: Runnable? = null
    private var consecutiveErrors = 0
    private var lastResumeSaveMs = 0L
    private val progressTick = object : Runnable {
        override fun run() {
            emitProgress()
            if (isPlaying) mainHandler.postDelayed(this, 500)
        }
    }

    private val focusListener = object : AudioManager.OnAudioFocusChangeListener {
        override fun onAudioFocusChange(change: Int) {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    pauseOnFocusLoss = false
                    pause()
                    runCatching { audioManager?.abandonAudioFocus(this) }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (micListening) {
                        // Reconhecedor de voz da Virgin ativo: abaixa a musica em vez de pausar.
                        micDucked = true
                        runCatching { mp?.setVolume(0.35f, 0.35f) }
                        mainHandler.removeCallbacks(panRunnable)
                    } else if (isPlaying) {
                        pauseOnFocusLoss = true
                        pause()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ducked = true
                    runCatching { mp?.setVolume(0.25f, 0.25f) }
                    mainHandler.removeCallbacks(panRunnable)
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (micDucked || ducked) {
                        micDucked = false
                        ducked = false
                        restoreVolume()
                    }
                    if (pauseOnFocusLoss) {
                        pauseOnFocusLoss = false
                        play()
                    }
                }
            }
        }
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        runCatching {
            am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        runCatching { am.abandonAudioFocus(focusListener) }
    }

    private fun restoreVolume() {
        if (ducked) {
            runCatching { mp?.setVolume(0.25f, 0.25f) }
            return
        }
        if (micListening) {
            runCatching { mp?.setVolume(0.35f, 0.35f) }
            return
        }
        updateEightD()
        if (!Settings.audio8d(this) || !isPlaying) {
            runCatching { mp?.setVolume(1f, 1f) }
        }
    }

    /** A Virgin esta ouvindo via microfone: baixa (nao pausa) a musica. */
    fun setMicListening(on: Boolean) {
        if (micListening == on) return
        micListening = on
        mainHandler.post {
            if (on) {
                if (isPlaying) {
                    micDucked = true
                    runCatching { mp?.setVolume(0.35f, 0.35f) }
                    mainHandler.removeCallbacks(panRunnable)
                }
            } else if (micDucked) {
                micDucked = false
                restoreVolume()
            }
        }
    }

    private var panAngle = 0.0
    private val panRunnable = object : Runnable {
        override fun run() {
            if (fadeOutRunnable != null || fadeInRunnable != null) {
                mainHandler.postDelayed(this, PAN_STEP_MS)
                return
            }
            val player = mp
            if (player == null || !player.isPlaying) return
            panAngle = (panAngle + PAN_ANGLE_STEP) % (2.0 * Math.PI)
            val pan = (Math.sin(panAngle) + 1.0) / 2.0
            val left = Math.sqrt(1.0 - pan).toFloat()
            val right = Math.sqrt(pan).toFloat()
            runCatching { player.setVolume(left, right) }
            mainHandler.postDelayed(this, PAN_STEP_MS)
        }
    }

    private fun crossfadeMs(): Int = Settings.crossfadeMs(Settings.crossfade(this))
    private fun isCrossfadeOn(): Boolean = crossfadeMs() > 0

    val currentSong: Song? get() = queue.getOrNull(index.coerceAtLeast(0))
    val isPlaying: Boolean get() = mp?.isPlaying == true
    val audioSessionId: Int get() = mp?.audioSessionId ?: 0

    private fun currentSongGenre(): String? =
        runCatching { currentSong?.id?.let { Library.genreOf(applicationContext, it) } }.getOrNull()

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        createChannel()
        session = MediaSessionCompat(this, "PulsaPlayback").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = toggle()
                override fun onPause() = toggle()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = prev()
                override fun onSeekTo(pos: Long) = seekTo(pos)
            })
        }
        Playback.service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopEightD()
        fadeOutRunnable?.let { fadeHandler.removeCallbacks(it) }
        fadeInRunnable?.let { fadeHandler.removeCallbacks(it) }
        mp?.release()
        AudioFx.release()
        MusicVisualizer.detach()
        session.release()
        if (Playback.service === this) Playback.service = null
        super.onDestroy()
    }

    fun refreshFx() {
        mp?.let { AudioFx.apply(applicationContext, it.audioSessionId, currentSongGenre()) }
        updateEightD()
    }

    private fun applyDanceParams() {
        mp?.let { p ->
            runCatching {
                p.playbackParams = p.playbackParams
                    .setSpeed(Settings.danceSpeed(this))
                    .setPitch(Settings.dancePitch(this))
            }
        }
    }

    fun applyDanceParamsForRefresh() {
        val playing = isPlaying
        runCatching { mp?.pause() }
        applyDanceParams()
        if (playing) runCatching { mp?.start() }
    }

    private fun updateEightD() {
        mainHandler.removeCallbacks(panRunnable)
        if (!Settings.audio8d(this) || !isPlaying) return
        runCatching { mp?.setVolume(1f, 1f) }
        panAngle = 0.0
        mainHandler.post(panRunnable)
    }

    private fun startEightD() {
        if (!Settings.audio8d(this)) return
        panAngle = 0.0
        mainHandler.removeCallbacks(panRunnable)
        mainHandler.post(panRunnable)
    }

    private fun stopEightD() {
        mainHandler.removeCallbacks(panRunnable)
        runCatching { mp?.setVolume(1f, 1f) }
    }

    fun setShuffle(value: Boolean) {
        shuffle = value
    }

    fun setRepeatAll(value: Boolean) {
        repeatAll = value
        if (value) repeatOne = false
    }

    fun setRepeatOne(value: Boolean) {
        repeatOne = value
        if (value) repeatAll = false
    }

    /** Liga/desliga o modo dormir (mix CALM): ao fechar o set, fade e pausa no lugar de repetir. */
    fun setSleepMix(on: Boolean) {
        if (sleepMix == on) return
        sleepMix = on
        if (!on) {
            removeSleepFade()
            runCatching { mp?.setVolume(1f, 1f) }
        }
    }

    /** Marca o ponto A (início do loop) na posição atual da faixa. */
    fun setMarkerA() {
        val pos = currentPosMs()
        if (pos <= 0L) return
        abA = pos
        abB = -1L
    }

    /** Marca o ponto B (fim do loop) na posição atual, logo após o A. */
    fun setMarkerB() {
        if (abA < 0L) return
        val pos = currentPosMs()
        if (pos > abA) abB = pos.coerceAtMost(durationMs())
    }

    fun clearAbLoop() {
        abA = -1L
        abB = -1L
    }

    private fun resetAbLoop() {
        abA = -1L
        abB = -1L
    }

    private fun currentPosMs(): Long = try {
        mp?.currentPosition?.toLong() ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun durationMs(): Long = try {
        mp?.duration?.toLong() ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun removeSleepFade() {
        sleepFadeRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepFadeRunnable = null
    }

    /** Diminui o volume lentamente e pausa quando o mix dormir completa o set. */
    private fun startSleepFade() {
        removeSleepFade()
        val player = mp ?: return
        if (player !== mp) return
        val stepMs = 900L
        var step = 0
        val r = object : Runnable {
            override fun run() {
                step++
                val volume = 1f - step.toFloat() / FADE_STEPS
                runCatching { player.setVolume(volume.coerceAtLeast(0f), volume.coerceAtLeast(0f)) }
                if (step >= FADE_STEPS) {
                    sleepFadeRunnable = null
                    if (player !== mp) return
                    sleepMix = false
                    pause()
                    runCatching { player.setVolume(1f, 1f) }
                } else {
                    sleepHandler.postDelayed(this, stepMs)
                }
            }
        }
        sleepFadeRunnable = r
        sleepHandler.post(r)
    }

    fun cycleRepeat() {
        when {
            repeatOne -> repeatOne = false
            repeatAll -> {
                repeatOne = true
                repeatAll = false
            }
            else -> repeatAll = true
        }
    }

    fun start(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        queue = songs.toList()
        index = startIndex.coerceIn(0, queue.size - 1)
        prepareCurrent()
    }

    fun refreshCurrentMeta() {
        val song = currentSong ?: return
        val meta = runCatching {
            PlaylistDb.get(this).songMeta(song.id)
        }.getOrNull() ?: return
        if (meta.title.isBlank()) return
        val i = index.coerceAtLeast(0)
        if (i >= queue.size) return
        val updated = song.copy(
            title = meta.title,
            artist = if (meta.artist.isBlank()) song.artist else meta.artist,
            album = if (meta.album.isBlank()) song.album else meta.album
        )
        val q = queue.toMutableList()
        q[i] = updated
        queue = q
        publishMetadata(updated)
        updateNotification()
        Playback.notifySong(updated, i)
    }

    fun toggle() {
        if (isPlaying) pause() else play()
    }

    fun play() {
        val p = mp ?: return
        if (!p.isPlaying) p.start()
        requestAudioFocus()
        startEightD()
        scheduleTick()
        publishState()
        applyDanceParams()
    }

    fun pause() {
        val p = mp ?: return
        if (p.isPlaying) p.pause()
        saveResumeState()
        if (!pauseOnFocusLoss) abandonAudioFocus()
        stopEightD()
        mainHandler.removeCallbacks(progressTick)
        publishState()
    }

    fun seekTo(ms: Long) {
        mp?.let { p ->
            try {
                p.seekTo(ms.toInt())
            } catch (e: Exception) {
            }
            emitProgress()
        }
    }

    fun next() = advanceIndex(1)

    fun prev() {
        val pos = mp?.currentPosition ?: 0
        if (pos > 3000) {
            mp?.seekTo(0)
            emitProgress()
            return
        }
        advanceIndex(-1)
    }

    private fun advanceIndex(delta: Int) {
        if (queue.isEmpty()) return
        index = if (shuffle && queue.size > 1) {
            var nextIndex = index
            while (nextIndex == index) nextIndex = Random.nextInt(queue.size)
            nextIndex
        } else {
            val size = queue.size
            (((index + delta) % size) + size) % size
        }
        prepareCurrent()
    }

    private fun prepareCurrent() {
        val song = currentSong ?: return
        positionMs = 0L
        resetAbLoop()
        try {
            val old = mp
            if (old != null) {
                old.setOnPreparedListener(null)
                old.setOnCompletionListener(null)
                old.setOnErrorListener(null)
            }
            if (isCrossfadeOn() && old != null && old.isPlaying) {
                fadeOut(old, crossfadeMs())
            } else {
                old?.release()
            }
            val player = MediaPlayer()
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                player.setDataSource(song.path)
                player.setOnPreparedListener { onPrepared(player) }
                player.setOnCompletionListener { onTrackEnded() }
                player.setOnErrorListener { _, _, _ -> onTrackError(); true }
                player.prepareAsync()
            } catch (e: Exception) {
                runCatching { player.release() }
                throw e
            }
            mp = player
            AudioFx.apply(applicationContext, player.audioSessionId, currentSongGenre())
            MusicVisualizer.attach(player.audioSessionId)
            ensureForeground(song)
            publishMetadata(song)
            LastFm.nowPlaying(applicationContext, song, song.durationMs)
            Playback.notifySong(song, index)
            announceInBackground(song)
        } catch (e: Exception) {
            onTrackError()
        }
    }

    private var bgVoice: DjVoice? = null
    private var bgLastAnnounceId = -1L

    private fun announceInBackground(song: Song) {
        if (Playback.listener != null) return
        if (!Settings.djRadio(this) || !Settings.djVoice(this)) return
        if (song.id == bgLastAnnounceId) return
        bgLastAnnounceId = song.id
        val voice = bgVoice ?: DjVoice(
            this, Settings.languageTag(Settings.language(this))
        ).also { bgVoice = it }
        val leading = DjFacts.leadIn(Settings.djIntensity(this))
        val track = getString(R.string.dj_voice_track, song.artist, song.title)
        val songId = song.id
        val speakNow = { text: String ->
            voice.init { ready ->
                if (ready && song.id == bgLastAnnounceId) voice.speak(text)
            }
        }
        if (!DjFacts.curiosityDue()) {
            speakNow(track)
            return
        }
        val fact = DjFacts.curiosityFor(song.artist ?: "")
        if (fact != null) {
            DjFacts.markCuriositySpoken()
            speakNow("$leading $fact $track")
            return
        }
        speakNow(track)
        DjFacts.fetchRemoteCuriosity(this, song.artist ?: "") { remote ->
            if (remote == null || Playback.listener != null) return@fetchRemoteCuriosity
            if (song.id != songId || !DjFacts.curiosityDue()) return@fetchRemoteCuriosity
            DjFacts.markCuriositySpoken()
            voice.init { ready ->
                if (ready && song.id == songId && Playback.listener == null) {
                    voice.speak("$leading $remote")
                }
            }
        }
    }

    private fun onPrepared(player: MediaPlayer) {
        if (player !== mp) return
        consecutiveErrors = 0
        restoreSavedPosition(player)
        val fadeMs = crossfadeMs()
        if (fadeMs > 0 && player != null) {
            runCatching { player.setVolume(0f, 0f) }
            try {
                player.start()
            } catch (e: Exception) {
            }
            fadeIn(player, fadeMs)
        } else {
            player?.let {
                try {
                    it.setVolume(1f, 1f)
                    it.start()
                } catch (e: Exception) {
                }
            }
        }
        requestAudioFocus()
        publishState()
        scheduleTick()
        startEightD()
        applyDanceParams()
    }

    private fun restoreSavedPosition(player: MediaPlayer) {
        if (!Settings.resumeOn(this)) return
        val song = currentSong ?: return
        if (song.id != Settings.resumeSongId(this)) return
        val savedPos = Settings.resumePosition(this)
        val dur = try {
            player.duration.toLong()
        } catch (e: Exception) {
            0L
        }
        if (savedPos in 5001L..(dur - 10000)) {
            runCatching { player.seekTo(savedPos.toInt()) }
        }
    }

    private fun fadeOut(player: MediaPlayer, ms: Int) {
        fadeOutRunnable?.let { fadeHandler.removeCallbacks(it) }
        fadeOutRunnable = null
        val stepMs = ms / FADE_STEPS
        var step = 0
        val r = object : Runnable {
            override fun run() {
                step++
                val volume = 1f - step.toFloat() / FADE_STEPS
                runCatching { player.setVolume(volume.coerceAtLeast(0f), volume.coerceAtLeast(0f)) }
                if (step >= FADE_STEPS) {
                    runCatching { player.release() }
                    fadeOutRunnable = null
                } else {
                    fadeHandler.postDelayed(this, stepMs.toLong())
                }
            }
        }
        fadeOutRunnable = r
        fadeHandler.post(r)
    }

    private fun fadeIn(player: MediaPlayer, ms: Int) {
        fadeInRunnable?.let { fadeHandler.removeCallbacks(it) }
        fadeInRunnable = null
        val stepMs = ms / FADE_STEPS
        var step = 0
        val r = object : Runnable {
            override fun run() {
                step++
                val volume = step.toFloat() / FADE_STEPS
                runCatching { player.setVolume(volume.coerceAtMost(1f), volume.coerceAtMost(1f)) }
                if (step < FADE_STEPS) {
                    fadeHandler.postDelayed(this, stepMs.toLong())
                } else {
                    fadeInRunnable = null
                }
            }
        }
        fadeInRunnable = r
        fadeHandler.post(r)
    }

    private fun onTrackEnded() {
        scrobbleCurrentIfNeeded()
        clearResumeState()
        SleepTimer.onTrackCompleted()
        if (SleepTimer.isActive() && SleepTimer.isEndOfTrack()) return
        if (abActive) {
            try {
                mp?.seekTo(abA.toInt())
                mp?.start()
                positionMs = abA
                emitProgress()
            } catch (e: Exception) {
            }
            publishState()
            scheduleTick()
            startEightD()
            return
        }
        if (repeatOne) {
            try {
                mp?.seekTo(0)
                mp?.start()
                positionMs = 0L
                emitProgress()
            } catch (e: Exception) {
            }
            publishState()
            scheduleTick()
            startEightD()
            return
        }
        if (sleepMix && queue.isNotEmpty() && index >= queue.lastIndex) {
            startSleepFade()
            return
        }
        if (repeatAll || index < queue.lastIndex) {
            advanceIndex(1)
        } else {
            stopEightD()
            pause()
            try {
                mp?.seekTo(0)
            } catch (e: Exception) {
            }
        }
    }

    private fun onTrackError() {
        consecutiveErrors++
        if (queue.isNotEmpty() && consecutiveErrors <= queue.size) {
            advanceIndex(1)
        } else {
            consecutiveErrors = 0
            pause()
        }
    }

    private fun publishState() {
        Playback.notifyPlayState(isPlaying)
        // Mãos-livres acompanha a reprodução: liga ao tocar, desliga ao pausar.
        if (isPlaying) com.pulsa.player.dj.Hotword.startIfNeeded(this)
        else com.pulsa.player.dj.Hotword.stopIfRunning(this)
        updateSessionState()
        updateNotification()
    }

    fun currentArt(): android.graphics.Bitmap? = largeIcon

    private fun scheduleTick() {
        mainHandler.removeCallbacks(progressTick)
        mainHandler.post(progressTick)
    }

    private fun emitProgress() {
        val p = mp ?: return
        val pos = try {
            p.currentPosition.toLong()
        } catch (e: Exception) {
            0L
        }
        val dur = try {
            p.duration.toLong().coerceAtLeast(0L)
        } catch (e: Exception) {
            0L
        }
        positionMs = pos
        Playback.notifyProgress(pos, dur)
        if (abActive && isPlaying && dur > 0L && abB <= dur && pos >= abB) {
            seekTo(abA)
            return
        }
        if (Settings.resumeOn(this) && pos - lastResumeSaveMs >= 5000L) {
            saveResumeState()
            lastResumeSaveMs = pos
        }
    }

    private fun saveResumeState() {
        val song = currentSong ?: return
        if (!Settings.resumeOn(this)) return
        val pos = try {
            (mp?.currentPosition ?: 0).toLong()
        } catch (e: Exception) {
            0L
        }
        if (pos > 0) {
            Settings.setResumeState(this, song.id, pos, song.title, song.artist)
        }
    }

    private fun clearResumeState() {
        if (Settings.resumeSongId(this) < 0L) return
        Settings.setResumeState(this, -1L, 0L, "", "")
    }

    private fun scrobbleCurrentIfNeeded() {
        val song = currentSong ?: return
        if (!Settings.lastFmKey(this).isNullOrBlank() &&
            Settings.lastFmUser(this).isNotBlank() && Settings.lastFmSession(this).isNotBlank()
        ) {
            val dur = try {
                (mp?.duration ?: 0).toLong()
            } catch (e: Exception) {
                0L
            }
            if (dur >= 30000 && positionMs >= dur * 0.5) {
                LastFm.scrobble(applicationContext, song, dur)
            }
        }
    }

    private fun publishMetadata(song: Song) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.durationMs)
                .build()
        )
        updateSessionState()
    }

    private fun updateSessionState() {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val pos = (mp?.currentPosition ?: 0).toLong()
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, pos, 1f, SystemClock.elapsedRealtime())
                .build()
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(song: Song): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, PlaybackService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(song.title)
            .setContentText(song.artist + " • " + song.album)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(R.drawable.ic_skip_prev, getString(R.string.prev_action), prevIntent)
            .addAction(playIcon, getString(R.string.play), playPauseIntent)
            .addAction(R.drawable.ic_skip_next, getString(R.string.next_action), nextIntent)

        largeIcon?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private fun ensureForeground(song: Song) {
        val notification = buildNotification(song)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        loadLargeIcon(song)
    }

    private fun updateNotification() {
        PulsaWidget.refresh(this)
        val song = currentSong ?: return
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(song))
        } catch (e: Exception) {
        }
    }

    private fun loadLargeIcon(song: Song) {
        ThreadPool.post {
            val bmp = ArtLoader.decode(applicationContext, song.albumId, song.path)
            ThreadPool.onUi {
                if (bmp != null) {
                    largeIcon = bmp
                    if (currentSong?.id == song.id) {
                        updateNotification()
                        PulsaWidget.refresh(applicationContext)
                    }
                }
            }
        }
    }
}
