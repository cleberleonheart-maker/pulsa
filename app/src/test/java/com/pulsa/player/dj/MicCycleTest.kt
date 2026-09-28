package com.pulsa.player.dj

import org.junit.Assert.assertEquals
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
        var waited: Long? = 0L
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
    fun `escuta por pedido nunca reabre o microfone, nem depois de ouvir`() {
        val c = cycle(MicMode.ONE_SHOT)
        assertEquals(null, c.waitBeforeReopen(heard = true))
        assertEquals(null, c.waitBeforeReopen(heard = false))
    }

    @Test
    fun `escuta por pedido nunca devolve uma janela sequer, em nenhuma recorrencia`() {
        val c = cycle(MicMode.ONE_SHOT)
        repeat(50) {
            assertEquals(null, c.waitBeforeReopen(heard = it % 2 == 0))
        }
        // Era aqui que morava o bug: o laço abria o microfone sem parar e o ponto laranja
        // ficava aceso enquanto a maos-livres estivesse ligada.
    }
}
