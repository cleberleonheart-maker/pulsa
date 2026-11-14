package com.pulsa.player.podcast

import com.pulsa.player.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A fala da Virgin para cada resposta da transcrição.
 *
 * O mapeamento morava copiado nos dois dispatchers (`MainVirgin` e `DjSession`), e uma cópia
 * pode divergir da outra sem ninguém perceber. Agora é uma função só — e ela é o que garante
 * que o comando de podcast responde a mesma coisa na tela principal e na cabine do DJ.
 */
class PodcastAnswerTest {

    /** Substitui as strings de recurso por um rótulo, para o teste ver o mapeamento e não o texto. */
    private fun resolve(resId: Int, args: List<String>): String =
        "$resId:${args.joinToString("|")}"

    @Test
    fun token_nulo_fala_que_nao_tem_transcricao() {
        assertEquals(
            "${R.string.dj_voice_transcribe_none}:",
            PodcastTranscribe.answerSpeech(null, ::resolve)
        )
    }

    @Test
    fun sem_transcricao_fala_que_nao_tem() {
        assertEquals(
            "${R.string.dj_voice_transcribe_none}:",
            PodcastTranscribe.answerSpeech("transcribe_none", ::resolve)
        )
    }

    @Test
    fun falta_de_chave_fala_que_precisa_da_chave() {
        assertEquals(
            "${R.string.dj_voice_transcribe_need_key}:",
            PodcastTranscribe.answerSpeech("transcribe_need_key", ::resolve)
        )
    }

    @Test
    fun em_andamento_fala_que_esta_pendente() {
        assertEquals(
            "${R.string.dj_voice_transcribe_pending}:",
            PodcastTranscribe.answerSpeech("transcribe_pending", ::resolve)
        )
    }

    @Test
    fun falha_fala_que_falhou() {
        assertEquals(
            "${R.string.dj_voice_transcribe_failed}:",
            PodcastTranscribe.answerSpeech("transcribe_failed", ::resolve)
        )
    }

    @Test
    fun sem_trecho_fala_que_nao_achou() {
        assertEquals(
            "${R.string.dj_voice_transcribe_no_match}:",
            PodcastTranscribe.answerSpeech("transcribe_no_match", ::resolve)
        )
    }

    @Test
    fun offline_manda_o_tempo_e_a_citacao() {
        // 754000 ms = 12m34s. O token leva o tempo em milissegundos porque o "min:seg"
        // tem ":" e a citação também pode ter.
        assertEquals(
            "${R.string.dj_voice_transcribe_offline}:12:34|falaram do bitcoin",
            PodcastTranscribe.answerSpeech("transcribe_offline:754000:falaram do bitcoin", ::resolve)
        )
    }

    @Test
    fun offline_guarda_a_citacao_inteira_com_dois_pontos() {
        // A transcrição costuma ter ":" no meio do trecho ("autor: fulano"). Cortar no
        // ":" perderia o resto da frase — a citação é tudo depois do tempo.
        assertEquals(
            "${R.string.dj_voice_transcribe_offline}:0:45|autor: fulano disse isso",
            PodcastTranscribe.answerSpeech("transcribe_offline:45000:autor: fulano disse isso", ::resolve)
        )
    }

    @Test
    fun offline_sem_tempo_ou_citacao_nao_quebra() {
        assertEquals(
            "${R.string.dj_voice_transcribe_offline}:|",
            PodcastTranscribe.answerSpeech("transcribe_offline:", ::resolve)
        )
    }

    @Test
    fun resposta_do_gemini_vai_literal() {
        assertEquals(
            "Falaram do bitcoin no minuto 12.",
            PodcastTranscribe.answerSpeech("Falaram do bitcoin no minuto 12.", ::resolve)
        )
    }
}
