package com.pulsa.player.podcast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastPrefetchTest {

    private fun decision(
        currentIsPodcast: Boolean = true,
        enabled: Boolean = true,
        episodeId: Long = 7L,
        alreadyRequested: Boolean = false,
        unmetered: Boolean = true
    ) = PodcastPrefetch.shouldPrefetch(
        currentIsPodcast = currentIsPodcast,
        enabled = enabled,
        episodeId = episodeId,
        alreadyRequested = alreadyRequested,
        unmetered = unmetered
    )

    @Test
    fun prefetcha_o_proximo_episodio_no_wifi() {
        assertTrue(decision())
    }

    @Test
    fun nao_prefetcha_quando_o_atual_nao_e_podcast() {
        assertFalse(decision(currentIsPodcast = false))
    }

    @Test
    fun nao_prefetcha_com_a_opcao_desligada() {
        assertFalse(decision(enabled = false))
    }

    @Test
    fun nao_prefetcha_sem_episodio_seguinte() {
        assertFalse(decision(episodeId = 0L))
    }

    @Test
    fun nao_repete_o_mesmo_pedido() {
        assertFalse(decision(alreadyRequested = true))
    }

    @Test
    fun nao_prefetcha_em_rede_medida() {
        assertFalse(decision(unmetered = false))
    }
}
