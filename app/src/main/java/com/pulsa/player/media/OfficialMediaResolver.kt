package com.pulsa.player.media

import android.os.Looper
import com.pulsa.player.core.ThreadPool
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * #277 – Tenta resolver links de páginas para links diretos via APIs oficiais/públicas.
 * Foco em casos seguros: iTunes previewUrl. Evita scraping de HTML.
 */
object OfficialMediaResolver {

    data class Resolved(val directUrl: String, val name: String?)

    /**
     * Cache de resoluções bem-sucedidas. Os links resolvidos são estáveis, e mais
     * importante: a validação de UI (`UrlDownloader.validate`) é sincrona e não pode
     * tocar na rede. Ela só consulta este cache.
     */
    private val cache = ConcurrentHashMap<String, Resolved>()

    private fun key(url: String): String = url.trim().lowercase()

    /** Resultado já resolvido para esta URL, se houver. Nunca faz rede — seguro no main. */
    fun cached(input: String): Resolved? = cache[key(input)]

    /**
     * Resolve fora da thread principal e devolve o resultado na UI. É o caminho usado
     * pelos botões: `resolve()` faz HTTP de propósito e no main thread lançaria
     * `NetworkOnMainThreadException` (que antes era engolido e virava "link de página").
     */
    fun resolveAsync(input: String, onResult: (Resolved?) -> Unit) {
        val url = input.trim()
        val hit = cached(url)
        if (hit != null) {
            onResult(hit)
            return
        }
        ThreadPool.postNetwork {
            val result = resolve(url)
            ThreadPool.onUi { onResult(result) }
        }
    }

    fun resolve(input: String): Resolved? {
        val url = input.trim()
        if (url.isEmpty()) return null
        cached(url)?.let { return it }
        if (isDirectMedia(url)) return null
        // Rede no main thread falharia silenciosamente (NetworkOnMainThreadException).
        if (Looper.myLooper() == Looper.getMainLooper()) return null

        val lower = url.lowercase()
        val resolved = if (lower.contains("music.apple.com") || lower.contains("itunes.apple.com")) {
            resolveItunes(url)
        } else {
            null
        }
        if (resolved != null) cache[key(url)] = resolved
        return resolved
    }

    private fun isDirectMedia(url: String): Boolean {
        val u = url.lowercase()
        val path = u.substringBefore('?')
        return path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac") ||
            path.endsWith(".ogg") || path.endsWith(".opus") || path.endsWith(".flac") ||
            path.endsWith(".wav") || path.endsWith(".mp4") || path.endsWith(".m4v") ||
            path.endsWith(".webm") || path.endsWith(".mov")
    }

    private fun resolveItunes(url: String): Resolved? {
        return try {
            val uri = java.net.URI(url)
            val query = uri.query ?: ""
            // Prioriza o ID da música quando vem via ?i= (links do Apple Music)
            var trackId = ""
            query.split("&").forEach { part ->
                if (part.isBlank()) return@forEach
                val kv = part.split("=")
                if (kv.size >= 2 && kv[0] == "i") {
                    trackId = kv[1]
                }
            }
            if (trackId.isBlank()) {
                val path = uri.path ?: ""
                // /us/song/brave-heart/.../ID ou /song/.../ID
                trackId = path.substringAfterLast('/').filter { it.isDigit() }
            }
            if (trackId.isNotBlank()) {
                val lookup = URL("https://itunes.apple.com/lookup?id=$trackId")
                val conn = (lookup.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Pulsa")
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return null
                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    val preview = r.optString("previewUrl", "")
                    if (preview.isNotBlank() && preview.startsWith("http")) {
                        val name = buildString {
                            val track = r.optString("trackName", "")
                            val artist = r.optString("artistName", "")
                            if (artist.isNotBlank()) append(artist).append(" - ")
                            if (track.isNotBlank()) append(track) else append("itunes")
                        }
                        return Resolved(preview, name)
                    }
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }
}
