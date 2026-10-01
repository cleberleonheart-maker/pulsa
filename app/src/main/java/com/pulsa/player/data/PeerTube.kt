package com.pulsa.player.data

import com.pulsa.player.core.ThreadPool
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * F2 — busca e reprodução no PeerTube.
 *
 * **Por que PeerTube e não YouTube.** O YouTube não publica URL de mídia: o `watch?v=...`
 * devolve HTML, e os URLs de stream só existem dentro da API interna, com áudio e vídeo
 * separados e protegidos por *signature deciphering* (uma função JS que o player baixa e
 * executa). Não existe regra de reescrita que faça o ExoPlayer tocar isso. O PeerTube é o
 * equivalente que **devolve a URL de verdade**, na API pública e sem token.
 *
 * **A busca é em dois tempos, e não por preguiça.** `GET /api/v1/search/videos` devolve
 * só metadados — `uuid`, `name`, `duration`, `url`, `thumbnailPath`. Os streams
 * (`streamingPlaylists[].playlistUrl` e `files[].fileUrl`) ficam **fora** da resposta da
 * busca e só aparecem em `GET /api/v1/videos/<uuid>`. Buscar e já tocar exigiria 25
 * requisições a mais, uma por resultado, para maybe-discartar 24. Então a lista é instantânea
 * e o stream é resolvido **no clique**, num pedido só.
 *
 * **Instância.** PeerTube é federado: cada instância é uma rede separada, instância pública
 * cai, muda de nome ou bloqueia o app. O padrão é `peertube.tv`, e o usuário troca em Ajustes
 * (ver [com.pulsa.player.core.Settings.peerTubeInstance]).
 */
object PeerTube {

    const val DEFAULT_INSTANCE = "https://peertube.tv"
    private const val TIMEOUT_MS = 12000
    private const val UA = "Pulsa/1.0 (Android)"

