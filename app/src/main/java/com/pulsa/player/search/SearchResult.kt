package com.pulsa.player.search

import com.pulsa.player.model.Song
import com.pulsa.player.model.Video

/**
 * F5 — Um resultado da busca unificada, venha de onde vier (música, vídeo, episódio,
 * artista ou feed de podcast).
 *
 * A ideia é ter **um** tipo para a lista, com o suficiente para desenhar a linha (título,
 * subtítulo, arte, duração) e uma carga opcional por tipo (`song`/`video`/`episode`/feeds)
 * para o clique saber o que fazer. É por isso que o `type` existe além dos campos: uma música
 * e um episódio podem ter o mesmo título e o clique é diferente.
 */
data class SearchResult(
    val type: Type,
    val title: String,
    val subtitle: String,
    val albumId: Long = 0L,
    val artPath: String? = null,
    val durationMs: Long = 0L,
    val isDownloaded: Boolean = false,
    val lastPlayedMs: Long = 0L,
    val addedAt: Long = 0L,
    val song: Song? = null,
    val video: Video? = null,
    val episode: com.pulsa.player.podcast.Episode? = null,
    val feedId: Long = 0L,
    val artistName: String? = null
) {
    enum class Type {
        SONG,
        VIDEO,
        EPISODE,
        ARTIST,
        FEED
    }
}
