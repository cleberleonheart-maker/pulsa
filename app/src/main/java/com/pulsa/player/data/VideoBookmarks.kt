package com.pulsa.player.data

import android.content.Context
import com.pulsa.player.data.db.PulsaDatabase
import com.pulsa.player.data.db.VideoBookmarkDao
import com.pulsa.player.data.db.VideoBookmarkEntity
import com.pulsa.player.podcast.Bookmark

/**
 * Os marcadores do usuário nos **vídeos locais**.
 *
 * O podcast tem marcador desde 01/11 ([com.pulsa.player.podcast.PodcastDb]); o vídeo não
 * tinha, e a folha de capítulos escondia o botão de marcar justamente nos vídeos (o
 * `episodeId = 0` era o sinal de "isto aqui não tem marcador"). Agora o vídeo tem a mesma
 * coisa — o "voltar neste minuto" num filme de 2 h é tão útil quanto num episódio de
 * podcast.
 *
 * **A chave é o caminho do arquivo** ([VideoBookmarkEntity.videoKey]): é o que o app já
 * usa para abrir o vídeo e é o que sobrevive entre reinícios do app. O id do MediaStore não
 * serviria: ele é local e muda quando o arquivo é reindexado.
 *
 * A lista volta como [Bookmark], o mesmo tipo do podcast — a folha de capítulos junta
 * capítulo e marcador numa lista só e não precisa saber de onde veio cada linha.
 */
class VideoBookmarks(private val dao: VideoBookmarkDao) {

    /** Os marcadores deste vídeo, em ordem de tempo. */
    fun list(videoKey: String): List<Bookmark> {
        if (videoKey.isBlank()) return emptyList()
        return dao.bookmarks(videoKey).map {
            Bookmark(
                id = it.id,
                positionMs = it.positionMs,
                title = it.title,
                createdAt = it.createdAt
            )
        }
    }

    /**
     * Cria (ou atualiza) um marcador no instante pedido.
     *
     * O instante é limitado a zero porque um `Playback.position` negativo (motor ainda sem
     * timeline) não é um momento do vídeo. O `REPLACE` do DAO usa o índice único para
     * atualizar o título quando o mesmo ponto é marcado de novo, em vez de duplicar a linha.
     */
    fun add(videoKey: String, positionMs: Long, title: String): Long {
        if (videoKey.isBlank()) return 0L
        return dao.upsert(
            VideoBookmarkEntity(
                videoKey = videoKey,
                positionMs = positionMs.coerceAtLeast(0L),
                title = title.trim(),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    fun delete(id: Long) = dao.delete(id)

    companion object {
        fun get(context: Context): VideoBookmarks =
            VideoBookmarks(PulsaDatabase.get(context.applicationContext).videoBookmarkDao())
    }
}
