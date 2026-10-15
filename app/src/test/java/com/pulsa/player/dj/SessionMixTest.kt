package com.pulsa.player.dj

import com.pulsa.player.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMixTest {

    private fun song(id: Long, minutes: Long, path: String = "music:$id"): Song = Song(
        id = id,
        title = "faixa $id",
        artist = "artista",
        album = "album",
        albumId = 0L,
        durationMs = minutes * 60_000L,
        path = path,
        year = 0
    )

    private fun plan(
        minutes: Int = 40,
        mood: String? = null,
        genre: String? = null,
        songs: Int? = null,
        podcasts: Int? = null
    ) = DjCommander.SessionPlan(minutes, mood, genre, songs, podcasts)

    @Test
    fun enche_ate_a_duracao_somada() {
        val songs = listOf(song(1, 5), song(2, 5), song(3, 5), song(4, 5))
        val set = SessionMix.build(songs, emptyList(), plan(minutes = 12))
        // 5 + 5 + 5 = 15 >= 12: para na terceira, sem levar a quarta.
        assertEquals(3, set.size)
    }

    @Test
    fun contagem_de_musicas_e_o_minimo_da_duracao() {
        val songs = listOf(song(1, 1), song(2, 1), song(3, 1), song(4, 1))
        // Pede 2, mas a duração de 3 min obriga a terceira faixa.
        val set = SessionMix.build(songs, emptyList(), plan(minutes = 3, songs = 2))
        assertEquals(3, set.size)
    }

    @Test
    fun duracao_do_podcast_conta_para_o_alvo() {
        val songs = listOf(song(1, 10), song(2, 10))
        val episodes = listOf(song(-7, 30, path = "podcast:7"))
        // 10 min de música + 30 de podcast = 40: satisfaz e não puxa a 2ª música.
        val set = SessionMix.build(songs, episodes, plan(minutes = 40, podcasts = 1))
        assertEquals(2, set.size)
        assertEquals(1L, set.first().id)
        assertEquals(-7L, set.last().id)
    }

    @Test
    fun musicas_vem_antes_do_podcast() {
        val songs = listOf(song(1, 20), song(2, 20))
        val episodes = listOf(song(-7, 30, path = "podcast:7"))
        val set = SessionMix.build(songs, episodes, plan(minutes = 40, songs = 2, podcasts = 1))
        assertEquals(3, set.size)
        assertTrue(set.last().path.startsWith("podcast:"))
    }

    @Test
    fun podcast_sem_contagem_nao_entra() {
        val songs = listOf(song(1, 40))
        val episodes = listOf(song(-7, 30, path = "podcast:7"))
        val set = SessionMix.build(songs, episodes, plan(minutes = 40))
        assertEquals(1, set.size)
        assertTrue(set.none { it.path.startsWith("podcast:") })
    }

    @Test
    fun duracao_invalida_cai_no_padrao_de_40_minutos() {
        val songs = listOf(song(1, 25), song(2, 25))
        val set = SessionMix.build(songs, emptyList(), plan(minutes = 0))
        // 25 + 25 = 50 >= 40 (padrão): pega as duas.
        assertEquals(2, set.size)
    }
}
