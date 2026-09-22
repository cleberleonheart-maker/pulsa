package com.pulsa.player.dj

import android.content.Context
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.Library
import com.pulsa.player.model.Song

/**
 * Música favorita do avatar (Virgin/Victor): a faixa mais tocada no histórico
 * local (Janela ampla). Sem histórico, cai numa favorita aleatória da biblioteca.
 */
object AvatarFavorites {

    private const val WINDOW_DAYS = 3650

    /** SongId mais tocada no histórico, se houver alguma já tocada. */
    fun favoriteId(context: Context): Long? {
        val top = runCatching { DjLearn.topSongs(context, WINDOW_DAYS, 1) }.getOrDefault(emptyList())
        return top.firstOrNull()?.first
    }

    fun favorite(context: Context): Song? {
        val songs = runCatching { Library.allSongs(context) }.getOrDefault(emptyList())
        if (songs.isEmpty()) return null
        favoriteId(context)?.let { id ->
            songs.firstOrNull { it.id == id }?.let { return it }
        }
        return songs.randomOrNull()
    }

    fun favorite(context: Context, onResult: (Song?) -> Unit) {
        ThreadPool.post { onResult(favorite(context)) }
    }
}