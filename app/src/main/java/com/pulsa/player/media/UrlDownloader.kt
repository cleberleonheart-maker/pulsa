package com.pulsa.player.media

import com.pulsa.player.R

/**
 * F5 — Download Center: o "baixar por URL de arquivo" num lugar só.
 *
 * A tela de URL viveu nas Configurações até a aba Baixados ganhar a sua própria
 * entrada. A validação tinha tudo para divergir nas duas telas (cada uma copiaria o
 * `startsWith("http")` e a lista de sites), então a regra subiu para cá: quem quer
 * baixar pede o veredito no mesmo lugar, e o [R.string.download_direct_only] vale igual
 * nos dois cantos.
 *
 * Só aceita link **de arquivo**: o site de página (YouTube, Spotify, Deezer…) precisa de
 * um injetor de áudio, não de um download direto — e a URL de página baixaria o HTML.
 */
object UrlDownloader {

    /**
     * Só consulta o cache do [OfficialMediaResolver]. Este método é chamado no main
     * thread, então não pode fazer rede — a resolução acontece antes, em
     * [OfficialMediaResolver.resolveAsync].
     */
    fun resolveOfficial(url: String): OfficialMediaResolver.Resolved? =
        OfficialMediaResolver.cached(url)

    /** Id de string de erro, ou `null` quando a URL pode ir para a fila do WorkManager. */
    fun validate(url: String): Int? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return R.string.download_invalid_url
        }
        // #277 – se já conseguimos resolver via API oficial, não bloquear
        if (OfficialMediaResolver.cached(url) != null) return null
        if (urlLooksLikePage(url)) {
            return R.string.download_direct_only
        }
        return null
    }

    private fun urlLooksLikePage(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("youtube.com") || lower.contains("youtu.be") ||
            lower.contains("spotify.com") || lower.contains("deezer.com") ||
            lower.contains("soundcloud.com") || lower.contains("tidal.com") ||
            lower.contains("music.youtube.com")
        ) return true
        // Apple Music/iTunes: página, mesmo /song/ — o áudio real vem do previewUrl.
        if (lower.contains("music.apple.com") || lower.contains("itunes.apple.com")) return true
        val path = lower.substringBefore('?').substringAfter("://").substringAfter('/')
        if (path.startsWith("watch") || path.startsWith("track") || path.startsWith("album") ||
            path.startsWith("playlist") || path.startsWith("shorts") || path.startsWith("embed")
        ) return true
        return false
    }

    fun guessName(url: String): String {
        val segment = url.substringBefore('?').substringAfterLast('/')
        return segment.substringBeforeLast('.', segment).ifEmpty { "música" }
    }
}