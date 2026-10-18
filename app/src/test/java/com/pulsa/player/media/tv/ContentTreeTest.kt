package com.pulsa.player.media.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2 — a árvore DLNA pura. Cada teste cobre um defeito que só apareceria na TV:
 * título com `&` partindo o XML, paginação mentindo o TotalMatches, MIME errado fazendo a
 * Roku Media Player recusar o arquivo.
 */
class ContentTreeTest {

    private fun entry(
        id: String,
        title: String = "Faixa",
        mime: String = "audio/mpeg",
        size: Long = 1_000_000L,
        durationMs: Long = 180_000L,
        isVideo: Boolean = false,
        artist: String? = "Artista",
        album: String? = "Álbum",
    ) = ContentTree.Entry(id, title, artist, album, mime, size, durationMs, isVideo)

    @Test
    fun arvoreTemRaizMusicasEDividePorTipo() {
        val nodes = ContentTree.build(
            listOf(
                entry("m1", "B song"),
                entry("m2", "A song"),
                entry("v1", "Video", mime = "video/mp4", isVideo = true),
            ),
        )
        val root = nodes[ContentTree.ROOT] as ContentTree.Node.Container
        assertEquals(2, root.childCount)
        val music = nodes[ContentTree.MUSIC] as ContentTree.Node.Container
        assertEquals(2, music.childCount)
        val videos = nodes[ContentTree.VIDEOS] as ContentTree.Node.Container
        assertEquals(1, videos.childCount)
    }

    @Test
    fun musicaOrdenadaPorTituloCaseInsensitive() {
        val nodes = ContentTree.build(
            listOf(entry("m1", "banana"), entry("m2", "Abacaxi"), entry("m3", "Cereja")),
        )
        val (children, total) = ContentTree.children(nodes, ContentTree.MUSIC, 0, 10)
        assertEquals(3, total)
        assertEquals(listOf("m2", "m1", "m3"), children.map { it.id })
    }

    @Test
    fun paginaHonestaTotalMatches() {
        val entries = (1..7).map { entry("m$it", "F$it") }
        val nodes = ContentTree.build(entries)
        val (page, total) = ContentTree.children(nodes, ContentTree.MUSIC, 2, 3)
        assertEquals(7, total)
        assertEquals(listOf("m3", "m4", "m5"), page.map { it.id })
    }

    @Test
    fun paginaAlemDoFimDevolveVazioENaoEstoura() {
        val nodes = ContentTree.build(listOf(entry("m1")))
        val (page, total) = ContentTree.children(nodes, ContentTree.MUSIC, 50, 10)
        assertEquals(1, total)
        assertTrue(page.isEmpty())
    }

    @Test
    fun countZeroDevolveTudo() {
        val nodes = ContentTree.build((1..4).map { entry("m$it") })
        val (page, total) = ContentTree.children(nodes, ContentTree.MUSIC, 0, 0)
        assertEquals(4, total)
        assertEquals(4, page.size)
    }

    @Test
    fun tituloComEspecialNaoParteXml() {
        val nodes = ContentTree.build(
            listOf(entry("m1", "Rock & Roll <ao vivo> \"duplo\" 'unico'")),
        )
        val didl = ContentTree.didl(nodes, ContentTree.MUSIC, 0, 10, "http://x:1")
        assertTrue(didl.contains("Rock &amp; Roll &lt;ao vivo&gt; &quot;duplo&quot; &apos;unico&apos;"))
        assertFalse(didl.contains("Rock & Roll"))
    }

    @Test
    fun resCarregaUrlCompletaEProtocolInfoComOp01() {
        val nodes = ContentTree.build(listOf(entry("m42", "T")))
        val didl = ContentTree.didl(nodes, ContentTree.MUSIC, 0, 1, "http://192.168.0.5:8200")
        assertTrue(didl.contains("http://192.168.0.5:8200/f/m42"))
        assertTrue(didl.contains("http-get:*:audio/mpeg:DLNA.ORG_OP=01"))
    }

    @Test
    fun videoUsaClasseVideoItem() {
        val nodes = ContentTree.build(
            listOf(entry("v1", "Vid", mime = "video/mp4", isVideo = true)),
        )
        val didl = ContentTree.didl(nodes, ContentTree.VIDEOS, 0, 1, "http://x")
        assertTrue(didl.contains("object.item.videoItem"))
    }

    @Test
    fun duracaoFormatadaNoPadraoHms() {
        assertEquals("0:03:00", ContentTree.hms(180_000L))
        assertEquals("1:01:01", ContentTree.hms(3_661_000L))
        assertEquals("0:00:00", ContentTree.hms(0L))
    }

    @Test
    fun mimeSoParaExtensaoQueRokuToca() {
        assertEquals("audio/mpeg", ContentTree.mimeFor("x.mp3"))
        assertEquals("audio/flac", ContentTree.mimeFor("X.FLAC"))
        assertEquals("video/x-matroska", ContentTree.mimeFor("filme.mkv"))
        assertEquals("video/mp4", ContentTree.mimeFor("clipe.m4v"))
        // AVI/DTS: a Roku não toca — listar seria pior que esconder.
        assertNull(ContentTree.mimeFor("pesado.avi"))
        assertNull(ContentTree.mimeFor("sem_extensao"))
    }

    @Test
    fun didlSingleDevolveSoONO() {
        val nodes = ContentTree.build(listOf(entry("m1", "Só uma"), entry("m2", "Outra")))
        val item = nodes["m1"]!!
        val didl = ContentTree.didlSingle(item, "http://x")
        assertTrue(didl.contains("Só uma"))
        assertFalse(didl.contains("Outra"))
    }
}
