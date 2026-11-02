package com.pulsa.player.dj

import android.content.Context
import com.pulsa.player.R
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.playback.Playback
import com.pulsa.player.podcast.Chapters
import com.pulsa.player.podcast.PodcastDb

/**
 * Marcadores de podcast por voz — "marca esse momento", "quais marcadores", "apaga o marcador".
 *
 * Um lugar só para os dois dispatchers ([MainVirgin] e [DjSession]): o que muda entre eles é
 * **como** a Virgin fala (idioma da pergunta, voz do DJ), não **o que** ela faz com o banco.
 * Por isso o texto vem pronto de fora (`say`) e a fala também (`speak`).
 *
 * O marcador pendente de confirmação mora aqui, e não em cada dispatcher: um comando novo
 * descarta a pergunta que ficou no ar, e com o estado em dois lugares isso seria fácil de
 * errar (um "sim" apagando a música enquanto a Virgin perguntava do marcador).
 */
object DjBookmark {

    const val MODE_MARK = "mark"
    const val MODE_LIST = "list"
    const val MODE_REMOVE = "remove"

    /** Id do marcador à espera de "sim". */
    @Volatile
    private var pendingRemoval: Long? = null

    /**
     * Executa o comando de marcador no episódio que está tocando.
     *
     * `say` recebe o id da string e os argumentos (o idioma é o da pergunta, como no resto do
     * DJ) e `speak` fala o texto pronto — sempre na main thread.
     */
    fun execute(
        context: Context,
        mode: String,
        title: String?,
        say: (Int, Array<out Any?>) -> String,
        speak: (String) -> Unit
    ) {
        val song = Playback.currentSong
        val episodeId = song?.takeIf { it.isPodcast }?.podcastId
        if (song == null || episodeId == null || episodeId <= 0L) {
            speak(say(R.string.dj_voice_chapter_not, emptyArray()))
            return
        }
        val position = Playback.position
        val app = context.applicationContext
        ThreadPool.post {
            val db = PodcastDb.get(app)
            val text = when (mode) {
                MODE_LIST -> listText(db, episodeId, say)
                MODE_REMOVE -> removeText(db, episodeId, say)
                else -> markText(context, db, episodeId, position, title, say)
            }
            ThreadPool.onUi { speak(text) }
        }
    }

    /**
     * Confirma a remoção pedida por voz. `true` quando havia um marcador esperando — aí o
     * dispatcher não trata o "sim" como confirmação de outra coisa.
     */
    fun confirmRemoval(
        context: Context,
        say: (Int, Array<out Any?>) -> String,
        speak: (String) -> Unit
    ): Boolean {
        val id = pendingRemoval ?: return false
        pendingRemoval = null
        val app = context.applicationContext
        ThreadPool.post {
            runCatching { PodcastDb.get(app).deleteBookmark(id) }
            ThreadPool.onUi { speak(say(R.string.dj_voice_bookmark_removed, emptyArray())) }
        }
        return true
    }

    /** Cancela a remoção pedida por voz. `true` quando havia um marcador esperando. */
    fun cancelRemoval(
        say: (Int, Array<out Any?>) -> String,
        speak: (String) -> Unit
    ): Boolean {
        if (pendingRemoval == null) return false
        pendingRemoval = null
        speak(say(R.string.dj_voice_delete_cancel, emptyArray()))
        return true
    }

    /** A pergunta que ficou no ar vale até o próximo comando: depois disso, some. */
    fun clearPending() {
        pendingRemoval = null
    }

    private fun markText(
        context: Context,
        db: PodcastDb,
        episodeId: Long,
        positionMs: Long,
        title: String?,
        say: (Int, Array<out Any?>) -> String
    ): String {
        val at = positionMs.coerceAtLeast(0L)
        // O título guardado é no idioma do app (é o que a folha de capítulos mostra); o nome
        // falado depois de "como" vem da frase, que já foi normalizada.
        val name = title?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
            ?: context.getString(R.string.bookmark_default_title, Chapters.formatTime(at))
        db.addBookmark(episodeId, at, name)
        return say(R.string.dj_voice_bookmark_saved, arrayOf(Chapters.formatTime(at)))
    }

    private fun listText(
        db: PodcastDb,
        episodeId: Long,
        say: (Int, Array<out Any?>) -> String
    ): String {
        val bookmarks = db.bookmarks(episodeId)
        if (bookmarks.isEmpty()) return say(R.string.dj_voice_bookmark_none, emptyArray())
        val shown = minOf(bookmarks.size, 6)
        val list = bookmarks.take(shown).joinToString("; ") {
            val label = it.title.ifBlank { Chapters.formatTime(it.positionMs) }
            "${Chapters.formatTime(it.positionMs)} $label"
        }
        val extra = if (bookmarks.size > shown) {
            " " + say(R.string.dj_voice_bookmark_more, arrayOf(bookmarks.size - shown))
        } else {
            ""
        }
        return say(R.string.dj_voice_bookmark_list, arrayOf(bookmarks.size, list + extra))
    }

    /**
     * Prepara a remoção: guarda o marcador escolhido e devolve a pergunta.
     *
     * O alvo é o **mais recente** (id maior). "Apaga o marcador" sem dizer qual é não tem como
     * ser adivinhado — e apagar o mais recente é o que a pessoa quer quando marca e desmarca
     * o mesmo ponto. A pergunta diz qual é, e o "sim" de depois é que apaga.
     */
    private fun removeText(
        db: PodcastDb,
        episodeId: Long,
        say: (Int, Array<out Any?>) -> String
    ): String {
        val target = db.bookmarks(episodeId).maxByOrNull { it.id }
            ?: return say(R.string.dj_voice_bookmark_none, emptyArray())
        pendingRemoval = target.id
        val label = target.title.ifBlank { Chapters.formatTime(target.positionMs) }
        return say(
            R.string.dj_voice_bookmark_remove_confirm,
            arrayOf("${Chapters.formatTime(target.positionMs)} $label")
        )
    }
}
