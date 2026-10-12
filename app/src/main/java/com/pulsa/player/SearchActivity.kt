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
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.model.Album
import com.pulsa.player.model.Artist
import com.pulsa.player.model.Song
import com.pulsa.player.model.Video
import com.pulsa.player.model.toSong
import com.pulsa.player.dj.DjLearn
import com.pulsa.player.podcast.Episode
import com.pulsa.player.podcast.PodcastDb
import com.pulsa.player.playback.Playback
import com.pulsa.player.ui.adapter.SearchAdapter
import com.pulsa.player.ui.adapter.SearchResult
import com.pulsa.player.ui.AnimatedBackground
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool

/**
 * F5 — a busca unificada.
 *
 * Antes casava só música (título, artista, álbum), álbum e artista. Agora casa também vídeo e
 * episódio de podcast, com uma tira de chips que restringe por tipo. O desenho é o mesmo em
 * todos os tipos: carrega as bibliotecas uma vez no `ThreadPool`, filtra em memória a cada tecla
 * e agrupa por seção.
 *
 * A Activity também assume o slot de [Playback.Listener] enquanto está na frente, porque a
 * `MainActivity` solta o slot no `onStop` e um resultado de vídeo tocado daqui não teria quem
 * abrisse a tela cheia — o áudio sairia e a imagem, não.
 */
class SearchActivity : AppCompatActivity(), Playback.Listener {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: SearchAdapter

    private var songsCache: List<Song> = emptyList()
    private var albumsCache: List<Album> = emptyList()
    private var artistsCache: List<Artist> = emptyList()
    private var videosCache: List<Video> = emptyList()
    private var episodesCache: List<SearchResult.EpisodeResult> = emptyList()

