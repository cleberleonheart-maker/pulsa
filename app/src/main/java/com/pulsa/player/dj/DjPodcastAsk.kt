package com.pulsa.player.dj

import android.content.Context
import com.pulsa.player.R
import com.pulsa.player.core.CrashLogger
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.podcast.PodcastDb
import com.pulsa.player.podcast.PodcastTranscribe
import com.pulsa.player.playback.Playback

/**
 * O comando "pergunta sobre o podcast", nos dois caminhos da Virgin.
 *
 * Havia o mesmo código duas vezes — na sessão do DJ ([DjSession]) e na tela de perguntas
 * ([MainVirgin]) — e as duas cópias podiam divergir sem ninguém perceber: a correção de uma
 * não chegava na outra. Aqui mora o caminho do dado uma vez só.
 *
 * A voz continua sendo de cada lado: o idioma da resposta ([resolver]) e quem fala
 * ([falar]) são diferentes nas duas telas. O que é comum é o resto.
 */
internal object DjPodcastAsk {

    /**
     * Responde, falando, à pergunta sobre o episódio que está tocando.
     *
     * Ler o episódio é query de Room, e o comando chega na main thread (o `SpeechRecognizer`
     * entrega assim) — ler o banco ali derrubava o app. Por isso a leitura sai para o pool,
     * e a resposta volta na main thread.
     */
    fun ask(
        app: Context,
        question: String,
        resolver: (Int, List<String>) -> String,
        falar: (String) -> Unit
    ) {
        val song = Playback.currentSong
        if (song == null || !song.isPodcast) {
            falar(resolver(R.string.dj_voice_chapter_not, emptyList()))
            return
        }
        val pid = song.podcastId
        if (pid == null) {
            falar(resolver(R.string.dj_voice_transcribe_none, emptyList()))
            return
        }
        ThreadPool.post {
            val ep = runCatching {
                PodcastDb.get(app).getEpisodeById(pid)
            }.getOrElse {
                CrashLogger.writeLog(app, "DJ: pergunta sobre podcast falhou id=$pid -> $it")
                null
            }
            if (ep == null) {
                ThreadPool.onUi { falar(resolver(R.string.dj_voice_transcribe_none, emptyList())) }
                return@post
            }
            PodcastTranscribe.askAboutEpisode(app, ep, question) { ans ->
                falar(
                    PodcastTranscribe.answerSpeech(ans) { resId, args ->
                        resolver(resId, args)
                    }
                )
            }
        }
    }
}
