package com.pulsa.player.media
import com.pulsa.player.core.CrashLogger
import com.pulsa.player.core.ThreadPool

import android.app.Activity
import android.content.Context
import android.content.ContentUris
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.MainActivity
import com.pulsa.player.R
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback

object MusicEditor {

    private data class Pending(
        val activity: MainActivity,
        val song: Song,
        val title: String,
        val artist: String,
        val album: String,
        val onChanged: (() -> Unit)?
    )

    @Volatile
    private var pending: Pending? = null

    private val pendingLock = Any()

    fun edit(context: Context, song: Song, onChanged: (() -> Unit)?) {
        val input = LayoutInflater.from(context).inflate(R.layout.dialog_edit_song, null, false)
        val titleInput = input.findViewById<EditText>(R.id.edit_title_input)
        val artistInput = input.findViewById<EditText>(R.id.edit_artist_input)
        val albumInput = input.findViewById<EditText>(R.id.edit_album_input)

        titleInput.setText(song.title)
        artistInput.setText(song.artist)
        albumInput.setText(song.album)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.edit_song_title)
            .setView(input)
            .setPositiveButton(R.string.save) { dialog, _ ->
                val title = titleInput.text.toString().trim()
                val artist = artistInput.text.toString().trim()
                val album = albumInput.text.toString().trim()
                if (title.isNotEmpty()) save(context, song, title, artist, album, onChanged)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun onWriteRequestResult(granted: Boolean) {
        val p = synchronized(pendingLock) {
            val cur = pending
            pending = null
            cur
        } ?: return
        CrashLogger.writeLog(p.activity, "EDIT: onWriteRequestResult granted=$granted song=" + p.song.title)
        if (!granted) {
            CrashLogger.writeLog(p.activity, "EDIT: usuario negou permissao, salvando so na Pulsa")
            fallback(p)
            return
        }
        ThreadPool.post {
            if (apply(p)) {
                CrashLogger.writeLog(p.activity, "EDIT: update OK apos permissao")
            } else {
                CrashLogger.writeLog(p.activity, "EDIT: update FALHOU mesmo apos permissao")
                fallback(p)
            }
        }
    }

    fun renameDetected(
        context: Context,
        song: Song,
        title: String,
        artist: String,
        album: String?,
        onDone: (Boolean) -> Unit
    ) {
        ThreadPool.post {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, title)
                if (artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, artist)
                if (!album.isNullOrBlank()) put(MediaStore.Audio.Media.ALBUM, album)
            }
            val updated = runCatching {
                context.contentResolver.update(
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id),
                    values, null, null
                ) > 0
            }.getOrDefault(false)
            val dbSaved = runCatching {
                PlaylistDb.get(context).updateSongMeta(song.id, title, artist, album ?: song.album)
                true
            }.getOrDefault(false)
            if (song.path.isNotBlank()) {
                runCatching {
                    MediaScannerConnection.scanFile(
                        context.applicationContext, arrayOf(song.path), arrayOf("audio/*"), null
                    )
                }
            }
            ThreadPool.onUi {
                if (updated || dbSaved) Playback.refreshCurrentMeta()
                onDone(updated || dbSaved)
            }
        }
    }

    private fun save(
        context: Context,
        song: Song,
        title: String,
        artist: String,
        album: String,
        onChanged: (() -> Unit)?
    ) {
        val activity = (context as? Activity) as? MainActivity
        val p = Pending(activity ?: return, song, title, artist, album, onChanged)
        CrashLogger.writeLog(activity, "EDIT: save iniciado song=" + song.title + " -> " + title + " sdk=" + Build.VERSION.SDK_INT)
        ThreadPool.post {
            if (apply(p)) {
                CrashLogger.writeLog(activity, "EDIT: update OK direto (sem pedir permissao)")
                return@post
            }
            CrashLogger.writeLog(activity, "EDIT: update inicial FALHOU, tentando createWriteRequest")
            if (activity == null) {
                CrashLogger.writeLog(activity, "EDIT: activity null, caindo no fallback")
                fallback(p)
                return@post
            }
            val request = runCatching {
                MediaStore.createWriteRequest(activity.contentResolver, listOf(uri(song.id)))
            }.getOrNull()
            if (request == null) {
                CrashLogger.writeLog(activity, "EDIT: createWriteRequest retornou null/erro, fallback")
                fallback(p)
                return@post
            }
            synchronized(pendingLock) { pending = p }
            CrashLogger.writeLog(activity, "EDIT: lancando createWriteRequest dialog")
            runCatching {
                ThreadPool.onUi { activity.launchWriteRequest(request.intentSender) }
            }.onFailure {
                CrashLogger.writeLog(activity, "EDIT: erro ao lancar dialog: $it")
                synchronized(pendingLock) { pending = null }
                fallback(p)
            }
        }
    }

    private fun apply(p: Pending): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.TITLE, p.title)
            put(MediaStore.Audio.Media.ARTIST, p.artist)
            put(MediaStore.Audio.Media.ALBUM, p.album)
        }
        var ok = false
        var err: String? = null
        try {
            ok = p.activity.contentResolver.update(uri(p.song.id), values, null, null) > 0
        } catch (t: Throwable) {
            err = t.toString()
        }
        if (!ok) {
            CrashLogger.writeLog(p.activity, "EDIT: update retornou ok=false err=$err songId=" + p.song.id)
            return false
        }
        runCatching {
            PlaylistDb.get(p.activity).updateSongMeta(p.song.id, p.title, p.artist, p.album)
            PlaylistDb.get(p.activity).clearMetaOverride(p.song.id)
            if (!p.song.path.isNullOrEmpty()) {
                MediaScannerConnection.scanFile(
                    p.activity.applicationContext,
                    arrayOf(p.song.path),
                    arrayOf("audio/*"),
                    null
                )
            }
        }
        ThreadPool.onUi {
            Toast.makeText(p.activity, R.string.song_edited, Toast.LENGTH_SHORT).show()
            p.onChanged?.invoke()
        }
        return true
    }

    private fun fallback(p: Pending) {
        val dbSaved = runCatching {
            PlaylistDb.get(p.activity).updateSongMeta(p.song.id, p.title, p.artist, p.album)
            true
        }.getOrDefault(false)
        CrashLogger.writeLog(p.activity, "EDIT: fallback dbSaved=$dbSaved")
        ThreadPool.onUi {
            Toast.makeText(p.activity, if (dbSaved) R.string.edit_db_only else R.string.edit_failed, Toast.LENGTH_SHORT).show()
            if (dbSaved) p.onChanged?.invoke()
        }
    }

    private fun uri(songId: Long): android.net.Uri {
        return ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            songId
        )
    }
}
