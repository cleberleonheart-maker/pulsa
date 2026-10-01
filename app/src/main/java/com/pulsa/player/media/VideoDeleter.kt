package com.pulsa.player.media

import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.model.Video

/**
 * Exclusão de vídeo do MediaStore.
 *
 * Irmão do [MusicDeleter] e separado dele de propósito: os ids de vídeo e de música são
 * números de coleções **diferentes** e podem colidir — o `MediaStore` numera cada
 * coleção por conta própria, então o vídeo de id 42 e a música de id 42 são coisas
 * distintas. Passar um `Song` para cá construiria uma URI de áudio apontando para um id
 * de vídeo, e o "apagar" não apagaria nada (ou pior, apagaria a música com aquele id).
 *
 * Não há limpeza de playlist: vídeo não entra em playlist nem em favorito.
 */
object VideoDeleter {

    private const val TAG = "PulsaDelete"
    private const val CHUNK = 150

    private var launcher: ((IntentSender) -> Unit)? = null
    private var pending: Pending? = null

    private data class Pending(val onDone: (MusicDeleter.Outcome) -> Unit)

    /** Registrado pelo `MainActivity` no `onCreate`, junto com o do [MusicDeleter]. */
    fun attachLauncher(launch: (IntentSender) -> Unit) {
        launcher = launch
    }

    fun uriFor(video: Video) = VideoLibrary.contentUri(video.id)

    fun delete(context: Context, video: Video, onDone: (MusicDeleter.Outcome) -> Unit) =
        delete(context, listOf(video), onDone)

    /** Apaga vários vídeos. Mesmo contrato do [MusicDeleter.delete] em lote. */
    fun delete(context: Context, videos: List<Video>, onDone: (MusicDeleter.Outcome) -> Unit) {
        if (videos.isEmpty()) {
            onDone(MusicDeleter.Outcome.Deleted)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            deleteDirect(context, videos, onDone)
            return
        }
        val launch = launcher
        if (launch == null) {
            Log.w(TAG, "vídeo: sem launcher registrado; delete direto de ${videos.size} itens")
            deleteDirect(context, videos, onDone)
            return
        }
        if (videos.size <= CHUNK) {
            askConsent(context, videos, onDone)
            return
        }
        val queue = ArrayDeque(videos.chunked(CHUNK))
        fun next() {
            val part = queue.removeFirstOrNull()
            if (part == null) {
                onDone(MusicDeleter.Outcome.Deleted)
                return
            }
            askConsent(context, part) { outcome ->
                if (outcome is MusicDeleter.Outcome.Deleted) next() else onDone(outcome)
            }
        }
        next()
    }

    /** Resultado do diálogo de consentimento do sistema. */
    fun onDeleteRequestResult(granted: Boolean) {
        val p = pending ?: return
        pending = null
        p.onDone(if (granted) MusicDeleter.Outcome.Deleted else MusicDeleter.Outcome.Cancelled)
    }

    private fun askConsent(context: Context, videos: List<Video>, onDone: (MusicDeleter.Outcome) -> Unit) {
        val uris = videos.map { uriFor(it) }
        val consent = runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, uris)
        }.onFailure {
            Log.w(TAG, "vídeo: createDeleteRequest falhou para ${uris.size} itens", it)
        }.getOrNull()
        val launch = launcher
        if (consent == null || launch == null) {
            Log.w(TAG, "vídeo: sem consentimento; delete direto")
            deleteDirect(context, videos, onDone)
            return
        }
        pending = Pending(onDone)
        runCatching { launch(consent.intentSender) }.onFailure {
            Log.w(TAG, "vídeo: falha ao lançar o consentimento", it)
            pending = null
            onDone(MusicDeleter.Outcome.Failed(it.message ?: "launcher"))
        }
    }

    private fun deleteDirect(context: Context, videos: List<Video>, onDone: (MusicDeleter.Outcome) -> Unit) {
        val app = context.applicationContext
        val items = videos.distinctBy { it.id }
        ThreadPool.post {
            var removed = 0
            var firstError: String? = null
            for (video in items) {
                val uri = uriFor(video)
                try {
                    if (app.contentResolver.delete(uri, null, null) > 0) {
                        removed++
                    } else if (firstError == null) {
                        Log.w(TAG, "vídeo: delete direto de $uri não removeu nada")
                        firstError = "direct-delete-noop"
                    }
                } catch (t: Throwable) {
                    if (firstError == null) {
                        Log.w(TAG, "vídeo: delete direto de $uri lançou ${t.javaClass.simpleName}", t)
                        firstError = t.javaClass.simpleName
                    }
                }
            }
            val outcome = when {
                removed == items.size -> MusicDeleter.Outcome.Deleted
                removed > 0 -> {
                    Log.w(TAG, "vídeo: lote parcial: $removed de ${items.size}")
                    MusicDeleter.Outcome.Failed("partial:$removed/${items.size}")
                }
                else -> MusicDeleter.Outcome.Failed(firstError ?: "direct-delete-failed")
            }
            ThreadPool.onUi { onDone(outcome) }
        }
    }
}
