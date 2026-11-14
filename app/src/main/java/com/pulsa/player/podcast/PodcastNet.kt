package com.pulsa.player.podcast

import com.pulsa.player.core.ThreadPool
import java.net.HttpURLConnection
import java.net.URL

/**
 * F3 — a rede do podcast: baixa o XML do feed e procura podcast por nome.
 *
 * Mesma forma do [com.pulsa.player.data.PeerTube]: método síncrono puro para o teste, e o
 * embrulho em [ThreadPool] para a tela. Sem corrotina, como o resto do app.
 */
object PodcastNet {

    private const val TIMEOUT_MS = 15000
    private const val UA = "Pulsa/1.0 (Android)"

    /** Host permitido na [iTunesSearch]: a API é pública, mas não é um proxy aberto. */
    private const val ITUNES_HOST = "itunes.apple.com"


    /**
     * `https` obrigatório.
     *
     * Feed é conteúdo de terceiro, e o app vai puxar e tocar o que o feed mandar. Um feed em
     * `http://` abre espaço para o atacante trocar o XML no caminho e apontar o `enclosure`
     * para o arquivo que ele quiser. Sem essa linha, `http://podcast.exemplo/feed.xml` falha
     * com erro de TLS em vez de ser silenciosamente recusado — que é a falha certa.
     */
    private fun fetch(url: String): ByteArray {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("url vazia")
        if (!trimmed.startsWith("https://")) throw IllegalStateException("HTTP $HTTPS_REQUIRED")
        val conn = (URL(trimmed).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                // O corpo do erro é HTML de página de erro na maioria dos CDNs; guardar os
                // bytes não ajuda ninguém e o `last_error` ficaria ilegível na lista.
                throw IllegalStateException("HTTP $code")
            }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * `true` quando a URL tem cara de feed. Antes de gastar uma requisição.
     *
     * `feed` no caminho é o padrão, mas não é regra — `podcast.xml`, `rss` e `index.xml`
     * aparecem bastante. Por isso a checagem é frouxa de propósito e a decisão real é o
     * [FeedParser] no `parse`.
     */
    fun looksLikeFeedUrl(input: String): Boolean {
        val s = input.trim().lowercase()
        if (!s.startsWith("https://") && !s.startsWith("http://")) {
            // Sem esquema ainda pode ser um host colado; a UI completa com https:// e tenta.
            return s.contains('.')
        }
        if (!s.startsWith("https://")) return false
        return s.contains(".xml") || s.contains("/feed") || s.contains("/rss") ||
            s.contains("podcast") || s.contains("?format=")
    }

    /**
     * Um feed que responde e é parseável, com o erro no próprio `Result`.
     *
     * Não há campo `lastHttpError` de propósito: o [PodcastSync.refreshAll] atualiza vários
     * feeds em sequência e, com um campo compartilhado, o podcast que deu 404 acabaria com o
     * erro do podcast seguinte gravado no `last_error`. O erro viaja **com** o resultado, e
     * não em uma variable que o próximo chamador sobrescreve.
     */
    fun fetchFeedResult(url: String): Result<Podcast> = runCatching {
        // O motivo vem do parser, e não um "feed inválido" genérico: quem assinou quer saber
        // se colou a página do site, se o XML veio quebrado ou se o servidor devolveu 404.
        val body = fetch(url)
        FeedParser.parseOrError(body).getOrElse {
            // A segunda linha é só para o log: o começo do corpo diz na hora o que veio (JSON,
            // HTML de verificação, metade de um XML) sem precisar baixar de novo. A tela fica
            // com a primeira linha, que é a parte legível.
            throw IllegalStateException("${it.message}\ncorpo=${preview(body)}", it)
        }
    }

    /** Primeiros bytes do corpo, uma linha só, para o log de erro do feed. */
    private fun preview(body: ByteArray): String {
        val text = String(body, 0, minOf(body.size, 160), Charsets.UTF_8)
            .replace("\\s+".toRegex(), " ")
        return text + if (body.size > 160) "…" else ""
    }

    // ---- busca online ---------------------------------------------------------------------

    /** Um podcast de resultado, com o `feedUrl` pronto para assinar. */
    data class Found(
        val feedUrl: String,
        val title: String,
        val author: String,
        val artworkUrl: String,
        val genre: String,
        val episodeCount: Int = 0
    )

    /**
     * Busca na **iTunes Search API** — ou no **Podcast Index**, quando há credencial.
     *
     * **Por que as duas.** O Podcast Index é melhor indexado, mas exige cadastro e uma chave —
     * e um app que não pode mostrar chave na tela de busca não pode depender dele. A iTunes é
     * sem chave, devolve JSON, e o `feedUrl` vem pronto no `lookup`: assinar o resultado é só
     * gravar a string, sem segunda requisição para descobrir o XML. Com a credencial
     * configurada (opcional, nas Configurações), o Podcast Index entra **primeiro** e a
     * iTunes fica como reserva — catálogo maior, e a reserva garante que a busca nunca
     * fique muda por causa de uma chave expirada.
     *
     * O host é fixo e o caminho é montado à mão, sem interpolar a query na URL: um termo
     * com `../` na busca não pode escolher outro host.
     */
    fun search(
        query: String,
        credentials: IndexCredentials? = null,
        onResult: (Result<List<Found>>) -> Unit
    ) {
        val q = query.trim()
        if (q.isEmpty()) {
            onResult(Result.success(emptyList()))
            return
        }
        ThreadPool.postNetwork {
            val result = runCatching {
                val fromIndex = if (credentials != null) searchIndex(q, credentials) else null
                if (!fromIndex.isNullOrEmpty()) fromIndex else searchItunes(q)
            }
            ThreadPool.onUi { onResult(result) }
        }
    }

    /** A credencial do Podcast Index: chave pública e segredo da assinatura. */
    data class IndexCredentials(val key: String, val secret: String)

    /**
     * A assinatura que a API exige: `Bearer` + MD5(chave + segredo + data), em maiúsculas.
     *
     * A data vai em segundos, e é a mesma nos três cabeçalhos — se a data do cabeçalho
     * divergir da data da assinatura, a API responde 401 sem explicar.
     */
    fun indexAuthHeader(key: String, secret: String, epochSeconds: Long): String {
        val stamp = epochSeconds.toString()
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest((key + secret + stamp).toByteArray())
        val hex = digest.joinToString("") { "%02X".format(it) }
        return "Bearer $hex"
    }

    /** A URL da busca do índice. O host é fixo; a query vai codificada. */
    fun indexSearchUrl(query: String, limit: Int = SEARCH_LIMIT): String =
        "https://api.podcastindex.org/api/1.0/search/byterm?max=$limit&q=" +
            java.net.URLEncoder.encode(query, "UTF-8")

    /** Converte um resultado do índice. `null` quando não dá para assinar (sem `feedUrl`). */
    fun fromIndexHit(obj: org.json.JSONObject?): Found? {
        if (obj == null) return null
        val feed = obj.optString("feedUrl").trim()
        if (feed.isEmpty()) return null
        return Found(
            feedUrl = feed,
            title = obj.optString("title").ifBlank { "Podcast" },
            author = obj.optString("author"),
            artworkUrl = obj.optString("image"),
            genre = categoriesOf(obj),
            episodeCount = obj.optInt("episodeCount", 0)
        )
    }

    private fun categoriesOf(obj: org.json.JSONObject): String {
        val arr = obj.optJSONArray("categories") ?: return ""
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            obj.optJSONArray("categories")?.optString(i)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { out += it }
        }
        return out.joinToString(" · ")
    }

