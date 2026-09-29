package com.pulsa.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.collect.ImmutableList
import com.pulsa.player.MainActivity
import com.pulsa.player.R
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.data.VideoLibrary
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
import java.io.File
import kotlin.random.Random

// `UnstableApi` e um marcador de anotacao, nao um requisito de opt-in: o `@OptIn` aqui
// seria ignorado pelo compilador. Anotar a classe resolve de fato.
@UnstableApi
class PlaybackService : MediaLibraryService() {

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 10
        const val ACTION_TOGGLE = "com.pulsa.player.TOGGLE"
        const val ACTION_NEXT = "com.pulsa.player.NEXT"
        const val ACTION_PREV = "com.pulsa.player.PREV"
        const val FADE_STEPS = 10
        private const val PAN_STEP_MS = 150L
        private const val PAN_ANGLE_STEP = 0.105
        private const val USER_AGENT = "PulsaRadio/3.42 (Android)"

        /**
         * Quantas faixas seguidas podem falhar antes de desistir da fila. O limite era
         * `queue.size` (pula tudo), e com a fonte de dados errada TODAS as faixas falhavam: o
         * `advanceIndex` remontava fila + notificação no main thread uma vez por faixa e o app
         * deixava de responder. Três seguidas já é biblioteca quebrada, não faixa ruim — aí
         * para e pausa, que é o mesmo desfecho de sempre com uma música só.
         */
        private const val MAX_CONSECUTIVE_ERRORS = 3
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

    /**
     * E5 — o `Player` vivo do motor, para a tela de vídeo anexar o `SurfaceView` e ler estado
     * de buffer/erro. O transporte continua sendo por [Playback] (comandos da fachada).
     */
    val playerView: Player? get() = player

