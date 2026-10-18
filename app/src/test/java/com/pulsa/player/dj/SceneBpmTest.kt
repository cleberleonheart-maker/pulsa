package com.pulsa.player.dj

import com.pulsa.player.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneBpmTest {

    private fun song(
        id: Long,
        durMs: Long = 180_000L,
        path: String = "/music/faixa$id.mp3"
    ) = Song(id, "faixa$id", "artista", "album", 1L, durMs, path, 2020)

    @Test
    fun janelaPorCena() {
        assertEquals(120f..185f, SceneBpm.janela(DjCommander.SCENE_MALHAR))
        assertEquals(100f..160f, SceneBpm.janela(DjCommander.SCENE_DIRIGIR))
        assertEquals(85f..130f, SceneBpm.janela(DjCommander.SCENE_VIAJAR))
        assertEquals(70f..105f, SceneBpm.janela(DjCommander.SCENE_ESTUDAR))
        assertNull(SceneBpm.janela("qualquer coisa"))
    }

    @Test
    fun aplicarSoFiltraQuandoBastamNaJanela() {
        val pool = (1L..10L).map { song(it) }
        // 6 faixas a 130 BPM (na janela da cena "malhar") e 4 a 80 (fora).
        val bpms = (1L..10L).associate { it to if (it <= 6) 130f else 80f }
        val out = SceneBpm.aplicar(pool, bpms, 120f..185f)
        assertEquals(6, out.size)
        assertTrue(out.all { bpms.getValue(it.id) in 120f..185f })
    }

    @Test
    fun aplicarCaiNoPoolInteiroComPoucoConhecido() {
        val pool = (1L..10L).map { song(it) }
        // Só 2 na janela: filtrar deixaria a fila presa nas mesmas 2 para sempre.
        val dois = mapOf(1L to 130f, 2L to 140f)
        assertSame(pool, SceneBpm.aplicar(pool, dois, 120f..185f))
        // Desconhecido não conta; o 0 (inconclusivo) também não — ele é "já analisado"
        // só para o lote pular a faixa, nunca para virar candidata à fila.
        val mistura = mapOf(1L to 130f, 2L to 0f, 3L to 999f)
        assertSame(pool, SceneBpm.aplicar(pool, mistura, 120f..185f))
    }

    @Test
    fun paraAnaliseSoArquivoLocalGrandeEDesconhecido() {
        val conhecidos = mapOf(1L to 120f, 2L to 0f)
        val lote = SceneBpm.paraAnalise(
            listOf(
                song(1),                       // já tem BPM
                song(2),                       // 0 = inconclusivo, carimbado
                song(3),                       // candidata
                song(4, path = "radio:http://fm"), // sem arquivo local
                song(5, path = "stream:http://x"),
                song(6, path = "video:9"),
                song(7, durMs = 8_000),        // curta demais para autocorrelação
                song(8),
                song(9),
                song(10),
            ),
            conhecidos,
            limite = 4
        )
        assertEquals(listOf(3L, 8L, 9L, 10L), lote.map { it.id })
    }

    @Test
    fun paraAnaliseRespeitaOLimiteDoLote() {
        val pool = (1L..50L).map { song(it) }
        assertEquals(SceneBpm.LOTE, SceneBpm.paraAnalise(pool, emptyMap(), SceneBpm.LOTE).size)
    }
}
