package com.pulsa.player.media.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A2 — parse do cabeçalho `Range`. Cada caso é um jeito real de a Roku Media Player pedir
 * seek; errar aqui é a TV travar no minuto 3 ou recarregar o arquivo inteiro.
 */
class HttpRangeTest {

    @Test
    fun semRangeDevolveNull() {
        assertNull(HttpRange.parse(null, 1000))
        assertNull(HttpRange.parse("", 1000))
    }

    @Test
    fun intervaloFechado() {
        val spec = HttpRange.parse("bytes=0-499", 1000)
        assertEquals(0L, spec!!.start)
        assertEquals(499L, spec.endInclusive)
        assertEquals(500L, spec.length)
    }

    @Test
    fun doMeioAteOFim() {
        val spec = HttpRange.parse("bytes=500-", 1000)
        assertEquals(500L, spec!!.start)
        assertEquals(999L, spec.endInclusive)
        assertEquals(500L, spec.length)
    }

    @Test
    fun sufixoUltimosBytes() {
        val spec = HttpRange.parse("bytes=-200", 1000)
        assertEquals(800L, spec!!.start)
        assertEquals(999L, spec.endInclusive)
    }

    @Test
    fun sufixoMaiorQueArquivoDevolveInteiro() {
        val spec = HttpRange.parse("bytes=-5000", 1000)
        assertEquals(0L, spec!!.start)
        assertEquals(999L, spec.endInclusive)
    }

    @Test
    fun fimAlemDoTamanhoCortaNoFimReal() {
        // O RFC manda servir o que existe, não erro.
        val spec = HttpRange.parse("bytes=900-99999", 1000)
        assertEquals(900L, spec!!.start)
        assertEquals(999L, spec.endInclusive)
    }

    @Test
    fun inicioAlemDoTamanhoDevolveNull() {
        assertNull(HttpRange.parse("bytes=1000-", 1000))
        assertNull(HttpRange.parse("bytes=2000-3000", 1000))
    }

    @Test
    fun inicioMaiorQueFimDevolveNull() {
        assertNull(HttpRange.parse("bytes=500-100", 1000))
    }

    @Test
    fun multiplosIntervalosDevolveNull() {
        // RFC: responder 200 com o arquivo inteiro; fingir que o primeiro é o único
        // quebra o Content-Range.
        assertNull(HttpRange.parse("bytes=0-1,5-6", 1000))
    }

    @Test
    fun LixoNaoEstoura() {
        assertNull(HttpRange.parse("bytes=abc-def", 1000))
        assertNull(HttpRange.parse("bytes=", 1000))
        assertNull(HttpRange.parse("bits=0-1", 1000))
    }

    @Test
    fun totalZeroDevolveNull() {
        assertNull(HttpRange.parse("bytes=0-10", 0))
    }

    @Test
    fun primeiroByteDeUmArquivoDeUmByte() {
        val spec = HttpRange.parse("bytes=0-0", 1)
        assertEquals(0L, spec!!.start)
        assertEquals(0L, spec.endInclusive)
        assertEquals(1L, spec.length)
    }
}
