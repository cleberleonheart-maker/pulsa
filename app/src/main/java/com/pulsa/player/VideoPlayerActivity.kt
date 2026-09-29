package com.pulsa.player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.media3.common.Player
import com.pulsa.player.core.CrashLogger
import com.pulsa.player.core.Helper
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.VideoLibrary
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
class VideoPlayerActivity : AppCompatActivity(), Playback.Listener {

    companion object {
        private const val EXTRA_IDS = "video_ids"
        private const val EXTRA_INDEX = "video_index"
        private const val HIDE_DELAY = 3000L

        fun start(context: Context, videos: List<Video>, index: Int) {
            val ids = ArrayList(videos.map { it.id })
            context.startActivity(
                Intent(context, VideoPlayerActivity::class.java)
                    .putExtra(EXTRA_IDS, ids)
                    .putExtra(EXTRA_INDEX, index.coerceIn(0, ids.lastIndex))
            )
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
    
    private var videos: List<Video> = emptyList()
    private var videoSongs: List<Song> = emptyList()
    private var index = 0
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
        if (videos.isEmpty()) return
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
        if (song?.isVideo == true) {
            val video = videos.firstOrNull { it.id == song.videoId }
            if (video != null) return video.title
        }
        return ""
    }

    private fun render() {
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
        if (videos.isEmpty()) return
        if (delta > 0 && index >= videos.lastIndex) {
            Playback.seekTo(0)
            Playback.play()
            showControls()
            return
        }
        Playback.next()
        showControls()
    }

    override fun onSongChanged(song: Song?, index: Int) {
        if (song?.isVideo != true) return
        render()
        // Cada video tem sua legenda: reavalia no START, senão a legenda do video
        // anterior ficaria desenhada no seguinte (e alguns nem tem legenda nenhuma).
        applySubtitle(song)
    }

    /**
     * Liga a `SubtitleView` ao player e anexa a legenda do video que esta tocando, se houver.
     *
     * A busca pelo arquivo `.srt`/`.vtt` toca o disco, entao vai para a `ThreadPool`: o
     * `attach` devolve o MediaItem so quando acha legenda, e nesse caso trocamos o item
     * atual no player. Sem legenda, so escondemos a view e deixamos o video tocar.
     */
    private fun applySubtitle(song: Song) {
        val player = Playback.player
        if (player == null) return
        val before = player.currentMediaItem?.localConfiguration?.uri
        ThreadPool.post {
            val attached = runCatching {
                player.currentMediaItem?.let { Subtitles.attach(applicationContext, it) }
            }.getOrNull()
            ThreadPool.onUi {
                if (isFinishing || isDestroyed) return@onUi
                // O video pode ter trocado de faixa enquanto procurava o .srt. So troca o
                // item se for o MESMO: se nao, a legenda do video anterior entraria no
                // seguinte, e o MediaItem novo seria sobrescrito por um item velho.
                if (player.currentMediaItem?.localConfiguration?.uri != before) return@onUi
                val target = attached ?: return@onUi
                // Substitui o item no lugar (replaceMediaItem), para nao perder a posicao
                // nem a fila: setMediaItems reiniciaria o video do zero.
                player.replaceMediaItem(player.currentMediaItemIndex, target)
            }
        }
    }

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
        if (pipWanted && videos.isNotEmpty() && Playback.isPlaying && supportsPiP()) {
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
            Playback.currentSong?.takeIf { it.isVideo }?.let { applySubtitle(it) }
            if (Playback.isPlaying) scheduleHide()
        }
    }

    override fun onResume() {
        super.onResume()
        // Volta a manter a tela acesa: o onStop limpa a flag ao sair (inclusive para o PiP).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (videos.isNotEmpty() && !finished) {
            Playback.listener = this
            playerView.player = Playback.player
            if (autoplay) Playback.play()
            render()
            // Recomecando, religa a legenda do video que ja esta tocando.
            Playback.currentSong?.takeIf { it.isVideo }?.let { applySubtitle(it) }
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
        if (videos.isNotEmpty()) {
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