package com.pulsa.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.model.Video
import com.pulsa.player.playback.Playback
import com.pulsa.player.util.Helper

class VideoPlayerActivity : AppCompatActivity() {

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

    private lateinit var videoView: VideoView
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
    private var index = 0
    private var resumeMusic = false
    private var dragging = false
    private var autoplay = true
    private val handler = Handler(Looper.getMainLooper())

    private val hideRunnable = Runnable { hideControls() }

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            if (videoView.isPlaying) handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoView = findViewById(R.id.vp_video)
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
                runCatching { videoView.seekTo(seekBar.progress) }
                scheduleHide()
            }
        })

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleControls()
                return true
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

        videoView.setOnPreparedListener { onPrepared() }
        videoView.setOnCompletionListener { onCompleted() }
        videoView.setOnErrorListener { _, _, _ -> onVideoError() }

        resumeMusic = Playback.isPlaying
        if (resumeMusic) Playback.toggle()

        openCurrent()
        showControls()
    }

    private fun openCurrent() {
        val video = videos[index]
        titleView.text = video.title
        seekBar.max = 1000
        loading.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        durationText.text = Helper.formatDuration(video.durationMs)
        currentText.text = Helper.formatDuration(0)
        runCatching {
            videoView.setVideoURI(Uri.parse(video.path))
            if (autoplay) videoView.start()
        }.onFailure { showError(getString(R.string.video_player_error)) }
    }

    private fun onPrepared() {
        loading.visibility = View.GONE
        val duration = runCatching { videoView.duration }.getOrDefault(0)
        if (duration > 0) {
            seekBar.max = duration
            durationText.text = Helper.formatDuration(duration.toLong())
        }
        if (autoplay) videoView.start()
    }

    private fun onCompleted() {
        step(1)
    }

    private fun onVideoError(): Boolean {
        showError(getString(R.string.video_player_error))
        return true
    }

    private fun showError(msg: String) {
        loading.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorView.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun togglePlayback() {
        if (errorView.visibility == View.VISIBLE) return
        if (videoView.isPlaying) pausePlayback() else startPlayback()
    }

    private fun startPlayback() {
        autoplay = true
        videoView.start()
        updatePlayIcon()
        handler.removeCallbacks(tick)
        handler.post(tick)
        scheduleHide()
    }

    private fun pausePlayback() {
        autoplay = false
        videoView.pause()
        updatePlayIcon()
        handler.removeCallbacks(hideRunnable)
        showControls()
    }

    private fun updatePlayIcon() {
        playBtn.setImageResource(if (videoView.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun step(delta: Int) {
        if (videos.isEmpty()) return
        if (delta > 0 && index >= videos.lastIndex) {
            videoView.seekTo(0)
            startPlayback()
            return
        }
        index = (((index + delta) % videos.size) + videos.size) % videos.size
        autoplay = videoView.isPlaying || autoplay
        openCurrent()
        showControls()
    }

    private fun updateProgress() {
        if (dragging) return
        val pos = runCatching { videoView.currentPosition }.getOrDefault(0)
        seekBar.progress = pos
        currentText.text = Helper.formatDuration(pos.toLong())
    }

    private fun toggleControls() {
        if (topBar.visibility == View.VISIBLE) hideControls() else showControls()
    }

    private fun showControls() {
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        updatePlayIcon()
        if (videoView.isPlaying) scheduleHide()
    }

    private fun hideControls() {
        if (errorView.visibility == View.VISIBLE || !videoView.isPlaying) return
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, HIDE_DELAY)
    }

    override fun onResume() {
        super.onResume()
        if (videos.isNotEmpty() && errorView.visibility == View.GONE) {
            runCatching {
                if (videoView.isPlaying) {
                    startPlayback()
                } else if (autoplay) {
                    videoView.start()
                }
            }
        }
    }

    override fun onPause() {
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(tick)
        if (videoView.isPlaying) {
            videoView.pause()
            autoplay = true
        } else {
            autoplay = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(tick)
        runCatching {
            if (videoView.isPlaying) videoView.stopPlayback()
        }
        if (resumeMusic && !Playback.isPlaying) Playback.toggle()
        super.onDestroy()
    }
}