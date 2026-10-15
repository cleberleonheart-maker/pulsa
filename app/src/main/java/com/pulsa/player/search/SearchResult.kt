package com.pulsa.player.search

import com.pulsa.player.model.Song
import com.pulsa.player.podcast.PodcastEpisode
import com.pulsa.player.podcast.PodcastFeed

data class SearchResult(
    val type: Type,
    val title: String,
    val subtitle: String,
    val artPath: String? = null,
    val albumId: Long = 0L,
    val durationMs: Long = 0L,
    val isDownloaded: Boolean = false,
    val lastPlayedMs: Long = 0L,
    val addedAt: Long = 0L,
    val song: Song? = null,
    val videoId: Long? = null,
    val episode: PodcastEpisode? = null,
    val feed: PodcastFeed? = null,
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
