package com.pulsa.player.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class KaraokeTest {

    @Test
    fun words_offsetsCobremSoAsPalavras() {
        val ws = Karaoke.words("  nada   a gente  ")
        assertEquals(3, ws.size)
        assertEquals("nada", "  nada   a gente  ".substring(ws[0].start, ws[0].end))
        assertEquals("a", "  nada   a gente  ".substring(ws[1].start, ws[1].end))
        assertEquals("gente", "  nada   a gente  ".substring(ws[2].start, ws[2].end))
    }

    @Test
    fun words_textoVazio() {
        assertEquals(0, Karaoke.words("   ").size)
        assertEquals(0, Karaoke.words("").size)
    }

    @Test
    fun fractionFor_antesDentroDepois() {
        assertEquals(0f, Karaoke.fractionFor(500L, 1000L, 2000L), 0.0001f)
        assertEquals(0.5f, Karaoke.fractionFor(1500L, 1000L, 2000L), 0.0001f)
        assertEquals(1f, Karaoke.fractionFor(2500L, 1000L, 2000L), 0.0001f)
    }

    @Test
    fun fractionFor_spanNulo() {
        assertEquals(0f, Karaoke.fractionFor(999L, 1000L, 1000L), 0.0001f)
        assertEquals(1f, Karaoke.fractionFor(1000L, 1000L, 1000L), 0.0001f)
    }

    @Test
    fun sungWords_avancaPalavraAPalavra() {
        val text = "hoje eu vou dançar"
        assertEquals(0, Karaoke.sungWords(text, 0f))
        assertEquals(1, Karaoke.sungWords(text, 0.01f))
        assertEquals(2, Karaoke.sungWords(text, 0.5f))
        assertEquals(4, Karaoke.sungWords(text, 1f))
    }

    @Test
    fun highlightEnd_achaOFimDaPalavraCantada() {
        val text = "hoje eu vou"
        assertEquals(0, Karaoke.highlightEnd(text, 0f))
        assertEquals(4, Karaoke.highlightEnd(text, 0.2f))
        assertEquals(7, Karaoke.highlightEnd(text, 0.5f))
        assertEquals(text.length, Karaoke.highlightEnd(text, 1f))
    }

    @Test
    fun highlightEnd_linhaEmBranco() {
        assertEquals(0, Karaoke.highlightEnd("   ", 1f))
    }
}
