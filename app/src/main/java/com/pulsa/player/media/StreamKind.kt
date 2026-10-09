package com.pulsa.player.media

import android.net.Uri
import androidx.media3.common.MimeTypes

/**
 * F2 — descobre se uma URL é HLS, DASH ou progressivo, e devolve o `MimeType` para o
 * `MediaItem` tocar o stream do jeito certo.
 *
 * **Por que isso é necessário.** O Media3 1.3.1 (a versão do app) NÃO tem
 * `DefaultMediaSourceFactory.setMimeTypeDetector` — esse método só entrou na 1.4. Então a
 * detecção de formato é a do próprio Media3, `Util.inferContentType(Uri)`, que olha
 * **somente a extensão do arquivo**. Na prática:
 *
 * - `https://cdn/canal/live.m3u8` → funciona (extensão `.m3u8`);
 * - `https://cdn/canal/playlist?type=hls` → **quebra**: sem extensão o stream é tratado
 *   como progressivo, o `DefaultDataSource` tenta ler o manifesto como se fosse vídeo e o
 *   playback morre em silêncio (a tela fica em "carregando" e o erro não diz nada);
 * - `https://cdn/.../manifest` sem extensão nenhuma → idem.
 *
 * CDN de vídeo esconde o formato atrás de query string com frequência, então isso não é
 * raro. A saída é passar o `mimeType` **na mão** no `MediaItem` — o
 * `DefaultMediaSourceFactory` respeita o `mimeType` do `MediaItem` antes de inferir, e aí o
 * `HlsMediaSource`/`DashMediaSource` (módulos já declarados em `build.gradle.kts:80-81`)
 * entram mesmo sem extensão. É a mesma correção que faz o rádio em `.m3u8` parar de falhar
 * calado.
 */
object StreamKind {

    private val HLS_EXT = setOf("m3u8", "m3u")
    private val DASH_EXT = setOf("mpd")

    /**
     * `MimeType` da URL, ou `null` para deixar o Media3 inferir (arquivo local, mp4 normal,
     * ou URL de progressivo onde a extensão já basta).
     */
    fun mimeTypeOf(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null

        // `path` já vem sem a query. Cai no schemeSpecificPart para URL sem `://` (rss, magnet).
        val path = (uri.path ?: uri.schemeSpecificPart ?: "").lowercase()
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "")
        if (ext in HLS_EXT) return MimeTypes.APPLICATION_M3U8
        if (ext in DASH_EXT) return MimeTypes.APPLICATION_MPD

        // Sem extensão: alguns CDNs escondem o formato na query (?type=hls, ?format=mpd).
        // Só olha a query de verdade (e o fragment), nunca a URL inteira — senão um
        // `?title=HLS%20Channel` viraria stream HLS e o vídeo mp4 tocaria errado.
        val query = "${uri.query.orEmpty()} ${uri.fragment.orEmpty()}".lowercase()
        if (query.isBlank()) return null
        val qTokens = query.split('&', ' ', '=').filter { it.isNotBlank() }
        return when {
            qTokens.any { it == "hls" || it == "m3u8" || it == "mpegurl" || it == "x-mpegurl" } ->
                MimeTypes.APPLICATION_M3U8
            qTokens.any { it == "dash" || it == "mpd" } -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    fun isStream(url: String): Boolean = mimeTypeOf(url) != null

    /** Extensão para salvar o stream como arquivo, quando um dia isso virar download. */
    fun extensionOf(mimeType: String?): String = when (mimeType) {
        MimeTypes.APPLICATION_M3U8 -> "m3u8"
        MimeTypes.APPLICATION_MPD -> "mpd"
        else -> "mp4"
    }

    /**
     * URL http/https utilizável como stream de vídeo/áudio. Rejeita o que o app já bloqueia
     * de propósito (YouTube/Spotify e companhia) e o que não é URL, para o erro chegar na
     * hora em vez de o playback morrer em silêncio.
     */
    fun playableUrl(input: String): String? {
        val url = input.trim()
        if (url.isEmpty()) return null
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") return null
        if (urlLooksLikePage(url)) return null
        return url
    }

    /**
     * Mesma lista de `SettingsActivity.urlLooksLikePage()`, replicada aqui porque aquele é
     * `private`. Essas páginas não são stream: são HTML de browser, e o Media3 leria aquilo
     * como um manifesto e falharia sem explicar nada.
     */
    fun urlLooksLikePage(url: String): Boolean {
        val u = url.lowercase()
        val pageHosts = listOf(
            "youtube.com", "youtu.be", "spotify.com", "deezer.com",
            "soundcloud.com", "tidal.com", "music.apple.com", "vimeo.com"
        )
        if (pageHosts.none { u.contains(it) }) return false
        val pagePaths = listOf(
            "watch", "track", "album", "playlist", "shorts", "embed", "video", "videos"
        )
        return pagePaths.any { u.contains("/$it") || u.contains("?$it") }
    }
}
