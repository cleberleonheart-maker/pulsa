package com.pulsa.player.sync

import android.content.Context
import android.os.Environment
import com.pulsa.player.model.Song
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Letras sincronizadas no formato LRC.
 *
 * Busca em cascata:
 *  1. arquivo .lrc exato ao lado do arquivo de música (mesmo nome);
 *  2. busca local recursiva por .lrc dentro do diretório da música,
 *     combinando por título/artista/álbum;
 *  3. cache interno do app;
 *  4. busca online (LRCLIB API), salvando em cache local.
 *
 * Suporta múltiplos timestamps por linha e timestamps [mm:ss.xx].
 */
object Lyrics {

    data class Line(val timeMs: Long, val text: String)

    data class Result(
        val lines: List<Line>,
        val source: Source,
        val verbatim: String
    ) {
        enum class Source { LOCAL, ONLINE_SYNCED, ONLINE_PLAIN }
    }

    private val TIME_RE = Regex("\\[(\\d{1,3}):(\\d{1,2}(?:\\.\\d{1,3})?)]")

    fun fileFor(song: Song): File? {
        val f = File(song.path)
        val lrc = File(f.parent, f.name.substringBeforeLast('.') + ".lrc")
        return lrc.takeIf { it.exists() && it.isFile }
    }

    fun resolve(song: Song, context: Context? = null): Result? {
        readFile(fileFor(song))?.let { r -> return r }

        localSearch(song)?.let { f ->
            readFile(f)?.let { r -> return r }
        }

        context?.let { ctx ->
            loadCache(ctx, song)?.let { text ->
                val lines = parse(text)
                if (lines.isNotEmpty()) return Result(lines, Result.Source.LOCAL, text)
            }
        }

        fetchOnline(song)?.let { online ->
            if (online.source == Result.Source.ONLINE_SYNCED) {
                context?.let { ctx -> saveCache(ctx, song, online.verbatim) }
                saveBesideMusic(song, online.verbatim)
            }
            return online
        }

        return null
    }

