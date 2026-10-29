package com.pulsa.player

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.playback.QueueKey
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.sync.Karaoke
import com.pulsa.player.sync.Lyrics
import com.pulsa.player.ui.adapter.LyricsAdapter

/**
 * Karaokê de viagem (item 171): letra sincronizada em **landscape fullscreen**, feita para
 * levar no painel do carro ou jogar na TV — letra gigante com o karaokê palavra-a-palavra
 * (item 126) e o avatar da Virgin dançando. Reaproveita [Lyrics] e [LyricsAdapter]; os
 * controles são mínimos (voltar/play/avançar) porque a ideia é só acompanhar e cantar.
 */
class KaraokeActivity : AppCompatActivity(), Playback.Listener {

    private lateinit var lyricsList: RecyclerView
    private lateinit var adapter: LyricsAdapter
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var playBtn: MaterialButton

    private var playbackBind: Playback.Bind? = null
    private var lines: List<Lyrics.Line> = emptyList()
    private var lastIndex = -1
    private var loadingFor = -1L
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(Settings.accentStyle(this))
        // Passeio/TV: não faz sentido a tela apagar. A orientação (paisagem) vem do manifesto.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_karaoke)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        titleView = findViewById(R.id.karaoke_title)
        artistView = findViewById(R.id.karaoke_artist)
        playBtn = findViewById(R.id.karaoke_btn_play)
        lyricsList = findViewById(R.id.karaoke_lyrics)
        // Texto grande: 2x, o suficiente para ler de longe sem virar parede de letras.
        adapter = LyricsAdapter(textScale = 2f)
        lyricsList.layoutManager = LinearLayoutManager(this)
        lyricsList.adapter = adapter

        findViewById<MaterialButton>(R.id.karaoke_btn_close).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.karaoke_btn_prev).setOnClickListener { Playback.prev() }
        findViewById<MaterialButton>(R.id.karaoke_btn_next).setOnClickListener { Playback.next() }
        playBtn.setOnClickListener {
            Playback.toggle()
            render()
        }

        com.pulsa.player.util.TvFocus.applyTo(findViewById(android.R.id.content))
    }

    override fun onStart() {
        super.onStart()
        Playback.listener = this
        playbackBind = Playback.connect(this) { render() }
        onSongChanged(Playback.currentSong, 0)
        render()
    }

    override fun onStop() {
        if (Playback.listener === this) Playback.listener = null
        playbackBind?.let { Playback.release(it) }
        playbackBind = null
        super.onStop()
    }

    override fun onSongChanged(song: Song?, index: Int) {
        loadLyrics(song)
        render()
    }

    override fun onPlayStateChanged(isPlaying: Boolean) = render()

    override fun onProgress(positionMs: Long, durationMs: Long) {
        highlight(positionMs, durationMs)
    }

    private fun loadLyrics(song: Song?) {
        val app = applicationContext
        if (song == null) {
            lines = emptyList()
            adapter.submit(emptyList())
            findViewById<View>(R.id.karaoke_empty).visibility = View.VISIBLE
            return
        }
        if (song.id == loadingFor || loading) return
        loading = true
        loadingFor = song.id
        val key = QueueKey.encode(song)
        lines = emptyList()
        lastIndex = -1
        adapter.submit(emptyList())
        ThreadPool.postNetwork {
            val result = Lyrics.resolve(song, app)
            ThreadPool.onUi {
                loading = false
                if (!QueueKey.sameType(Playback.currentKey, key)) {
                    loadingFor = -1L
                    return@onUi
                }
                lines = result?.lines.orEmpty()
                adapter.submit(lines)
                findViewById<View>(R.id.karaoke_empty).visibility =
                    if (lines.isEmpty()) View.VISIBLE else View.GONE
                highlight(Playback.position, Playback.currentSong?.durationMs ?: 0L)
            }
        }
    }

    private fun highlight(positionMs: Long, durationMs: Long) {
        if (lines.isEmpty()) return
        val idx = Lyrics.lineAt(lines, positionMs)
        adapter.setCurrent(idx)
        if (idx in lines.indices) {
            val start = lines[idx].timeMs
            val end = lines.getOrNull(idx + 1)?.timeMs
                ?: durationMs.takeIf { it > start } ?: (start + 4_000L)
            adapter.setCursor(Karaoke.fractionFor(positionMs, start, end))
        }
        if (idx in lines.indices && idx != lastIndex) {
            lastIndex = idx
            lyricsList.smoothScrollToPosition(idx)
        }
    }

    private fun render() {
        if (!::playBtn.isInitialized) return
        val now = Playback.currentSong
        playBtn.setIconResource(if (Playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        titleView.text = now?.title ?: "—"
        artistView.text = now?.artist ?: "—"
    }
}
