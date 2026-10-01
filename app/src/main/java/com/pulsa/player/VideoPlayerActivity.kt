package com.pulsa.player

import android.content.Context
import android.content.Intent
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
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.PeerTube
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.media.SubtitleConfig
import com.pulsa.player.media.Subtitles
import com.pulsa.player.model.Song
import com.pulsa.player.model.Video
import com.pulsa.player.playback.Playback
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
        private const val HIDE_DELAY = 3000L

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

    /** Texto do botão de legenda: "CC" quando há uma trilha, "CC 2" quando há várias. */
    private var currentTrackLabel = ""

    /** Trilhas que o PeerTube declarou no detalhe do vídeo, se o stream veio de lá. */
    private var streamCaptions: List<PeerTube.Caption> = emptyList()

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
                toggleControls()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (abs(dx) > abs(dy) && abs(dx) > 60 && abs(velocityX) > 400) {
                    step(if (dx < 0) -1 else 1)
                    return true
                }
                return false
            }
        })
        findViewById<View>(R.id.vp_root).setOnTouchListener { _, e ->
            detector.onTouchEvent(e)
            true
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
            videoSongs = videos.map {
                Song(
                    id = it.id,
                    title = it.title,
                    artist = "",
                    album = getString(R.string.tab_videos),
                    albumId = 0L,
                    durationMs = it.durationMs,
                    path = Song.VIDEO_PREFIX + it.id,
                    year = 0
                )
            }
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
        // O vídeo anda como fila normal do motor: repeat-all, sem shuffle nem repeat-one,
        // para "next" avançar em vez de repetir/embaralhar o próprio vídeo.
        Playback.setShuffle(false)
        Playback.setRepeatAll(true)
        Playback.setRepeatOne(false)
        Playback.start(videoSongs, index)
        render()
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
        // Stream tem um item só: "next" no fim volta do começo em vez de não fazer nada, que
        // é o que o gesto de arrastar espera de um item só.
        if (delta > 0 && current >= videoSongs.lastIndex) {
            Playback.seekTo(0)
            Playback.play()
            showControls()
            return
        }
        // O `delta` precisa chegar ao motor. Antes o "voltar" — botão e gesto de arrastar
        // para a esquerda — caía no `Playback.next()` abaixo, que só avança: voltar andava
        // para a frente e o gesto parecia não ter efeito nenhum.
        if (delta > 0) Playback.next() else Playback.prev()
        showControls()
    }

    override fun onSongChanged(song: Song?, index: Int) {
        if (song?.isVideo != true && song?.isStream != true) return
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
            val attached = runCatching {
                val base = player.currentMediaItem ?: return@runCatching null
                // Base sem legenda: a convenção primeiro, e a config por cima.
                val withAuto = Subtitles.attach(ctx, base) ?: base
                SubtitleConfig.apply(ctx, withAuto, mediaId)
            }.getOrNull()
            ThreadPool.onUi {
                if (isFinishing || isDestroyed) return@onUi
                if (player.currentMediaItem?.localConfiguration?.uri != before) return@onUi
                // `attached` pode ser o mesmo item (sem legenda nenhuma) — nesse caso não
                // troca nada, para não reiniciar a decodificação à toa.
                if (attached == null || attached == player.currentMediaItem) return@onUi
                player.replaceMediaItem(player.currentMediaItemIndex, attached)
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
        if (dragging) return
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
        if (videoSongs.isNotEmpty()) {
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
        Playback.setShuffle(prevShuffle)
        Playback.setRepeatAll(prevRepeatAll)
        Playback.setRepeatOne(prevRepeatOne)
        if (leftForMusic) {
            // Só o "voltar" da tela cheia devolve a fila da música. Fechar o PiP é o
            // contrário: o usuário quer CONTINUAR no vídeo.
            if (resumePlaying && resumeQueue.isNotEmpty()) {
                Playback.start(resumeQueue, resumeIndex.coerceAtLeast(0))
            } else {
                Playback.stop()
            }
        }
        super.onDestroy()
    }
}