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

    // ── Árvore hierárquica (A2b) ────────────────────────────────────────────

    @Test
    fun topoTemArtistasAlbunsEPlaylistsQuandoHaConteudo() {
        val nodes = ContentTree.buildTree(
            entries = listOf(entry("m1", "A", artist = "The Beatles", album = "Abbey Road")),
            playlists = listOf(ContentTree.Group("1", "Minhas", listOf("m1"))),
            podcasts = emptyList(),
        )
        val root = nodes[ContentTree.ROOT] as ContentTree.Node.Container
        // Músicas + Artistas + Álbuns + Playlists = 4 (sem vídeo/podcast).
        assertEquals(4, root.childCount)
        assertTrue(nodes.containsKey(ContentTree.ARTISTS))
        assertTrue(nodes.containsKey(ContentTree.ALBUMS))
        assertTrue(nodes.containsKey(ContentTree.PLAYLISTS))
        assertFalse(nodes.containsKey(ContentTree.PODCASTS))
        assertFalse(nodes.containsKey(ContentTree.VIDEOS))
    }

    @Test
    fun artistasLevamAAlbunsEAlbumLevaAFaixas() {
        val nodes = ContentTree.buildTree(
            listOf(
                entry("m1", "Come Together", artist = "The Beatles", album = "Abbey Road"),
                entry("m2", "Something", artist = "The Beatles", album = "Abbey Road"),
                entry("m3", "Imagine", artist = "John Lennon", album = "Imagine"),
            ),
        )
        val artistas = ContentTree.children(nodes, ContentTree.ARTISTS, 0, 10).first
        assertEquals(2, artistas.size)
        val beatles = artistas.first { it.title == "The Beatles" }
        val albuns = ContentTree.children(nodes, beatles.id, 0, 10).first
        assertEquals(listOf("Abbey Road"), albuns.map { it.title })
        val faixas = ContentTree.children(nodes, albuns.first().id, 0, 10).first
        assertEquals(listOf("Come Together", "Something"), faixas.map { it.title })
    }

    @Test
    fun mesmaFaixaEmVariosCaminhosTemIdsDistintosEPaiUnico() {
        val nodes = ContentTree.buildTree(
            entries = listOf(entry("m1", "Única", artist = "A", album = "B")),
            playlists = listOf(ContentTree.Group("1", "P", listOf("m1"))),
        )
        // A faixa aparece em Músicas, no álbum (por artista), no álbum (flat) e na playlist.
        val musicChildren = ContentTree.children(nodes, ContentTree.MUSIC, 0, 10).first
        assertEquals(listOf("m1"), musicChildren.map { it.id })
        // Cada aparição tem um id próprio; nenhuma colide.
        val itemIds = nodes.values.filterIsInstance<ContentTree.Node.Item>().map { it.id }
        assertEquals(itemIds.size, itemIds.toSet().size)
        // Todas apontam para o MESMO arquivo na URL do res — é isso que faz o servidor achar os bytes.
        val didl = ContentTree.didl(nodes, ContentTree.MUSIC, 0, 1, "http://x")
        assertTrue(didl.contains("http://x/f/m1"))
    }

    @Test
    fun playlistViraPastaComAsFaixasNaOrdem() {
        val nodes = ContentTree.buildTree(
            entries = listOf(entry("m1", "Primeira"), entry("m2", "Segunda")),
            playlists = listOf(ContentTree.Group("1", "Favoritas", listOf("m2", "m1"))),
        )
        val pls = ContentTree.children(nodes, ContentTree.PLAYLISTS, 0, 10).first
        assertEquals(listOf("Favoritas"), pls.map { it.title })
        val faixas = ContentTree.children(nodes, pls.first().id, 0, 10).first
        // Ordem da playlist (m2, m1), não ordem alfabética.
        assertEquals(listOf("Segunda", "Primeira"), faixas.map { it.title })
    }

    @Test
    fun podcastViraPastaPorFeed() {
        val nodes = ContentTree.buildTree(
            entries = listOf(
                entry("e1", "Ep 1", artist = "Meu Podcast", album = null, isVideo = false),
                entry("e2", "Ep 2", artist = "Meu Podcast", album = null, isVideo = false),
            ),
            podcasts = listOf(ContentTree.Group("7", "Meu Podcast", listOf("e1", "e2"))),
        )
        val feeds = ContentTree.children(nodes, ContentTree.PODCASTS, 0, 10).first
        assertEquals(listOf("Meu Podcast"), feeds.map { it.title })
        val eps = ContentTree.children(nodes, feeds.first().id, 0, 10).first
        assertEquals(listOf("Ep 1", "Ep 2"), eps.map { it.title })
    }

    @Test
    fun idDePastaNaoColideComIdDeFaixa() {
        // Artista chamado literalmente de "m1" não pode virar a mesma chave de uma faixa "m1".
        val nodes = ContentTree.buildTree(listOf(entry("m1", "X", artist = "m1", album = "A")))
        val artistas = ContentTree.children(nodes, ContentTree.ARTISTS, 0, 10).first
        assertTrue(artistas.first().id != "m1")
    }
}
