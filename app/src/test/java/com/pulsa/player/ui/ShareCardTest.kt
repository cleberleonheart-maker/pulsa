package com.pulsa.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A quebra de linha do cartão compartilhável. É a parte do desenho que dá para provar sem
 * aparelho, e é onde o erro apareceria como texto saindo do cartão — então vale mais que um
 * teste de fumaça. O `widthOf` falso mede 1 por caractere, que é o suficiente para exercitar
 * as três regras: caber, quebrar e cortar.
 */
class ShareCardTest {

    private val widthOf: (String) -> Float = { it.length.toFloat() }
    private val space = 1f

    @Test
    fun vazioDevolveListaVazia() {
        assertEquals(emptyList<String>(), ShareCard.breakLines("   ", 10f, 2, widthOf, space))
        assertEquals(emptyList<String>(), ShareCard.breakLines("abc", 10f, 0, widthOf, space))
    }

    @Test
    fun oQueCabeFicaNumaLinha() {
        val lines = ShareCard.breakLines("aa bb", 10f, 3, widthOf, space)
        assertEquals(listOf("aa bb"), lines)
    }

    @Test
    fun quebraPorPalavraQuandoNaoCabe() {
        val lines = ShareCard.breakLines("aa bb cc dd", 5f, 3, widthOf, space)
        assertEquals(listOf("aa bb", "cc dd"), lines)
    }

    @Test
    fun normalizaEspacosEBarrasDeLinha() {
        val lines = ShareCard.breakLines("  aa\n\tbb  ", 10f, 2, widthOf, space)
        assertEquals(listOf("aa bb"), lines)
    }

    @Test
    fun passaDoLimiteGanhaReticencia() {
        // "aa bb cc dd ee" em 2 linhas: perde o "ee" e a última linha ganha "…".
        val lines = ShareCard.breakLines("aa bb cc dd ee", 5f, 2, widthOf, space)
        assertEquals(2, lines.size)
        assertEquals("aa bb", lines[0])
        assertEquals("cc d…", lines[1])
    }

    @Test
    fun palavraGiganteEhCortadaPorCaractere() {
        val lines = ShareCard.breakLines("abcdefghij", 5f, 3, widthOf, space)
        assertEquals(listOf("abcde", "fghij"), lines)
    }

    @Test
    fun retienciaCabeNaLargura() {
        val lines = ShareCard.breakLines("aaaa bbbb cccc", 4f, 1, widthOf, space)
        assertEquals(1, lines.size)
        // "aaaa" + "…" não cabe em 4, então cai para "aaa…".
        assertEquals("aaa…", lines[0])
    }
}
