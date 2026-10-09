package com.pulsa.player.media

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.model.Song

/**
 * Exclusão de música do MediaStore.
 *
 * Do Android 11 em diante o app não apaga direto arquivo que não criou: o
 * `contentResolver.delete` devolve `SecurityException`. A saída correta é
 * `MediaStore.createDeleteRequest`, que abre a confirmação do sistema — o mesmo caminho
 * que a Virgin já usava, e que por isso funcionava por voz e falhava pelo menu.
 *
 * O pedido de consentimento precisa de uma Activity para lançar o `IntentSender`, e
 * `registerForActivityResult` só pode ser chamado antes do `STARTED`. Como o `SongActions`
 * é um `object` sem Activity, o `MainActivity` registra o launcher e o entrega aqui.
 *
 * Duas coisas que esta versão conserta em relação à anterior:
 *
 * 1. **A mensagem mentia.** Tudo caía em "permissão negada", inclusive quando o motivo era
 *    o usuário ter cancelado o diálogo do sistema, ou o app não ter launcher registrado
 *    porque a tela de exclusão foi aberta de um contexto sem Activity. Agora cada caso é
 *    distinguído e o `why` vai para o log.
 * 2. **Só havia um caminho.** Se o `createDeleteRequest` não pode ser construído, a
 *    exclusão morria ali. Agora ela cai para o `delete` direto, que é o caminho correto
 *    quando o app é dono do arquivo (o que vale para o que o próprio app baixou).
 */
object MusicDeleter {

    private const val TAG = "PulsaDelete"

    private var launcher: ((IntentSender) -> Unit)? = null
    private var pending: Pending? = null

    private data class Pending(val songs: List<Song>, val context: Context, val onDone: (Outcome) -> Unit)

    /**
     * Quantos itens por pedido de consentimento.
     *
     * `createDeleteRequest` aceita a lista toda de uma vez, mas cada item vira uma entrada
     * na tela de confirmação do sistema. Acima disso a tela fica com centenas de linhas
     * ilegíveis, então o lote é partido e a pessoa confirma em partes.
     */
    private const val CHUNK = 150

    /**
     * Como a exclusão terminou.
     *
     * [Cancelled] não é erro: o usuário viu o diálogo do sistema e disse que não. Tratar
     * isso como falha é o que fazia ele achar que o app estava com defeito.
     */
    sealed interface Outcome {
        object Deleted : Outcome
        object Cancelled : Outcome
        data class Failed(val why: String) : Outcome
    }

    /** Registrado pelo `MainActivity` no `onCreate`. */
    fun attachLauncher(launch: (IntentSender) -> Unit) {
        launcher = launch
    }

