package com.pulsa.player.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.pulsa.player.R
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object MusicDownloader {

    private class HtmlResponseException : Exception()

    fun download(
        context: Context,
        urlText: String,
        name: String,
        onDone: (ok: Boolean, message: String?) -> Unit
    ) {
        ThreadPool.post {
            var ok = false
            var message: String? = null
            try {
                val uri = downloadToStore(context, urlText, name)
                ok = uri != null
                message = if (uri != null) {
                    context.getString(R.string.download_done, name)
                } else {
                    context.getString(R.string.download_failed, name)
                }
            } catch (t: HtmlResponseException) {
                message = context.getString(R.string.download_html_error)
            } catch (t: Throwable) {
                message = context.getString(R.string.download_failed, name)
            }
            ThreadPool.onUi { onDone(ok, message) }
        }
    }

    private fun downloadToStore(context: Context, urlText: String, name: String): Uri? {
        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Accept", "audio/*,video/*")
        }
        return try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) return null
            val contentType = conn.contentType ?: ""
            if (contentType.contains("text/html", ignoreCase = true)) throw HtmlResponseException()
            val length = conn.contentLengthLong
            val ext = extFromUrl(urlText, contentType)
            val mime = contentType.substringBefore(';').trim().ifEmpty {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    ?: if (isVideoExt(ext)) "video/mp4" else "audio/mpeg"
            }
            val fileName = Helper.sanitizeFileName(name) + ".$ext"
            val isVideo = mime.startsWith("video/") || isVideoExt(ext)
            val input = conn.inputStream
            input.use { it -> saveStream(context, it, fileName, mime, length, isVideo) }
        } finally {
            conn.disconnect()
        }
    }

    private fun saveStream(context: Context, input: InputStream, fileName: String, mime: String, length: Long, isVideo: Boolean): Uri? {
        return if (Build.VERSION.SDK_INT >= 29) {
            saveModern(context, input, fileName, mime, length, isVideo)
        } else {
            saveLegacy(context, input, fileName, mime, isVideo)
        }
    }

    private fun saveModern(context: Context, input: InputStream, fileName: String, mime: String, length: Long, isVideo: Boolean): Uri? {
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) + "/"
            )
            if (!isVideo) put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (length > 0) put(MediaStore.MediaColumns.SIZE, length)
        }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                copyWithProgress(input, out, length)
            } ?: return null
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun saveLegacy(context: Context, input: InputStream, fileName: String, mime: String, isVideo: Boolean): Uri? {
        val resolver = context.contentResolver
        val dir = if (isVideo) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        }
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, fileName)
        return try {
            file.outputStream().use { out -> copyWithProgress(input, out, 0L) }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, file.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.SIZE, file.length())
            }
            if (isVideo) {
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            } else {
                values.put(MediaStore.Audio.Media.IS_MUSIC, 1)
                resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (t: Throwable) {
            runCatching { file.delete() }
            null
        }
    }

    private fun copyWithProgress(input: InputStream, output: OutputStream, total: Long) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            output.write(buf, 0, n)
            done += n
            if (total > 0 && done % (256 * 1024) == 0L) {
                Thread.sleep(0)
            }
        }
    }

    private fun isVideoExt(ext: String): Boolean {
        return ext in setOf("mp4", "webm", "mkv", "3gp", "3gpp", "mov", "avi", "flv", "m2ts", "ts")
    }

    private fun extFromUrl(url: String, contentType: String): String {
        val fromPath = url.substringBefore('?').substringAfterLast('.').lowercase()
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
        if (fromPath != null) return fromPath
        val type = contentType.substringBefore(';').trim().lowercase()
        return when (type) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/flac" -> "flac"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/x-m4a", "audio/mp4", "audio/mp4a-latm" -> "m4a"
            "audio/x-wav", "audio/wav" -> "wav"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/3gpp" -> "3gp"
            "video/quicktime" -> "mov"
            "video/x-msvideo" -> "avi"
            "video/x-flv" -> "flv"
            else -> "mp3"
        }
    }
}