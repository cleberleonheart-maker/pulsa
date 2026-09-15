package com.pulsa.player.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTest {

    private val sample = """
        [ar:Marilia Mendonca]
        [ti:Fazendo Completo]
        [00:12.34]Era pra ser só mais uma noite
        [00:18.90][00:25.11]Faz o L que é sucesso
        [00:31.40]Cola nessa que é sucesso
    """.trimIndent()

    @Test
    fun parse_ignores_metadata_and_creates_lines_sorted() {
        val lines = Lyrics.parse(sample)
        assertEquals(4, lines.size)
        assertEquals(12_340L, lines[0].timeMs)
        assertEquals(18_900L, lines[1].timeMs)
        assertEquals(25_110L, lines[2].timeMs)
        assertEquals("Faz o L que é sucesso", lines[1].text)
    }

    @Test
    fun parse_sorts_entries_by_time() {
        val out = Lyrics.parse("[00:30]b\n[00:10]a\n[00:20]c")
        assertEquals(listOf("a", "c", "b"), out.map { it.text })
    }

    @Test
    fun lineAt_returns_last_line_before_position() {
        val lines = Lyrics.parse("[00:10]a\n[00:20]b\n[00:30]c")
        assertEquals(0, Lyrics.lineAt(lines, 0))
        assertEquals(0, Lyrics.lineAt(lines, 10_000))
        assertEquals(0, Lyrics.lineAt(lines, 15_000))
        assertEquals(1, Lyrics.lineAt(lines, 25_000))
        assertEquals(2, Lyrics.lineAt(lines, 60_000))
    }

    @Test
    fun lineAt_handles_empty_list() {
        assertEquals(-1, Lyrics.lineAt(emptyList(), 5_000))
    }
}