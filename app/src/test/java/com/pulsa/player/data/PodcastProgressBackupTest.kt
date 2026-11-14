package com.pulsa.player.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O progresso do podcast viaja pela identidade portátil do episódio (feed + guid), e o `parse`
 * é a parte testável: sem Context, sem banco, sem MediaStore.
 */
class PodcastProgressBackupTest {

    @Test
    fun arrayAusenteViraListaVazia() {
        assertTrue(PodcastProgressBackup.parse(null).isEmpty())
    }

    @Test
    fun leProgressoComAIdentidadeDoEpisodio() {
        val array = JSONArray().put(
            JSONObject()
                .put("feed", "https://exemplo.com/feed.xml")
                .put("guid", "ep-1")
                .put("positionMs", 2_040_000L)
                .put("played", false)
                .put("title", "Episódio 1")
        )
        val p = PodcastProgressBackup.parse(array).single()
        assertEquals("https://exemplo.com/feed.xml", p.feedUrl)
        assertEquals("ep-1", p.guid)
        assertEquals(2_040_000L, p.positionMs)
        assertEquals(false, p.played)
    }

    @Test
    fun progressoSemIdentidadeEDescartado() {
        // Sem feed ou sem guid não há como saber a qual episódio o progresso pertence — e um
        // progresso no episódio errado é pior do que um progresso perdido.
        val array = JSONArray()
            .put(JSONObject().put("guid", "ep-1").put("positionMs", 1000L))
            .put(JSONObject().put("feed", "https://exemplo.com/feed.xml").put("positionMs", 1000L))
        assertTrue(PodcastProgressBackup.parse(array).isEmpty())
    }

    @Test
    fun posicaoNegativaViraZero() {
        val array = JSONArray().put(
            JSONObject()
                .put("feed", "https://exemplo.com/feed.xml")
                .put("guid", "ep-1")
                .put("positionMs", -5000L)
        )
        assertEquals(0L, PodcastProgressBackup.parse(array).single().positionMs)
    }

    @Test
    fun arrayTruncadoNaoEstoura() {
        val array = JSONArray().put("isto não é um objeto")
        assertTrue(PodcastProgressBackup.parse(array).isEmpty())
    }

    @Test
    fun fusaoMantemOProgressoMaisAdiantado() {
        val remoto = PodcastProgressBackup.BackupProgress(
            feedUrl = "https://exemplo.com/feed.xml",
            guid = "ep-1",
            positionMs = 10_000L,
            played = false,
            title = "Episódio 1"
        )
        // O local está mais adiantado: a nuvem não pode puxar ele para trás.
        val local = PodcastProgressBackup.merge(60_000L, false, remoto)
        assertEquals(60_000L, local.positionMs)
        assertFalse(local.changed)
    }

    @Test
    fun fusaoTrazOProgressoMaisAdiantadoDaNuvem() {
        val remoto = PodcastProgressBackup.BackupProgress(
            feedUrl = "https://exemplo.com/feed.xml",
            guid = "ep-1",
            positionMs = 90_000L,
            played = false,
            title = "Episódio 1"
        )
        val local = PodcastProgressBackup.merge(20_000L, false, remoto)
        assertEquals(90_000L, local.positionMs)
        assertTrue(local.changed)
    }

    @Test
    fun fusaoMantemOEpisodioOuvido() {
        // Ouvido é estado final: a máquina que ouviu ganha de quem parou no meio.
        val remoto = PodcastProgressBackup.BackupProgress(
            feedUrl = "https://exemplo.com/feed.xml",
            guid = "ep-1",
            positionMs = 30_000L,
            played = false,
            title = "Episódio 1"
        )
        val local = PodcastProgressBackup.merge(30_000L, true, remoto)
        assertTrue(local.played)
        assertFalse(local.changed)
    }

    @Test
    fun fusaoPreencheLocalVazio() {
        val remoto = PodcastProgressBackup.BackupProgress(
            feedUrl = "https://exemplo.com/feed.xml",
            guid = "ep-1",
            positionMs = 5_000L,
            played = false,
            title = "Episódio 1"
        )
        val local = PodcastProgressBackup.merge(0L, false, remoto)
        assertEquals(5_000L, local.positionMs)
        assertTrue(local.changed)
    }
}
