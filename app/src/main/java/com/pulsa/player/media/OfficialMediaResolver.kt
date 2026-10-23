package com.pulsa.player.media

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * #277 – Tenta resolver links de páginas para links diretos via APIs oficiais/públicas.
 * Foco em casos seguros: iTunes previewUrl. Evita scraping de HTML.
 */
object OfficialMediaResolver {

    data class Resolved(val directUrl: String, val name: String?)

    fun resolve(input: String): Resolved? {
        val url = input.trim()
        if (url.isEmpty()) return null
        if (isDirectMedia(url)) return null

        val lower = url.lowercase()
        if (lower.contains("music.apple.com") || lower.contains("itunes.apple.com")) {
            return resolveItunes(url)
        }
        return null
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
