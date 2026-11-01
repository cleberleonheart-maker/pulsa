package com.pulsa.player.podcast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.pulsa.player.work.PulsaWork

/**
 * O caminho comum dos downloads automáticos de episódio.
 *
 * Existe para o prefetch da fila e o auto-download de novos episódios não repetirem as mesmas
 * guardas (já baixado, vídeo, URL que não é HTTP) nem a forma de enfileirar. O
 * [com.pulsa.player.work.DownloadWorker] grava o `file_path` pelo id do episódio, então quem
 * chama não precisa estar vivo quando o job termina.
 */
object PodcastDownloads {

    /**
     * Enfileira um episódio para download no Wi‑Fi.
     *
     * O `onFinished` é opcional e serve só para quem precisa saber de uma falha (o prefetch
     * libera a chance de tentar de novo). O sucesso não depende dele: o worker já persistiu o
     * caminho.
     */
    fun enqueue(context: Context, episode: Episode, onFinished: ((Boolean) -> Unit)? = null) {
        if (episode.id <= 0L || episode.downloaded || episode.isVideo ||
            !episode.audioUrl.startsWith("http")
        ) {
            onFinished?.invoke(false)
            return
        }
        val app = context.applicationContext
        val job = PulsaWork.prefetch(
            app, episode.audioUrl, "podcast-ep${episode.id}", episode.id
        )
        if (job == null) {
            onFinished?.invoke(false)
            return
        }
        if (onFinished != null) {
            PulsaWork.watchDownload(app, job) { ok, _ -> onFinished(ok) }
        }
    }

    /**
     * A rede atual é Wi‑Fi/Ethernet (não medida).
     *
     * O job já tem a constraint `UNMETERED`; esta checagem é para o prefetch não enfileirar
     * um "se der" que pode ficar parado até o Wi‑Fi voltar. O auto-download de novos episódios
     * enfileira mesmo no celular de propósito: a constraint segura o job até uma rede não
     * medida, e assim o episódio não se perde por o sync ter rodado nos dados móveis.
     */
    internal fun unmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
