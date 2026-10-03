package com.pulsa.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
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
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
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
import com.pulsa.player.media.StreamKind
import com.pulsa.player.model.Song
import com.pulsa.player.model.Video
import com.pulsa.player.model.toSong
import com.pulsa.player.audio.AudioFx
import com.pulsa.player.dj.DjFacts
import com.pulsa.player.dj.DjVoice
import com.pulsa.player.sync.LastFm
import com.pulsa.player.widget.PulsaWidget
import com.pulsa.player.audio.MusicVisualizer
import com.pulsa.player.audio.SleepTimer
import com.pulsa.player.core.RadioStations
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.dj.DjLearn
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

    /**
     * Faz o próximo `onPlayerReady` NÃO tocar.
     *
     * O `prepareCurrent` deixa `playWhenReady` desligado, mas isso não bastava: o
     * `onPlayerReady` chamava `play()` e `fadeIn()` sem olhar. Ou seja, restaurar a fila
     * ligava o som sozinho com fade — que era o oposto do combinado. Esta trava faz o
     * restore voltar de verdade pausado, e o `play()` do usuário passa a valer normal.
     */
    private var startPaused = false

    /**
     * Impede que o [start] chamado pelo próprio restore grave a fila de novo: nesse
     * momento o player ainda está na posição 0 e sobrescreveria a posição salva,
     * apagando exatamente o que o restore veio para recuperar.
     */
    private var suppressQueueSave = false
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

    /**
     * `true` desde o último `pause()` até o próximo `play()`.
     *
     * Existe por causa de um bug achado no E7: o `pause()` grava o `radio_resume` e logo em
     * seguida o apaga (pausa deliberada não pode ressuscitar a estação), mas o `p.pause()`
     * dentro dele dispara `onIsPlayingChanged` -> `syncExternalPlaybackState(false)`, que
     * chamava `saveResumeState()` DE NOVO — e isso acontece DEPOIS, porque o Media3 enfileira
     * o evento e o entrega via Handler. Resultado: o rádio ficava gravado em disco e voltava
     * sozinho no próximo `restoreRadioResume()`, o que o usuário viu ao pausar tirando o fone,
     * fechar o app e reabrir.
     *
     * Uma flag tipo "ligada só durante o clear" não resolveria, justamente porque o evento
     * chega tarde. Por isso a flag fica ligada até o próximo play, e o sync só deixa de gravar.
     *
     * O bug é anterior ao E7 (a duplicação é do E3b), mas só apareceu agora porque a pausa
     * passou a vir de fora, por broadcast, em vez do botão.
     */
    @Volatile
    private var pauseWasDeliberate = false

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

    /**
     * E7 — fone desconectado / cabo do carro puxado = pausa.
     *
     * O Android avisa por broadcast quando a saída de áudio deixa de ser o fone: é o mesmo
     * gatilho para Bluetooth e para o AUX do carro. Sem isto a música continuava no
     * alto-falante depois de arrancar o fone — e no carro isso significa o áudio saindo
     * pelos alto-falantes do carro para todo mundo ouvir.
     *
     * Registrado por código, e não no manifest, por dois motivos: `onDestroy` precisa
     * desregistrar (o manifest não tem como), e o `RECEIVER_NOT_EXPORTED` só existe via
     * `ContextCompat` — o `registerReceiver` cru em API 33+ exige a flag explícita.
     */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Só interessa se está tocando de fato: o broadcast também sai quando o app
            // pede foco e o sistema decide que o áudio vai para o alto-falante.
            if (isPlaying) pause()
        }
    }

    private var noisyReceiverRegistered = false

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        val ok = runCatching {
            ContextCompat.registerReceiver(
                this,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.isSuccess
        noisyReceiverRegistered = ok
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        noisyReceiverRegistered = false
        runCatching { unregisterReceiver(noisyReceiver) }
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
     * F2b — a pausa foi pedida por alguém, ou é só o motor entre uma faixa e outra?
     *
     * `isPlaying` vale `false` por dois motivos bem diferentes: a pessoa parou (e quer
     * continuar parado) ou o Media3 está no meio de uma troca de item, onde ele ainda não
     * voltou a tocar. Quem precisa distinguir é a tela de vídeo, que decide se toca ao
     * abrir: usar o `isPlaying` sozinho deixava o vídeo **pausado** quando ele tinha acabado
     * de virar o item atual por "next", que é justamente como a fila misturada funciona.
     *
     * A flag já existia para o resume do rádio; aqui é o mesmo sinal, com outro consumidor.
     */
    val pausedDeliberately: Boolean get() = pauseWasDeliberate

    /**
     * E4 — o foreground NÃO pode ler o `playbackState`.
     *
     * Esta condição já passou por duas versões. A primeira era `Player.isPlaying`, que cai
     * para `false` durante rebuffer — e stream de rádio re-bufera o tempo todo. A segunda,
     * a que está aqui, aceitava só `READY` e `BUFFERING`, o que consertou o rebuffer mas
     * criou o "a música continua e mãos livres aparecem, só a notificação some": **entre uma
     * faixa e outra** o motor passa por `STATE_ENDED` e `STATE_IDLE`, nenhum dos dois está
     * na lista, e o `onUpdateNotification` caía no `super` — que deixa o Media3 decidir que
     * "acabou" e chamar `stopForeground(true)`. Com vídeo e música dividindo a fila (F2b)
     * isso piorou, porque trocar de vídeo para música passa por estados que só o vídeo
     * produz.
     *
     * O que segura o foreground é a **intenção** de quem está usando, não o estado do
     * motor: `playWhenReady` continua `true` durante toda a transição. E o que faz a
     * notificação cair é o `pause()`, não o fim do estado: fila acabando é
     * `onTrackEnded` → `pause()`, e erro repetido é `onTrackError` → `pause()`, os dois
     * derrubando `playWhenReady`. Sem fila não há o que segurar.
     */
    val holdingForeground: Boolean
        get() = player?.playWhenReady == true && queue.isNotEmpty()
    val audioSessionId: Int get() = player?.audioSessionId ?: 0

    /**
     * Gênero do preset automático, e só de **áudio**.
     *
     * O `genreOf` consulta `MediaStore.Audio` por id, e o id de um vídeo pode ser o mesmo
     * de uma música (cada coleção numera por conta própria): sem esta guarda o vídeo
     * receberia o gênero da música com aquele número e o EQ mudaria junto. É o mesmo
     * cuidado do [QueueKey] — tipo e id andam juntos.
     */
    private fun currentSongGenre(): String? {
        val song = currentSong ?: return null
        if (song.isVideo || song.isStream || song.isRadio) return null
        return runCatching { Library.genreOf(applicationContext, song.id) }.getOrNull()
    }

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
     * o sintoma: "a música continua e mãos livres aparecem, só a notificação som"). Pausado,
     * o `super` cuida do resto.
     *
     * O critério é [holdingForeground], e não o estado do motor: deixar o `super` decidir
     * nos estados de transição (o que ele não distingue de "acabou") era o que sumia com a
     * notificação entre uma faixa e outra.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val song = currentSong
        if (startInForegroundRequired) {
            if (holdingForeground && song != null) {
                ensureForeground(song)
            } else {
                super.onUpdateNotification(session, true)
            }
            return
        }
        if (holdingForeground && song != null) {
            ensureForeground(song)
        } else {
            super.onUpdateNotification(session, false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        registerNoisyReceiver()
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
                    // F2: sem isto, uma legenda externa é tratada como **vídeo**. O Media3
                    // tenta adivinhar o tipo de um arquivo de texto e erra para video/mp4, o
                    // extractor procura moov/mdat num XML e o playback morre. O erro era mais
                    // visível no `.vtt`, bem menos comum que `.srt`, e o sintoma era "a tela
                    // de legenda não aparece e o vídeo trava".
                    //
                    // No 1.3.1 o conserto é o `DefaultSubtitleParserFactory` puro. O
                    // `SubtitleParser.Merging` (que junta várias trilhas numa saída só) só
                    // existe a partir da 1.4 — por isso **não** dá para usar aqui, e por isso
                    // a escolha manual de legenda substitui as trilhas em vez de somar.
                    .setSubtitleParserFactory(DefaultSubtitleParserFactory())
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
        // F2: a fachada precisa do contexto para ler/gravar a velocidade escolhida, e só
        // o serviço tem um `Context` de verdade (a tela de vídeo tem a Activity, que morre).
        Playback.appContext = applicationContext
        if (queue.isEmpty()) {
            // Processo renasceu com rádio no ar (ex.: encerrado no gesto): volta a tocar.
            runCatching { restoreRadioResume() }
        }
        if (queue.isEmpty()) {
            // Mesma coisa para a música: o processo foi morto (inclusive por instalação de
            // versão nova, caso em que o onDestroy nem chega a rodar) e a fila salva volta
            // PAUSADA na posição em que parou — o prepareCurrent já deixa playWhenReady
            // desligado, então basta montar a fila e posição sem chamar play().
            runCatching { restoreQueueState() }
        }
    }

    /**
     * Recarrega a fila salva e a deixa pronta, pausada, na posição salva.
     *
     * Só roda com a fila vazia — se o usuário já está tocando algo, o que está na tela
     * ganha. Devolve `true` se restaurou.
     */
    private fun restoreQueueState(): Boolean {
        if (!Settings.resumeOn(this)) return false
        val (keys, savedIndex, savedPos) = Settings.queueState(this) ?: return false
        if (keys.isEmpty()) return false
        // F2b: áudio e vídeo vêm de coleções separadas do MediaStore, cada uma numerada por
        // conta própria. Um `songsByIds` único não resolveria a fila misturada: o id do
        // vídeo 42 buscaria a música 42. Então cada chave vai para a biblioteca que é
        // dela, e a ordem salva é reconstruída por cima.
        val songIds = keys.filter { it.startsWith(QueueKey.VIDEO_PREFIX) == false }
            .mapNotNull { it.substringAfter(':').toLongOrNull() }
        val videoIds = keys.filter { it.startsWith(QueueKey.VIDEO_PREFIX) }
            .mapNotNull { it.substringAfter(':').toLongOrNull() }
        val songsById = HashMap<String, Song>(keys.size)
        Library.songsByIds(this, songIds).forEach { songsById[QueueKey.encode(it)!!] = it }
        val videosById = HashMap<Long, Video>(videoIds.size)
        if (videoIds.isNotEmpty()) {
            VideoLibrary.all(this).forEach { v -> if (videoIds.contains(v.id)) videosById[v.id] = v }
        }
        val songs = keys.mapNotNull { key ->
            songsById[key] ?: videosById[key.substringAfter(':').toLongOrNull()]?.let { videoSong(it) }
        }
        if (songs.isEmpty()) {
            // Toda a fila sumiu do MediaStore (fotos apagadas, sdcard removido). Não tenta
            // de novo em todo boot e não deixa lixo salvo para sempre.
            Settings.clearQueueState(this)
            return false
        }
        // Ajusta o índice: com faixa apagada, a posição original apontaria para a faixa errada.
        val index = QueueKey.reanchor(keys, savedIndex, QueueKey.encodeAll(songs))
        if (index < 0) {
            Settings.clearQueueState(this)
            return false
        }
        suppressQueueSave = true
        startPaused = true
        try {
            start(songs, index)
            val pos = savedPos.coerceAtLeast(0L)
            if (pos > 0L) {
                runCatching { seekTo(pos) }
            }
        } finally {
            suppressQueueSave = false
        }
        pauseWasDeliberate = true
        return true
    }

    /**
     * F2b — o item de fila de um vídeo do MediaStore.
     *
     * É a **mesma** forma que a [PulsaLibraryTree.songFor] e a `VideoPlayerActivity` montam,
     * e isso é deliberado: é o que permite a fila misturada tratar vídeo e música com o
     * mesmo código, e o que faz um vídeo vindo do Android Auto ser idêntico a um tocado
     * dentro do app.
     */
    private fun videoSong(video: Video) = video.toSong(getString(R.string.tab_videos))

    /**
     * Grava a fila atual para o próximo processo. Chamado de [saveResumeState], que por
     * sua vez roda no tick de posição — assim a fila e a posição andam juntas.
     *
     * **F2b: a fila é salva com chave tipada, e vídeo agora entra.** Antes ela gravava só
     * `song.id` e apagava o estado inteiro se a fila tivesse um único vídeo — daí o "não é
     * retomável". Com o [QueueKey] o vídeo é salvo como `v:<id>` e volta pelo
     * `VideoLibrary`, então a fila misturada sobrevive à morte do processo.
     *
     * Rádio e stream continuam fora, e por um motivo que não é "não dá": os dois usam id
     * sintético e **não têm item no MediaStore** para o restore reencontrar. O rádio tem
     * mecanismo próprio ([setRadioResume]).
     */
    private fun saveQueueState() {
        if (suppressQueueSave) return
        if (queue.isEmpty()) {
            Settings.clearQueueState(this)
            return
        }
        // Índice na fila **gravada**, e não na fila real: como rádio e stream são pulados,
        // o item tocando pode estar antes ou depois de todos os que foram salvos.
        val keys = QueueKey.encodeAll(queue)
        if (keys.isEmpty()) {
            // Fila só de rádio/stream. Deixar a fila antiga salva seria pior que não ter
            // nada: o próximo boot restauraria uma playlist que o usuário já trocou.
            Settings.clearQueueState(this)
            return
        }
        val currentKey = QueueKey.encode(queue.getOrNull(index) ?: return)
        if (currentKey == null) {
            Settings.clearQueueState(this)
            return
        }
        val savedIndex = keys.indexOfFirst { QueueKey.sameType(it, currentKey) }
        if (savedIndex < 0) {
            Settings.clearQueueState(this)
            return
        }
        val pos = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)
        Settings.setQueueState(this, keys, savedIndex, pos)
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
        unregisterNoisyReceiver()
        // Última chance de gravar a fila. numa instalação de versão nova o Android pode
        // matar o processo sem chamar isto, e aí quem salva é o tick de 5s — por isso o
        // start já grava na hora e isto aqui é só a rede de segurança.
        runCatching { saveQueueState() }
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
                    PlaybackParameters(
                        effectiveSpeed(),
                        // O pitch do modo dance só faz sentido em áudio: aplicar 1.06 em
                        // vídeo não muda o tom de nada e só gasta CPU.
                        if (currentSong?.isVideo == true || currentSong?.isStream == true) 1f
                        else Settings.dancePitch(this)
                    )
                )
            }
        }
    }

    /**
     * F2 — a velocidade que vai para o motor.
     *
     * O modo dance (1,12x) e a velocidade do usuário **multiplicam**, e não competem:
     * escolher 2x no vídeo tem que dar 2x mesmo com o dance ligado. Para áudio é só o
     * dance, porque a velocidade é controle de vídeo — aplicá-la em música deixaria
     * toda faixa em 1,5x sem o usuário ter pedido isso para a música.
     */
    private fun effectiveSpeed(): Float {
        val dance = Settings.danceSpeed(this)
        if (!isVideoOrStream(currentSong)) return dance
        return (dance * Settings.videoSpeed(this)).coerceIn(0.25f, 4.0f)
    }

    /** F2 — muda a velocidade do vídeo e persiste para a próxima faixa. */
    fun setVideoSpeed(value: Float) {
        val v = value.coerceIn(0.5f, 2.0f)
        Settings.setVideoSpeed(this, v)
        applyDanceParams()
        Playback.notifySpeedChanged(effectiveSpeed())
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
        // Grava a fila nova na hora. Sem isso, uma instalação de versão nova nos primeiros
        // segundos depois de dar play perderia a fila: o tick que grava a posição só roda a
        // cada 5s de reprodução. No restore isso é suprimido (ver suppressQueueSave) para
        // não sobrescrever a posição salva com 0 logo antes de aplicá-la.
        saveQueueState()
    }

    /**
     * F2b — junta itens ao **fim** da fila atual, sem trocar o que está tocando.
     *
     * A fila unificada só funciona se der para acrescentar um vídeo à fila da música sem
     * perder a música: [start] substitui tudo e recomeça do zero, que é o que a tela de
     * vídeo fazia. Aqui o que está tocando continua tocando e na mesma posição — o motor
     * só recebe os itens novos no fim da timeline.
     *
     * Devolve quantos entraram, para o chamador avisar quando não entrou nada (item já
     * presente) sem precisar comparar listas.
     */
    fun enqueue(songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        val p = player ?: return 0
        val fresh = QueueKey.filterNew(queue, songs)
        if (fresh.isEmpty()) return 0
        queue = queue + fresh
        runCatching {
            p.addMediaItems(fresh.map { mediaItemFor(it) })
        }.onFailure {
            // O motor recusou os itens: desfaz a fila para ela não descrever o que o
            // player não tem. Um índice além do fim faria `currentSong` estourar.
            queue = queue.take(queue.size - fresh.size)
            return 0
        }
        // Só grava com o que o player aceitou. `saveQueueState` também é o que faz a
        // posição do item atual não se perder: ela vem do tick, e o tick roda de 5 em 5s.
        saveQueueState()
        return fresh.size
    }

    /**
     * Reaplica o título/artist/album que o usuário editou, vinda do [PlaylistDb].
     *
     * Só para **áudio**: os overrides são guardados por `songId` e não sabem de que tipo é
     * o item, então um vídeo com id 42 receberia o título que o usuário deu à **música** 42.
     * A mesma colisão do [currentSongGenre], agora do lado do texto.
     */
    fun refreshCurrentMeta() {
        val song = currentSong ?: return
        if (song.isVideo || song.isStream || song.isRadio) return
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
        // Ao voltar a tocar, o `saveResumeState` precisa valer de novo (grava a posição para o
        // resume). A pausa anterior já cumpriu o seu papel de não deixar o rádio voltar sozinho.
        pauseWasDeliberate = false
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
        // Pausa deliberada (botão/fone/desconexão/erro fatal): não voltar com o rádio sozinho
        // se o processo renascer. O saveResumeState acima recém-gravou o rádio; limpo depois.
        // A flag também avisa o syncExternalPlaybackState para não regravar (ver o campo).
        pauseWasDeliberate = true
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
        // Sem isto, parar e tocar a mesma música logo em seguida não contava nada: o
        // guarda de `notePlay` ainda lembrava do `id` anterior.
        learnedId = -1L
        Settings.clearRadioResume(this)
        // Parada deliberada: não faz sentido a fila salva reaparecer no próximo boot.
        Settings.clearQueueState(this)
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
        // Rádio: a fila chega com UMA estação só (o RadioActivity chama
        // `start(listOf(songFor(station)))`), então `(0 + 1) % 1` devolvia a mesma estação e o
        // botão de avançar parecia morto. Aqui a fila é remontada com todas as estações do
        // usuário, na ordem da tela do rádio, e a que está tocando vira o ponto de partida —
        // aí o mesmo `((index + delta) % size)` da música passa a girar de verdade.
        if (currentSong?.isRadio == true) {
            advanceRadioStation(delta)
            return
        }
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

    /**
     * Gira as estações do rádio. A ordem é a mesma da tela do rádio: as padrão primeiro, depois
     * as que o usuário salvou. A estação no ar vira a posição 0 da fila, então `delta` escolhe a
     * vizinha e a última faz volta na primeira.
     */
    private fun advanceRadioStation(delta: Int) {
        val current = currentSong ?: return
        val currentUrl = current.radioUrl ?: return
        // `all` e nao `list`: sem as padrão o botão não giraria quando o que está tocando é
        // uma das 19 que já vêm no app (elas vivem em RadioStations.defaults, com URL, mas não
        // estão salvas nas preferences).
        val stations = RadioStations.all(this)
        if (stations.size < 2) return
        // Compara pela URL normalizada, não byte a byte: `all()` agrupa por ela e devolve a
        // estação que o usuário salvou, que pode diferir em esquema/query da que está
        // tocando. Comparação exata fazia o botão de avançar não achar a estação atual.
        val currentKey = RadioStations.normUrl(currentUrl)
        val startIndex = stations.indexOfFirst { RadioStations.normUrl(it.url) == currentKey }
        // Estação que não está na lista do usuário (padrão) ou lista vazia: sem base para girar.
        if (startIndex < 0) return
        val size = stations.size
        val nextIndex = (((startIndex + delta) % size) + size) % size
        val target = stations[nextIndex]
        // A fila vai com TODAS as estações a partir da atual, para o next/prev continuarem
        // funcionando depois (avançar duas vezes tem que andar duas estações, não voltar).
        val ordered = (stations.drop(nextIndex) + stations.take(nextIndex))
            .map { stationSong(it) }
        start(ordered, 0)
    }

    private fun stationSong(station: com.pulsa.player.core.UserStation) = Song(
        id = (station.url.hashCode() and 0x7fffffff).toLong(),
        title = station.name,
        artist = station.genre,
        album = getString(R.string.radio),
        albumId = 0L,
        durationMs = 0L,
        path = Song.RADIO_PREFIX + station.url,
        year = 0
    )

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
            // F2: a velocidade é do **item**, não do player. `setMediaItems` recria a
            // timeline, então sem isto um vídeo que chegava pela fila da música entrava
            // em 1x mesmo com 1,5x escolhido — e `onPlayerReady` (que chama
            // `applyDanceParams`) só roda depois do `prepare` começar, tarde demais para
            // a primeira troca de faixa.
            applyDanceParams()
            Playback.notifySpeedChanged(effectiveSpeed())
            if (isScrobbleable(song)) LastFm.nowPlaying(applicationContext, song, song.durationMs)
            notePlay(song)
            Playback.notifySong(song, index)
            loadLargeIcon(song)
            announceInBackground(song)
        } catch (e: Exception) {
            onTrackError()
        }
    }

    private fun mediaItemFor(song: Song): MediaItem {
        val streamUrl = song.streamUrl
        val uri = song.videoId?.let { VideoLibrary.contentUri(it) }
            ?: song.radioUrl?.let(Uri::parse)
            ?: streamUrl?.let(Uri::parse)
            ?: Uri.fromFile(File(song.path))
        return MediaItem.Builder()
            .setUri(uri)
            // F2: HLS/DASH na URL sem extensão (o Media3 1.3.1 só infere pela extensão, ver
            // StreamKind) e nenhum mime type para arquivo local — aí o Media3 infere como
            // sempre. `setMimeType(null)` é o mesmo que não chamar.
            .setMimeType(streamUrl?.let { StreamKind.mimeTypeOf(it) })
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

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Repetição é tratada aqui dentro (`onTrackEnded` → `player.seekTo(0)`), e o
            // player nunca recebe `setRepeatMode`, então REPEAT não muda de faixa.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            adoptPlayerIndex()
        }

        override fun onPlayerError(error: PlaybackException) {
            onTrackError()
        }
    }

    /**
     * O player andou para outro item sem passar pelo nosso [advanceIndex].
     *
     * "Tocar isto" chega de fora como `COMMAND_SEEK_TO_MEDIA_ITEM` — Android Auto, Wear,
     * Assistant, controle do Bluetooth — e o padrão do Media3 executa `player.seekTo(i)`
     * direto. O áudio sai da faixa A, mas `queue`/`index` continuam na A, e como
     * `currentSong` **é** `queue[index]` (ver [currentSong]), a mini player, a tela Now
     * Playing e o destaque da biblioteca passavam a descrever a música anterior enquanto
     * outra tocava. Pior: o desalinhamento não se desfaz sozinho, então só voltava a ficar
     * certo quando o app refazia a fila por outro caminho — era o "tá tocando outra mas o
     * mini player mostra a mesma", do AUTO e do fone.
     *
     * A reconciliação mora no evento do player, e não no comando, porque o mesmo
     * desalinhamento vem de qualquer transição que o player faça por conta própria. O
     * `if (at == index) return` é o que impede o laço: `start()`/`prepareCurrent()` já
     * publicam a faixa nova antes de o evento chegar, e nesse caso os dois já são iguais.
     */
    /**
     * F2 — registra o toque no log de aprendizado.
     *
     * Isto morava dentro do `if (djActive)` da cabine do DJ, e por isso as "Tendências
     * Locais" só-enchiam para quem usava a cabine: quem ouve música direto — tocando da
     * lista, pela mini player, pelo botão de próximo — nunca chegava ao `play_log`, e a
     * tela ficava vazia para sempre com o "toque mais músicas" mesmo depois de horas de
     * música. O registro é do motor, não da tela que deu o comando.
     *
     * Só entra áudio de biblioteca. Vídeo, stream e rádio dividem a fila (F2b) e não são a
     * música da pessoa; pior, o `id` deles pode bater com o de um áudio — o MediaStore
     * numera as coleções por conta própria — e a tendência passaria a contar um toque na
     * música errada.
     */
    private fun notePlay(song: Song?) {
        val s = song ?: return
        if (s.isVideo || s.isStream || s.isRadio) return
        if (s.path.isBlank()) return
        val id = s.id
        if (id < 0L || id == learnedId) return
        learnedId = id
        val app = applicationContext
        // Gravação de banco fora da main: `prepareCurrent` roda no meio da troca de faixa.
        ThreadPool.post { DjLearn.recordPlay(app, id) }
    }

    /** Último `id` já registrado no aprendizado; evita contar o mesmo toque duas vezes. */
    private var learnedId = -1L

    private fun adoptPlayerIndex() {
        val p = player ?: return
        if (queue.isEmpty()) return
        val at = p.currentMediaItemIndex
        if (at == index) return
        index = at
        val song = queue.getOrNull(at) ?: return
        positionMs = p.currentPosition.coerceAtLeast(0L)
        resetAbLoop()
        // O `resume` (posição por faixa) ainda descreve a que acabou de sair; zerar faz o
        // próximo tick gravar a nova, e sem isso o restore do próximo processo voltaria
        // para a faixa errada — com a posição da outra.
        clearResumeState()
        consecutiveErrors = 0
        publishMetadata(song)
        refreshNotification()
        if (isScrobbleable(song)) LastFm.nowPlaying(applicationContext, song, song.durationMs)
        notePlay(song)
        loadLargeIcon(song)
        announceInBackground(song)
        Playback.notifySong(song, at)
        saveQueueState()
        publishState()
        scheduleTick()
        emitProgress()
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
        if (song.isVideo || song.isStream) return
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
        if (startPaused) {
            // Restore de fila: fica pronto e parado. Sem play, sem fade, sem foco de áudio
            // — o volume vai a 1 para o primeiro play do usuário não sair do zero.
            startPaused = false
            runCatching { p.setVolume(1f) }
            publishState()
            return
        }
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
        // Só áudio. O `resume_song_id` é um id solto e o id de um vídeo pode ser o mesmo de
        // uma música (coleções separadas do MediaStore): sem esta guarda, o vídeo 42
        // buscava a posição salva da música 42 e voltava no meio de um lugar aleatório.
        if (song.isVideo || song.isStream || song.isRadio) return
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
        // Se o restore falhou, o onPlayerReady nunca vem e a trava ficaria armada: a
        // próxima música que tocasse normalmente entraria calada. Aqui ela é desarmada.
        startPaused = false
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
        } else if (!com.pulsa.player.dj.Hotword.somethingToListen()) {
            // Pausar VÍDEO não desliga o microfone. Desligar era certo para a música
            // (mic desligado = app quieto), mas no vídeo o pause é justamente o estado
            // em que se dá o comando seguinte: pausar matava a única via de dizer
            // "continua o filme", e sem ela o vídeo ficava preso parado.
            //
            // `somethingToListen()` é a MESMA definição que `Hotword.startIfNeeded` e
            // `HotwordService.staying` usam. Cada um tinha a sua cópia, e corrigir um
            // deixava os outros dois cobrando `Playback.isPlaying`.
            com.pulsa.player.dj.Hotword.stopIfRunning(this)
        }
        refreshNotification()
    }

    private fun isVideoOrStream(song: Song?): Boolean = song?.isVideo == true || song?.isStream == true

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

    /**
     * F2b — o item só de áudio, para o Last.fm.
     *
     * O `scrobble` era o único ponto do serviço **sem** guarda de tipo: um vídeo de 30s
     * ouvido até a metade virava scrobble com `artist = ""` e `album = "Vídeos"`, e um
     * stream ia pelo mesmo caminho com o id do hash da URL. O `nowPlaying` já era filtrado,
     * mas `isVideo` sozinho deixava `isStream` passar — por isso a guarda é uma função só,
     * chamada nos dois lugares, e o motivo fica num lugar só.
     */
    private fun isScrobbleable(song: Song): Boolean =
        !song.isVideo && !song.isStream && !song.isRadio

    private fun saveResumeState() {
        // O `syncExternalPlaybackState(false)` chega DEPOIS do `pause()` (o Media3 entrega o
        // evento do listener via Handler) e a chamada abaixo regravaria o rádio que o pause()
        // acabou de limpar, ressuscitando a estação no próximo restoreRadioResume.
        if (pauseWasDeliberate) return
        saveQueueState()
        // F2b — `currentSong` **é** `queue[index]`, e o `index` muda no `advanceIndex`
        // antes do motor sair da faixa anterior. Nesse intervalo `currentPosition` ainda é
        // o da música que acabou de terminar, então a gravação passava a guardar o par
        // trocado: id da música nova com o tempo da antiga.
        //
        // Depois o `restoreSavedPosition` via só o id, achava que aquela posição era da
        // música nova e fazia o seek — e ela entrava no meio. Era exatamente no fim natural
        // da faixa, que é quando o `onTrackEnded` chama `clearResumeState()` e um tick
        // na sequência imediato regrava tudo errado.
        //
        // `awaitingReady` cobre a janela inteira: ele é ligado no `prepareCurrent` e
        // desligado no `onPlayerReady`, e não existe posição confiável entre os dois.
        if (awaitingReady) return
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
        if (!isScrobbleable(song)) return
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

    /**
     * F2b — a capa da notificação, e ela só existe para áudio.
     *
     * `isVideo` não basta mais: o stream tem `albumId = 0` e `path` começando com
     * `stream:https://…`, então o `ArtLoader` ia abrir um `MediaMetadataRetriever` numa URL
     * e devolver sempre `null` — um retrabalho por faixa sem resultado. E a comparação de
     * `currentSong?.id` trocada por chave: com o id de um vídeo batendo com o de uma música,
     * a capa da música podia virar a capa do vídeo.
     */
    private fun loadLargeIcon(song: Song) {
        if (song.isVideo || song.isStream) return
        // Chave capturada agora: no `onUi` a fila pode já ter andado, e comparar `id` direto
        // deixaria a capa de uma faixa antiga virar a notificação da nova.
        val key = QueueKey.encode(song) ?: return
        ThreadPool.post {
            val bmp = ArtLoader.decode(applicationContext, song.albumId, song.path)
            ThreadPool.onUi {
                if (bmp != null) {
                    largeIcon = bmp
                    if (QueueKey.sameType(QueueKey.encode(currentSong ?: return@onUi), key)) {
                        refreshNotification()
                        PulsaWidget.refresh(applicationContext)
                    }
                }
            }
        }
    }
}
