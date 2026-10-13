package com.pulsa.player.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextEpisodeTest {

    private fun ep(id: Long, publishedAt: Long) = Episode(
        id = id,
        podcastId = 1L,
        guid = "g$id",
        title = "Episódio $id",
        audioUrl = "https://example.com/$id.mp3",
        publishedAt = publishedAt
    )

    @Test
    fun prefere_o_mais_recente_antes_do_atual() {
        val current = ep(5, 500)
        val candidates = listOf(ep(1, 100), ep(3, 300), ep(4, 400), ep(6, 600))
        assertEquals(4L, nextEpisodeToPlay(candidates, current)!!.id)
    }

    @Test
    fun sem_anterior_pega_o_mais_novo_seguinte() {
        val current = ep(1, 100)
        val candidates = listOf(ep(2, 200), ep(3, 300), ep(4, 400))
        assertEquals(2L, nextEpisodeToPlay(candidates, current)!!.id)
    }

    @Test
    fun so_anterior_pega_o_mais_proximo_de_tras() {
        val current = ep(9, 900)
        val candidates = listOf(ep(7, 700), ep(8, 800))
        assertEquals(8L, nextEpisodeToPlay(candidates, current)!!.id)
    }

    @Test
    fun sem_candidatos_devolve_nada() {
        assertNull(nextEpisodeToPlay(emptyList(), ep(5, 500)))
    }

    @Test
    fun ignora_empate_de_data() {
        val current = ep(5, 500)
        // Candidatos com a MESMA data do atual não são nem antes nem depois: ficam de fora.
        assertNull(nextEpisodeToPlay(listOf(ep(6, 500)), current))
    }
}
