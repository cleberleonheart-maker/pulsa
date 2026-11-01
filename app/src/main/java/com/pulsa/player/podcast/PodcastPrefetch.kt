package com.pulsa.player.podcast

import android.content.Context
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.model.Song
import java.util.concurrent.atomic.AtomicLong

/**
 * O "prefetch" da fila de podcast: enquanto um episódio toca, deixa o **próximo** já baixado.
 *
 * A música é local e o ExoPlayer já pré-carrega o item seguinte da playlist, então não há o que
 * adiantar ali. O podcast é o caso em que o buffer aparece de verdade: o episódio seguinte
 * vem por URL, e trocar para ele ainda em streaming custa a pausa do "carregando". Baixando o
 * arquivo com antecedência, a virada já abre do disco — e o episódio fica no offline de brinde.
 *
 * Só age quando [Settings.podcastPrefetch] está ligado e a rede é **não medida** (Wi-Fi/
 * Ethernet). Baixar sozinho no plano de dados seria surpresa caro, então o celular nunca
 * dispara isto.
 */
object PodcastPrefetch {

    /** O último episódio que já foi pedido, para a troca de faixa não reenfileirar o mesmo. */
    private val requested = AtomicLong(0L)

    /**
     * Decide e dispara o prefetch, se fizer sentido para ([current], [next]).
     *
     * Barato o bastante para rodar a cada troca de faixa: só as guardas de estado na memória
     * rodam no chamador; o banco e o WorkManager ficam no [ThreadPool].
     */
    fun maybePrefetch(context: Context, current: Song?, next: Song?) {
        val app = context.applicationContext
        val epId = next?.podcastId ?: 0L
        val already = requested.get() == epId
        if (!shouldPrefetch(
                currentIsPodcast = current?.isPodcast == true,
                enabled = Settings.podcastPrefetch(app),
                episodeId = epId,
                alreadyRequested = already,
                unmetered = PodcastDownloads.unmetered(app)
            )
        ) return
        requested.set(epId)
        ThreadPool.post {
            val ep = PodcastDb.get(app).episode(epId) ?: return@post
            // Já baixado, é vídeo (a tela cuida do download sozinha) ou nem tem URL de áudio.
            if (ep.downloaded || ep.isVideo || !ep.audioUrl.startsWith("http")) return@post
            PodcastDownloads.enqueue(app, ep) { ok ->
                // O `compareAndSet` devolve a chance: se o usuário voltar a este episódio
                // mais tarde, a próxima troca de faixa tenta de novo em vez de ficar presa
                // no "já pedi" de uma tentativa que falhou.
                if (!ok) requested.compareAndSet(ep.id, 0L)
            }
        }
    }

    /**
     * A decisão pura do prefetch, separada do Android para poder ser testada.
     *
     * Tudo tem que valer: vir de um episódio de podcast, a opção estar ligada, haver um
     * episódio seguinte com id, não ser um repeteco do que já foi pedido e a rede ser
     * não medida. Qualquer um destes "não" desliga o download.
     */
    internal fun shouldPrefetch(
        currentIsPodcast: Boolean,
        enabled: Boolean,
        episodeId: Long,
        alreadyRequested: Boolean,
        unmetered: Boolean
    ): Boolean = currentIsPodcast && enabled && episodeId > 0L && !alreadyRequested && unmetered
}
