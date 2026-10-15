package com.pulsa.player.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.pulsa.player.R
import com.pulsa.player.VideoPlayerActivity
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.Library
import com.pulsa.player.podcast.PodcastDb
import com.pulsa.player.playback.Playback
import com.pulsa.player.search.SearchEngine
import com.pulsa.player.search.SearchResult
import com.pulsa.player.ui.adapter.SearchAdapter

/**
 * F5 — A busca unificada.
 *
 * Busca música, vídeo, episódio, artista e feed num lugar só, com chips de filtro
 * (baixados / nunca tocados). A consulta roda no [ThreadPool] com um atraso curto de digitação
 * para não disparar uma varredura do MediaStore por tecla.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var engine: SearchEngine
    private lateinit var adapter: SearchAdapter
    private lateinit var emptyView: TextView
    private var filter = SearchEngine.Filter()

    private val main = Handler(Looper.getMainLooper())
    private val pending = Runnable { runSearch() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.search)

        engine = SearchEngine(this)
        adapter = SearchAdapter { onResultClick(it) }

        val input = findViewById<EditText>(R.id.search_input)
        val list = findViewById<RecyclerView>(R.id.search_list)
        emptyView = findViewById(R.id.search_empty)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = scheduleSearch()
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                main.removeCallbacks(pending)
                runSearch()
                true
            } else false
        }
        findViewById<ImageView>(R.id.search_clear).setOnClickListener {
            input.setText("")
            adapter.submit(emptyList())
            emptyView.visibility = View.GONE
        }
        findViewById<Chip>(R.id.chip_downloaded).setOnCheckedChangeListener { _, checked ->
            filter = filter.copy(downloadedOnly = checked)
            runSearch()
        }
        findViewById<Chip>(R.id.chip_not_played).setOnCheckedChangeListener { _, checked ->
            filter = filter.copy(notPlayed = checked)
            runSearch()
        }

        val initial = intent.getStringExtra(EXTRA_QUERY)
        if (!initial.isNullOrBlank()) {
            input.setText(initial)
            input.setSelection(input.text.length)
            runSearch()
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(pending)
        super.onDestroy()
    }

    /** Atraso de digitação: uma varredura por rajada, não por tecla. */
    private fun scheduleSearch() {
        main.removeCallbacks(pending)
        main.postDelayed(pending, 250)
    }

    private fun runSearch() {
        val q = findViewById<EditText>(R.id.search_input).text.toString()
        if (q.isBlank()) {
            adapter.submit(emptyList())
            emptyView.visibility = View.GONE
            return
        }
        val f = filter
        ThreadPool.post {
            val results = runCatching { engine.search(q, f) }.getOrDefault(emptyList())
            ThreadPool.onUi {
                if (isFinishing || isDestroyed) return@onUi
                adapter.submit(results)
                emptyView.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * O clique. Cada tipo leva ao que faz sentido: música/episódio toca, vídeo abre a tela de
     * vídeo e artista toca o acervo dele. Feed toca os episódios não ouvidos (é o caso comum de
     * quem busca o podcast para ouvir agora).
     */
    private fun onResultClick(result: SearchResult) {
        when (result.type) {
            SearchResult.Type.SONG -> result.song?.let { Playback.start(listOf(it), 0) }

            SearchResult.Type.VIDEO -> result.video?.let {
                VideoPlayerActivity.start(this, listOf(it), 0)
            }

            SearchResult.Type.EPISODE -> result.episode?.let { ep ->
                ThreadPool.post {
                    val db = PodcastDb.get(this)
                    val title = runCatching { db.feed(ep.podcastId)?.title }.getOrNull().orEmpty()
                    val song = db.toSong(title, ep)
                    ThreadPool.onUi { Playback.start(listOf(song), 0) }
                }
            }

            SearchResult.Type.ARTIST -> result.artistName?.let { name ->
                ThreadPool.post {
                    val songs = Library.songsByArtist(this, name)
                    ThreadPool.onUi { if (songs.isNotEmpty()) Playback.start(songs, 0) }
                }
            }

            SearchResult.Type.FEED -> if (result.feedId > 0L) {
                ThreadPool.post {
                    val db = PodcastDb.get(this)
                    val podcast = runCatching { db.feed(result.feedId) }.getOrNull()
                    val episodes = podcast?.episodes.orEmpty()
                        .filter { !it.played }
                        .ifEmpty { podcast?.episodes.orEmpty() }
                    val songs = episodes.map { db.toSong(podcast?.title.orEmpty(), it) }
                    ThreadPool.onUi { if (songs.isNotEmpty()) Playback.start(songs, 0) }
                }
            }
        }
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_QUERY = "query"
    }
}
