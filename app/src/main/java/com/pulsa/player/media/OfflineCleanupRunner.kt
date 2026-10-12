package com.pulsa.player.media

import android.content.Context
import com.pulsa.player.core.Settings
import com.pulsa.player.data.StreamHistory
import com.pulsa.player.dj.DjLearn
import com.pulsa.player.playback.Playback
import com.pulsa.player.podcast.PodcastDb
import com.pulsa.player.podcast.PodcastFiles
import java.io.File

/**
 * F5 · Limpeza automática do offline — o lado que **toca no disco**.
 *
 * A decisão de o que apagar está em [OfflineCleanup.plan], pura. Aqui só se junta o que o app
 * sabe sobre cada baixado, chama o `plan()` e executa.
 *
 * **O que a idade usa, por item** — cada fonte cobre o que a outra não vê:
 * - vídeo do Download Center: `addedAt` (quando baixou) e o `StreamHistory` (quando tocou por
 *   último, casado pela uuid);
 * - episódio: o `mtime` do arquivo (é a data do download, e reproduzir não o reescreve) e o
 *   `play_log` do DJ (chave `-episódio.id`, igual à que a limpeza por voz usa).
 *
 * Sem essas somas, um vídeo baixado há um ano e tocado ontem seria apagado — e o inverso
 * também. O `mtime` entra como piso quando não há data melhor, nunca sobrepondo um toque real.
 */
object OfflineCleanupRunner {

    data class Freed(val count: Int, val bytes: Long)

    /**
     * Roda uma passada. Devolve o que liberou (0 quando desligado ou nada vencido).
     *
     * Grava o aviso pendente só quando apagou algo de verdade, para a Virgin falar na próxima
     * vez em que estiver ativa — o Worker roda com o app fechado e não tem quem ouvir.
     */
    fun run(context: Context): Freed {
        val days = Settings.offlineCleanupDays(context)
        if (days <= 0) return Freed(0, 0L)

        DownloadStore.ensureLoaded(context)
        val now = System.currentTimeMillis()
        val candidates = gather(context)

        val protected = Playback.currentSong?.podcastId?.let { setOf(it.toString()) } ?: emptySet()
        val plan = OfflineCleanup.plan(candidates, now, days, protected)
        if (plan.count == 0) return Freed(0, 0L)

        plan.videos.forEach { DownloadService.delete(context, it.id) }

        if (plan.episodes.isNotEmpty()) {
            val db = PodcastDb.get(context)
            plan.episodes.forEach { c ->
                val epId = c.id.toLongOrNull() ?: return@forEach
                if (c.path.isNotBlank()) PodcastFiles.delete(context, c.path)
                db.setFilePath(epId, "")
            }
        }

        Settings.setPendingOfflineClean(context, plan.count, plan.bytes)
        return Freed(plan.count, plan.bytes)
    }

    /** Aviso pendente pronto para a voz, ou `null`. Consome o aviso ao devolver. */
    fun takePendingNotice(context: Context): Pair<Int, Long>? {
        val pending = Settings.pendingOfflineClean(context) ?: return null
        Settings.clearPendingOfflineClean(context)
        return pending
    }

    private fun gather(context: Context): List<OfflineCleanup.Candidate> {
        val out = ArrayList<OfflineCleanup.Candidate>()

        val streamAt = runCatching {
            StreamHistory.list(context).filter { it.uuid.isNotBlank() }.associate { it.uuid to it.at }
        }.getOrDefault(emptyMap())

        DownloadStore.list()
            .filter { it.status == DownloadStore.Status.DONE }
            .forEach { item ->
                val file = DownloadStore.fileFor(context, item)
                out += OfflineCleanup.Candidate(
                    id = item.id,
                    title = item.title,
                    kind = OfflineCleanup.Kind.VIDEO,
                    bytes = runCatching { file.length() }.getOrDefault(0L),
                    lastUsedMs = maxOf(
                        item.addedAt,
                        streamAt[item.id] ?: 0L,
                        runCatching { file.lastModified() }.getOrDefault(0L)
                    ),
                    path = file.absolutePath
                )
            }

        val db = PodcastDb.get(context)
        val lastPlayed = runCatching { DjLearn.lastPlayedMap(context) }.getOrDefault(emptyMap())
        db.feeds().forEach { feed ->
            runCatching { db.downloadedEpisodes(feed.id) }.getOrDefault(emptyList()).forEach { ep ->
                if (ep.filePath.isBlank()) return@forEach
                val file = File(ep.filePath)
                out += OfflineCleanup.Candidate(
                    id = ep.id.toString(),
                    title = ep.title,
                    kind = OfflineCleanup.Kind.EPISODE,
                    bytes = runCatching { file.length() }.getOrDefault(0L),
                    lastUsedMs = maxOf(
                        (lastPlayed[-ep.id] ?: 0L) * 1000L,
                        runCatching { file.lastModified() }.getOrDefault(0L)
                    ),
                    path = ep.filePath
                )
            }
        }

        return out
    }
}