    private var typeFilter: String = FILTER_ALL
    private var contentFilter: String = CONTENT_ALL
    private var playedMap: Map<Long, Long> = emptyMap()
    private var lastQuery: String = ""

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
            onArtist = { artist -> playFrom { Library.songsByArtist(this, artist.name) } },
            onVideo = { video ->
                Playback.start(listOf(video.toSong(getString(R.string.tab_videos))), 0)
            },
            onEpisode = { episode, feedTitle ->
                val song = runCatching {
                    PodcastDb.get(applicationContext).toSong(feedTitle, episode)
                }.getOrNull()
                if (song != null) Playback.start(listOf(song), 0)
            }
        )
        list = findViewById(R.id.search_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        empty = findViewById(R.id.search_empty)

        findViewById<View>(R.id.btn_search_back).setOnClickListener { finish() }
        setUpFilters()
        setUpContentFilters()

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
            val videos = VideoLibrary.all(applicationContext)
            // Os títulos dos feeds vêm numa leitura só; o episódio guarda apenas o `podcastId`,
            // e resolver o nome na hora de desenhar seria consultar o banco na main thread.
            val db = PodcastDb.get(applicationContext)
            val titles = db.feeds().associate { it.id to it.title.orEmpty() }
            val episodes = db.allEpisodes()
                .map { SearchResult.EpisodeResult(it, titles[it.podcastId].orEmpty()) }
            val played = DjLearn.lastPlayedMap(applicationContext)
            ThreadPool.onUi {
                songsCache = songs
                albumsCache = albums
                artistsCache = artists
                videosCache = videos
                episodesCache = episodes
                playedMap = played
                filter(input.text.toString())
            }
        }
    }

    private fun setUpFilters() {
        for ((key, chipId) in FILTER_CHIPS) {
            findViewById<View>(chipId).setOnClickListener {
                typeFilter = key
                syncFilterChips()
                filter(lastQuery)
            }
        }
        syncFilterChips()
    }

    private fun syncFilterChips() {
        for ((key, chipId) in FILTER_CHIPS) {
            findViewById<View>(chipId).isSelected = key == typeFilter
        }
    }

    /**
     * Segunda tira de chips — o filtro de **conteúdo**. Fica em estado próprio (não mexe
     * nos chips de tipo) e compõe com ele: tipo diz a seção, conteúdo diz o que entra.
     * Baixado/Não ouvido/Curtos/Recentes são o que a central de mídia sabe de cada item.
     */
    private fun setUpContentFilters() {
        for ((key, chipId) in CONTENT_CHIPS) {
            findViewById<View>(chipId).setOnClickListener {
                contentFilter = key
                syncContentChips()
                filter(lastQuery)
            }
        }
        syncContentChips()
    }

    private fun syncContentChips() {
        for ((key, chipId) in CONTENT_CHIPS) {
            findViewById<View>(chipId).isSelected = key == contentFilter
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
        lastQuery = query
        val q = query.trim().lowercase()
        if (q.isBlank()) {
            adapter.items = emptyList()
            empty.visibility = View.GONE
            return
        }

        val items = mutableListOf<SearchResult>()

        if (shows(FILTER_SONGS)) {
            val matches = songsCache.filter {
                (it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)) && songContentOk(it)
            }
            addSection(items, R.string.sections_header_songs, matches) { SearchResult.SongResult(it) }
        }
        if (shows(FILTER_ALBUMS) && contentFilter == CONTENT_ALL) {
            val matches = albumsCache.filter {
                it.name.lowercase().contains(q) || it.artist.lowercase().contains(q)
            }
            addSection(items, R.string.sections_header_albums, matches) { SearchResult.AlbumResult(it) }
        }
        if (shows(FILTER_ARTISTS) && contentFilter == CONTENT_ALL) {
            val matches = artistsCache.filter { it.name.lowercase().contains(q) }
            addSection(items, R.string.sections_header_artists, matches) { SearchResult.ArtistResult(it) }
        }
        if (shows(FILTER_VIDEOS)) {
            val matches = videosCache.filter {
                it.title.lowercase().contains(q) && videoContentOk(it)
            }
            addSection(items, R.string.sections_header_videos, matches) { SearchResult.VideoResult(it) }
        }
        if (shows(FILTER_PODCASTS)) {
            val matches = episodesCache.filter {
                (it.episode.title.lowercase().contains(q) ||
                    it.feedTitle.lowercase().contains(q)) && episodeContentOk(it.episode)
            }
            addSection(items, R.string.sections_header_podcasts, matches) { it }
        }

        adapter.items = items
        if (items.isEmpty()) {
            empty.text = getString(R.string.search_empty, query)
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
        }
    }

    /** `true` quando o chip atual mostra este tipo (ou "Tudo", que mostra todos). */
    private fun shows(type: String): Boolean = typeFilter == FILTER_ALL || typeFilter == type

    /**
     * Filtros de conteúdo. Cada tipo sabe o que é "baixado", "não ouvido", curto ou recente
     * do seu jeito — e o que não faz sentido vira `false` para sumir da busca quando o chip
     * está ligado (música antiga não é "recente", vídeo do MediaStore não tem data própria).
     */
    private fun songContentOk(s: Song): Boolean = when (contentFilter) {
        CONTENT_ALL -> true
        CONTENT_UNPLAYED -> playedMap[s.id] == null
        CONTENT_SHORT -> s.durationMs in 1..SHORT_MS
        CONTENT_RECENT -> s.dateAdded > 0L && nowMs() - s.dateAdded <= RECENT_MS
        CONTENT_DOWNLOADED -> false
        else -> true
    }

    private fun videoContentOk(v: Video): Boolean = when (contentFilter) {
        CONTENT_ALL -> true
        CONTENT_UNPLAYED -> playedMap[-(v.id)] == null
        CONTENT_SHORT -> v.durationMs in 1..SHORT_MS
        CONTENT_DOWNLOADED, CONTENT_RECENT -> false
        else -> true
    }

    private fun episodeContentOk(e: Episode): Boolean = when (contentFilter) {
        CONTENT_ALL -> true
        CONTENT_DOWNLOADED -> e.downloaded
        CONTENT_UNPLAYED -> !e.finished
        CONTENT_SHORT -> e.durationSec in 1..SHORT_SEC
        CONTENT_RECENT -> e.publishedAt > 0L && nowMs() - e.publishedAt <= RECENT_MS
        else -> true
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun <T> addSection(
        items: MutableList<SearchResult>,
        headerRes: Int,
        matches: List<T>,
        toResult: (T) -> SearchResult
    ) {
        if (matches.isEmpty()) return
        items += SearchResult.Header(getString(headerRes))
        matches.forEach { items += toResult(it) }
    }

    override fun onStart() {
        super.onStart()
        AnimatedBackground.apply(this)
        Playback.listener = this
    }

    override fun onStop() {
        if (Playback.listener === this) Playback.listener = null
        AnimatedBackground.stop()
        super.onStop()
    }

    override fun onSongChanged(song: Song?, index: Int) {
        if (song != null && song.needsVideoScreen) VideoPlayerActivity.showPlaying(this)
    }

    override fun onPlayStateChanged(isPlaying: Boolean) {}

    override fun onProgress(positionMs: Long, durationMs: Long) {}

    companion object {
        private const val FILTER_ALL = "all"
        private const val FILTER_SONGS = "songs"
        private const val FILTER_ALBUMS = "albums"
        private const val FILTER_ARTISTS = "artists"
        private const val FILTER_VIDEOS = "videos"
        private const val FILTER_PODCASTS = "podcasts"

        private val FILTER_CHIPS = listOf(
            FILTER_ALL to R.id.search_chip_all,
            FILTER_SONGS to R.id.search_chip_songs,
            FILTER_ALBUMS to R.id.search_chip_albums,
            FILTER_ARTISTS to R.id.search_chip_artists,
            FILTER_VIDEOS to R.id.search_chip_videos,
            FILTER_PODCASTS to R.id.search_chip_podcasts
        )

        private const val CONTENT_ALL = "content_all"
        private const val CONTENT_DOWNLOADED = "downloaded"
        private const val CONTENT_UNPLAYED = "unplayed"
        private const val CONTENT_SHORT = "short"
        private const val CONTENT_RECENT = "recent"

        private val CONTENT_CHIPS = listOf(
            CONTENT_ALL to R.id.search_chip_content_all,
            CONTENT_DOWNLOADED to R.id.search_chip_content_downloaded,
            CONTENT_UNPLAYED to R.id.search_chip_content_unplayed,
            CONTENT_SHORT to R.id.search_chip_content_short,
            CONTENT_RECENT to R.id.search_chip_content_recent
        )

        /** "Curtos" = até 5 minutos; "Recentes" = última semana. */
        private const val SHORT_MS = 5 * 60_000L
        private const val SHORT_SEC = 5 * 60L
        private const val RECENT_MS = 7L * 24L * 3_600_000L
    }
}
