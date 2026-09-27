package com.pulsa.player.dj

import org.junit.Assert.assertEquals
import org.junit.Test

class MicBackoffTest {

    @Test
    fun a_espera_dobra_a_cada_tentativa() {
        val b = MicBackoff(2000L, 15000L)
        assertEquals(2000L, b.next())
        assertEquals(4000L, b.next())
        assertEquals(8000L, b.next())
    }

    @Test
    fun a_espera_tem_teto() {
        val b = MicBackoff(2000L, 15000L)
        repeat(10) { b.next() }
        assertEquals(15000L, b.next())
        assertEquals(15000L, b.next())
    }

    @Test
    fun falar_de_novo_zera_a_espera() {
        val b = MicBackoff(2000L, 15000L)
        repeat(6) { b.next() }
        b.reset()
        assertEquals(2000L, b.next())
    }

    @Test
    fun o_ciclo_curto_de_3s_nao_volta_mais_sozinho() {
        // O defeito da 5.9.1: silencio devolvia reinicio imediato, e o
        // startListening so esperava o minGap de 3s — microfonte piscando sem parar.
        val b = MicBackoff(2000L, 15000L)
        val esperas = (1..6).map { b.next() }
        // A partir da 2a, nenhuma espera pode ser tao curta quanto o ciclo de 3s
        // que o usuario reclamou.
        assert(esperas.drop(1).none { it <= 3000L }) { "esperas muito curtas: $esperas" }
    }
}
