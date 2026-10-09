package com.pulsa.player.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * F2 · Histórico de streams (PeerTube e URL colada).
 *
 * O stream não tem item no MediaStore: sem isto, "toca o filme X" só alcança vídeo local,
 * e o que a pessoa acabou de ver no PeerTube some no próximo comando de voz. A identidade
 * aqui é a **URL** (e o uuid do PeerTube quando existe) — não o id do MediaStore, que o
 * stream não tem.
 *
 * Guardado em SharedPreferences (JSON), teto de 30, mais novo primeiro. Duplicata por
 * url/uuid move o item pro topo em vez de encher a lista com a mesma entrada.
 */
object StreamHistory {

    data class Entry(
        val title: String,
        val url: String,
        val pageUrl: String = "",
        val uuid: String = "",
        val thumbnail: String = "",
        val at: Long = 0L
    )

    private const val FILE = "pulsa_stream_history"
    private const val KEY = "entries"
    private const val MAX = 30

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun list(context: Context): List<Entry> = runCatching {
        val raw = prefs(context).getString(KEY, null).orEmpty()
        if (raw.isEmpty()) return emptyList()
        val arr = JSONArray(raw)
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").ifBlank { null } ?: continue
            out += Entry(
                title = o.optString("title").ifBlank { url },
                url = url,
                pageUrl = o.optString("pageUrl"),
                uuid = o.optString("uuid"),
                thumbnail = o.optString("thumbnail"),
                at = o.optLong("at", 0L)
            )
        }
        out
    }.getOrDefault(emptyList())

    /**
     * Grava no topo. Chave = uuid quando existe, senão a URL: o mesmo vídeo do PeerTube
     * resolvido por HLS e por MP4 tem URLs diferentes e o mesmo uuid — sem a uuid a lista
     * encheria de duplicata a cada clique.
     */
    fun record(context: Context, entry: Entry) {
        if (entry.url.isBlank()) return
        val key = entry.uuid.ifBlank { entry.url }
        val current = list(context).toMutableList()
        current.removeAll {
            it.uuid.isNotBlank() && it.uuid == entry.uuid ||
                it.uuid.isBlank() && it.url == entry.url
        }
        current.add(0, entry.copy(at = System.currentTimeMillis()))
        val arr = JSONArray()
        current.take(MAX).forEach { e ->
            arr.put(
                JSONObject()
                    .put("title", e.title)
                    .put("url", e.url)
                    .put("pageUrl", e.pageUrl)
                    .put("uuid", e.uuid)
                    .put("thumbnail", e.thumbnail)
                    .put("at", e.at)
            )
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
        // `key` é lido só para documentar a dedupe acima; o lint de unused varia por build.
        @Suppress("UNUSED_EXPRESSION")
        key
    }

    /**
     * Melhor casamento do histórico para a voz. Mesma lógica do
     * [com.pulsa.player.dj.VirginMedia.findVideo]: igual vence, depois contém, depois o
     * título inteiro cabe na fala.
     */
    fun find(context: Context, query: String): Entry? {
        val q = com.pulsa.player.dj.DjCommander.norm(query).trim()
        if (q.isEmpty()) return null
        var best: Entry? = null
        var bestScore = -1
        for (e in list(context)) {
            val n = com.pulsa.player.dj.DjCommander.norm(e.title)
            if (n.isEmpty()) continue
            val score = when {
                n == q -> 1000
                n.contains(q) -> 100 + q.length
                q.contains(n) && q.length >= n.length -> 50 + n.length
                else -> -1
            }
            if (score > bestScore) {
                bestScore = score
                best = e
            }
        }
        return best
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }
}
