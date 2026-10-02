package com.pulsa.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.PictureInPictureParams
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.pulsa.player.core.CrashLogger
import com.pulsa.player.core.Helper
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.PeerTube
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.media.SubtitleConfig
import com.pulsa.player.media.Subtitles
import com.pulsa.player.model.Song
import com.pulsa.player.model.Video
import com.pulsa.player.model.toSong
import com.pulsa.player.playback.Playback
import com.pulsa.player.playback.PlaybackSpeeds
import kotlin.math.abs

/**
 * E5 — a tela de vídeo agora é só uma tela: quem toca é o mesmo motor do rádio/música.
 *
 * Cada vídeo vira um `Song` com `path = "video:<id>"` ([Song.VIDEO_PREFIX]); a activity
 * anexa uma [androidx.media3.ui.PlayerView] ao `Player` do serviço ([Playback.player]) e
 * dirige o motor pela
 * fachada (play/pause/next/prev/seek). Entrar num vídeo substitui a fila da música — o
 * mesmo player é um só, então é impossível a música "invadir" o vídeo tocando junto (o
 * problema que este passo elimina). Sair da tela devolve a fila que estava tocando antes.
 */
@UnstableApi
class VideoPlayerActivity : AppCompatActivity(), Playback.Listener {

    companion object {
        private const val EXTRA_IDS = "video_ids"
        private const val EXTRA_INDEX = "video_index"
        private const val EXTRA_STREAM_URL = "stream_url"
        private const val EXTRA_STREAM_TITLE = "stream_title"
        private const val EXTRA_STREAM_CAPTIONS = "stream_captions"
        private const val EXTRA_ATTACH = "video_attach"
        private const val HIDE_DELAY = 3000L

        /** Quanto o dedo anda antes de o arrasto virar seek (px). */
        private const val SCRUB_TOUCH_SLOP = 24f

        /** Salto do toque duplo: 10 s, o mesmo que o YouTube usa. */
        private const val SEEK_STEP_MS = 10_000L

        /** Salto dos botões: 15 s, o outro valor do YouTube. */
        private const val SEEK_15_MS = 15_000L

        /**
         * Id sintético do item de stream, derivado da URL.
         *
         * Antes era uma constante `-1L` para todo stream, e isso quebrava a legenda: a
         * `SubtitleConfig` guarda a escolha por id, então abrir dois streams seguidos fazia
         * o segundo herdar a legenda do primeiro. O hash da URL dá id estável por endereço e
         * diferente para cada um.
         */
        private fun streamId(url: String): Long = url.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL

        fun start(context: Context, videos: List<Video>, index: Int) {
            val ids = ArrayList(videos.map { it.id })
            context.startActivity(
                Intent(context, VideoPlayerActivity::class.java)
                    .putExtra(EXTRA_IDS, ids)
                    .putExtra(EXTRA_INDEX, index.coerceIn(0, ids.lastIndex))
            )
        }

        /**
         * F2: abre uma URL de stream (HLS/DASH/progressivo) direto na tela de vídeo.
         *
         * A diferença para [start] é que não há id do MediaStore: a fila é montada com um
         * `Song` de `path = "stream:<url>"`, e o `PlaybackService` descobre o formato da URL
         * (ver [com.pulsa.player.media.StreamKind]).
         *
         * [captions] são as trilhas opcionais do PeerTube. Se viessem só pela URL, o player
         * nunca descobriria que existem: a legenda do PeerTube está em `captions[].url` e
         * não no manifesto HLS, então sem passar as trilhas a tela ficaria sem legenda.
         */
        fun startStream(
            context: Context,
            url: String,
            title: String = "",
            captions: List<PeerTube.Caption> = emptyList()
        ) {
            val intent = Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_STREAM_URL, url)
                .putExtra(EXTRA_STREAM_TITLE, title)
            if (captions.isNotEmpty()) {
                intent.putStringArrayListExtra(
                    EXTRA_STREAM_CAPTIONS,
                    ArrayList(captions.map { "${it.url}\n${it.label}\n${it.language}" })
                )
            }
            context.startActivity(intent)
        }

        /**
         * F2b — mostra em tela cheia o vídeo **que já está tocando** na fila única.
         *
         * É o caminho do vídeo que entrou pela fila (adicionar à fila, Android Auto,
         * controle do sistema) e chegou em "next" enquanto a música tocava: sem isto o
         * áudio do vídeo saía pela mini player e a imagem nunca aparecia, porque imagem
         * precisa de [androidx.media3.ui.PlayerView] e ela só existe nesta tela.
         *
         * A diferença para [start] é que **a fila não é tocada**: [start] monta a fila de
         * vídeo e chama `Playback.start`, que substitui tudo. Aqui a tela só gruda no motor,
         * por isso a guarda [onScreen] — sem ela, cada `onSongChanged` com vídeo abriria
         * outra instância da tela em cima da anterior.
         */
        @Volatile
        var onScreen = false

