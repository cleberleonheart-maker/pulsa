package com.pulsa.player.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastBookmarksTest {

    @Test
    fun junta_capitulos_e_marcadores_na_ordem_do_tempo() {
        val rows = PodcastBookmarks.rows(
            chapters = listOf(Chapter(0L, "Abertura"), Chapter(600_000L, "Entrevista")),
            bookmarks = listOf(
                Bookmark(id = 7L, positionMs = 300_000L, title = "Frase boa"),
                Bookmark(id = 9L, positionMs = 900_000L, title = "Voltar aqui")
            )
        )

        assertEquals(listOf(0L, 300_000L, 600_000L, 900_000L), rows.map { it.startMs })
        assertEquals(listOf("Abertura", "Frase boa", "Entrevista", "Voltar aqui"), rows.map { it.title })
    }

    @Test
    fun marcador_vem_antes_do_capitulo_no_mesmo_instante() {
        val rows = PodcastBookmarks.rows(
            chapters = listOf(Chapter(120_000L, "Bloco")),
            bookmarks = listOf(Bookmark(id = 3L, positionMs = 120_000L, title = "Meu ponto"))
        )

        assertEquals("Meu ponto", rows.first().title)
        assertTrue(rows.first().isBookmark)
        assertFalse(rows.last().isBookmark)
    }

    @Test
    fun marcador_sem_titulo_usa_o_carimbo_de_tempo() {
        val rows = PodcastBookmarks.rows(
            chapters = emptyList(),
            bookmarks = listOf(Bookmark(id = 4L, positionMs = 65_000L, title = "   "))
        )

        assertEquals("1:05", rows.single().title)
    }

    @Test
    fun capitulo_do_feed_nunca_vira_removivel() {
        val rows = PodcastBookmarks.rows(
            chapters = listOf(Chapter(10_000L, "Do feed")),
            bookmarks = emptyList()
        )

        assertEquals(0L, rows.single().bookmarkId)
        assertFalse(rows.single().isBookmark)
    }
}
