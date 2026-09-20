package com.pulsa.player.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.pulsa.player.model.Video
import com.pulsa.player.core.Permissions
import com.pulsa.player.core.ThreadPool

object VideoLibrary {

    fun all(context: Context): List<Video> {
        if (!Permissions.hasVideo(context)) return emptyList()
        val out = mutableListOf<Video>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        runCatching {
            context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (c.moveToNext()) {
                    out += Video(
                        id = c.getLong(idCol),
                        title = c.getString(titleCol) ?: "",
                        durationMs = c.getLong(durCol),
                        path = c.getString(dataCol) ?: "",
                        sizeBytes = c.getLong(sizeCol)
                    )
                }
            }
        }
        return out
    }

    fun contentUri(videoId: Long) =
        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)

    fun load(context: Context, onResult: (List<Video>) -> Unit) {
        ThreadPool.post {
            val videos = all(context)
            ThreadPool.onUi { onResult(videos) }
        }
    }
}