        fun showPlaying(context: Context) {
            if (onScreen) return
            runCatching {
                context.startActivity(
                    Intent(context, VideoPlayerActivity::class.java)
                        .putExtra(EXTRA_ATTACH, true)
                )
            }
        }
    }

    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var titleView: TextView
    private lateinit var currentText: TextView
    private lateinit var durationText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playBtn: ImageButton
    private lateinit var subtitleBtn: android.widget.TextView
    private lateinit var speedView: TextView
    
    private var videos: List<Video> = emptyList()
    private var videoSongs: List<Song> = emptyList()
    private var index = 0

    /**
     * F2: URL de stream aberta direto, sem passar pela biblioteca local. `videos` fica vazio
     * e a fila tem um item só — todos os pontos que falam de `videos` precisam considerar
     * isso, senão a tela abre e sai na hora.
     */
    private var streamUrl: String? = null
    private var streamTitle: String = ""
    private var resumeQueue: List<Song> = emptyList()
    private var resumeIndex = -1
    private var resumePlaying = false
    private var prevShuffle = false
    private var prevRepeatAll = true
    private var prevRepeatOne = false
    private var dragging = false
    private var autoplay = true
    private var finished = false

    /**
     * F2 — arrasto de seek (scrub).
     *
     * [scrubbing] vale enquanto o dedo está na tela depois de passar do [SCRUB_TOUCH_SLOP];
     * [scrubStart] é a posição do motor no primeiro pixel do gesto, e é ela — não a posição
     * corrente, que continua andando durante o arrasto — que serve de origem. Sem isso o
     * cursor "escapa" do dedo quando o vídeo está tocando.
     */
    private var scrubbing = false
    private var scrubStart = 0L
    private var scrubDuration = 0L

    private val scrubWidth: Float
        get() = findViewById<View>(R.id.vp_root)?.width?.toFloat() ?: 0f

    /** Texto do botão de legenda: "CC" quando há uma trilha, "CC 2" quando há várias. */
    private var currentTrackLabel = ""

    /** Trilhas que o PeerTube declarou no detalhe do vídeo, se o stream veio de lá. */
    private var streamCaptions: List<PeerTube.Caption> = emptyList()

    /**
     * F2b — a tela só vai **assistir** ao que já está tocando na fila única.
     *
     * Nesse modo nada do que a tela faz pode mexer na fila: nem o [startVideos] no
     * `Playback.start`, nem o `onDestroy` devolvendo a fila da música (aqui a fila é a mesma
     * que estava tocando, e "restaurá-la" seria recarregar do zero a faixa que estava no
     * minuto 34). Fechar a tela devolve para a mini player com o vídeo **ainda tocando**,
     * que é o comportamento que o usuário espera de um player.
     */
    private var attached = false

    /**
     * Seletor de arquivo do sistema para a legenda.
     *
     * `OpenDocument` e não `GetContent`: o primeiro concede acesso **persistente** ao
     * `content://`, então a legenda continua legível depois de reiniciar o app, enquanto o
     * segundo dá uma permissão de uma leitura só e a config salva vira uri morto.
     */
    private val pickSubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onSubtitlePicked(uri) }

    /**
     * O usuário pediu o Picture-in-Picture pelo botão.
     *
     * Existe porque o gesto de arrastar o vídeo para o PiP só faz sentido depois de uma
     * intenção explícita. Sem isso, sair da activity (qualquer uma) auto-reduz o vídeo.
     * Reinicia a cada entrada na tela cheia: o botão passa a ser o gatilho de novo.
     */
    private var pipWanted = false

    /**
     * O usuário pediu para SAIR da tela de vídeo (botão voltar ou gesto do sistema),
     * em vez de só fechar o PiP. Só nesse caso a fila da música é restaurada.
     *
     * É um AtomicBoolean e não um Boolean porque o onDestroy roda na main, mas o
     * Picture-in-Picture pode ter mudado a activity em outra thread de estado.
     */
    private val closingForMusic = java.util.concurrent.atomic.AtomicBoolean(false)
    private var playbackBind: Playback.Bind? = null
    private val handler = Handler(Looper.getMainLooper())

    private val hideRunnable = Runnable { hideControls() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.vp_video)
        loading = findViewById(R.id.vp_loading)
        errorView = findViewById(R.id.vp_error)
        topBar = findViewById(R.id.vp_top)
        bottomBar = findViewById(R.id.vp_bottom)
        titleView = findViewById(R.id.vp_title)
        currentText = findViewById(R.id.vp_current)
        durationText = findViewById(R.id.vp_duration)
        seekBar = findViewById(R.id.vp_seek)
        playBtn = findViewById(R.id.vp_play)

        findViewById<ImageButton>(R.id.vp_back).setOnClickListener {
            closingForMusic.set(true)
            finish()
        }
        // O gesto de voltar (botão do sistema/gesto) chama finish() por fora do listener
        // do botão, então precisa do mesmo tratamento — senão fechar a tela de vídeo pelo
        // gesto não devolveria a fila da música.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closingForMusic.set(true)
                finish()
            }
        })
        findViewById<ImageButton>(R.id.vp_pip).setOnClickListener { togglePip() }
        subtitleBtn = findViewById(R.id.vp_subtitle)
        subtitleBtn.setOnClickListener {
            val song = Playback.currentSong
            if (song == null) {
                Toast.makeText(this, R.string.video_subtitle_none, Toast.LENGTH_SHORT).show()
            } else {
                showSubtitleDialog(song)
            }
        }
        playBtn.setOnClickListener { togglePlayback() }
        findViewById<ImageButton>(R.id.vp_prev).setOnClickListener { step(-1) }
        findViewById<ImageButton>(R.id.vp_next).setOnClickListener { step(1) }

        // F2: saltos de 15s. São passos de *tempo*, então ficam perto do play/pause —
        // é onde o dedo já está, e é a operação mais usada depois de pausa.
        findViewById<ImageButton>(R.id.vp_rewind).setOnClickListener { seekBy(-SEEK_15_MS) }
        findViewById<ImageButton>(R.id.vp_forward).setOnClickListener { seekBy(SEEK_15_MS) }

        speedView = findViewById(R.id.vp_speed)
        speedView.setOnClickListener { cycleSpeed() }

        findViewById<ImageButton>(R.id.vp_rotate).setOnClickListener { toggleOrientation() }

        // F2 — os controles nascem do tamanho da tela atual, e a activity não é recriada
        // ao girar (o manifest declara configChanges de orientation justamente para o
        // vídeo não reiniciar). Sem isto, girar deixaria os botões de 48dp minúsculos
        // numa tela de 2400px de largura.
        applyOrientation(resources.configuration.orientation)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) currentText.text = Helper.formatDuration(progress.toLong())
            }

            override fun onStartTrackingTouch(seek: SeekBar) {
                dragging = true
                handler.removeCallbacks(hideRunnable)
            }

            override fun onStopTrackingTouch(seek: SeekBar) {
                dragging = false
                Playback.seekTo(seekBar.progress.toLong())
                scheduleHide()
            }
        })

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // Só o arrasto e o toque duplo fazem algo aqui. Mostrar os controles no
                // `onSingleTapUp` brigava com o `onSingleTapConfirmed`: um toque duplo
                // mostrava a barra e, logo em seguida, o salto a escondia de novo.
                return true
            }

            /**
             * F2: arrastar na horizontal **avança o tempo**, que é o que se espera de um
             * player de vídeo. Antes esse gesto trocava de faixa — e era o gesto mais usado
             * da tela, então "voltar 10 segundos" era impossível.
             *
             * Next/prev continuam existindo, mas por toque duplo nas bordas (que é o padrão
             * do YouTube) e pelos botões: são ações de faixa, e pular de faixa por um arrasto
             * sem querer é pior do que não ter.
             */
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e1.x - e2.x
                val dy = e1.y - e2.y
                // Só horizontal, e só quando ainda não começou: o vertical é do sistema.
                if (abs(dy) > abs(dx)) return false
                if (!scrubbing) {
                    if (abs(dx) < SCRUB_TOUCH_SLOP) return false
                    scrubbing = true
                    // Congela o cursor: sem isso a posição segue correndo por baixo do dedo e
                    // o texto de tempo mostra um número que não é o que está sendo arrastado.
                    handler.removeCallbacks(hideRunnable)
                    scrubDuration = seekBar.max.toLong()
                    scrubStart = Playback.position
                }
                val total = scrubDuration.coerceAtLeast(1L)
                val delta = (dx * total / scrubWidth).toLong()
                val alvo = (scrubStart + delta).coerceIn(0L, total)
                seekBar.progress = alvo.toInt()
                currentText.text = Helper.formatDuration(alvo)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val w = scrubWidth
                if (w > 0f) {
                    // Bordas de 1/3 da tela: toque duplo à esquerda volta, à direita avança.
                    // No meio, o toque duplo não tem ação — aí vale o toque simples.
                    when {
                        e.x < w / 3f -> return seekBy(-SEEK_STEP_MS)
                        e.x > w * 2f / 3f -> return seekBy(SEEK_STEP_MS)
                    }
                }
                toggleControls()
                return true
            }
        })
        findViewById<View>(R.id.vp_root).setOnTouchListener { _, e ->
            // O `onScroll` do detector já reposiciona a barra a cada pixel; o `ACTION_UP`
            // é o que entrega a posição ao motor, porque durante o arrasto ela é só
            // pré-visualização — o vídeo não deve pular 40 vezes enquanto o dedo anda.
            detector.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP) commitScrub()
            true
        }

        // F2b: terceiro modo de entrada — a tela gruda no que a fila já está tocando. Vem
        // primeiro porque os outros dois modos sempre trazem id ou URL; sem nenhum deles e
        // com o extra presente, é um vídeo que chegou pelo "next" da fila única.
        attached = intent.getBooleanExtra(EXTRA_ATTACH, false)
        if (attached) {
            val song = Playback.currentSong
            if (song?.isVideo != true && song?.isStream != true) {
                // Chegou aqui tarde demais (o vídeo já pulou para a música) ou o extra veio
                // sem nada tocando. Sair em silêncio: a mini player está certa e uma tela de
                // vídeo preta não ajuda ninguém.
                finish()
                return
            }
            onScreen = true
            // Quem chama isto é o "next" da fila, então normalmente já está tocando. Se
            // chegou pausado (a pessoa pausou e o vídeo virou o item atual de outro jeito),
            // forçar o play aqui desmentiria o estado do motor na cara dela.
            //
            // F2b: `isPlaying` sozinho não separava "a pessoa parou" de "o Media3 ainda
            // não voltou a tocar depois de trocar de faixa". O vídeo que acabou de virar o
            // item atual cai na segunda — e a tela abria mostrando o vídeo **pausado**, que
            // é o jeito normal de um vídeo aparecer na fila misturada. `pausedDeliberately`
            // distingue as duas: só a pausa de verdade impede o play.
            autoplay = Playback.isPlaying || !Playback.pausedDeliberately
            // A "fila de vídeo" desta tela é a fila inteira do motor: os controles de
            // next/prev precisam saber o tamanho real, e `videoSongs` é o que vários pontos
            // usam para decidir se há vídeo tocando.
            videoSongs = Playback.queue
            index = Playback.index.coerceAtLeast(0)
            if (song.isStream) {
                streamUrl = song.streamUrl
                streamTitle = song.title
            }
            videos = VideoLibrary.all(this).filter { videoSongs.any { s -> s.videoId == it.id } }
            attachToPlaying()
            return
        }

        @Suppress("DEPRECATION")
        val ids = intent.getSerializableExtra(EXTRA_IDS) as? ArrayList<Long> ?: arrayListOf()
        val byId = VideoLibrary.all(this).associateBy { it.id }
        videos = ids.mapNotNull { byId[it] }

        // F2: dois modos de entrada. Ou a tela abre a biblioteca (ids do MediaStore) ou abre
        // uma URL de stream. O stream tem precedência porque não tem id nenhum.
        val url = intent.getStringExtra(EXTRA_STREAM_URL)?.trim()
        if (!url.isNullOrEmpty()) {
            streamUrl = url
            streamTitle = intent.getStringExtra(EXTRA_STREAM_TITLE).orEmpty()
                .ifBlank { getString(R.string.stream_default_title) }
            videoSongs = listOf(
                Song(
                    id = streamId(url),
                    title = streamTitle,
                    artist = "",
                    album = getString(R.string.tab_videos),
                    albumId = 0L,
                    // Duração desconhecida até o manifesto carregar: 0 deixa a barra em modo
                    // indeterminado em vez de mentir que o vídeo tem 1 segundo.
                    durationMs = 0L,
                    path = Song.STREAM_PREFIX + url,
                    year = 0
                )
            )
            index = 0
            streamCaptions = intent.getStringArrayListExtra(EXTRA_STREAM_CAPTIONS)
                ?.mapNotNull { parseCaptionExtra(it) }
                .orEmpty()
        } else {
            if (videos.isEmpty()) {
                showError(getString(R.string.video_player_error))
                return
            }
            index = (intent.getIntExtra(EXTRA_INDEX, 0)).coerceIn(0, videos.lastIndex)
            videoSongs = videos.map { it.toSong(getString(R.string.tab_videos)) }
        }

        // Guarda o que estava tocando para devolver na saída — o motor é único, então lançar
        // o vídeo já corta a música (nada de dois tocadores ao mesmo tempo).
        resumeQueue = Playback.queue
        resumeIndex = Playback.index
        resumePlaying = Playback.isPlaying
        prevShuffle = Playback.shuffle
        prevRepeatAll = Playback.repeatAll
        prevRepeatOne = Playback.repeatOne

        playbackBind = Playback.connect(this) { startVideos() }
        showControls()
    }

    private fun startVideos() {
        if (videoSongs.isEmpty()) return
        // F2b: no modo anexo quem manda é a fila única do app — a tela só mostra. Chamar
        // `Playback.start` aqui recomeçaria a fila de vídeo do zero e apagaria a música que
        // está depois dela na linha do tempo.
        if (attached) {
            render()
            return
        }
        // O vídeo anda como fila normal do motor: repeat-all, sem shuffle nem repeat-one,
        // para "next" avançar em vez de repetir/embaralhar o próprio vídeo.
        Playback.setShuffle(false)
        Playback.setRepeatAll(true)
        Playback.setRepeatOne(false)
        onScreen = true
        Playback.start(videoSongs, index)
        render()
    }

    /**
     * F2b — anexa a tela ao motor sem tocar em nada: [PlayerView] no player, listener na
     * activity e desenho do estado atual.
     *
     * Separado do [startVideos] porque aqui não há fila para montar. O listener é o mesmo
     * slot único do resto do app, então é esta tela que passa a desenhar — inclusive se o
     * usuário fechar e outro vídeo entrar no "next" enquanto ela estiver aberta.
     */
    private fun attachToPlaying() {
        Playback.listener = this
        playerView.player = Playback.player
        Playback.currentSong?.let { if (it.isVideo) applySubtitle(it) }
        render()
        showControls()
    }

    private fun currentVideoTitle(): String {
        val song = Playback.currentSong
        if (song?.isStream == true) return streamTitle
        if (song?.isVideo == true) {
            val video = videos.firstOrNull { it.id == song.videoId }
            if (video != null) return video.title
        }
        return ""
    }

    private fun render() {
        subtitleBtn.text = if (currentTrackLabel.isEmpty()) getString(R.string.video_subtitle) else currentTrackLabel
        titleView.text = currentVideoTitle().ifBlank { getString(R.string.tab_videos) }
        val dur = Playback.currentSong?.durationMs ?: 0L
        if (dur > 0L) {
            seekBar.max = dur.toInt()
            durationText.text = Helper.formatDuration(dur)
        } else {
            seekBar.max = 1000
        }
        errorView.visibility = View.GONE
        updateState()
    }

    private fun togglePlayback() {
        if (errorView.visibility == View.VISIBLE) return
        if (Playback.isPlaying) Playback.pause() else Playback.play()
        updateState()
    }

    private fun step(delta: Int) {
        if (videoSongs.isEmpty()) return
        // A posição viva é a do motor, não o `index` da activity: o `index` só é setado na
        // criação da tela, então quem já pulei de vídeo e voltasse pelo gesto cairia aqui
        // com o número antigo e o item do fim voltaria a "dar a volta" no lugar errado.
        val current = Playback.index
        // F2b: no modo anexo a fila é a fila única do app, e "dar a volta" aqui significaria
        // saltar do fim da playlist para o começo — que é o repeat que o motor já faz. O
        // next/prev sai direto para o motor, sem o atalho do item único.
        if (!attached) {
            // Stream tem um item só: "next" no fim volta a começo em vez de não fazer nada, que
            // é o que o gesto de arrastar espera de um item só.
            if (delta > 0 && current >= videoSongs.lastIndex) {
                Playback.seekTo(0)
                Playback.play()
                showControls()
                return
            }
        }
        // O `delta` precisa chegar ao motor. Antes o "voltar" — botão e gesto de arrastar
        // para a esquerda — caía no `Playback.next()` abaixo, que só avança: voltar andava
        // para a frente e o gesto parecia não ter efeito nenhum.
        if (delta > 0) Playback.next() else Playback.prev()
        showControls()
    }

    /**
     * F2 — salto fixo de tempo, usado pelo toque duplo nas bordas.
     *
     * Clampa nas pontas: em 3 s de vídeo, "voltar 10" não pode virar posição negativa
     * nem travar o motor.
     */
    private fun seekBy(deltaMs: Long): Boolean {
        // `Playback` não expõe a duração; a barra é quem já recebeu a duração real do
        // motor em `onProgress`, e é a mesma que o usuário está vendo.
        val dur = seekBar.max.toLong()
        if (dur <= 0L) return false
        val alvo = (Playback.position + deltaMs).coerceIn(0L, dur)
        Playback.seekTo(alvo)
        // Os controles precisam aparecer, senão o salto acontece sem feedback nenhum e
        // parece que o toque não fez nada.
        showControls()
        currentText.text = Helper.formatDuration(alvo)
        Toast.makeText(
            this,
            if (deltaMs > 0) "+${deltaMs / 1000}s" else "${deltaMs / 1000}s",
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    /**
     * F2 — gira a tela entre retrato e paisagem.
     *
     * `SCREEN_ORIENTATION_USER` (e não `SENSOR`) porque o botão precisa ** inverter** o que
     * o usuário está fazendo: com sensor, girar o aparelho de novo desfaria o botão na hora.
     * E `LANDSCAPE` fixo, não o descape do sensor, para o vídeo não virar de cabeça para
     * baixo quando o aparelho for segurado deitado.
     *
     * Não há `ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED` no meio do caminho: sair do
     * estado travado é o que devolve a rotação automática, e é o que o botão de fechar
     * precisa fazer ao voltar para a música.
     */
    private fun toggleOrientation() {
        val paisagem = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (paisagem) {
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
    }

    /**
     * F2 — a activity sobrevive à rotação (configChanges no manifest), então o layout não
     * é re-inflado. Aqui é que a tela se adapta: alvos maiores e barras mais finas em
     * paisagem, onde a barra de status e a de navegação comem as pontas da tela.
     *
     * Paisagem **não** vira um layout diferente: os mesmos botões, maiores. Duas cópias
     * do layout divergem na primeira alteração de um controle, e o vertical já funciona
     * bem.
     */
    private fun applyOrientation(orientation: Int) {
        val paisagem = orientation == Configuration.ORIENTATION_LANDSCAPE
        val lado = if (paisagem) 64.dp else 48.dp
        for (id in intArrayOf(
            R.id.vp_back, R.id.vp_pip, R.id.vp_rotate, R.id.vp_prev, R.id.vp_next,
            R.id.vp_rewind, R.id.vp_forward, R.id.vp_play
        )) {
            findViewById<View>(id)?.let { b ->
                b.layoutParams = b.layoutParams.apply { width = lado; height = lado }
            }
        }
        // Em paisagem a barra de status ocupa a ponta, então o padding fixo de 8dp
        // deixava o botão de voltar colado no notch. some com ela nos dois casos, que é
        // o que o inset-aware faz sozinho.
        topBar.setPadding(0, 0, if (paisagem) 8.dp else 16.dp, 0)
        // O rodapé é onde o polegar fica, então em paisagem ele precisa de respiro da
        // barra de navegação — sem isso o "próximo" cai embaixo do gesto do sistema.
        bottomBar.setPadding(0, 0, 0, if (paisagem) 20.dp else 0)
        titleView.textSize = if (paisagem) 18f else 16f
        applyImmersive(paisagem)
    }

    /**
     * F2 — em paisagem as barras do sistema viram duas faixas pretas nas pontas da tela,
     * e num celular deitado elas comem altura, que é a dimensão que falta. Esconder as
     * duas é o que dá a tela inteira para o vídeo, que é o motivo de girar.
     *
     * Em retrato as barras voltam: retrato é o jeito de navegar o app, e esconder a de
     * navegação ali tira o botão "voltar" sem dar nada em troca.
     *
     * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` (e não o default) para que um arrasto
     * traga a barra de volta temporariamente em vez de prendê-la na tela.
     */
    private fun applyImmersive(paisagem: Boolean) {
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        if (paisagem) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig.orientation)
    }

    /** Fim do arrasto: o que o dedo deixou vira a posição real do motor. */
    private fun commitScrub() {
        if (!scrubbing) return
        scrubbing = false
        val alvo = seekBar.progress.toLong()
        Playback.seekTo(alvo)
        currentText.text = Helper.formatDuration(alvo)
        // A barra some sozinha de novo: um arrasto é uma consulta de tempo, não um
        // pedido para deixar a interface aberta.
        scheduleHide()
    }

    /**
     * F2 — botão de velocidade. Percorre os passos de [PlaybackSpeeds] e persiste.
     *
     * O rótulo mostra o valor **escolhido**, não o efetivo: com o modo dance ligado o
     * efetivo é 1,12x maior, e mostrar esse número aqui faria o botão pular passos que
     * ninguém pediu (1x → 1,4x). O que o usuário escolheu é o que ele reconhece.
     */
    private fun cycleSpeed() {
        val escolhido = Playback.cycleVideoSpeed()
        speedView.text = PlaybackSpeeds.label(escolhido)
        // Os controles precisam aparecer, senão o número troca sozinho e parece não ter
        // acontecido nada.
        showControls()
    }

    override fun onSpeedChanged(speed: Float) {
        // A faixa mudou de tipo (música → vídeo na fila misturada) ou o dance mudou: o
        // botão reflete o que está valendo agora.
        if (!::speedView.isInitialized) return
        speedView.text = PlaybackSpeeds.label(speed)
    }

    override fun onSongChanged(song: Song?, index: Int) {
        if (song?.isVideo != true && song?.isStream != true) {
            // F2b: no modo anexo a fila é misturada, então o item atual deixa de ser vídeo
            // com frequência — o vídeo acabou e a música seguinte entrou. A tela só serve
            // para vídeo: ficar aberta com a área preta enquanto a música toca é pior do
            // que voltar para o app, que é para onde a pessoa vai de qualquer jeito.
            if (attached) finish()
            return
        }
        render()
        // Cada video tem sua legenda: reavalia no START, senão a legenda do video
        // anterior ficaria desenhada no seguinte (e alguns nem tem legenda nenhuma).
        // Stream remoto não tem arquivo de lado no MediaStore, então só o que o
        // usuário escolheu (SubtitleConfig) entra — o resto fica vazio.
        applySubtitle(song)
    }

    /**
     * Aplica a legenda escolhida ao item que está tocando.
     *
     * Ordem de prioridade: o que o usuário escolheu ([SubtitleConfig]) substitui a de
     * convenção de nome ([Subtitles.attach]). Sem config salva, vale a convenção — o
     * comportamento antigo, que é o caso comum. Sem nenhuma das duas, o item fica sem
     * legenda e nada acontece.
     *
     * A busca toca o disco (MediaStore) e o load por link toca a rede, então vai para a
     * `ThreadPool`. Só trocamos o item se ainda for o MESMO: senão a legenda do vídeo
     * anterior entraria no seguinte, e o `MediaItem` novo seria sobrescrito por um velho.
     */
    private fun applySubtitle(song: Song) {
        val player = Playback.player
        if (player == null) return
        val before = player.currentMediaItem?.localConfiguration?.uri
        val ctx = applicationContext
        val mediaId = song.id
        ThreadPool.post {
            val withTrack = runCatching {
                val base = player.currentMediaItem ?: return@runCatching null
                // Base sem legenda: a convenção primeiro, e a config por cima.
                val withAuto = Subtitles.attach(ctx, base) ?: base
                SubtitleConfig.apply(ctx, withAuto, mediaId)
            }.getOrNull()
            ThreadPool.onUi {
                if (isFinishing || isDestroyed) return@onUi
                if (player.currentMediaItem?.localConfiguration?.uri != before) return@onUi
                // `withTrack` pode ser o mesmo item (sem legenda nenhuma) — nesse caso não
                // troca nada, para não reiniciar a decodificação à toa.
                if (withTrack == null || withTrack == player.currentMediaItem) return@onUi
                player.replaceMediaItem(player.currentMediaItemIndex, withTrack)
                updateSubtitleLabel()
            }
        }
    }

    /**
     * Rótulo do botão: "CC" com uma trilha, "CC 2" com duas. Sem trilha nenhuma fica só
     * "Legenda" — que é o convite a abrir o diálogo e carregar uma.
     */
    private fun updateSubtitleLabel() {
        val n = runCatching {
            Playback.player?.currentMediaItem?.localConfiguration?.subtitleConfigurations?.size ?: 0
        }.getOrDefault(0)
        currentTrackLabel = if (n > 1) getString(R.string.video_subtitle_count, n) else ""
        if (::subtitleBtn.isInitialized) {
            subtitleBtn.text = currentTrackLabel.ifEmpty { getString(R.string.video_subtitle) }
        }
    }

    /**
     * Diálogo da legenda: **Automático** (a de convenção de nome) e **Normal** (a que você
     * carregou por link ou arquivo), mais desligar. Tudo é aplicado na hora no player
     * atual, com `replaceMediaItem`, para não perder a posição do vídeo.
     */
    private fun showSubtitleDialog(song: Song) {
        val ctx = this
        val player = Playback.player
        val base = player?.currentMediaItem ?: run {
            Toast.makeText(ctx, R.string.video_subtitle_none, Toast.LENGTH_SHORT).show()
            return
        }
        val mediaId = song.id
        val saved = SubtitleConfig.tracks(ctx, mediaId)
        val autoUri = runCatching { Subtitles.autoUri(ctx, base) }.getOrNull()

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels += getString(R.string.video_subtitle_off)
        actions += { applyTrackChoice(base, emptyList()) }

        if (autoUri != null) {
            labels += getString(R.string.video_subtitle_auto, displayName(autoUri))
            actions += {
                applyTrackChoice(base, listOf(Subtitles.Loaded(autoUri, "application/x-subrip", "pt")))
            }
        }
        saved.forEach { track ->
            labels += getString(R.string.video_subtitle_manual, track.label)
            actions += { applyTrackChoice(base, listOf(track.toLoaded())) }
        }
        // As do PeerTube entram junto das "Normais": para o usuário é a mesma coisa
        // (uma legenda que ele escolheu), e o `autoGenerated` é transparente no WebVTT.
        streamCaptions.forEach { cap ->
            val track = SubtitleConfig.Track(cap.url, MimeTypes.TEXT_VTT, cap.label)
            labels += getString(R.string.video_subtitle_manual, cap.label)
            actions += {
                applyTrackChoice(base, listOf(track.toLoaded()))
                SubtitleConfig.save(ctx, mediaId, listOf(track))
            }
        }
        if (saved.isNotEmpty()) {
            labels += getString(R.string.video_subtitle_clear)
            actions += {
                SubtitleConfig.clear(ctx, mediaId)
                applyTrackChoice(base, emptyList())
            }
        }

        labels += getString(R.string.video_subtitle_from_url)
        actions += { promptSubtitleUrl(song) }
        labels += getString(R.string.video_subtitle_from_file)
        actions += { pickSubtitleLauncher.launch(SubtitleConfig.MIME_FILTER) }
        labels += getString(R.string.close)
        actions += { }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.video_subtitle)
            .setItems(labels.toTypedArray()) { d, which -> actions[which].invoke(); d.dismiss() }
            .show()
    }

    /**
     * Troca as trilhas de legenda do item **que está tocando** e salva a escolha.
     *
     * `replaceMediaItem` e não `setMediaItems`: o primeiro mantém posição e fila, o segundo
     * jogaria o vídeo de volta ao segundo zero — trocar legenda não pode custar a hora aonde
     * a pessoa parou.
     */
    private fun applyTrackChoice(base: MediaItem, tracks: List<Subtitles.Loaded>) {
        val player = Playback.player ?: return
        val song = Playback.currentSong ?: return
        // Lista vazia significa "desligado", e aí tem que remover as trilhas de verdade.
        // Não dá para passar a lista vazia para o [Subtitles.withSubtitle] e esperar: ele
        // devolve o item intacto, e o item intacto ainda tem a legenda antiga ligada.
        val target = if (tracks.isEmpty()) {
            base.buildUpon().setSubtitleConfigurations(emptyList()).build()
        } else {
            Subtitles.withSubtitle(base, tracks)
        }
        player.replaceMediaItem(player.currentMediaItemIndex, target)
        if (tracks.isEmpty()) {
            SubtitleConfig.clear(this, song.id)
        } else {
            SubtitleConfig.save(this, song.id, tracks.map {
                SubtitleConfig.Track(it.uri.toString(), it.mimeType, displayName(it.uri))
            })
        }
        updateSubtitleLabel()
        Toast.makeText(this, R.string.video_subtitle_applied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Troca o item por um já pronto, como o que [Subtitles.attachFromUrl] devolve.
     *
     * Separado do [applyTrackChoice] porque aqui o `MediaItem` já vem com a trilha dentro:
     * passar por ele com lista vazia apagaria justamente a legenda que foi baixada.
     */
    private fun applyPreparedItem(item: MediaItem) {
        val player = Playback.player ?: return
        player.replaceMediaItem(player.currentMediaItemIndex, item)
        updateSubtitleLabel()
    }

    /** Pede o link do `.srt`/`.vtt` e baixa. */
    private fun promptSubtitleUrl(song: Song) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.video_subtitle_url_hint)
            setSingleLine()
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.video_subtitle_from_url)
            .setView(input)
            .setPositiveButton(R.string.video_stream_play) { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) return@setPositiveButton
                downloadSubtitle(song, url)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Baixa a legenda por link e aplica. A rede acontece na `ThreadPool`. */
    private fun downloadSubtitle(song: Song, url: String) {
        val player = Playback.player
        val base = player?.currentMediaItem ?: return
        val before = base.localConfiguration?.uri
        Toast.makeText(this, R.string.video_subtitle_downloading, Toast.LENGTH_SHORT).show()
        ThreadPool.post {
            val updated = Subtitles.attachFromUrl(applicationContext, base, url)
            ThreadPool.onUi {
                if (isFinishing || isDestroyed) return@onUi
                if (player.currentMediaItem?.localConfiguration?.uri != before) return@onUi
                if (updated == null) {
                    Toast.makeText(this, R.string.video_subtitle_failed, Toast.LENGTH_LONG).show()
                    return@onUi
                }
                applyPreparedItem(updated)
                // A extensão vem do link, não da resposta: a API do PeerTube serve `.vtt` e
                // alguns servidores devolvem `.srt` no mesmo campo, então o que vale é o
                // endereço que a pessoa digitou.
                val ext = Subtitles.extOf(url) ?: "srt"
                val mime = if (ext == "vtt") MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
                val song2 = Playback.currentSong
                if (song2 != null) {
                    SubtitleConfig.save(
                        this, song2.id,
                        listOf(SubtitleConfig.Track(url, mime, displayName(Uri.parse(url))))
                    )
                }
                Toast.makeText(this, R.string.video_subtitle_applied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** `.srt` escolhido pelo seletor do sistema: usa a `content://` como está. */
    private fun onSubtitlePicked(uri: Uri) {
        val player = Playback.player ?: return
        val base = player.currentMediaItem ?: return
        // O `OpenDocument` pede permissão persistente, mas ela só é **retida** se alguém
        // chamar `takePersistableUriPermission`. Sem esta linha o `content://` salvo
        // funciona na hora e morre no próximo reinício do aparelho — a legenda escolhida
        // sumiria sozinha, e a culpa pareceria do app.
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val ext = Subtitles.extOf(uri.lastPathSegment.orEmpty()) ?: "srt"
        // O terceiro campo de `Loaded` é `language`, não rótulo: o nome do arquivo é
        // guardado pelo `applyTrackChoice`, e aqui só interessa o idioma.
        val loaded = Subtitles.Loaded(
            uri,
            if (ext == "vtt") MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP,
            "pt"
        )
        applyTrackChoice(base, listOf(loaded))
    }

    /**
     * Desmonta uma trilha que veio no extra da intent.
     *
     * `\n` separa os campos porque o rótulo do PeerTube é texto livre e pode ter espaços,
     * mas nunca quebra de linha. linhas a menos são uma intent velha/ corrompida: melhor
     * descartar a trilha do que oferecer um item que quebra ao tocar.
     */
    private fun parseCaptionExtra(raw: String): PeerTube.Caption? {
        val parts = raw.split('\n')
        if (parts.size < 2) return null
        val url = parts[0].trim()
        val label = parts[1].trim()
        if (url.isEmpty() || label.isEmpty()) return null
        return PeerTube.Caption(url = url, label = label, language = parts.getOrNull(2)?.trim().orEmpty())
    }

    private fun displayName(uri: Uri): String =
        runCatching { uri.lastPathSegment?.substringBeforeLast('.').orEmpty() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()

    override fun onPlayStateChanged(isPlaying: Boolean) {
        updateState()
    }

    override fun onProgress(positionMs: Long, durationMs: Long) {
        // `scrubbing` é o arrasto do dedo; `dragging` é o SeekBar. Os dois precisam
        // segurar o cursor, senão o progresso que chega a cada tique repõe a barra no
        // meio do gesto e o dedo "briga" com o vídeo.
        if (dragging || scrubbing) return
        if (durationMs > 0L) {
            seekBar.max = durationMs.toInt()
            durationText.text = Helper.formatDuration(durationMs)
        }
        seekBar.progress = positionMs.toInt().coerceIn(0, seekBar.max)
        currentText.text = Helper.formatDuration(positionMs)
    }

    override fun onTrackError(song: Song?) {
        if (song?.isVideo == true) showError(getString(R.string.video_player_error))
    }

    private fun updateState() {
        if (errorView.visibility == View.VISIBLE) {
            loading.visibility = View.GONE
            return
        }
        val p = Playback.player
        val playing = Playback.isPlaying
        val buffering = p?.playbackState == Player.STATE_IDLE || p?.playbackState == Player.STATE_BUFFERING
        loading.visibility = if (buffering) View.VISIBLE else View.GONE
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (playing) scheduleHide()
    }

    private fun showError(msg: String) {
        loading.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorView.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun toggleControls() {
        if (topBar.visibility == View.VISIBLE) hideControls() else showControls()
    }

    private fun showControls() {
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        updateState()
        if (Playback.isPlaying) scheduleHide()
    }

    private fun hideControls() {
        if (errorView.visibility == View.VISIBLE || !Playback.isPlaying) return
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, HIDE_DELAY)
    }

    /**
     * Entra (ou sai) do Picture-in-Picture.
     *
     * O toque do botão chama [togglePip] explicitamente; o gesto do sistema (arrastar o vídeo
     * pro canto, ou o botão do player do Android) chega por [onUserLeaveHint] /
     * [onPictureInPictureModeChanged]. No gesto do sistema nós só *autorizamos* — quem entra
     * de fato é o framework, senão a activity tentaria se reduzir duas vezes.
     */
    private fun togglePip() {
        if (isInPictureInPictureMode) {
            // Sai do PiP voltando a tela cheia; o vídeo segue tocando. Usa o
            // onPictureInPictureModeChanged(boolean, Configuration) com a config real.
            pipWanted = false
            onPictureInPictureModeChanged(false, resources.configuration)
        } else if (supportsPiP()) {
            pipWanted = true
            try {
                enterPictureInPictureMode(pipParams())
            } catch (e: IllegalStateException) {
                //alguns ROMs recusam fora de fullscreen; nesse caso seguimos fullscreen
                CrashLogger.writeLog(this, "PIP recusado: $e")
            }
        } else {
            Toast.makeText(this, R.string.video_pip_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun supportsPiP(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.N || packageManager.hasSystemFeature(
            PackageManager.FEATURE_PICTURE_IN_PICTURE
        )

    private fun pipParams(): PictureInPictureParams {
        // O `Video` do modelo não guarda largura/altura, então pegamos o tamanho real do
        // vídeo que o player já decodificou. Antes do primeiro frame (ou para um áudio
        // escapado na fila) cai no 16:9, que é o formato de vídeo mais comum e nunca
        // deixa o PiP esticado.
        val ratio = Playback.player?.videoSize?.let { size ->
            if (size.width > 0 && size.height > 0) {
                size.width.toFloat() / size.height
            } else null
        } ?: (16f / 9f)
        return PictureInPictureParams.Builder()
            .setAspectRatio(
                android.util.Rational(
                    (ratio * 1000).toInt().coerceIn(420, 2390),
                    1000
                )
            )
            .build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Autoriza o sistema a reduzir quando o usuário sair (botão home/recente).
        //
        // Só depois que o PiP foi pedido explicitamente pelo botão (pipWanted). Sem essa
        // trava, `onUserLeaveHint` dispara a cada saída da activity — abrir o WhatsApp,
        // trocar de aba, tocar numa notificação — e o vídeo cairia no cantinho sozinho,
        // que não é o que o usuário pediu nem o que o Youtube faz.
        if (pipWanted && videoSongs.isNotEmpty() && Playback.isPlaying && supportsPiP()) {
            try {
                setPictureInPictureParams(pipParams())
            } catch (e: IllegalStateException) {
                CrashLogger.writeLog(this, "PIP params recusado: $e")
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPipMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPipMode, newConfig)
        if (isInPipMode) {
            // Esconde as barras direto, sem passar por hideControls(): aquele método volta
            // cedo quando o player está pausado, e esconder as barras dentro da janela
            // minúscula do PiP ocupa metade dela. Aqui não há como pausado ser erro —
            // pausado no PiP é um estado legítimo.
            handler.removeCallbacks(hideRunnable)
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
        } else {
            // Voltou a tela cheia — reanexar o surface e mostrar as barras de novo.
            handler.removeCallbacks(hideRunnable)
            playerView.player = Playback.player
            render()
            // Voltando do PiP, a legenda foi solta no onStop: religa.
            Playback.currentSong?.let { if (it.isVideo) applySubtitle(it) }
            if (Playback.isPlaying) scheduleHide()
        }
    }

    override fun onResume() {
        super.onResume()
        // Volta a manter a tela acesa: o onStop limpa a flag ao sair (inclusive para o PiP).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // F2: o rótulo da velocidade é lido do `Settings`, então precisa ser reposto ao
        // voltar para a tela — inclusive quando ela foi aberta em modo anexo por um
        // vídeo que já estava tocando a 1,5x, e nesse caso abriram-se os controles com
        // "1x" por baixo de um vídeo correndo a 1,5x.
        if (::speedView.isInitialized) {
            speedView.text = PlaybackSpeeds.label(Settings.videoSpeed(this))
        }
        // F2: ao voltar do background o sistema pode ter trazido as barras de volta (um
        // arrasto do usuário esconde-as só temporariamente), e o vídeo ficaria espremido
        // de novo sem ninguém ter pedido. Reaplica a orientação em vez de confiar no
        // estado que ficou.
        applyOrientation(resources.configuration.orientation)
        // A condição é `videoSongs`, não `videos`: stream (F2) não tem id do MediaStore, então
        // `videos` fica vazio e esta tela nunca anexava o player — o áudio saía (o player toca
        // sozinho, sem surface) e o vídeo não, porque não havia nem `PlayerView.player`
        // nem `onSongChanged` para renderizar. A tela abria, o áudio tocava, e a área de
        // vídeo ficava preta. Checagem equivalente em onPause/onDestroy.
        if (videoSongs.isNotEmpty() && !finished) {
            Playback.listener = this
            playerView.player = Playback.player
            if (autoplay) Playback.play()
            render()
            // Recomecando, religa a legenda do video que ja esta tocando.
            Playback.currentSong?.let { if (it.isVideo) applySubtitle(it) }
        }
    }

    override fun onPause() {
        handler.removeCallbacks(hideRunnable)
        if (Playback.listener === this) Playback.listener = null
        // Em PiP a activity fica em pausa mas o vídeo TEM de continuar tocando. Por isso o
        // pause e o clear do surface são pulados quando estamos no modo PiP — pausar aqui
        // mataria justamente o recurso que o usuário pediu.
        if (isInPictureInPictureMode) {
            super.onPause()
            return
        }
        playerView.player = null
        // F2b: no modo anexo a tela é só um visor da fila, e a fila continua sozinha. Pausar
        // aqui mataria a música que está tocando por baixo da fila misturada — e "saiu da
        // tela do vídeo" é justamente o gesto de voltar para o app, não um pedido de pausa.
        if (videoSongs.isNotEmpty() && !attached) {
            autoplay = Playback.isPlaying
            Playback.pause()
        }
        super.onPause()
    }

    override fun onStop() {
        if (Playback.listener === this) Playback.listener = null
        // KEEP_SCREEN_ON só faz sentido com a tela de vídeo na mão. Em PiP deixava a tela
        // acesa o tempo todo; e a flag é do lado do SO, então sobreviveria à activity.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onDestroy() {
        // Fechar o PiP (arrastando pra fora) mata a activity. Sem esta guarda, o
        // onDestroy restaurava a fila da música ou dava Playback.stop(), e o vídeo
        // MORRIA junto — um player que não sobrevive ao PiP não serve pra nada.
        // O destino correto é voltar pro app em tela cheia, com o vídeo tocando.
        val leftForMusic = closingForMusic.get()
        finished = true
        handler.removeCallbacks(hideRunnable)
        if (Playback.listener === this) Playback.listener = null
        playerView.player = null
        playbackBind?.let { Playback.release(it) }
        playbackBind = null
        // F2b: o modo anexo não mexe em shuffle/repeat nem devolve fila — quem toca é a fila
        // única do app e o repeat dela é o do usuário. Mexer aqui trocaria o repeat de quem
        // estava ouvindo música só por ter aberto a tela do vídeo.
        //
        // `onScreen` é zerado **antes** dos dois retornos: ele é a trava contra uma segunda
        // tela de vídeo, e valem os dois modos de entrada. Marcando só no anexo, um vídeo
        // aberto pela biblioteca deixava a trava livre, e o `showPlaying` do `onSongChanged`
        // podia abrir outra tela de vídeo por cima desta.
        onScreen = false
        if (attached) {
            super.onDestroy()
            return
        }
        Playback.setShuffle(prevShuffle)
        Playback.setRepeatAll(prevRepeatAll)
        Playback.setRepeatOne(prevRepeatOne)
        if (leftForMusic) {
            // F2: a rotação é travada por `setRequestedOrientation` enquanto esta tela
            // existe, e a activity **não** morre ao girar (configChanges), então a trava
            // sobrevive à rotação — que é o objetivo. Mas a trava também sobreviveria à
            // activity: sem desfazer aqui, a próxima tela do app abriria deitada por ter
            // herdado a orientação pedida por uma tela que já foi embora.
            //
            // Só no caminho "voltar para a música": fechar o PiP mantém a trava, porque
            // a activity volta a abrir em tela cheia e o vídeo continua tocando.
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // Só o "voltar" da tela cheia devolve a fila da música. Fechar o PiP é o
            // contrário: o usuário quer CONTINUAR no vídeo.
            //
            // F2b: "devolve a fila da música" só faz sentido quando HÁ uma. O vídeo é
            // aberto pela biblioteca com `Playback.start`, que substitui a fila do motor,
            // e a fila anterior é guardada em `resumeQueue` para voltar no fim. Quando nada
            // estava tocando, essa fila guardada está vazia — e a ramificação de baixo caía
            // no `Playback.stop()`, que derrubava o vídeo junto. Resultado: sair do vídeo
            // deixava o app em silêncio, sem mini player, como se nada estivesse tocando,
            // mesmo com o vídeo na tela.
            //
            // Sem fila para devolver, o certo é o comportamento do modo anexo: o vídeo
            // continua na mini player (o áudio tocando, a miniatura no quadrado), e tocar
            // nele reabre a tela de vídeo.
            //
            // Só toca se não foi uma pausa pedida: quem parou o vídeo e depois saiu quer
            // que ele continue parado na mini player, não que o app volte a fazer barulho
            // sozinho.
            if (resumeQueue.isEmpty()) {
                if (!Playback.pausedDeliberately) Playback.play()
            } else if (resumePlaying) {
                Playback.start(resumeQueue, resumeIndex.coerceAtLeast(0))
            } else {
                Playback.stop()
            }
        }
        super.onDestroy()
    }
}