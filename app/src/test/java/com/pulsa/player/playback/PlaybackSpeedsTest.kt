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

    /** F5: o passo público substitui a conta privada que a tela fazia. */
    private fun next(atual: Float): Float = PlaybackSpeeds.step(atual, up = true)

    @Test
    fun `subir passa por todos os passos e volta ao inicio`() {
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
    fun `descer roda ao contrario e fecha no fim`() {
        assertEquals(0.75f, PlaybackSpeeds.step(1f, up = false), 0.001f)
        assertEquals(0.5f, PlaybackSpeeds.step(0.75f, up = false), 0.001f)
        // O fundo da lista desce de volta para o topo, sem travar.
        assertEquals(2.0f, PlaybackSpeeds.step(0.5f, up = false), 0.001f)
    }

    @Test
    fun `subindo do teto volta ao chao`() {
        assertEquals(0.5f, PlaybackSpeeds.step(2.0f, up = true), 0.001f)
    }

    @Test
    fun `valor fora da lista parte do normal`() {
        // Um 1.1 que não é passo (veio de onde for) anda a partir do 1x, sem cravar.
        assertEquals(1.25f, PlaybackSpeeds.step(1.1f, up = true), 0.001f)
        assertEquals(0.75f, PlaybackSpeeds.step(1.1f, up = false), 0.001f)
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

    @Test
    fun `a fala da Virgin usa palavra em vez do numero cru`() {
        assertEquals("meio", PlaybackSpeeds.speech(0.5f))
        assertEquals("tres quartos", PlaybackSpeeds.speech(0.75f))
        assertEquals("normal", PlaybackSpeeds.speech(1f))
        assertEquals("um e um quarto", PlaybackSpeeds.speech(1.25f))
        assertEquals("um e meio", PlaybackSpeeds.speech(1.5f))
        assertEquals("o dobro", PlaybackSpeeds.speech(2f))
    }

    @Test
    fun `fala de valor fora da lista cai no rotulo`() {
        assertEquals("1.1x", PlaybackSpeeds.speech(1.1f))
    }
}
