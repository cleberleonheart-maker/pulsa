package com.pulsa.player.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * F5 · Baixar capas das músicas.
 *
 * O app nunca buscou capa de música na internet: o [ArtLoader] só olha o que já existe no
 * aparelho (albumart do MediaStore ou a imagem embutida no arquivo). Muita biblioteca vem sem
 * nada disso — arquivo baixado, CD ripado sem tag — e aí a capa fica a nota musical para
 * sempre. Aqui busca-se a arte pelo **iTunes Search**, que é o mesmo host que o
 * [com.pulsa.player.podcast.PodcastNet] já usa para achar podcasts, e que **não pede chave**.
 *
 * **Por que a resposta é lida à mão.** O app não carrega biblioteca de JSON e o `org.json` do
 * Android não existe no teste de unidade puro (JVM). Extrair o campo com regex mantém
 * [pickArtwork] pura e testável, que é justamente onde mora o erro que apareceria como "capa
 * errada"/"nenhuma capa" em silêncio.
 *
 * A URL que o iTunes devolve é pequena (100×100); o padrão do próprio serviço é trocar o
 * tamanho no nome do arquivo por um maior, então [upscale] pede 600×600 em vez de baixar a
 * miniatura e ampliar pixelado.
 */
object CoverFetcher {

    private const val HOST = "itunes.apple.com"
    private const val UA = "Pulsa/1.0 (Android)"

    private val ART_100 = Regex("\"artworkUrl100\"\\s*:\\s*\"([^\"]+)\"")
    private val ART_60 = Regex("\"artworkUrl60\"\\s*:\\s*\"([^\"]+)\"")
    private val SIZE = Regex("\\d+x\\d+bb")

    /** A consulta por álbum (artista + nome). Pura, para o teste conferir a montagem. */
    fun searchUrl(artist: String, album: String): String {
        val term = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" ")
        return "https://$HOST/search?media=music&entity=album&limit=5&term=" +
            URLEncoder.encode(term, "UTF-8")
    }

    /**
     * Tira a URL da capa da resposta do iTunes. Pura.
     *
     * Prefere o `artworkUrl100` ao `artworkUrl60` porque o primeiro costuma vir em todos os
     * resultados; se nenhum dos dois existir devolve `null` (resultado sem arte — o chamador
     * simplesmente segue para o próximo álbum, em vez de gravar lixo).
     */
    fun pickArtwork(json: String): String? {
        val raw = ART_100.find(json)?.groupValues?.get(1)
            ?: ART_60.find(json)?.groupValues?.get(1)
            ?: return null
        return upscale(raw)
    }

    /** Troca o tamanho no nome do arquivo por 600×600 (o iTunes serve o mesmo id em vários). */
    fun upscale(url: String): String = url.replace(SIZE, "600x600bb")

    /**
     * Busca a capa e devolve o bitmap já decodificado, ou `null`.
     *
     * **Bloqueia** (rede): quem chama é o job de capas, numa thread de IO.
     */
    fun fetch(artist: String, album: String): Bitmap? {
        val json = getText(searchUrl(artist, album)) ?: return null
        val url = pickArtwork(json) ?: return null
        val bytes = getBytes(url) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun getText(url: String): String? = runCatching {
        val conn = open(url)
        try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun getBytes(url: String): ByteArray? = runCatching {
        val conn = open(url)
        try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun open(url: String): HttpURLConnection {
        // Só https: o iTunes e as capas são https, e aceitar http abriria um caminho que o
        // resto do app já evita (cleartext bloqueado no manifest).
        require(url.startsWith("https://")) { "http nao permitido" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
        }
    }
}
