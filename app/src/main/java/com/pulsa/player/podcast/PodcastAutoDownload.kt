package com.pulsa.player.podcast

import android.content.Context
import com.pulsa.player.core.Settings

/**
 * Baixa automaticamente os episódios **novos** de cada feed, conforme a regra das Configurações.
 *
 * Diferente do [PodcastPrefetch], que só adianta o próximo da fila, este caminho pega o que o
 * refresh acabou de inserir. A quantidade é por feed: um podcast que publica cinco episódios de
 * uma vez não consome a cota dos outros. Só entram áudio com URL HTTP; vídeo continua sendo uma
 * escolha manual, porque o arquivo é grande e a tela de vídeo já tem o próprio download.
 */
object PodcastAutoDownload {

    /** Ponto de entrada do worker e das telas depois de um refresh. */
    fun afterRefresh(context: Context, results: List<PodcastDb.RefreshResult>) {
        afterNewEpisodes(context, results.flatMap { it.newEpisodes })
    }

    /** Ponto de entrada da assinatura, que também pode trazer episódios novos. */
    fun afterNewEpisodes(context: Context, episodes: List<Episode>) {
        val limit = Settings.podcastAutoDownloadCount(context)
        if (limit <= 0 || episodes.isEmpty()) return
        val app = context.applicationContext
        choose(episodes, limit).forEach { PodcastDownloads.enqueue(app, it) }
    }

    /**
     * Aplica a regra por feed. Pura para o teste cobrir a parte que decide o que baixa.
     *
     * Ordena por publicação **descendente** e corta em [limit]: quando o feed publica vários de
     * uma vez, os mais recentes são os que a pessoa tem mais chance de ouvir primeiro. Episódio
     * já baixado, vídeo e URL que não é HTTP ficam de fora.
     */
    internal fun choose(episodes: List<Episode>, limit: Int): List<Episode> =
        if (limit <= 0) emptyList()
        else episodes.groupBy { it.podcastId }.values.flatMap { select(it, limit) }

    internal fun select(episodes: List<Episode>, limit: Int): List<Episode> =
        if (limit <= 0) emptyList()
        else episodes.asSequence()
            .filter {
                it.id > 0L && !it.downloaded && !it.isVideo && it.audioUrl.startsWith("http")
            }
            .sortedByDescending { it.publishedAt }
            .take(limit)
            .toList()
}