    /**
     * Normaliza o que o usuário digitou em Ajustes: aceita `peertube.tv`,
     * `https://peertube.tv/` e `https://peertube.tv/api/v1` e devolve a raiz limpa.
     */
    fun normalizeInstance(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) return DEFAULT_INSTANCE
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        s = s.trimEnd('/')
        s = s.substringBefore("/api/v1").trimEnd('/')
        return s.ifEmpty { DEFAULT_INSTANCE }
    }

    /**
     * Um vídeo. [streamUrl] é null até [resolve]: a busca não devolve stream, então na lista
     * essa informação simplesmente não existe ainda.
     */
    /**
     * Uma legenda do PeerTube, no formato que o `SubtitleConfig` consome.
     *
     * O `url` da API já vem apontando para o `.vtt` traduzido (`/captions/<uuid>/<code>.vtt`),
     * então não há busca nem parse: é só usar.
     */
    data class Caption(val url: String, val label: String, val language: String)

    data class Item(
        val uuid: String,
        val title: String,
        val durationSec: Int,
        val pageUrl: String,
        val thumbnail: String?,
        val streamUrl: String? = null,
        val isHls: Boolean = false,
        /** Instância onde a busca foi feita — base do `thumbnailPath` relativo. */
        val instanceRoot: String = DEFAULT_INSTANCE,
        /** Legendas da API. Vazio na maioria dos vídeos: só quem enviou legenda tem. */
        val captions: List<Caption> = emptyList()
    )

    /**
     * Busca na instância. [onResult] recebe [Result] para a UI diferenciar **erro** (rede
     * caiu, instância bloqueou) de **zero resultado** — os dois caem em `runCatching` se
     * alguém não olhar, e a mensagem errada manda o usuário trocar de instância à toa.
     */
    fun search(instance: String, query: String, onResult: (Result<List<Item>>) -> Unit) {
        val q = query.trim()
        if (q.isEmpty()) {
            onResult(Result.success(emptyList()))
            return
        }
        val root = normalizeInstance(instance)
        ThreadPool.postNetwork {
            val result = runCatching {
                val url = "$root/api/v1/search/videos?search=" +
                    URLEncoder.encode(q, "UTF-8") + "&count=25"
                val data = JSONObject(get(url)).optJSONArray("data")
                val out = mutableListOf<Item>()
                for (i in 0 until (data?.length() ?: 0)) {
                    fromSearch(data!!.optJSONObject(i), root)?.let { out += it }
                }
                out
            }
            ThreadPool.onUi { onResult(result) }
        }
    }

    /**
     * Busca o stream de um resultado. Uma requisição, no clique, e devolve `null` quando a
     * instância não expõe nem HLS nem MP4 utilizável (vídeo removido, ou formato exótico).
     */
    fun resolve(item: Item, onResult: (Item?) -> Unit) {
        ThreadPool.postNetwork {
            val resolved = runCatching {
                val url = "${item.pageUrl.substringBefore("/videos/watch")}/api/v1/videos/${item.uuid}"
                fromDetail(JSONObject(get(url)), item)
            }.getOrNull()
            ThreadPool.onUi { onResult(resolved) }
        }
    }

    /**
     * O manifesto HLS ainda existe e é um manifesto?
     *
     * Só um GET, sem `Range`: o `master.m3u8` tem algumas centenas de bytes e alguns CDNs
     * respondem 403 a `HEAD` ou a pedido parcial. Exige `#EXTM3U` porque um CDN de erro
     * devolve HTML 200 com o manifesto embutido, e isso passaria num teste de status.
     */
    private fun manifestAlive(url: String): Boolean = runCatching {
        val body = get(url, json = false)
        body.startsWith("#EXTM3U")
    }.getOrDefault(false)

    fun get(url: String, json: Boolean = true): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", UA)
            if (json) setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Metadados da busca. Sem stream, porque a resposta da busca não tem stream. */
    fun fromSearch(obj: JSONObject?, instanceRoot: String): Item? {
        if (obj == null) return null
        val uuid = obj.optString("uuid").ifBlank { return null }
        val pageUrl = obj.optString("url").ifBlank { return null }

        return Item(
            uuid = uuid,
            title = obj.optString("name").ifBlank { "PeerTube" },
            durationSec = obj.optInt("duration", 0),
            pageUrl = pageUrl,
            thumbnail = thumbnailOf(obj, instanceRoot),
            instanceRoot = instanceRoot
        )
    }

    /**
     * Thumbnail.
     *
     * `thumbnails[].fileUrl` já vem absoluto e correto — é a fonte preferida. O
     * `thumbnailPath` é relativo à **instância que você buscou**, e não à que hospeda o
     * vídeo: um vídeo buscado no `peertube.tv` e hospedado no `tube.tchncs.de` tem
     * `thumbnailPath` servido pelo primeiro, e prefixar o host errado dá 404 em toda thumb
     * da lista (parece lista quebrada, não é).
     */
    private fun thumbnailOf(obj: JSONObject, instanceRoot: String): String? {
        val list = obj.optJSONArray("thumbnails")
        for (i in 0 until (list?.length() ?: 0)) {
            val t = list!!.optJSONObject(i) ?: continue
            val url = t.optString("fileUrl")
            if (url.isNotBlank() && t.optInt("width", 0) >= 640) return url
        }
        for (i in 0 until (list?.length() ?: 0)) {
            val url = list!!.optJSONObject(i)?.optString("fileUrl").orEmpty()
            if (url.isNotBlank()) return url
        }
        val path = obj.optString("thumbnailPath")
        return if (path.isBlank()) null else instanceRoot.trimEnd('/') + path
    }

    /**
     * Detalhe do vídeo: aqui sim vêm os streams. HLS quando a instância tem resolução
     * adaptativa ligada (sobe/desce de qualidade sozinho), senão o MP4 de maior `height`.
     *
     * O `height` decide a qualidade, não o `size`: o `size` às vezes vem `-1` ou 0 na API.
     *
     * **Por que o HLS é testado antes de virar `streamUrl`.** A URL do `streamingPlaylists`
     * é a que o vídeo tinha **quando foi federado**, e o CDN de destino não é o da instância:
     * vídeo hospedado em `tube.tchncs.de` é servido de `media.tube.tchncs.de`, e vídeo
     * importado de outra rede vem de `spectra-prod.us-east-1.linodeobjects.com` e friends.
     * Quando o dono remove o original, apaga a conta ou o CDN migra, **a instância continua
     * anunciando a playlist e o endereço morre** — medido em 30/09 no `peertube.tv`: 13 dos 25
     * primeiros resultados de "musica" responderam **404** no manifesto. Sem este teste, o
     * app entregava um `master.m3u8` morto ao ExoPlayer, que abre a tela do player e fica
     * **preta** — a falha nunca chega como erro, parece vídeo quebrado. Uma leitura de GET do
     * manifesto (uns 500 bytes) decide antes, e o MP4 passa a ser a reserva real.
     */
    fun fromDetail(obj: JSONObject?, fallback: Item): Item? {
        if (obj == null) return null

        var hls: String? = null
        val playlists = obj.optJSONArray("streamingPlaylists")
        for (i in 0 until (playlists?.length() ?: 0)) {
            val candidate = playlists!!.optJSONObject(i)?.optString("playlistUrl").orEmpty()
            if (candidate.isNotBlank() && manifestAlive(candidate)) {
                hls = candidate
                break
            }
        }

        val files = obj.optJSONArray("files")
        var best: JSONObject? = null
        for (i in 0 until (files?.length() ?: 0)) {
            val f = files!!.optJSONObject(i)
            // Sem `?: continue` aqui: `ifBlank { continue }` e `?: continue` com lambda
            // inline disparam "break continue in inline lambdas", experimental no Kotlin 1.9.
            if (f == null) continue
            if (f.optString("fileUrl").isBlank()) continue
            if (f.optBoolean("hasVideo") != true) continue
            val b = best
            if (b == null || f.optInt("height", 0) > b.optInt("height", 0)) best = f
        }
        val fileUrl = best?.optString("fileUrl").orEmpty()
        // Nem HLS nem MP4 utilizável: a instância devolveu algo que não sabemos tocar.
        if (hls == null && fileUrl.isEmpty()) return null

        return fallback.copy(
            streamUrl = hls ?: fileUrl,
            isHls = hls != null,
            durationSec = obj.optInt("duration", fallback.durationSec),
            thumbnail = thumbnailOf(obj, fallback.instanceRoot) ?: fallback.thumbnail,
            captions = captionsOf(obj, fallback.instanceRoot)
        )
    }

    /**
     * As legendas que o dono do vídeo enviou.
     *
     * A API traz `captions[].url` **relativo** à instância (ex.
     * `/static/captions/<uuid>/pt-BR.vtt`), então precisa do mesmo host treatment do
     * `thumbnailPath` — daí reusar [absolute]. Também vem `autoGenerated`/`language` que
     * não interessam aqui: o Media3 lê WebVTT dos dois jeitos, e o idioma serve só para
     * escolher um rótulo.
     */
    private fun captionsOf(obj: JSONObject, instanceRoot: String): List<Caption> {
        val arr = obj.optJSONArray("captions") ?: return emptyList()
        val out = mutableListOf<Caption>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            // Sem `ifBlank { continue }` aqui: em lambda inline isso dispara "break continue
            // in inline lambdas", que é experimental no Kotlin 1.9 (o mesmo motivo do
            // comentário lá em cima, no chooses de `files`).
            val raw = c.optString("url")
            if (raw.isBlank()) continue
            val url = if (raw.startsWith("http")) raw else instanceRoot.trimEnd('/') + raw
            val label = c.optString("label").ifBlank { c.optString("language") }.ifBlank { "CC" }
            out += Caption(url = url, label = label, language = c.optString("language"))
        }
        return out
    }
}
