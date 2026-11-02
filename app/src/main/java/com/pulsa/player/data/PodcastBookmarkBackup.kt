package com.pulsa.player.data

import com.pulsa.player.data.db.PodcastBookmarkEntity
import com.pulsa.player.data.db.PodcastDao
import org.json.JSONArray
import org.json.JSONObject

/**
 * Os marcadores de podcast dentro do arquivo de backup.
 *
 * **Por que o episódio viaja pela URL do feed e pelo `guid`.** O `episode_id` do marcador é o
 * id local do Room (autoincrement): no celular novo ele aponta para outro episódio, ou para
 * nenhum — e um marcador que "voltou" no lugar errado é pior do que um marcador que não
 * voltou. A URL do feed e o `guid` do episódio são os mesmos em qualquer máquina, e é por
 * eles que o episódio é procurado aqui.
 *
 * **Por que o que não volta é dito.** Um marcador só existe em cima de um episódio. Se o
 * episódio não está neste aparelho (feed novo, assinatura removida), o marcador não tem onde
 * ficar — e o restore conta isso em vez de dizer que restaurou tudo.
 */
object PodcastBookmarkBackup {

    /** Um marcador como ele viaja. */
    data class BackupBookmark(
        val feedUrl: String,
        val guid: String,
        val positionMs: Long,
        val title: String,
        val createdAt: Long
    )

    /** O que voltou e o que ficou de fora. */
    data class Report(val restored: Int = 0, val missing: Int = 0)

    fun export(dao: PodcastDao): JSONArray {
        val array = JSONArray()
        for (row in dao.bookmarksWithEpisode()) {
            array.put(
                JSONObject().apply {
                    put("feed", row.feedUrl)
                    put("guid", row.guid)
                    put("positionMs", row.positionMs)
                    put("title", row.title)
                    put("createdAt", row.createdAt)
                }
            )
        }
        return array
    }

    /**
     * Lê o array do arquivo. Puro de propósito: sem Context e sem banco — é o pedaço que dá
     * para testar de verdade num arquivo malformado.
     *
     * Tolerante como o resto do backup: chave ausente vira lista vazia, e marcador sem
     * identidade (feed ou guid vazio) é descartado — ele não tem como ser resolvido.
     */
    fun parse(array: JSONArray?): List<BackupBookmark> {
        if (array == null) return emptyList()
        val out = ArrayList<BackupBookmark>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val feed = obj.optString("feed").trim()
            val guid = obj.optString("guid").trim()
            if (feed.isEmpty() || guid.isEmpty()) continue
            out += BackupBookmark(
                feedUrl = feed,
                guid = guid,
                positionMs = obj.optLong("positionMs", 0L).coerceAtLeast(0L),
                title = obj.optString("title").trim(),
                // Data ausente ou zero: cai em "agora". Perder a data só muda a ordem em que
                // os marcadores aparecem — não apaga o marcador.
                createdAt = obj.optLong("createdAt", 0L).takeIf { it > 0L }
                    ?: System.currentTimeMillis()
            )
        }
        return out
    }

    /**
     * Grava os marcadores que podem ser resolvidos neste aparelho.
     *
     * O `upsert` do DAO usa o índice único `(episode_id, position_ms)`: restaurar o mesmo
     * arquivo duas vezes atualiza o marcador em vez de duplicá-lo.
     */
    fun import(dao: PodcastDao, parsed: List<BackupBookmark>): Report {
        var restored = 0
        var missing = 0
        for (bookmark in parsed) {
            val episodeId = dao.episodeIdByFeedAndGuid(bookmark.feedUrl, bookmark.guid)
            if (episodeId == null) {
                missing++
                continue
            }
            dao.upsertBookmark(
                PodcastBookmarkEntity(
                    episodeId = episodeId,
                    positionMs = bookmark.positionMs,
                    title = bookmark.title,
                    createdAt = bookmark.createdAt
                )
            )
            restored++
        }
        return Report(restored, missing)
    }
}
