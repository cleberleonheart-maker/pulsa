package com.pulsa.player.media

import android.content.Context
import com.pulsa.player.core.Settings
import com.pulsa.player.podcast.PodcastDb
import com.pulsa.player.podcast.PodcastFiles

/**
 * Quanto o offline ocupa e quanto ainda cabe.
 *
 * Antes o teto (`Settings.downloadLimitMb`) olhava só o [DownloadStore] — os vídeos do
 * Download Center. Podcast baixado ocupa o mesmo cartão e ficava de fora: o usuário escolhia
 * 1 GB, baixava 900 MB de episódio e o app ainda aceitava mais 1 GB de vídeo, enchendo o
 * aparelho. Aqui a conta é uma só, para vídeo e podcast, e é a mesma que o serviço, o Worker
 * e o banner usam.
 *
 * O download por URL também é barrado pelo mesmo teto. Ele entra na fila do
 * [DownloadService], guarda o parcial em `filesDir` e, ao concluir, publica no MediaStore e
 * registra `mediaUri` no [DownloadStore] — por isso o tamanho final aparece no "usado" do
 * banner como qualquer vídeo baixado.
 */
object OfflineSpace {

    /** Abaixo disto o Android começa a chiar (cache, atualização de app). */
    const val LOW_FREE_BYTES = 512L * 1024L * 1024L

    /** Folga que tem que sobrar além do arquivo para o sistema respirar. */
    private const val MIN_FREE = 32L * 1024L * 1024L

    /**
     * Tamanho dos episódios baixados é caro de medir (Room + um `query` por `content://`), e
     * o serviço de vídeo consulta o teto a cada 400 ms. O cache curto evita essa varredura no
     * meio do download; quem grava ou apaga episódio chama [invalidate].
     */
    private const val PODCAST_CACHE_MS = 10_000L

    @Volatile private var podcastCacheBytes = -1L
    @Volatile private var podcastCacheAt = 0L

    fun podcastBytes(context: Context, fresh: Boolean = false): Long {
        val now = System.currentTimeMillis()
        val cached = podcastCacheBytes
        if (!fresh && cached >= 0L && now - podcastCacheAt < PODCAST_CACHE_MS) return cached
        val value = runCatching {
            PodcastDb.get(context).downloadedPaths().sumOf { PodcastFiles.size(context, it) }
        }.getOrDefault(0L)
        podcastCacheBytes = value
        podcastCacheAt = now
        return value
    }

    fun usedBytes(context: Context, fresh: Boolean = false): Long =
        DownloadStore.usedBytes(context) + podcastBytes(context, fresh)

    fun limitBytes(context: Context): Long {
        val mb = Settings.downloadLimitMb(context)
        return if (mb <= 0) 0L else mb * 1024L * 1024L
    }

    /** O teto está estourado por [extra] bytes. Sem limite (`0`), nunca está. */
    fun exceeds(usedBytes: Long, limitBytes: Long, extra: Long): Boolean =
        limitBytes > 0L && usedBytes + extra > limitBytes

    fun limitExceeded(context: Context, extra: Long, fresh: Boolean = false): Boolean =
        exceeds(usedBytes(context, fresh), limitBytes(context), extra)

    fun freeBytes(context: Context): Long =
        runCatching { DownloadStore.dir(context).usableSpace }.getOrDefault(0L)

    /**
     * Cabe [need] bytes com a folga de [MIN_FREE]. `usableSpace` pode dar `0` em alguns file
     * systems; sem um número confiável não bloqueia — quem avisa é a falha do fim.
     */
    fun hasRoomFor(context: Context, need: Long): Boolean {
        val free = freeBytes(context)
        if (free <= 0L) return true
        return free >= need + MIN_FREE
    }

    /** Chamar depois de gravar ou apagar episódio, para o próximo cálculo ser honesto. */
    fun invalidate() {
        podcastCacheBytes = -1L
    }
}