    /**
     * A busca no índice.
     *
     * Devolve `null` (e não lista vazia) quando a chamada falhou: a diferença decide se o
     * app cai na iTunes ou fica com a busca muda.
     */
    private fun searchIndex(query: String, credentials: IndexCredentials): List<Found>? {
        return try {
            val epoch = System.currentTimeMillis() / 1000L
            val conn = (URL(indexSearchUrl(query)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Auth-Key", credentials.key)
                setRequestProperty("X-Auth-Date", epoch.toString())
                setRequestProperty("Authorization", indexAuthHeader(credentials.key, credentials.secret, epoch))
            }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
            conn.disconnect()
            if (body.isBlank()) return null
            val hits = org.json.JSONObject(body).optJSONArray("hits") ?: return null
            val out = mutableListOf<Found>()
            for (i in 0 until hits.length()) fromIndexHit(hits.optJSONObject(i))?.let { out += it }
            out
        } catch (t: Throwable) {
            null
        }
    }

    /** A busca na iTunes — a de sempre, sem chave. */
    private fun searchItunes(query: String): List<Found> {
        val url = "https://$ITUNES_HOST/search?" +
            "media=podcast&entity=podcast&limit=$SEARCH_LIMIT&term=" +
            java.net.URLEncoder.encode(query, "UTF-8")
        val arr = org.json.JSONObject(getText(url)).optJSONArray("results")
        val out = mutableListOf<Found>()
        for (i in 0 until (arr?.length() ?: 0)) fromSearch(arr!!.optJSONObject(i))?.let { out += it }
        return out
    }

    /** Converte um resultado da busca. `null` quando não tem `feedUrl` — inscrevível é obrigatório. */
    fun fromSearch(obj: org.json.JSONObject?): Found? {
        if (obj == null) return null
        val feed = obj.optString("feedUrl").trim()
        if (feed.isEmpty()) return null
        return Found(
            feedUrl = feed,
            title = obj.optString("collectionName").ifBlank { "Podcast" },
            author = obj.optString("artistName"),
            artworkUrl = obj.optString("artworkUrl600")
                .ifBlank { obj.optString("artworkUrl100") }
                .ifBlank { obj.optString("artworkUrl60") },
            genre = genresOf(obj),
            episodeCount = obj.optInt("trackCount", 0)
        )
    }

    private fun genresOf(obj: org.json.JSONObject): String {
        val arr = obj.optJSONArray("genres") ?: return ""
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out.joinToString(" · ")
    }

    /**
     * Igual a [fetch], e pela **mesma** razão: sem esta checagem, a busca aceitaria um termo
     * que resultasse em URL `http://` e o app buscaria o catálogo em texto puro — ou pior,
     * aceitaria um host controlado pela resposta de outro. A busca devolve JSON que vira
     * `feedUrl` assinado, então o que vier de lá é conteúdo de terceiro e vale o mesmo
     * nível de desconfiança que o feed.
     */
    private fun getText(url: String): String {
        if (!url.startsWith("https://")) throw IllegalStateException("HTTP $HTTPS_REQUIRED")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private const val SEARCH_LIMIT = 30
    private const val HTTPS_REQUIRED = 426
}
