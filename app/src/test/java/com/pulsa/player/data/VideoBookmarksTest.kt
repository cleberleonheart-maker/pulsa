package com.pulsa.player.data

import com.pulsa.player.data.db.VideoBookmarkDao
import com.pulsa.player.data.db.VideoBookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Os marcadores do vídeo local — a parte que dá para testar sem aparelho.
 *
 * O DAO real é do Room (precisa de Android), então o teste usa um dublê que guarda em
 * memória. O que está sendo verificado aqui é a regra do [VideoBookmarks]: a chave do
 * vídeo, o instante nunca negativo e a ordem por tempo.
 */
class VideoBookmarksTest {

    private class FakeDao : VideoBookmarkDao {
        val rows = ArrayList<VideoBookmarkEntity>()
        private var nextId = 1L

        override fun bookmarks(key: String): List<VideoBookmarkEntity> =
            rows.filter { it.videoKey == key }.sortedBy { it.positionMs }

        override fun upsert(entity: VideoBookmarkEntity): Long {
            val existing = rows.indexOfFirst {
                it.videoKey == entity.videoKey && it.positionMs == entity.positionMs
            }
            if (existing >= 0) {
                val id = rows[existing].id
                rows[existing] = entity.copy(id = id)
                return id
            }
            val id = nextId++
            rows.add(entity.copy(id = id))
            return id
        }

        override fun delete(id: Long) {
            rows.removeAll { it.id == id }
        }
    }

    @Test
    fun marcadorGravadoVoltaNaOrdemDoTempo() {
        val dao = FakeDao()
        val bookmarks = VideoBookmarks(dao)
        bookmarks.add("/filmes/a.mkv", 30_000L, "Cena 2")
        bookmarks.add("/filmes/a.mkv", 10_000L, "Cena 1")
        val found = bookmarks.list("/filmes/a.mkv")
        assertEquals(2, found.size)
        assertEquals("Cena 1", found[0].title)
        assertEquals(10_000L, found[0].positionMs)
        assertEquals("Cena 2", found[1].title)
    }

    @Test
    fun instanteNegativoViraZero() {
        val dao = FakeDao()
        val bookmarks = VideoBookmarks(dao)
        bookmarks.add("/filmes/a.mkv", -5_000L, "Início")
        assertEquals(0L, bookmarks.list("/filmes/a.mkv")[0].positionMs)
    }

    @Test
    fun mesmoInstanteAtualizaEmVezDeDuplicar() {
        val dao = FakeDao()
        val bookmarks = VideoBookmarks(dao)
        bookmarks.add("/filmes/a.mkv", 10_000L, "Primeiro")
        bookmarks.add("/filmes/a.mkv", 10_000L, "Corrigido")
        val found = bookmarks.list("/filmes/a.mkv")
        assertEquals(1, found.size)
        assertEquals("Corrigido", found[0].title)
    }

    @Test
    fun marcadorDeUmVideoNaoApareceNoOutro() {
        val dao = FakeDao()
        val bookmarks = VideoBookmarks(dao)
        bookmarks.add("/filmes/a.mkv", 10_000L, "A")
        bookmarks.add("/filmes/b.mkv", 10_000L, "B")
        assertEquals(1, bookmarks.list("/filmes/a.mkv").size)
        assertEquals("A", bookmarks.list("/filmes/a.mkv")[0].title)
    }

    @Test
    fun chaveVaziaNaoGravaNemLe() {
        val dao = FakeDao()
        val bookmarks = VideoBookmarks(dao)
        assertEquals(0L, bookmarks.add("", 10_000L, "X"))
        assertTrue(bookmarks.list("").isEmpty())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun apagarRemoveOMarcador() {
        val dao = FakeDao()
        val bookmarks = VideoBookmarks(dao)
        val id = bookmarks.add("/filmes/a.mkv", 10_000L, "A")
        bookmarks.delete(id)
        assertTrue(bookmarks.list("/filmes/a.mkv").isEmpty())
    }
}
