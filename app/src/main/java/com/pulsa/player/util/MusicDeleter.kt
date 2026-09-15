package com.pulsa.player.util

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.model.Song

object MusicDeleter {

    fun delete(context: Context, song: Song, onDone: (Boolean) -> Unit) {
        ThreadPool.post {
            val ok = try {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    song.id
                )
                context.contentResolver.delete(uri, null, null) > 0
            } catch (t: Throwable) {
                false
            }
            if (ok) {
                runCatching {
                    val db = PlaylistDb.get(context)
                    db.removeSongFromAll(song.id)
                    db.removeFavorite(song.id)
                }
            }
            ThreadPool.onUi { onDone(ok) }
        }
    }
}