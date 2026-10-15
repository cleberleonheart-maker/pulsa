package com.pulsa.player.search

import android.content.Context
import com.pulsa.player.data.Library
import com.pulsa.player.media.VideoLibrary
import com.pulsa.player.podcast.PodcastDb
import com.pulsa.player.sync.DjLearn
import java.util.Locale
import kotlin.math.abs

class SearchEngine(private val context: Context) {

    data class Filter(
        val downloadedOnly: Boolean = false,
        val notPlayed: Boolean = false,
        val sortBy: Sort = Sort.RELEVANT
    ) {
        enum class Sort { RELEVANT, MOST_PLAYED, RECENT, OLDEST, LONGEST, SHORTEST }
    }

    fun search(query: String, filter: Filter = Filter()): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase(Locale.getDefault())
        val results = mutableListOf<SearchResult>()

        // Músicas
        Library.allSongs(context).forEach { song ->
            if (!matches(song.title, song.artist, song.album, q)) return@forEach
            if (filter.downloadedOnly && !Library.isLocal(song.path)) return@forEach
            val last = DjLearn.lastPlayedAt(context, song.id, false)
            if (filter.notPlayed && last > 0) return@forEach
            results.add(
                SearchResult(
                    type = SearchResult.Type.SONG,
                    title = song.title,
                    subtitle = "${song.artist} · ${song.album}",
                    albumId = song.albumId,
                    durationMs = song.durationMs,
                    lastPlayedMs = last,
                    addedAt = song.addedAt,
                    song = song
                )
            )
        }

        // Vídeos
        VideoLibrary.all(context).forEach { v ->
            if (!matches(v.title, v.artist, "", q)) return@forEach
            if (filter.downloadedOnly && v.path.startsWith("http")) return@forEach
            results.add(
                SearchResult(
                    type = SearchResult.Type.VIDEO,
                    title = v.title,
                    subtitle = v.artist.ifEmpty { v.duration.toString() },
                    videoId = v.id,
                    durationMs = v.duration * 1000L,
                    isDownloaded = !v.path.startsWith("http"),
                    addedAt = v.addedAt
                )
            )
        }

        // Artistas
        Library.artists(context).forEach { a ->
            if (a.name.lowercase(Locale.getDefault()).contains(q)) {
                results.add(
                    SearchResult(
                        type = SearchResult.Type.ARTIST,
                        title = a.name,
                        subtitle = "${a.count} músicas",
                        artistName = a.name
                    )
                )
            }
        }

        // Feeds de podcast
        PodcastDb.feeds(context).forEach { f ->
            if (matches(f.title, f.author, f.description, q)) {
                results.add(
                    SearchResult(
                        type = SearchResult.Type.FEED,
                        title = f.title,
                        subtitle = f.author.ifEmpty { "Podcast" },
                        feed = f,
                        isDownloaded = true
                    )
                )
            }
        }

        // Episódios de podcast
        PodcastDb.episodes(context).forEach { e ->
            if (matches(e.title, e.feedTitle, e.description, q)) {
                if (filter.downloadedOnly && e.filePath.isNullOrBlank()) return@forEach
                results.add(
                    SearchResult(
                        type = SearchResult.Type.EPISODE,
                        title = e.title,
                        subtitle = e.feedTitle,
                        episode = e,
                        durationMs = e.durationMs,
                        isDownloaded = !e.filePath.isNullOrBlank(),
                        addedAt = e.pubDate
                    )
                )
            }
        }

        return sort(results, filter.sortBy, q)
    }

    private fun matches(vararg fields: String): Boolean {
        val q = "" // será passado externamente? simplifica: vamos reimplementar
        return false
    }

    private fun matches(t: String, a: String, b: String, q: String): Boolean {
        return t.lowercase(Locale.getDefault()).contains(q) ||
            a.lowercase(Locale.getDefault()).contains(q) ||
            b.lowercase(Locale.getDefault()).contains(q)
    }

    private fun sort(list: List<SearchResult>, sort: Filter.Sort, q: String): List<SearchResult> {
        return when (sort) {
            Filter.Sort.RELEVANT -> list.sortedBy {
                val t = it.title.lowercase(Locale.getDefault())
                when {
                    t == q -> 0
                    t.startsWith(q) -> 1
                    t.contains(q) -> 2
                    else -> 3
                }
            }
            Filter.Sort.MOST_PLAYED -> list.sortedByDescending { it.lastPlayedMs }
            Filter.Sort.RECENT -> list.sortedByDescending { it.addedAt }
            Filter.Sort.OLDEST -> list.sortedBy { it.addedAt }
            Filter.Sort.LONGEST -> list.sortedByDescending { it.durationMs }
            Filter.Sort.SHORTEST -> list.sortedBy { it.durationMs }
        }
    }
}
