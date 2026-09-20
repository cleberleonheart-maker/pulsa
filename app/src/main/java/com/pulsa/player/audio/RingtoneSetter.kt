package com.pulsa.player.audio
import com.pulsa.player.core.Helper
import com.pulsa.player.core.ThreadPool

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.pulsa.player.R
import com.pulsa.player.model.Song
import java.io.File

object RingtoneSetter {

    fun setAs(context: Context, song: Song, type: Int) {
        ThreadPool.post {
            val uri = try {
                addToMediaStore(context, song, type)
            } catch (t: Throwable) {
                null
            }
            if (uri != null) {
                RingtoneManager.setActualDefaultRingtoneUri(context, type, uri)
            }
            ThreadPool.onUi { notifyResult(context, uri != null, type) }
        }
    }

    private fun addToMediaStore(context: Context, song: Song, type: Int): Uri? {
        val ext = song.path.substringAfterLast('.', "mp3").ifEmpty { "mp3" }
        val displayName = Helper.sanitizeFileName(song.title) + ".$ext"
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "audio/mpeg"
        return if (Build.VERSION.SDK_INT >= 29) {
            insertModern(context, song, type, displayName, mime)
        } else {
            insertLegacy(context, song, type, displayName, mime)
        }
    }

    private fun insertModern(context: Context, song: Song, type: Int, displayName: String, mime: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        resolver.delete(collection, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(displayName))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(type))
            put(MediaStore.Audio.Media.TITLE, song.title)
            put(MediaStore.Audio.Media.ARTIST, song.artist)
            put(MediaStore.Audio.Media.ALBUM, song.album)
            put(MediaStore.Audio.Media.DURATION, song.durationMs)
            put(flag(type), 1)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                resolver.openInputStream(sourceUri(song))?.use { input ->
                    input.copyTo(out)
                } ?: throw IllegalStateException("source not readable")
            } ?: throw IllegalStateException("cannot open output")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun insertLegacy(context: Context, song: Song, type: Int, displayName: String, mime: String): Uri? {
        val resolver = context.contentResolver
        val dir = Environment.getExternalStoragePublicDirectory(subdir(type))
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, displayName)
        return try {
            resolver.openInputStream(sourceUri(song))?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, target.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.SIZE, target.length())
                put(MediaStore.Audio.Media.TITLE, song.title)
                put(MediaStore.Audio.Media.ARTIST, song.artist)
                put(flag(type), 1)
            }
            resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        } catch (t: Throwable) {
            runCatching { target.delete() }
            null
        }
    }

    private fun sourceUri(song: Song): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)

    private fun subdir(type: Int): String = when (type) {
        RingtoneManager.TYPE_NOTIFICATION -> Environment.DIRECTORY_NOTIFICATIONS
        RingtoneManager.TYPE_ALARM -> Environment.DIRECTORY_ALARMS
        else -> Environment.DIRECTORY_RINGTONES
    }

    private fun relativePath(type: Int): String = "${subdir(type)}/"

    private fun flag(type: Int): String = when (type) {
        RingtoneManager.TYPE_NOTIFICATION -> MediaStore.Audio.Media.IS_NOTIFICATION
        RingtoneManager.TYPE_ALARM -> MediaStore.Audio.Media.IS_ALARM
        else -> MediaStore.Audio.Media.IS_RINGTONE
    }

    private fun label(type: Int): Int = when (type) {
        RingtoneManager.TYPE_NOTIFICATION -> R.string.ringtone_notification
        RingtoneManager.TYPE_ALARM -> R.string.ringtone_alarm
        else -> R.string.ringtone_phone
    }

    private fun notifyResult(context: Context, ok: Boolean, type: Int) {
        val msg = if (ok) {
            context.getString(R.string.ringtone_set, context.getString(label(type)))
        } else {
            context.getString(R.string.ringtone_failed)
        }
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
