package com.pulsa.player.data

import com.pulsa.player.data.db.PodcastDao
import org.json.JSONArray
import org.json.JSONObject

/**
 * Onde a pessoa parou em cada episódio, dentro do backup e da nuvem.
 *
 * **Por que isto viaja.** O `position_ms` e o `played` do episódio são estado local: o celular
 * novo sabe que a pessoa tem podcast assinado, mas não sabe que ela parou no minuto 34 do
 * episódio de segunda — e o episódio recomeça do zero, que é a pior forma de "voltar de onde
 * parou". A nuvem tinha só **um** `resume`, o da última faixa de música.
 *
 * **Por que feed + guid.** O `_id` do episódio é autoincrement do Room e não vale nada em
 * outra máquina; o `guid` do feed junto com a URL do feed são os mesmos em qualquer aparelho.
 */
object PodcastProgressBackup {

    /** O progresso de um episódio, como ele viaja. */
    data class BackupProgress(
        val feedUrl: String,
        val guid: String,
        val positionMs: Long,
        val played: Boolean,
        val title: String
    )

    /** O que voltou e o que ficou de fora. */
    data class Report(val restored: Int = 0, val missing: Int = 0)

    /** O progresso depois da fusão, e se a fusão mudou alguma coisa. */
    data class Merged(val positionMs: Long, val played: Boolean, val changed: Boolean)

    /**
     * A fusão do progresso: **quem está mais adiantado ganha**.
     *
     * "Adiantado" é a posição maior; o "ouvido" é o mais forte dos dois, porque ouvido é
     * estado final (uma máquina pode ter o episódio ouvido e a outra estar no meio — o
     * resultado correto é "ouvido").
     *
     * Puro de propósito: é a regra inteira, e ela se testa sem banco.
     */
    fun merge(localPositionMs: Long, localPlayed: Boolean, remote: BackupProgress): Merged {
        val position = maxOf(localPositionMs, remote.positionMs)
        val played = localPlayed || remote.played
        return Merged(position, played, position != localPositionMs || played != localPlayed)
    }

    fun export(dao: PodcastDao): JSONArray {
        val array = JSONArray()
        for (row in dao.episodesWithProgress()) {
            array.put(
                JSONObject().apply {
                    put("feed", row.feedUrl)
                    put("guid", row.guid)
                    put("positionMs", row.positionMs)
                    put("played", row.played)
                    put("title", row.title)
                }
            )
        }
        return array
    }

    /**
     * Lê o array do backup. Puro de propósito: sem Context e sem banco — é o pedaço que dá
     * para testar de verdade num arquivo malformado.
     *
     * Tolerante como o resto: chave ausente vira lista vazia, e progresso sem identidade
     * (feed ou guid vazio) é descartado — ele não tem como ser resolvido em nenhum aparelho.
     */
    fun parse(array: JSONArray?): List<BackupProgress> {
        if (array == null) return emptyList()
        val out = ArrayList<BackupProgress>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val feed = obj.optString("feed").trim()
            val guid = obj.optString("guid").trim()
            if (feed.isEmpty() || guid.isEmpty()) continue
            out += BackupProgress(
                feedUrl = feed,
                guid = guid,
                positionMs = obj.optLong("positionMs", 0L).coerceAtLeast(0L),
                played = obj.optBoolean("played", false),
                title = obj.optString("title").trim()
            )
        }
        return out
    }

    /**
     * Grava o progresso que pode ser resolvido neste aparelho.
     *
     * [overwrite] decide o que acontece quando o episódio **já** tem progresso aqui. Na
     * restauração de um arquivo, o arquivo manda — a pessoa escolheu aquele arquivo. Na fusão
     * com a nuvem, quem está mais adiantado ganha: o progresso local não pode regredir para
     * o de outra máquina só porque o push da nuvem é mais velho.
     *
     * "Mais adiantado" é a **posição** maior; o "ouvido" é o mais forte dos dois (ouvido é
     * estado final: uma máquina pode ter o episódio ouvido e a outra estar no meio, e o
     * resultado correto é "ouvido").
     */
    fun import(dao: PodcastDao, parsed: List<BackupProgress>, overwrite: Boolean): Report {
        var restored = 0
        var missing = 0
        for (progress in parsed) {
            val episodeId = dao.episodeIdByFeedAndGuid(progress.feedUrl, progress.guid)
            if (episodeId == null) {
                missing++
                continue
            }
            if (overwrite) {
                dao.applyProgress(episodeId, progress.positionMs, progress.played)
                restored++
                continue
            }
            val local = dao.episode(episodeId)
            val merged = merge(local?.positionMs ?: 0L, local?.played == true, progress)
            // Nada a trazer da nuvem: o local já está no ponto mais adiantado.
            if (!merged.changed) continue
            dao.applyProgress(episodeId, merged.positionMs, merged.played)
            restored++
        }
        return Report(restored, missing)
    }
}