    private var player: ExoPlayer? = null
    private var awaitingReady = false
    private var playerLink: ServicePlayerLink? = null
    private lateinit var session: MediaLibraryService.MediaLibrarySession
    private lateinit var notificationManager: NotificationManager
    private var notificationChangedCallback: MediaNotification.Provider.Callback? = null
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
                        runCatching { player?.setVolume(0.35f) }
                        mainHandler.removeCallbacks(panRunnable)
                    } else if (isPlaying) {
                        pauseOnFocusLoss = true
                        pause()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ducked = true
                    runCatching { player?.setVolume(0.25f) }
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
            runCatching { player?.setVolume(0.25f) }
            return
        }
        if (micListening) {
            runCatching { player?.setVolume(0.35f) }
            return
        }
        updateEightD()
        if (!Settings.audio8d(this) || !isPlaying) {
            runCatching { player?.setVolume(1f) }
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
                    runCatching { player?.setVolume(0.35f) }
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
            if (fadeInRunnable != null) {
                mainHandler.postDelayed(this, PAN_STEP_MS)
                return
            }
            val p = player ?: return
            if (!p.isPlaying) return
            panAngle = (panAngle + PAN_ANGLE_STEP) % (2.0 * Math.PI)
            val pan = (Math.sin(panAngle) + 1.0) / 2.0
            // E3: sem volume estéreo no ExoPlayer, o 8D vira um "fôlego" de volume
            // (mesma curva e período do pan LR), não a posição em 2 canais.
            val volume = (0.22f + 0.78f * pan.toFloat()).coerceIn(0f, 1f)
            runCatching { p.setVolume(volume) }
            mainHandler.postDelayed(this, PAN_STEP_MS)
        }
    }

    private fun crossfadeMs(): Int = Settings.crossfadeMs(Settings.crossfade(this))

    val currentSong: Song? get() = queue.getOrNull(index.coerceAtLeast(0))
    val isPlaying: Boolean get() = player?.isPlaying == true

    /**
     * E4 — "tem som pra tocar": playWhenReady + READY/BUFFERING. O `Player.isPlaying` cai para
     * `false` durante `isLoading`/rebuffer, e stream de rádio re-buffereia o tempo todo (música
     * local quase nunca). O foreground NÃO pode ler esse flicker no background: com ele o
     * `onUpdateNotification` cai no super, o manager do Media3 decide que "acabou" e faz
     * `stopForeground(true)`/`stopSelf` — era o rádio morrendo junto com o app.
     */
    val playingLike: Boolean
        get() = player?.let { p ->
            p.playWhenReady &&
                (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
        } ?: false
    val audioSessionId: Int get() = player?.audioSessionId ?: 0

    private fun currentSongGenre(): String? =
        runCatching { currentSong?.id?.let { Library.genreOf(applicationContext, it) } }.getOrNull()

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    /**
     * E3b: o [MediaSessionService] só devolve binder de mídia quando o intent chega com as
     * actions de sessão (`SERVICE_INTERFACE`/`MediaBrowserService`); para qualquer outro — e o
     * `Playback.connect` liga sem action — ele devolve null, o que derruba o bind. Por isso o
     * override: sem action (o bind da fachada) sai o [LocalBinder] de sempre; as actions de
     * mídia passam para o base, que cuida da sessão do sistema e dos browsers legados.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == null) LocalBinder() else super.onBind(intent)
    }

    override fun onGetSession(controller: MediaSession.ControllerInfo):
        MediaLibraryService.MediaLibrarySession = session

    /**
     * E3b — enquanto toca, nós seguramos o foreground (ver `ensureForeground`) e NÃO
     * deixamos o `MediaSessionService` decidir por conta própria: a decisão dele depende do
     * controller interno (playWhenReady + STATE_READY) e, com o app em background, ele oscila
     * e chama `stopForeground(true)`, o que remove a nossa notificação da bandeja (foi exatamente
     * o sintoma: "a música continua e mãos livres aparecem, só a notificação some"). Pausado,
     * o `super` cuida do resto.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val song = currentSong
        if (startInForegroundRequired) {
            if (playingLike && song != null) {
                ensureForeground(song)
            } else {
                super.onUpdateNotification(session, true)
            }
            return
        }
        if (playingLike && song != null) {
            ensureForeground(song)
        } else {
            super.onUpdateNotification(session, false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        createChannel()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(
                // E4: o datasource HTTP cobre rádio (UA + redirect + timeouts). E5: o vídeo é
                // `content://` e a música `file://` — passá-los POR UM DefaultDataSource embaixo
                // para não os jogar no DefaultHttpDataSource (que só aceita http/https). Sem o
                // wrapper, música e vídeo locais morriam com erro de fonte.
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(
                        this,
                        DefaultHttpDataSource.Factory()
                            .setUserAgent(USER_AGENT)
                            .setConnectTimeoutMs(10000)
                            .setReadTimeoutMs(10000)
                            .setAllowCrossProtocolRedirects(true)
                    )
                )
            )
            .build()
            .also { it.addListener(playerListener) }
        // E6: o callback vai no CONSTRUTOR do `MediaLibrarySession.Builder` (é o único lugar
        // que aceita `MediaLibrarySession.Callback`), e é o mesmo objeto do E3b. Não chamar
        // `.setCallback()` aqui: o `MediaSession.Builder` base devolveria o `MediaSession`
        // simples e a parte de biblioteca deixaria de existir.
        session = MediaLibraryService.MediaLibrarySession.Builder(this, player!!, sessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        setMediaNotificationProvider(pulsaNotificationProvider)
        // F1/E2: o serviço se anuncia na fachada pelo `PlayerLink`, em vez de expor
        // `Playback.service`. O widget e os broadcasts dependem disso para desenhar estado
        // sem connectar — por isso o registro mora aqui e não no `bindService`.
        playerLink = ServicePlayerLink(this)
        Playback.attach(playerLink!!)
        if (queue.isEmpty()) {
            // Processo renasceu com rádio no ar (ex.: encerrado no gesto): volta a tocar.
            runCatching { restoreRadioResume() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        stopEightD()
        fadeInRunnable?.let { fadeHandler.removeCallbacks(it) }
        playerLink?.let { Playback.detach(it) }
        if (::session.isInitialized) session.release()
        player?.let {
            it.removeListener(playerListener)
            AudioFx.release()
            MusicVisualizer.detach()
            it.release()
        }
        player = null
        super.onDestroy()
    }

    fun refreshFx() {
        player?.let {
            if (it.audioSessionId > 0) {
                AudioFx.apply(applicationContext, it.audioSessionId, currentSongGenre())
            }
        }
        updateEightD()
    }

    private fun applyDanceParams() {
        player?.let { p ->
            runCatching {
                p.setPlaybackParameters(
                    PlaybackParameters(Settings.danceSpeed(this), Settings.dancePitch(this))
                )
            }
        }
    }

    fun applyDanceParamsForRefresh() {
        val playing = isPlaying
        player?.pause()
        applyDanceParams()
        if (playing) player?.play()
    }

    private fun updateEightD() {
        mainHandler.removeCallbacks(panRunnable)
        if (!Settings.audio8d(this) || !isPlaying) return
        runCatching { player?.setVolume(1f) }
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
        runCatching { player?.setVolume(1f) }
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
            runCatching { player?.setVolume(1f) }
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

    private fun currentPosMs(): Long = player?.currentPosition ?: 0L

    private fun durationMs(): Long = player?.duration?.let { if (it > 0L) it else 0L } ?: 0L

    private fun removeSleepFade() {
        sleepFadeRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepFadeRunnable = null
    }

    /** Diminui o volume lentamente e pausa quando o mix dormir completa o set. */
    private fun startSleepFade() {
        removeSleepFade()
        val p = player ?: return
        if (p !== player) return
        val stepMs = 900L
        var step = 0
        val r = object : Runnable {
            override fun run() {
                step++
                val volume = 1f - step.toFloat() / FADE_STEPS
                runCatching { p.setVolume(volume.coerceAtLeast(0f)) }
                if (step >= FADE_STEPS) {
                    sleepFadeRunnable = null
                    if (p !== player) return
                    sleepMix = false
                    pause()
                    runCatching { p.setVolume(1f) }
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
        refreshNotification()
        Playback.notifySong(updated, i)
    }

    fun toggle() {
        if (isPlaying) pause() else play()
    }

    fun play() {
        val p = player ?: return
        if (!p.isPlaying) p.play()
        requestAudioFocus()
        startEightD()
        scheduleTick()
        publishState()
        applyDanceParams()
    }

    fun pause() {
        val p = player ?: return
        if (p.isPlaying) p.pause()
        saveResumeState()
        // Pausa deliberada (botão/fone/erro fatal): não voltar com o rádio sozinho se o
        // processo renascer. O saveResumeState acima recém-gravou o rádio; limpo depois.
        Settings.clearRadioResume(this)
        if (!pauseOnFocusLoss) abandonAudioFocus()
        stopEightD()
        mainHandler.removeCallbacks(progressTick)
        publishState()
    }

    /**
     * E5 — interrompe a fila e zera o motor. É a "parada" que a fachada não tinha: sair da
     * tela de vídeo sem música anterior não pode deixar o último vídeo encalhado na fila
     * (a música que "invade" o nada). Não para o serviço: ele continua de pé ocioso.
     * Mantém o `resume` de música intacto — vídeo nunca chega a gravá-lo.
     */
    fun stop() {
        val p = player ?: return
        runCatching { p.stop() }
        runCatching { p.clearMediaItems() }
        runCatching { p.setPlayWhenReady(false) }
        queue = emptyList()
        index = -1
        positionMs = 0L
        consecutiveErrors = 0
        Settings.clearRadioResume(this)
        stopEightD()
        mainHandler.removeCallbacks(progressTick)
        Playback.notifySong(null, -1)
        Playback.notifyPlayState(false)
        refreshNotification()
    }

    fun seekTo(ms: Long) {
        try {
            player?.seekTo(ms)
            emitProgress()
        } catch (e: Exception) {
        }
    }

    fun next() = advanceIndex(1)

    fun prev() {
        val pos = player?.currentPosition ?: 0
        if (pos > 3000) {
            player?.seekTo(0)
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
            if (song.path.isBlank()) throw IllegalStateException("sem arquivo")
            val p = player ?: return
            awaitingReady = true
            p.setPlayWhenReady(false)
            p.setMediaItems(
                queue.map { mediaItemFor(it) },
                index.coerceIn(0, queue.lastIndex),
                0L
            )
            p.prepare()
            if (!song.isRadio && !song.isVideo) LastFm.nowPlaying(applicationContext, song, song.durationMs)
            Playback.notifySong(song, index)
            loadLargeIcon(song)
            announceInBackground(song)
        } catch (e: Exception) {
            onTrackError()
        }
    }

    private fun mediaItemFor(song: Song): MediaItem {
        val uri = song.videoId?.let { VideoLibrary.contentUri(it) }
            ?: song.radioUrl?.let(Uri::parse)
            ?: Uri.fromFile(File(song.path))
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .build()
            )
            .build()
    }

    /** Religou os efeitos na sessão de áudio do ExoPlayer (estável por instância). */
    private fun attachEffects() {
        val p = player ?: return
        val sessionId = p.audioSessionId
        if (sessionId <= 0) return
        AudioFx.apply(applicationContext, sessionId, currentSongGenre())
        MusicVisualizer.attach(sessionId)
    }

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            attachEffects()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> onPlayerReady()
                Player.STATE_ENDED -> onTrackEnded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // E3b: comandos vindos de fora (SystemUI, fone, MediaController) tocam o player
            // direto pela sessão — aqui o serviço sincroniza o que a reprodução precisa fazer
            // por conta própria. Os chamadores internos passam por play()/pause(), e o set é
            // idempotente, então o dobro de execução não muda nada.
            syncExternalPlaybackState(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            onTrackError()
        }
    }

    /**
     * E3b — o roteamento da sessão Media3: comandos que o serviço precisa tratar com a própria
     * lógica (próxima/anterior refazem a fila no ExoPlayer; um `seekToNext` do sistema depois
     * seria uma segunda passada) são interceptados e bloqueados com um resultado de erro — o
     * [MediaSessionService] não re-executa, e o guia é só receber o "não", sem retry. Play/pause
     * e seek ficam com o comportamento padrão da sessão (a execução certa pra cada botão), e o
     * `onIsPlayingChanged` do listener cuida do resto. O STOP volta a ser no-op, como era no
     * callback legado da `MediaSessionCompat`.
     */
    /**
     * E6 — a árvore do Auto/Wear/Assistant, servida pela MESMA sessão e o MESMO player do app.
     * É aqui que `setMediaItems` de um browser externo entra na nossa fila, em vez de num
     * player separado.
     *
     * Fica no MESMO objeto que o [sessionCallback] de E3b, e não em um callback à parte,
     * porque o `MediaLibrarySession.Builder` aceita um callback só: separar os dois faria o
     * builder usar só um deles e perder o outro. `MediaLibrarySession.Callback` estende
     * `MediaSession.Callback`, então os dois conjuntos de override convivem aqui.
     */
    private val libraryTree by lazy { PulsaLibraryTree(applicationContext) }

    private val sessionCallback = object : MediaLibraryService.MediaLibrarySession.Callback {
        // `onPlayerCommandRequest` foi depreciado no Media3 1.3, mas ainda e o gancho que
        // entrega next/prev ao botao do carro e ao controle do Wear. Suprimido aqui de
        // proposito; migrar para `onConnect` + `Player` commands fica para quando a API
        // substituta estiver estavel.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onPlayerCommandRequest(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int = when (playerCommand) {
            // `COMMAND_SEEK_TO_NEXT_WINDOW` e `COMMAND_SEEK_TO_PREVIOUS_WINDOW` foram
            // renomeados e valem o mesmo numero dos `..._MEDIA_ITEM`: deixa-los aqui daria
            // "duplicate label" e o when nem compilava.
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT -> {
                next()
                SessionResult.RESULT_ERROR_UNKNOWN
            }

            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS -> {
                prev()
                SessionResult.RESULT_ERROR_UNKNOWN
            }

            Player.COMMAND_STOP -> SessionResult.RESULT_ERROR_UNKNOWN
            else -> SessionResult.RESULT_SUCCESS
        }

        // --- E6: a árvore que o Auto/Wear/Assistant navegam. Fica no mesmo callback porque
        // o `MediaLibrarySession.Builder` aceita um callback só.

        override fun onGetLibraryRoot(
            mediaSession: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ) = libraryTree.rootFuture(params)

        override fun onGetItem(
            mediaSession: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ) = libraryTree.itemFuture(mediaId, null)

        override fun onGetChildren(
            mediaSession: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ) = libraryTree.childrenFuture(parentId, page, pageSize, params)

        override fun onSearch(
            mediaSession: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?
        ) = libraryTree.voidFuture(params)

        override fun onGetSearchResult(
            mediaSession: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ) = libraryTree.searchFuture(query, page, pageSize, params)

        /**
         * O browser manda os `MediaItem` que quer tocar e o Media3 pede os completos de volta,
         * resolvendo pelo `mediaId`. É o que faz o "tocar" do sistema cair na NOSSA fila: o
         * item devolvido é o mesmo `MediaItem` que o app monta, então a sessão toca sem
         * traduzir nada.
         */
        // `onAddMediaItems` e `onSetMediaItems` são herdados de `MediaSession.Callback`, e lá o
        // primeiro parâmetro é `MediaSession` (não `MediaLibrarySession`, como nos callbacks de
        // biblioteca). O Kotlin exige correspondência exata no override, então este `MediaSession`
        // é obrigatório — foi o que compilation failed.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val out = mediaItems.map { item ->
                libraryTree.itemById(item.mediaId) ?: item
            }
            return Futures.immediateFuture(out.toMutableList())
        }

        /**
         * Um browser externo (Assistant, Wear, botão do carro) pedindo "tocar isto" chega aqui,
         * e o default do Media3 entregaria os `MediaItem` direto ao `ExoPlayer`. Isso deixaria
         * a nossa `queue` de `Song` desatualizada: `currentSong` continuaria null e a
         * notificação, o widget e o next/prev do app passariam a descrever a faixa errada.
         * Então traduzimos para `Song` e seguimos pelo nosso próprio `start()`, que é quem
         * monta a fila, prepara e publica o estado.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val songs = mediaItems.mapNotNull { libraryTree.songFor(it.mediaId) }
            if (songs.isEmpty()) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(ImmutableList.of(), 0, 0L)
                )
            }
            val at = startIndex.coerceIn(0, songs.size - 1)
            start(songs, at)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(ImmutableList.copyOf(mediaItems), at, startPositionMs)
            )
        }
    }

    private var bgVoice: DjVoice? = null
    private var bgLastAnnounceId = -1L

    private fun announceInBackground(song: Song) {
        if (song.isVideo) return
        if (Playback.listener != null) return
        if (!Settings.djRadio(this) || !Settings.djVoice(this)) return
        if (song.id == bgLastAnnounceId) return
        bgLastAnnounceId = song.id
        val voice = bgVoice ?: DjVoice(
            this, Settings.languageTag(Settings.language(this))
        ).also { bgVoice = it }
        val leading = DjFacts.leadIn(Settings.djIntensity(this))
        val track = getString(R.string.dj_voice_track, song.title, song.artist)
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
        val fact = DjFacts.curiosityFor(song.artist)
        if (fact != null) {
            DjFacts.markCuriositySpoken()
            speakNow("$leading $fact $track")
            return
        }
        speakNow(track)
        DjFacts.fetchRemoteCuriosity(this, song.artist) { remote ->
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

    private fun onPlayerReady() {
        if (!awaitingReady) return
        awaitingReady = false
        val p = player ?: return
        consecutiveErrors = 0
        restoreSavedPosition(p)
        val fadeMs = crossfadeMs()
        attachEffects()
        runCatching { p.setVolume(if (fadeMs > 0) 0f else 1f) }
        runCatching { p.play() }
        if (fadeMs > 0) fadeIn(p, fadeMs)
        requestAudioFocus()
        publishState()
        scheduleTick()
        startEightD()
        applyDanceParams()
    }

    private fun restoreSavedPosition(p: ExoPlayer) {
        if (!Settings.resumeOn(this)) return
        val song = currentSong ?: return
        if (song.id != Settings.resumeSongId(this)) return
        val savedPos = Settings.resumePosition(this)
        val dur = p.duration
        if (dur > 0L && savedPos in 5001L..(dur - 10000)) {
            runCatching { p.seekTo(savedPos) }
        }
    }

    private fun fadeIn(p: ExoPlayer, ms: Int) {
        fadeInRunnable?.let { fadeHandler.removeCallbacks(it) }
        fadeInRunnable = null
        val stepMs = ms / FADE_STEPS
        var step = 0
        val r = object : Runnable {
            override fun run() {
                step++
                val volume = step.toFloat() / FADE_STEPS
                runCatching { p.setVolume(volume.coerceAtMost(1f)) }
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
                player?.seekTo(abA)
                player?.play()
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
                player?.seekTo(0)
                player?.play()
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
                player?.seekTo(0)
            } catch (e: Exception) {
            }
        }
    }

    private fun onTrackError() {
        consecutiveErrors++
        Playback.notifyTrackError(currentSong)
        if (queue.isNotEmpty() && consecutiveErrors <= MAX_CONSECUTIVE_ERRORS) {
            advanceIndex(1)
        } else {
            consecutiveErrors = 0
            pause()
        }
    }

    private fun publishState() {
        Playback.notifyPlayState(isPlaying)
        if (isPlaying) {
            currentSong?.let { ensureForeground(it) }
            // Mãos-livres acompanha a reprodução: liga ao tocar, desliga ao pausar.
            com.pulsa.player.dj.Hotword.startIfNeeded(this)
        } else {
            com.pulsa.player.dj.Hotword.stopIfRunning(this)
        }
        refreshNotification()
    }

    fun currentArt(): android.graphics.Bitmap? = largeIcon

    private fun scheduleTick() {
        mainHandler.removeCallbacks(progressTick)
        mainHandler.post(progressTick)
    }

    private fun emitProgress() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.let { if (it > 0L) it else 0L }
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
        if (song.isVideo) return
        if (song.isRadio) {
            song.radioUrl?.let { Settings.setRadioResume(this, it, song.title, song.artist) }
            return
        }
        val pos = player?.currentPosition ?: 0L
        if (pos > 0) {
            Settings.setResumeState(this, song.id, pos, song.title, song.artist)
        }
    }

    private fun clearResumeState() {
        if (Settings.resumeSongId(this) < 0L) return
        Settings.setResumeState(this, -1L, 0L, "", "")
    }

    /** E4 — estende o restore do `resume`: o rádio volta se o processo renasceu com a estação no ar. */
    private fun restoreRadioResume() {
        if (!Settings.resumeOn(this)) return
        val saved = Settings.radioResume(this) ?: return
        val (url, title, genre) = saved
        Settings.clearRadioResume(this)
        val song = Song(
            id = (url.hashCode() and 0x7fffffff).toLong(),
            title = title.ifBlank { getString(R.string.radio) },
            artist = genre,
            album = getString(R.string.radio),
            albumId = 0L,
            durationMs = 0L,
            path = Song.RADIO_PREFIX + url,
            year = 0
        )
        start(listOf(song), 0)
    }

    private fun scrobbleCurrentIfNeeded() {
        val song = currentSong ?: return
        if (!Settings.lastFmKey(this).isNullOrBlank() &&
            Settings.lastFmUser(this).isNotBlank() && Settings.lastFmSession(this).isNotBlank()
        ) {
            val dur = player?.duration?.let { if (it > 0L) it else 0L } ?: 0L
            if (dur >= 30000 && positionMs >= dur * 0.5) {
                LastFm.scrobble(applicationContext, song, dur)
            }
        }
    }

    private fun publishMetadata(song: Song) {
        val p = player ?: return
        // E3b: a metadata da sessão vem dos MediaItems do player; trocar o item da posição
        // corrente dispara o snapshot novo (SystemUI/notificação do sistema).
        runCatching {
            p.replaceMediaItem(
                p.currentMediaItemIndex,
                p.getMediaItemAt(p.currentMediaItemIndex).buildUpon().setMediaMetadata(mediaItemFor(song).mediaMetadata).build()
            )
        }
    }

    /**
     * E3b: comandos de fora tocam o player direto pela sessão; este set é o que a reprodução
     * faz por conta própria. Idempotente de propósito: `play()`/`pause()` da fachada rodam o
     * mesmo conjunto logo depois do `onIsPlayingChanged` disparar.
     */
    private fun syncExternalPlaybackState(playing: Boolean) {
        if (playing) {
            requestAudioFocus()
            startEightD()
            scheduleTick()
            applyDanceParams()
            publishState()
        } else {
            saveResumeState()
            if (!pauseOnFocusLoss) abandonAudioFocus()
            stopEightD()
            mainHandler.removeCallbacks(progressTick)
            publishState()
        }
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
                    .setMediaSession(session.getSessionCompatToken())
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(R.drawable.ic_skip_prev, getString(R.string.prev_action), prevIntent)
            .addAction(playIcon, getString(R.string.play), playPauseIntent)
            .addAction(R.drawable.ic_skip_next, getString(R.string.next_action), nextIntent)

        largeIcon?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    /**
     * E3b — o `MediaSessionService` decide quando entrar em foreground pelo controller interno
     * (playWhenReady + STATE_READY). Isso é frágil na hora de ir ao background: se a decisão
     * atrasar ou o controller oscilar, o serviço cai do foreground e o sistema mata o processo
     * ("toca uns minutos e para", notificação some junto). Enquanto toca, seguro o foreground
     * aqui também, com a nossa notificação (mesmo id 10) — igual ao E3a.
     */
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
    }

    private fun buildIdleNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    /**
     * E3b — o `MediaSessionService` exige um provider e cuida do foreground por conta própria
     * (sobe ao tocar, desce ao pausar). O provider devolve a **nossa** notificação de sempre
     * (mesmo layout, id 10, actions); nada de notificação dupla porque o padrão do Media3 não
     * roda junto.
     */
    private val pulsaNotificationProvider = object : MediaNotification.Provider {
        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback
        ): MediaNotification {
            notificationChangedCallback = onNotificationChangedCallback
            val song = currentSong
            val notification = if (song != null) buildNotification(song) else buildIdleNotification()
            return MediaNotification(NOTIFICATION_ID, notification)
        }

        override fun handleCustomCommand(
            mediaSession: MediaSession,
            action: String,
            extras: Bundle
        ): Boolean = false
    }

    private fun refreshNotification() {
        PulsaWidget.refresh(this)
        val song = currentSong ?: return
        val cb = notificationChangedCallback ?: return
        runCatching {
            cb.onNotificationChanged(MediaNotification(NOTIFICATION_ID, buildNotification(song)))
        }
    }

    private fun loadLargeIcon(song: Song) {
        if (song.isVideo) return
        ThreadPool.post {
            val bmp = ArtLoader.decode(applicationContext, song.albumId, song.path)
            ThreadPool.onUi {
                if (bmp != null) {
                    largeIcon = bmp
                    if (currentSong?.id == song.id) {
                        refreshNotification()
                        PulsaWidget.refresh(applicationContext)
                    }
                }
            }
        }
    }
}
