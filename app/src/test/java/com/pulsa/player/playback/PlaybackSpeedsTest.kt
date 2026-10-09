package com.pulsa.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * F2 — os passos de velocidade e o rótulo do botão.
 *
 * Sem Android: [PlaybackSpeeds] é lista e formatação. O que importa aqui é o
 * **ciclo** (o botão precisa voltar ao 1x, não travar no fim da lista) e o rótulo
 * (é o único feedback que o usuário tem do valor escolhido).
 */
class PlaybackSpeedsTest {

    /** Mesmo cálculo do `Playback.cycleVideoSpeed`, isolado do Android. */
    private fun next(atual: Float): Float {
        val i = PlaybackSpeeds.VALUES.indexOfFirst { abs(it - atual) < 0.01f }
        return PlaybackSpeeds.VALUES[(i + 1).mod(PlaybackSpeeds.VALUES.size)]
    }

    @Test
    fun `o ciclo passa por todos os passos e volta ao inicio`() {
        var v = 1f
        val vistos = mutableListOf(v)
        repeat(PlaybackSpeeds.VALUES.size - 1) {
            v = next(v)
            vistos.add(v)
        }
        // Um passo a mais fecha o ciclo de volta no começo.
        v = next(v)
        assertEquals(1f, v, 0.001f)
        // Nenhum passo se repete antes de fechar.
        assertEquals(PlaybackSpeeds.VALUES.size, vistos.distinct().size)
    }

    @Test
    fun `o 1x fica no meio da lista, nao no fim`() {
        // Botão de acelerar é usado a partir do normal: se o 1x fosse o último, iam
        // ser dois toques para acelerar e cinco para voltar.
        val i = PlaybackSpeeds.VALUES.indexOfFirst { abs(it - 1f) < 0.01f }
        assertTrue("1x na posicao $i", i > 0 && i < PlaybackSpeeds.VALUES.size - 1)
    }

    @Test
    fun `a lista esta dentro dos limites que o botao expoe`() {
        assertEquals(0.5f, PlaybackSpeeds.VALUES.min(), 0.001f)
        assertEquals(2.0f, PlaybackSpeeds.VALUES.max(), 0.001f)
        // Nenhum passo intermediario que o rotulo mostraria como numero estranho.
        PlaybackSpeeds.VALUES.forEach { v ->
            assertTrue("passo $v", v >= PlaybackSpeeds.MIN && v <= PlaybackSpeeds.MAX)
        }
    }

    @Test
    fun `o rotulo nao mostra casa decimal em velocidade inteira`() {
        assertEquals("1x", PlaybackSpeeds.label(1f))
        assertEquals("2x", PlaybackSpeeds.label(2f))
    }

    @Test
    fun `o rotulo mostra uma casa nos passos quebrados`() {
        assertEquals("0.5x", PlaybackSpeeds.label(0.5f))
        assertEquals("0.75x", PlaybackSpeeds.label(0.75f))
        assertEquals("1.25x", PlaybackSpeeds.label(1.25f))
        assertEquals("1.5x", PlaybackSpeeds.label(1.5f))
    }
}
