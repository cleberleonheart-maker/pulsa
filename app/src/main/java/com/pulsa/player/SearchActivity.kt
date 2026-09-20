package com.pulsa.player

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.data.Library
import com.pulsa.player.model.Album
import com.pulsa.player.model.Artist
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.ui.adapter.SearchAdapter
import com.pulsa.player.ui.adapter.SearchResult
import com.pulsa.player.ui.AnimatedBackground
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool

class SearchActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: SearchAdapter
    private var songsCache: List<Song> = emptyList()
    private var albumsCache: List<Album> = emptyList()
    private var artistsCache: List<Artist> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        AnimatedBackground.apply(this)

        adapter = SearchAdapter(
            onSong = { songs, index ->
                if (songs.isNotEmpty()) Playback.start(songs, index)
            },
            onAlbum = { album -> playFrom { Library.songsByAlbum(this, album.id) } },
            onArtist = { artist -> playFrom { Library.songsByArtist(this, artist.name) } }
        )
        list = findViewById(R.id.search_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        empty = findViewById(R.id.search_empty)

        findViewById<View>(R.id.btn_search_back).setOnClickListener { finish() }

        val input = findViewById<EditText>(R.id.search_input)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter(s?.toString().orEmpty())
            }
        })

        ThreadPool.post {
            val songs = Library.allSongs(applicationContext)
            val albums = Library.albums(applicationContext)
            val artists = Library.artists(applicationContext)
            ThreadPool.onUi {
                songsCache = songs
                albumsCache = albums
                artistsCache = artists
                filter(input.text.toString())
            }
        }
    }

    private fun playFrom(loader: () -> List<Song>) {
        ThreadPool.post {
            val songs = loader()
            ThreadPool.onUi {
                if (songs.isNotEmpty()) Playback.start(songs, 0)
            }
        }
    }

    private fun filter(query: String) {
        val q = query.trim().lowercase()
        if (q.isBlank()) {
            adapter.items = emptyList()
            empty.visibility = View.GONE
            return
        }
        val matchingSongs = songsCache.filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
        val matchingAlbums = albumsCache.filter {
            it.name.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }
        val matchingArtists = artistsCache.filter { it.name.lowercase().contains(q) }

        val items = mutableListOf<SearchResult>()
        if (matchingSongs.isNotEmpty()) {
            items += SearchResult.Header(getString(R.string.sections_header_songs))
            items += matchingSongs.map { SearchResult.SongResult(it) }
        }
        if (matchingAlbums.isNotEmpty()) {
            items += SearchResult.Header(getString(R.string.sections_header_albums))
            items += matchingAlbums.map { SearchResult.AlbumResult(it) }
        }
        if (matchingArtists.isNotEmpty()) {
            items += SearchResult.Header(getString(R.string.sections_header_artists))
            items += matchingArtists.map { SearchResult.ArtistResult(it) }
        }
        adapter.items = items
        if (items.isEmpty()) {
            empty.text = "Nenhum resultado para \"$query\""
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        AnimatedBackground.apply(this)
    }

    override fun onStop() {
        AnimatedBackground.stop()
        super.onStop()
    }
}
