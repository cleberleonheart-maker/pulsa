package com.pulsa.player.media
import com.pulsa.player.core.Helper
import com.pulsa.player.core.ThreadPool

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.pulsa.player.R
import java.io.File
import java.io.FileOutputStream
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
        ThreadPool.postNetwork {
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

    /**
     * O mesmo download, mas **na thread que já está esperando pelo resultado** — é o que o
     * `DownloadWorker` precisa, porque o WorkManager só sabe se deu certo quando a função
     * retorna.
     *
     * Dá para repetir com segurança: [saveModern] apaga a entrada `IS_PENDING` quando a
     * cópia falha, então um retry não deixa arquivo órfão no MediaStore.
     */
    fun downloadBlocking(context: Context, urlText: String, name: String): Boolean =
        runCatching { downloadToStore(context, urlText, name) != null }.getOrDefault(false)

    /**
     * O mesmo download, devolvendo o **endereço** que o MediaStore deu (`content://…`) em vez
     * de um booleano.
     *
     * Quem precisa do endereço é o podcast (F3): o episódio baixado guarda o `Uri` em
     * `file_path` para tocar offline, e um simples "deu certo" não deixaria nada com o que
     * localizar o arquivo depois — nem para tocar, nem para apagar quando a assinatura sai.
     */
    fun downloadBlockingUri(context: Context, urlText: String, name: String): String? =
        runCatching { downloadToStore(context, urlText, name)?.toString() }.getOrNull()

    private fun downloadToStore(context: Context, urlText: String, name: String): Uri? {
        // O parcial fica em `cacheDir` (privado do app, some sozinho), e não no
        // `DownloadStore` do Download Center: lá os arquivos aparecem na Biblioteca como
        // vídeos, e um `.part` de música não é vídeo nem baixado.
        val part = partFile(context, urlText, name)
        val existing = if (part.exists()) part.length() else 0L
        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Accept", "audio/*,video/*")
            // Retomar por `Range` só é seguro sem compressão: com gzip os offsets mudam e os
            // bytes anexados sairiam fora de lugar.
            setRequestProperty("Accept-Encoding", "identity")
            resumeRangeHeader(existing)?.let { setRequestProperty("Range", it) }
        }
        return try {
            conn.connect()
            val code = conn.responseCode
            // `416` = o parcial pediu um trecho que não existe mais (arquivo mudou de
            // tamanho no servidor). O parcial é lixo: joga fora e baixa do zero na próxima.
            if (code == 416) {
                part.delete()
                return null
            }
            if (code !in 200..299) return null
            val contentType = conn.contentType ?: ""
            if (contentType.contains("text/html", ignoreCase = true)) throw HtmlResponseException()
            // `206` diz que o servidor aceitou o `Range`; `200` quer dizer que ele ignorou (ou
            // o arquivo mudou) e a resposta é o arquivo inteiro — anexar nesse caso produziria
            // um arquivo com dois começos.
            val append = shouldAppend(code, existing)
            val ext = extFromUrl(urlText, contentType)
            val mime = contentType.substringBefore(';').trim().ifEmpty {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    ?: if (isVideoExt(ext)) "video/mp4" else "audio/mpeg"
            }
            val fileName = Helper.sanitizeFileName(name) + ".$ext"
            val isVideo = mime.startsWith("video/") || isVideoExt(ext)
            // Com `206` o `Content-Length` é só o resto; o total real vem do `Content-Range`.
            val total = if (append) parseContentRangeTotal(conn.getHeaderField("Content-Range")) ?: -1L
            else conn.contentLengthLong
            part.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out -> copyWithProgress(input, out, total) }
            }
            // Só entra no MediaStore depois de completo: um parcial anunciado como música
            // apareceria na biblioteca e tocaria pela metade.
            val uri = part.inputStream().use { saveStream(context, it, fileName, mime, part.length(), isVideo) }
            if (uri != null) part.delete()
            uri
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

    /**
     * Cabeçalho `Range` para retomar a partir do que já está no parcial; `null` quando não há
     * nada baixado (download do zero, sem `Range`).
     */
    fun resumeRangeHeader(existingBytes: Long): String? =
        if (existingBytes > 0L) "bytes=$existingBytes-" else null

    /**
     * Só anexa quando o servidor confirma o `Range` (`206`) **e** já existe parcial. Um `200`
     * é o arquivo inteiro (o servidor ignorou o `Range`, ou o arquivo mudou) e tem de
     * sobrescrever; um `206` sem parcial seria o primeiro byte de um download novo.
     */
    fun shouldAppend(code: Int, existingBytes: Long): Boolean =
        code == 206 && existingBytes > 0L

    /** Total real do arquivo em uma resposta `206`, lido de `Content-Range: bytes a-b/total`. */
    fun parseContentRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    /**
     * Nome do parcial, estável e sem separador de caminho (o `sanitizeFileName` já tira o que
     * o MediaStore recusa). O hash da URL distingue dois downloads de mesmo nome — retomar com
     * o parcial de outra URL colaria bytes de um arquivo na frente do outro.
     */
    fun partFileName(urlText: String, name: String): String {
        val hash = urlText.hashCode().toLong() and 0xffffffffL
        return Helper.sanitizeFileName(name) + "-" + java.lang.Long.toHexString(hash) + ".part"
    }

    private fun partFile(context: Context, urlText: String, name: String): File =
        File(File(context.cacheDir, "urlparts").apply { if (!exists()) mkdirs() }, partFileName(urlText, name))

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
