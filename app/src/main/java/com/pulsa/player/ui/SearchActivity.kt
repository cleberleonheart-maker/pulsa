package com.pulsa.player.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.pulsa.player.R
import com.pulsa.player.search.SearchEngine
import com.pulsa.player.search.SearchResult
import com.pulsa.player.ui.adapter.SearchAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchActivity : AppCompatActivity() {

    private lateinit var searchEngine: SearchEngine
    private lateinit var adapter: SearchAdapter
    private var searchJob: Job? = null
    private var currentFilter = SearchEngine.Filter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.search)

        searchEngine = SearchEngine(this)
        val input = findViewById<EditText>(R.id.search_input)
        val list = findViewById<RecyclerView>(R.id.search_list)
        val clear = findViewById<ImageView>(R.id.search_clear)
        val chips = findViewById<ChipGroup>(R.id.search_chips)

        adapter = SearchAdapter { result -> onResultClick(result) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                debounceSearch(s?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchJob?.cancel()
                searchNow(input.text.toString())
                true
            } else false
        }
        clear.setOnClickListener {
            input.setText("")
            adapter.submit(emptyList())
        }

        setupChips(chips)
        if (intent.getStringExtra("query")?.isNotBlank() == true) {
            input.setText(intent.getStringExtra("query"))
            input.setSelection(input.text.length)
            searchNow(input.text.toString())
        }
    }

    private fun setupChips(group: ChipGroup) {
        // Filtros simples
        val downloaded = findViewById<Chip>(R.id.chip_downloaded)
        val notPlayed = findViewById<Chip>(R.id.chip_not_played)
        downloaded.setOnCheckedChangeListener { _, checked ->
            currentFilter = currentFilter.copy(downloadedOnly = checked)
            searchNow(findViewById<EditText>(R.id.search_input).text.toString())
        }
        notPlayed.setOnCheckedChangeListener { _, checked ->
            currentFilter = currentFilter.copy(notPlayed = checked)
            searchNow(findViewById<EditText>(R.id.search_input).text.toString())
        }
    }

    private fun debounceSearch(q: String) {
        searchJob?.cancel()
        searchJob = CoroutineScope(Dispatchers.Main).launch {
            delay(300)
            searchNow(q)
        }
    }

    private fun searchNow(q: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val res = searchEngine.search(q, currentFilter)
            withContext(Dispatchers.Main) {
                adapter.submit(res)
            }
        }
    }

    private fun onResultClick(result: SearchResult) {
        when (result.type) {
            SearchResult.Type.SONG -> {
                result.song?.let { com.pulsa.player.playback.Playback.start(listOf(it), 0) }
            }
            SearchResult.Type.VIDEO -> {
                result.videoId?.let {
                    val v = com.pulsa.player.media.VideoLibrary.get(this, it)
                    if (v != null) com.pulsa.player.VideoPlayerActivity.startStream(this, v.path, v.title, v.artist)
                }
            }
            SearchResult.Type.EPISODE -> {
                result.episode?.let { ep ->
                    val song = com.pulsa.player.podcast.PodcastDb.toSong(this, ep)
                    com.pulsa.player.playback.Playback.start(listOf(song), 0)
                }
            }
            SearchResult.Type.ARTIST -> {
                result.artistName?.let { startArtist(it) }
            }
            SearchResult.Type.FEED -> {
                result.feed?.let { startFeed(it) }
            }
        }
        finish()
    }

    private fun startArtist(name: String) {
        val intent = android.content.Intent(this, ArtistActivity::class.java)
        intent.putExtra("artist", name)
        startActivity(intent)
    }

    private fun startFeed(feed: com.pulsa.player.podcast.PodcastFeed) {
        val intent = android.content.Intent(this, PodcastDetailFragment::class.java)
        // PodcastDetailFragment é fragment - melhor abrir activity wrapper? vamos tentar via MainActivity
        com.pulsa.player.MainActivity.openLibrarySection(this, "podcasts")
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
