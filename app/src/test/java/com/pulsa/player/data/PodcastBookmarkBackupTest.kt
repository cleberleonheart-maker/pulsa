package com.pulsa.player.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O `parse` dos marcadores é a única parte testável sem aparelho: não usa Context nem banco.
 * E é onde mora a compatibilidade com arquivo velho — um backup sem o array (ou com ele
 * quebrado) tem de virar lista vazia, e não uma exceção no meio do restore.
 */
class PodcastBookmarkBackupTest {

    @Test
    fun blocoAusenteViraListaVazia() {
        // É o caminho do backup antigo (v2), que não tinha marcadores.
        assertTrue(PodcastBookmarkBackup.parse(null).isEmpty())
    }

    @Test
    fun leMarcadorComAIdentidadeDoEpisodio() {
        val json = JSONArray().put(
            JSONObject()
                .put("feed", "https://exemplo.com/feed.xml")
                .put("guid", "ep-1")
                .put("positionMs", 754000)
                .put("title", "Bitcoin")
                .put("createdAt", 1_700_000_000_000L)
        )
        val bm = PodcastBookmarkBackup.parse(json).single()
        // O episódio viaja pelo feed e pelo guid: é o que resolve o marcador no celular novo.
        assertEquals("https://exemplo.com/feed.xml", bm.feedUrl)
        assertEquals("ep-1", bm.guid)
        assertEquals(754000L, bm.positionMs)
        assertEquals("Bitcoin", bm.title)
        assertEquals(1_700_000_000_000L, bm.createdAt)
    }

    @Test
    fun marcadorSemIdentidadeEDescartado() {
        val json = JSONArray()
            .put(JSONObject().put("guid", "ep-1").put("positionMs", 1000))
            .put(JSONObject().put("feed", "https://exemplo.com").put("positionMs", 1000))
        // Sem feed ou sem guid não há como saber a qual episódio o marcador pertence — e um
        // marcador órfão apareceria em algum episódio qualquer.
        assertTrue(PodcastBookmarkBackup.parse(json).isEmpty())
    }

    @Test
    fun marcadorSemDataCaiEmAgora() {
        val json = JSONArray().put(
            JSONObject().put("feed", "https://exemplo.com").put("guid", "ep-1")
        )
        // Perder a data só muda a ordem em que os marcadores aparecem; perder o marcador é
        // bem pior.
        assertTrue(PodcastBookmarkBackup.parse(json).single().createdAt > 0L)
    }

    @Test
    fun posicaoNegativaViraZero() {
        val json = JSONArray().put(
            JSONObject()
                .put("feed", "https://exemplo.com")
                .put("guid", "ep-1")
                .put("positionMs", -5)
        )
        assertEquals(0L, PodcastBookmarkBackup.parse(json).single().positionMs)
    }

    @Test
    fun arquivoTruncadoNaoEstoura() {
        val json = JSONArray()
            .put(JSONObject().put("feed", "https://exemplo.com").put("guid", "ep-1"))
            .put("isto nao e um objeto")
        assertEquals(1, PodcastBookmarkBackup.parse(json).size)
    }

    @Test
    fun blocoDaBibliotecaLeOsMarcadores() {
        val json = JSONObject().put(
            "podcastBookmarks",
            JSONArray().put(
                JSONObject()
                    .put("feed", "https://exemplo.com")
                    .put("guid", "ep-1")
                    .put("positionMs", 1000)
            )
        )
        // O bloco da biblioteca é o mesmo arquivo: os marcadores têm de voltar junto com as
        // playlists, senão o backup volta pela metade.
        assertEquals(1, PlaylistBackup.parse(json).podcastBookmarks.size)
    }
}
