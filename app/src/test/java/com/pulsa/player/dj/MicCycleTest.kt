package com.pulsa.player.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A regra de quando reabrir o microfone.
 *
 * Acompanhar o bug: o laço era infinito e ninguem viu, porque a espera vivia dentro de
 * `onResults`/`onError` e nao tinha teste. Aqui a regra e uma funcao pura, entao este arquivo
 * trava o comportamento que o usuario reclamou — microfone piscando sem parar.
 */
class MicCycleTest {

    private fun cycle(mode: MicMode) = MicCycle(mode, MicBackoff(2000L, 15000L))

    @Test
    fun `botao de microfone reabre na hora depois de ouvir`() {
        val c = cycle(MicMode.CONTINUOUS)
        assertEquals(0L, c.waitBeforeReopen(heard = true))
    }

    @Test
    fun `botao de microfone espera mais a cada silencio`() {
        val c = cycle(MicMode.CONTINUOUS)
        assertEquals(2000L, c.waitBeforeReopen(heard = false))
        assertEquals(4000L, c.waitBeforeReopen(heard = false))
        assertEquals(8000L, c.waitBeforeReopen(heard = false))
    }

    @Test
    fun `botao de microfone nunca espera mais que o teto`() {
        val c = cycle(MicMode.CONTINUOUS)
        var waited = 0L
        repeat(10) { waited = c.waitBeforeReopen(heard = false) }
        assertEquals(15000L, waited)
    }

    @Test
    fun `ouvir alguem volta a espera ao comeco`() {
        val c = cycle(MicMode.CONTINUOUS)
        c.waitBeforeReopen(heard = false)
        c.waitBeforeReopen(heard = false)
        c.reset()
        assertEquals(2000L, c.waitBeforeReopen(heard = false))
    }

    @Test
    fun `maos-livres sempre fecha o microfone, mesmo ouvindo a palavra`() {
        val c = cycle(MicMode.WORD_WATCH)
        assertEquals(MicMode.DUTY_IDLE_MS, c.waitBeforeReopen(heard = true))
        assertEquals(MicMode.DUTY_IDLE_MS, c.waitBeforeReopen(heard = false))
    }

    @Test
    fun `maos-livres nao abre o microfone em sequencia, nunca`() {
        val c = cycle(MicMode.WORD_WATCH)
        var menor = Long.MAX_VALUE
        repeat(50) { menor = minOf(menor, c.waitBeforeReopen(heard = it % 2 == 0)) }
        // Com a janela de escuta de ~1,2-2,5s e o intervalo de 13s, o microfone fica
        // aberto ~10% do tempo. O laço antigo abria sem parar; este e o que impede.
        assertEquals(MicMode.DUTY_IDLE_MS, menor)
        assertTrue(
            "microfone aberto o tempo todo de novo",
            MicMode.DUTY_IDLE_MS > (1200L + 2500L) * 2
        )
    }
}