    fun uriFor(song: Song) = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)

    /** Atalho para uma música só — é o que o menu da faixa usa. */
    fun delete(context: Context, song: Song, onDone: (Outcome) -> Unit) =
        delete(context, listOf(song), onDone)

    /**
     * Apaga várias músicas, pedindo o consentimento do sistema quando precisa.
     *
     * O pedido vai em lote: `createDeleteRequest` aceita a lista inteira de URIs de uma
     * vez, e abrir um diálogo por música seria o caminho mais lento e ainda assim o mesmo
     * resultado. Lotes grandes são partidos em [CHUNK] e confirmados em sequência, senão a
     * tela de confirmação do sistema vira uma lista de centenas de linhas ilegível.
     *
     * [onDone] responde uma vez, quando o **último** pedaço termina.
     */
    fun delete(context: Context, songs: List<Song>, onDone: (Outcome) -> Unit) {
        if (songs.isEmpty()) {
            onDone(Outcome.Deleted)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            deleteDirect(context, songs, onDone)
            return
        }
        val launch = launcher
        if (launch == null) {
            // Sem Activity não dá para mostrar o consentimento do sistema. Ainda tenta o
            // caminho direto, que resolve quando o app é dono do arquivo.
            Log.w(TAG, "sem launcher registrado; caindo para o delete direto de ${songs.size} itens")
            deleteDirect(context, songs, onDone)
            return
        }
        if (songs.size <= CHUNK) {
            askConsent(context, songs, onDone)
            return
        }

        val queue = ArrayDeque(songs.chunked(CHUNK))
        fun next() {
            val part = queue.removeFirstOrNull()
            if (part == null) {
                onDone(Outcome.Deleted)
                return
            }
            askConsent(context, part) { outcome ->
                if (outcome is Outcome.Cancelled || outcome is Outcome.Failed) {
                    onDone(outcome)
                } else {
                    next()
                }
            }
        }
        next()
    }

    /** Um pedido de consentimento do sistema para um lote. */
    private fun askConsent(context: Context, songs: List<Song>, onDone: (Outcome) -> Unit) {
        val uris = songs.map { uriFor(it) }
        // `createDeleteRequest` devolve PendingIntent, e o launcher quer IntentSender.
        // O PendingIntent real herda de IntentSender, mas isso não aparece no android.jar
        // (lá ele só declara `implements Parcelable`), então a conversão é por `.intentSender`.
        val consent = runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, uris)
        }.onFailure {
            Log.w(TAG, "createDeleteRequest falhou para ${uris.size} itens", it)
        }.getOrNull()
        if (consent == null) {
            Log.w(TAG, "sem PendingIntent de consentimento; caindo para o delete direto")
            deleteDirect(context, songs, onDone)
            return
        }
        val launch = launcher
        if (launch == null) {
            deleteDirect(context, songs, onDone)
            return
        }
        // Guarda antes de lançar: o callback do launcher chega depois deste retorno.
        pending = Pending(songs, context.applicationContext, onDone)
        runCatching { launch(consent.intentSender) }.onFailure {
            Log.w(TAG, "falha ao lançar o diálogo de consentimento", it)
            pending = null
            onDone(Outcome.Failed(it.message ?: "launcher"))
        }
    }

    /** Resultado do diálogo de consentimento do sistema. */
    fun onDeleteRequestResult(granted: Boolean) {
        val p = pending ?: return
        pending = null
        if (!granted) {
            p.onDone(Outcome.Cancelled)
            return
        }
        // O usuário confirmou e o MediaStore já removeu. Falta limpar os rastros internos.
        ThreadPool.post {
            p.songs.forEach { cleanup(p.context, it) }
            ThreadPool.onUi { p.onDone(Outcome.Deleted) }
        }
    }

    /**
     * Caminho direto: Android 10 e abaixo, onde ele é sempre permitido, e Android 11+ quando
     * o app é dono do arquivo.
     *
     * No resto do Android 11+ cai em `SecurityException`, e aí a mensagem precisa dizer isso
     * em vez de "permissão negada" genérico.
     */
    private fun deleteDirect(context: Context, songs: List<Song>, onDone: (Outcome) -> Unit) {
        val app = context.applicationContext
        val items = songs.distinctBy { it.id }
        ThreadPool.post {
            var removed = 0
            var firstError: String? = null
            for (song in items) {
                val uri = uriFor(song)
                try {
                    if (app.contentResolver.delete(uri, null, null) > 0) {
                        cleanup(app, song)
                        removed++
                    } else if (firstError == null) {
                        // Não lançou e não removeu: sem permissão, ou o item já não existe.
                        Log.w(TAG, "delete direto de $uri não removeu nada")
                        firstError = "direct-delete-noop"
                    }
                } catch (t: Throwable) {
                    // Um item protegido não pode derrubar o resto do lote: a pessoa marcou
                    // 50 músicas e perderia as 50 por causa de uma.
                    if (firstError == null) {
                        Log.w(TAG, "delete direto de $uri lançou ${t.javaClass.simpleName}", t)
                        firstError = t.javaClass.simpleName
                    }
                }
            }
            val outcome = when {
                removed == items.size -> Outcome.Deleted
                removed > 0 -> {
                    // Parcial: some o que saiu, mas avisa que não foi tudo.
                    Log.w(TAG, "lote parcial: $removed de ${items.size} removidos")
                    Outcome.Failed("partial:$removed/${items.size}")
                }
                else -> Outcome.Failed(firstError ?: "direct-delete-failed")
            }
            ThreadPool.onUi { onDone(outcome) }
        }
    }

    private fun cleanup(context: Context, song: Song) {
        runCatching {
            val db = PlaylistDb.get(context)
            db.removeSongFromAll(song.id)
            db.removeFavorite(song.id)
        }
    }
}
