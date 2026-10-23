package com.pulsa.player.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdSkipTest {

    private val segments = listOf(
        TranscriptSegment(0, 15_000, "Abertura do programa."),
        TranscriptSegment(15_000, 45_000, "Este episódio é trazido por Acme: use o cupom PULSA.", isAd = true),
        TranscriptSegment(45_000, 120_000, "Hoje falaremos de bitcoin.", isAd = false),
        TranscriptSegment(120_000, 150_000, "Um oferecimento de outra marca.", isAd = true),
        TranscriptSegment(150_000, 180_000, "Encerramento.")
    )

    @Test
    fun pula_para_o_fim_do_bloco() {
        assertEquals(45_000L, AdSkip.skipTarget(segments, 20_000))
    }

    @Test
    fun fora_da_propaganda_nao_pula() {
        assertNull(AdSkip.skipTarget(segments, 10_000))
        assertNull(AdSkip.skipTarget(segments, 60_000))
        assertNull(AdSkip.skipTarget(segments, 155_000))
    }

    @Test
    fun no_fim_do_bloco_nao_pula_de_novo() {
        assertNull(AdSkip.skipTarget(segments, 45_000))
        assertFalse(AdSkip.isInsideAd(segments, 150_000))
    }

    @Test
    fun inicio_do_bloco_conta_como_propaganda() {
        assertTrue(AdSkip.isInsideAd(segments, 120_000))
        assertEquals(150_000L, AdSkip.skipTarget(segments, 120_000))
    }

    @Test
    fun proximo_bloco_a_partir_da_posicao() {
        assertEquals(15_000L, AdSkip.nextAdStart(segments, 0))
        assertEquals(120_000L, AdSkip.nextAdStart(segments, 60_000))
        assertNull(AdSkip.nextAdStart(segments, 170_000))
    }

    @Test
    fun bloco_em_varios_trechos_pula_de_uma_vez_so() {
        val block = listOf(
            TranscriptSegment(0, 10_000, "Conteúdo."),
            TranscriptSegment(10_000, 20_000, "Primeiro trecho do patrocínio.", isAd = true),
            TranscriptSegment(20_000, 30_000, "Segundo trecho do patrocínio.", isAd = true),
            TranscriptSegment(31_000, 40_000, "Terceiro trecho do patrocínio.", isAd = true),
            TranscriptSegment(40_000, 50_000, "Voltamos ao programa.")
        )
        assertEquals(40_000L, AdSkip.skipTarget(block, 12_000))
        assertEquals(40_000L, AdSkip.skipTarget(block, 25_000))
    }

    @Test
    fun trechos_separados_nao_viram_um_bloco_so() {
        val apart = listOf(
            TranscriptSegment(10_000, 20_000, "Patrocínio.", isAd = true),
            TranscriptSegment(60_000, 70_000, "Outro patrocínio.", isAd = true)
        )
        assertEquals(20_000L, AdSkip.skipTarget(apart, 10_000))
    }

    @Test
    fun lista_vazia_nunca_pula() {
        assertNull(AdSkip.skipTarget(emptyList(), 0))
        assertFalse(AdSkip.isInsideAd(emptyList(), 0))
        assertNull(AdSkip.nextAdStart(emptyList(), 0))
    }
}
