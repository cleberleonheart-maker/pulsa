package com.pulsa.player.search

import android.content.Context
import com.pulsa.player.data.Library
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.podcast.PodcastDb
import com.pulsa.player.sync.DjLearn
import java.util.Locale

/**
 * F5 — A busca unificada: procura em música, vídeo, episódio, artista e feed de podcast de
 * uma vez.
 *
 * Roda fora da main thread (quem chama coloca num `ThreadPool`/IO); o Room e o MediaStore são
 * consultados direto, como no resto do app. O `DjLearn.lastPlayedMap` é lido **uma vez** por
 * busca, e não por faixa, porque abrir o banco de aprendizado a cada música de uma biblioteca
 * grande era o custo que não valia a pena pela informação.
 */
class SearchEngine(private val context: Context) {

    /**
     * Os filtros da barra de chips. `downloadedOnly` só olha mídia que existe no aparelho
     * (vídeo com caminho local, episódio baixado); `notPlayed` deixa passar só o que o DJ
     * nunca registrou como tocado.
     */
    data class Filter(
        val downloadedOnly: Boolean = false,
        val notPlayed: Boolean = false,
        val sortBy: Sort = Sort.RELEVANT
    ) {
        enum class Sort { RELEVANT, RECENT, OLDEST, LONGEST, SHORTEST }
    }

    fun search(query: String, filter: Filter = Filter()): List<SearchResult> {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isBlank()) return emptyList()

        val played = runCatching { DjLearn.lastPlayedMap(context) }.getOrDefault(emptyMap())
        val out = mutableListOf<SearchResult>()

        // Músicas
        runCatching {
            Library.allSongs(context).forEach { s ->
                if (!matches(q, s.title, s.artist, s.album)) return@forEach
                val lastSec = played[s.id] ?: 0L
                if (filter.notPlayed && lastSec > 0) return@forEach
                out += SearchResult(
                    type = SearchResult.Type.SONG,
                    title = s.title,
                    subtitle = if (s.artist.isBlank()) s.album else s.artist,
                    albumId = s.albumId,
                    artPath = s.path,
                    durationMs = s.durationMs,
                    isDownloaded = true,
                    lastPlayedMs = lastSec * 1000L,
                    addedAt = s.dateAdded,
                    song = s
                )
            }
        }

        // Vídeos
        runCatching {
            VideoLibrary.all(context).forEach { v ->
                if (!matches(q, v.title)) return@forEach
                val local = v.path.isNotBlank() && !v.path.startsWith("http")
                if (filter.downloadedOnly && !local) return@forEach
                out += SearchResult(
                    type = SearchResult.Type.VIDEO,
                    title = v.title,
                    subtitle = context.getString(com.pulsa.player.R.string.search_kind_video),
                    albumId = 0L,
                    artPath = com.pulsa.player.model.Song.VIDEO_PREFIX + v.id,
                    durationMs = v.durationMs,
                    isDownloaded = local,
                    addedAt = 0L,
                    video = v
                )
            }
        }

        // Artistas
        runCatching {
            Library.artists(context).forEach { a ->
                if (!a.name.lowercase(Locale.getDefault()).contains(q)) return@forEach
                out += SearchResult(
                    type = SearchResult.Type.ARTIST,
                    title = a.name,
                    subtitle = context.resources.getQuantityString(
                        com.pulsa.player.R.plurals.search_artist_songs, a.numSongs, a.numSongs
                    ),
                    artistName = a.name
                )
            }
        }

        // Feeds de podcast
        val db = runCatching { PodcastDb.get(context) }.getOrNull()
        runCatching {
            db?.feeds()?.forEach { f ->
                val title = f.title.orEmpty()
                val author = f.author.orEmpty()
                val desc = f.description.orEmpty()
                if (!matches(q, title, author, desc)) return@forEach
                out += SearchResult(
                    type = SearchResult.Type.FEED,
                    title = title,
                    subtitle = author.ifBlank {
                        context.getString(com.pulsa.player.R.string.search_kind_podcast)
                    },
                    artPath = f.artworkUrl,
                    isDownloaded = true,
                    feedId = f.id
                )
            }
        }

        // Episódios de podcast
        runCatching {
            db?.allEpisodes()?.forEach { e ->
                if (!matches(q, e.title, e.description)) return@forEach
                val downloaded = e.filePath.isNotBlank()
                if (filter.downloadedOnly && !downloaded) return@forEach
                out += SearchResult(
                    type = SearchResult.Type.EPISODE,
                    title = e.title,
                    subtitle = context.getString(com.pulsa.player.R.string.search_kind_episode),
                    artPath = e.artworkUrl,
                    durationMs = e.durationSec * 1000L,
                    isDownloaded = downloaded,
                    addedAt = e.publishedAt,
                    episode = e
                )
            }
        }

        return sort(out, filter.sortBy, q)
    }

    private fun matches(q: String, vararg fields: String): Boolean =
        fields.any { it.lowercase(Locale.getDefault()).contains(q) }

    /**
     * A ordenação. "Relevante" é a única que olha o texto: título exato na frente, depois
     * começa-com, depois contém — empate resolvido por quem tem mais "peso" de tipo (música e
     * episódio antes de vídeo).
     */
    private fun sort(list: List<SearchResult>, sort: Filter.Sort, q: String): List<SearchResult> =
        when (sort) {
            Filter.Sort.RELEVANT -> list.sortedWith(
                compareBy(
                    { relevance(it, q) },
                    { typeRank(it.type) },
                    { it.title.lowercase(Locale.getDefault()) }
                )
            )
            Filter.Sort.RECENT -> list.sortedByDescending { it.addedAt }
            Filter.Sort.OLDEST -> list.sortedBy { it.addedAt }
            Filter.Sort.LONGEST -> list.sortedByDescending { it.durationMs }
            Filter.Sort.SHORTEST -> list.sortedBy { it.durationMs }
        }

    private fun relevance(r: SearchResult, q: String): Int {
        val t = r.title.lowercase(Locale.getDefault())
        return when {
            t == q -> 0
            t.startsWith(q) -> 1
            t.contains(q) -> 2
            else -> 3
        }
    }

    private fun typeRank(type: SearchResult.Type): Int = when (type) {
        SearchResult.Type.SONG -> 0
        SearchResult.Type.EPISODE -> 1
        SearchResult.Type.ARTIST -> 2
        SearchResult.Type.VIDEO -> 3
        SearchResult.Type.FEED -> 4
    }
}
