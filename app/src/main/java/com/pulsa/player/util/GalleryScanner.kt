package com.pulsa.player.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.pulsa.player.data.Library
import com.pulsa.player.data.VideoLibrary
import java.io.File

object GalleryScanner {

    @Volatile
    private var scanning: Boolean = false

    private val exts = setOf(
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma", "3gp", "mid",
        "midi", "amr", "mp4", "webm", "mkv", "mov", "avi", "ts", "m3u8"
    )

    fun scan(context: Context, onDone: (songs: Int, videos: Int) -> Unit) {
        if (scanning) {
            ThreadPool.onUi {
                onDone(Library.allSongs(context).size, VideoLibrary.all(context).size)
            }
            return
        }
        scanning = true
        ThreadPool.post {
            val dirs = listOfNotNull(
                Environment.getExternalStorageDirectory(),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES)
            ).distinct()
            val files = mutableListOf<String>()
            val seen = hashSetOf<String>()
            fun walk(dir: File, depth: Int) {
                if (depth > 3 || files.size >= 4000) return
                val children = runCatching { dir.listFiles() }.getOrNull() ?: return
                for (f in children) {
                    if (files.size >= 4000) return
                    if (f.isDirectory) {
                        walk(f, depth + 1)
                    } else if (exts.contains(f.extension.lowercase())) {
                        if (seen.add(f.absolutePath)) files.add(f.absolutePath)
                    }
                }
            }
            for (d in dirs) walk(d, 0)
            scanChunks(context, files, 0) {
                scanning = false
                val songs = Library.allSongs(context).size
                val videos = VideoLibrary.all(context).size
                ThreadPool.onUi { onDone(songs, videos) }
            }
        }
    }

    private fun scanChunks(context: Context, files: List<String>, index: Int, onDone: () -> Unit) {
        if (index >= files.size) {
            onDone()
            return
        }
        val chunk = files.subList(index, minOf(index + 500, files.size)).toTypedArray()
        MediaScannerConnection.scanFile(context.applicationContext, chunk, null) { _, _ ->
            ThreadPool.onUi { scanChunks(context, files, index + chunk.size, onDone) }
        }
    }
}