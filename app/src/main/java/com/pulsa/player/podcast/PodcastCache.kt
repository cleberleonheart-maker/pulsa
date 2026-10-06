package com.pulsa.player.podcast

/**
 * F3 — o episódio por id, disponível para quem monta a fila.
 *
 * **Por que um cache e não uma query.** A `Song` de um episódio carrega `podcast:<id>`, e o
 * motor precisa do endereço real da mídia na hora de montar o `MediaItem`. Esse caminho é
 * [com.pulsa.player.playback.PlaybackService.mediaItemFor], chamado do callback do ExoPlayer
 * na **main thread** — e o `PodcastDb` é Room, que lança exceção ali. Uma query resolveria o
 * problema criando um crash novo, exatamente na hora de dar play.
 *
 * Por isso o `PodcastDb` preenche este cache no mesmo acesso que já leu os episódios (ele
 * roda em `ThreadPool.post`), e o motor só lê um mapa.
 *
 * **Por que `ConcurrentHashMap` e não um `HashMap`.** A escrita vem da tela e do worker de
 * refresh, em pool diferentes da leitura do player. Um `HashMap` rehashando enquanto o
 * ExoPlayer lê é a forma de conseguir uma `ConcurrentModificationException` dentro do
 * player — que é um crash de reprodução, sem rastro em lugar nenhum.
 */
object PodcastCache {

    private val audioByEpisode = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /**
     * Grava o endereço da mídia de um episódio.
     *
     * `id <= 0` e URL vazia são **ignorados**, não gravados: um id `0` é o `Episode()`
     * padrão, e guardar `id -> ""` faria o player achar que o episódio tem endereço em branco.
     */
    fun put(episodeId: Long, url: String) {
        if (episodeId <= 0L || url.isBlank()) return
        audioByEpisode[episodeId] = url
    }

    fun putAll(episodes: List<Episode>) {
        episodes.forEach { prepare(it) }
    }

    /**
     * O endereço que o ExoPlayer deve abrir, ou `null` quando o episódio não está aqui.
     *
     * `null` é a resposta honesta para um episódio cujo feed sumiu do servidor: o
     * [com.pulsa.player.playback.PlaybackService] cai no `Uri.fromFile` e o player dá
     * `onTrackError`, que o usuário vê, em vez de um silêncio que parece app travado.
     */
    fun audioUrl(episodeId: Long): String? =
        if (episodeId <= 0L) null else audioByEpisode[episodeId]

    /**
     * Esquece os episódios de um podcast removido.
     *
     * Os ids são do episódio, não do podcast, então a assinatura chega junto com a lista dos
     * ids que ela tinha — quem chama já leu os episódios para poder apagar os arquivos.
     */
    fun forget(episodeIds: Collection<Long>) {
        episodeIds.forEach { audioByEpisode.remove(it) }
    }

    /**
     * Esquenta o cache com um episódio, no clique em "tocar".
     *
     * O caminho é o [Episode.playableUrl], que já prefere o arquivo baixado — é o que faz o
     * episódio offline tocar sem passar pela URL.
     */
    fun prepare(ep: Episode) = put(ep.id, ep.playableUrl)
}