    fun parse(text: String): List<Line> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Line>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val times = TIME_RE.findAll(line).toList()
            if (times.isEmpty()) return@forEach
            val body = line.replace(TIME_RE, "").trim()
            if (body.isEmpty() && !raw.startsWith("[")) return@forEach
            times.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: return@forEach
                val sec = m.groupValues[2].toDoubleOrNull() ?: return@forEach
                val ms = min * 60_000L + (sec * 1000).toLong()
                out.add(Line(ms, body))
            }
        }
        out.sortBy { it.timeMs }
        return out
    }

    fun lineAt(lines: List<Line>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var idx = lines.binarySearch {
            (it.timeMs - positionMs).compareTo(0)
        }
        if (idx < 0) idx = -idx - 2
        return idx.coerceIn(0, lines.lastIndex)
    }

    // ---------- busca local ----------

    private fun readFile(f: File?): Result? {
        if (f == null) return null
        val text = runCatching { f.readText() }.getOrNull()
        val lines = text?.let { parse(it) }
        if (!lines.isNullOrEmpty()) return Result(lines, Result.Source.LOCAL, text!!)
        return null
    }

    /**
     * Procura recursivamente por arquivos .lrc dentro do diretório da música
     * e retorna o que melhor combina com a música (por nome/título/artista/álbum).
     */
    private fun localSearch(song: Song): File? {
        val music = File(song.path)
        val titleNorm = normalize(song.title)
        val artistNorm = normalize(song.artist)
        val albumNorm = normalize(song.album)
        var best: File? = null
        var bestScore = Int.MIN_VALUE
        fun tryDir(dir: File?) {
            if (dir == null || !dir.isDirectory) return
            val baseNorm = normalize(stem(music.name))
            dir.walkTopDown()
                .filter { it.isFile && it != music && it.extension.equals("lrc", true) }
                .take(2000)
                .forEach { lrc ->
                    val name = normalize(stem(lrc.name))
                    val score = score(name, baseNorm, titleNorm, artistNorm, albumNorm)
                    if (score > bestScore) {
                        bestScore = score
                        best = lrc
                    }
                }
        }
        tryDir(music.parentFile)
        if (bestScore <= 0) {
            val roots = listOfNotNull(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES),
                Environment.getExternalStorageDirectory()
            ).distinct()
            roots.forEach { tryDir(it) }
        }
        return best?.takeIf { bestScore > 0 }
    }

    private fun stem(name: String): String = name.substringBeforeLast('.')

    /**
     * Pontua um .lrc candidato. 100 = mesmo nome do arquivo de música;
     * acima de 0 = combinação útil por título/artista/álbum.
     */
    private fun score(name: String, baseNorm: String, titleNorm: String, artistNorm: String, albumNorm: String): Int {
        if (name == baseNorm) return 100
        if (titleNorm.isEmpty()) return 0
        if (name.contains(titleNorm)) {
            var s = 40
            if (artistNorm.isNotEmpty() && name.contains(artistNorm)) s += 30
            if (albumNorm.isNotEmpty() && name.contains(albumNorm)) s += 10
            return s
        }
        if (titleNorm.contains(name) && name.length >= 4) return 25
        return 0
    }

    /** Lowercase, remove acentos, espaços e pontuação para comparação. */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val lower = c.lowercaseChar()
            if (lower in 'a'..'z' || lower in '0'..'9') sb.append(lower)
        }
        return sb.toString()
    }

    // ---------- cache ----------

    private fun cacheDir(context: Context): File =
        File(context.filesDir, "lyrics").apply { if (!exists()) mkdirs() }

    private fun cacheKey(song: Song): String {
        val raw = song.path.ifBlank { "${song.artist}|${song.title}" }
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun loadCache(context: Context, song: Song): String? {
        return runCatching {
            val f = File(cacheDir(context), "${cacheKey(song)}.lrc")
            if (f.exists()) f.readText() else null
        }.getOrNull()
    }

    private fun saveCache(context: Context, song: Song, text: String) {
        runCatching {
            File(cacheDir(context), "${cacheKey(song)}.lrc").writeText(text)
        }
    }

    /** Tenta salvar o LRC ao lado do arquivo de música (pode falhar em armazenamento restrito). */
    private fun saveBesideMusic(song: Song, text: String) {
        runCatching {
            val music = File(song.path)
            val dir = music.parentFile
            if (dir != null && dir.canWrite()) {
                val safe = sanitizeFileName(song.title).ifBlank { "lyrics" }
                val f = File(dir, "$safe.lrc")
                if (!f.exists()) f.writeText(text)
            }
        }
    }

    private fun sanitizeFileName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80)

    private fun enc(s: String): String = URLEncoder.encode(s.trim(), "UTF-8")

    private fun fromFileName(song: Song): Pair<String?, String?> {
        val name = song.path.substringAfterLast('/').substringBeforeLast('.')
        val idx = name.lastIndexOf(" - ")
        return if (idx > 0) {
            name.substring(0, idx).trim() to name.substring(idx + 3).trim()
        } else {
            null to name.trim()
        }
    }

    // ---------- busca online (LRCLIB) ----------

    private fun fetchOnline(song: Song): Result? {
        val fromName = fromFileName(song)
        var artist = song.artist.trim()
        var track = song.title.trim()
        if (artist.isEmpty() || track.isEmpty()) {
            if (artist.isEmpty()) artist = fromName.first ?: ""
            if (track.isEmpty()) track = fromName.second ?: ""
        }
        if (track.isEmpty()) return null
        val durationSec = (song.durationMs / 1000).coerceIn(1, 9999)
        val api = "https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(track)}&duration=$durationSec"
        return try {
            val conn = (URL(api).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Pulsa-Android/4.0")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != 200) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() }
                if (synced != null) {
                    val lines = parse(synced)
                    if (lines.isNotEmpty()) Result(lines, Result.Source.ONLINE_SYNCED, synced) else null
                } else {
                    val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() }
                    if (plain != null) {
                        val lines = fromPlain(plain, song.durationMs)
                        if (lines.isNotEmpty()) Result(lines, Result.Source.ONLINE_PLAIN, plain) else null
                    } else null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Letra sem timestamps vira linhas com timestamps aproximados,
     * distribuídos uniformemente pela duração da música (rolagem aproximada).
     */
    private fun fromPlain(text: String, durationMs: Long): List<Line> {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return emptyList()
        val endMs = durationMs.coerceAtLeast(30_000L)
        val step = (endMs / lines.size).coerceAtLeast(1)
        return lines.mapIndexed { i, t -> Line(i * step, t) }
    }
}
