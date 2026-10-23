package com.pulsa.player.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastTranscriptTest {

    private val segments = listOf(
        TranscriptSegment(0, 20_000, "Abertura do episódio e recados do patrocinador."),
        TranscriptSegment(20_000, 45_000, "Hoje a gente vai falar sobre bitcoin e a volatilidade do mercado."),
        TranscriptSegment(45_000, 70_000, "O bitcoin caiu forte depois do anúncio, mas recuperou parte da queda."),
        TranscriptSegment(70_000, 95_000, "Mudando de assunto, o novo disco da banda sai em março."),
        TranscriptSegment(95_000, 120_000, "A IA generativa mudou o mercado de trabalho.")
    )

    @Test
    fun encontra_trechos_por_palavra() {
        val found = PodcastTranscriptParser.findRelevant(segments, "o que falaram sobre bitcoin?")
        assertEquals(2, found.size)
        assertTrue(found.all { it.text.contains("bitcoin") })
        assertTrue(found[0].startMs < found[1].startMs)
    }

    @Test
    fun ignora_acentos_e_caixa() {
        val found = PodcastTranscriptParser.findRelevant(segments, "VOLATILIDADE")
        assertEquals(1, found.size)
        assertEquals(20_000, found[0].startMs)
    }

    @Test
    fun tema_curto_como_ia_funciona() {
        val found = PodcastTranscriptParser.findRelevant(segments, "o que falaram sobre IA nesse episódio?")
        assertEquals(1, found.size)
        assertEquals(95_000, found[0].startMs)
    }

    @Test
    fun frase_sem_tema_nao_busca_por_palavra_vazia() {
        val found = PodcastTranscriptParser.findRelevant(segments, "o que falaram nesse episódio?")
        assertTrue(found.isEmpty())
    }

    @Test
    fun sem_palavra_relevante_devolve_vazio() {
        assertTrue(PodcastTranscriptParser.findRelevant(segments, "culinária japonesa").isEmpty())
    }

    @Test
    fun consulta_vazia_devolve_vazio() {
        assertTrue(PodcastTranscriptParser.findRelevant(segments, "   ").isEmpty())
    }

    @Test
    fun formata_tempo_em_minuto_e_segundo() {
        assertEquals("0:00", PodcastTranscriptParser.formatTime(0))
        assertEquals("0:45", PodcastTranscriptParser.formatTime(45_000))
        assertEquals("2:05", PodcastTranscriptParser.formatTime(125_000))
    }

    @Test
    fun flag_de_propaganda_sobrevive_ao_json() {
        val withAd = listOf(
            TranscriptSegment(0, 10_000, "Conteúdo."),
            TranscriptSegment(10_000, 40_000, "Patrocínio: cupom PULSA.", isAd = true)
        )
        val back = PodcastTranscriptParser.fromJson(PodcastTranscriptParser.toJson(withAd))
        assertEquals(withAd, back)
        assertTrue(back[1].isAd)
        assertFalse(back[0].isAd)
    }

    @Test
    fun busca_nao_cita_propaganda() {
        val withAd = listOf(
            TranscriptSegment(0, 10_000, "O patrocinador fala de bitcoin e cupom.", isAd = true),
            TranscriptSegment(10_000, 30_000, "Agora sim: bitcoin e volatilidade.")
        )
        val found = PodcastTranscriptParser.findRelevant(withAd, "o que falaram sobre bitcoin?")
        assertEquals(1, found.size)
        assertEquals(10_000, found[0].startMs)
    }

    @Test
    fun json_ida_e_volta() {
        val json = PodcastTranscriptParser.toJson(segments)
        val back = PodcastTranscriptParser.fromJson(json)
        assertEquals(segments, back)
    }

    @Test
    fun json_invalido_nao_quebra() {
        assertTrue(PodcastTranscriptParser.fromJson("nada aqui").isEmpty())
        assertTrue(PodcastTranscriptParser.fromJson("[]").isEmpty())
    }
}
