package com.pulsa.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import com.pulsa.player.core.Helper
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.model.Song
import com.pulsa.player.model.Video
import com.pulsa.player.playback.Playback
import kotlin.math.abs

/**
 * E5 — a tela de vídeo agora é só uma tela: quem toca é o mesmo motor do rádio/música.
 *
 * Cada vídeo vira um `Song` com `path = "video:<id>"` ([Song.VIDEO_PREFIX]); a activity
 * anexa um [SurfaceView] ao `Player` do serviço ([Playback.player]) e dirige o motor pela
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

    private lateinit var surfaceView: SurfaceView
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
    private var playbackBind: Playback.Bind? = null
    private val handler = Handler(Looper.getMainLooper())

    private val hideRunnable = Runnable { hideControls() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.vp_video)
        loading = findViewById(R.id.vp_loading)
        errorView = findViewById(R.id.vp_error)
        topBar = findViewById(R.id.vp_top)
        bottomBar = findViewById(R.id.vp_bottom)
        titleView = findViewById(R.id.vp_title)
        currentText = findViewById(R.id.vp_current)
        durationText = findViewById(R.id.vp_duration)
        seekBar = findViewById(R.id.vp_seek)
        playBtn = findViewById(R.id.vp_play)

        findViewById<ImageButton>(R.id.vp_back).setOnClickListener { finish() }
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

    override fun onResume() {
        super.onResume()
        if (videos.isNotEmpty() && !finished) {
            Playback.listener = this
            Playback.player?.setVideoSurfaceView(surfaceView)
            if (autoplay) Playback.play()
            render()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(hideRunnable)
        if (Playback.listener === this) Playback.listener = null
        Playback.player?.clearVideoSurfaceView(surfaceView)
        if (videos.isNotEmpty()) {
            autoplay = Playback.isPlaying
            Playback.pause()
        }
        super.onPause()
    }

    override fun onStop() {
        if (Playback.listener === this) Playback.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        finished = true
        handler.removeCallbacks(hideRunnable)
        if (Playback.listener === this) Playback.listener = null
        Playback.player?.clearVideoSurfaceView(surfaceView)
        playbackBind?.let { Playback.release(it) }
        playbackBind = null
        Playback.setShuffle(prevShuffle)
        Playback.setRepeatAll(prevRepeatAll)
        Playback.setRepeatOne(prevRepeatOne)
        if (resumePlaying && resumeQueue.isNotEmpty()) {
            Playback.start(resumeQueue, resumeIndex.coerceAtLeast(0))
        } else {
            Playback.stop()
        }
        super.onDestroy()
    }
}