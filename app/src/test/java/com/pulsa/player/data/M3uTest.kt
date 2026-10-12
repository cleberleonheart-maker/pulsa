package com.pulsa.player.data

import com.pulsa.player.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O M3U é lido de arquivo de outros players, então o parse é onde as variações do formato
 * aparecem: CRLF, comentários, `#EXTINF` sem artista e título com vírgula. O serialize é o
 * lado que o Pulsa escreve, e o teste fecha o ciclo.
 */
class M3uTest {

    private fun song(id: Long, title: String, artist: String, path: String, ms: Long = 0L) = Song(
        id = id,
        title = title,
        artist = artist,
        album = "Album",
        albumId = 0L,
        durationMs = ms,
        path = path,
        year = 0
    )

    @Test
    fun serializeMarcaExtm3uEExtinfComSegundos() {
        val text = M3u.serialize(
            listOf(song(1, "Song", "Artist", "/m/a.mp3", 185_000L))
        )
        val lines = text.trim().split('\n')
        assertEquals("#EXTM3U", lines[0])
        assertEquals("#EXTINF:185,Artist - Song", lines[1])
        assertEquals("/m/a.mp3", lines[2])
    }

    @Test
    fun parseLeArtistaETituloDoExtinf() {
        val text = """
            #EXTM3U
            #EXTINF:185,Beatles - Hey Jude
            /music/hey.mp3
        """.trimIndent()
        val tracks = M3u.parse(text)
        assertEquals(1, tracks.size)
        assertEquals("Beatles", tracks[0].artist)
        assertEquals("Hey Jude", tracks[0].title)
        assertEquals("/music/hey.mp3", tracks[0].path)
    }

    @Test
    fun parseSemArtistaDeixaTituloInteiro() {
        val tracks = M3u.parse("#EXTINF:0,Rádio Head\nhttp://x/stream")
        assertEquals(1, tracks.size)
        assertEquals("", tracks[0].artist)
        assertEquals("Rádio Head", tracks[0].title)
        assertEquals("http://x/stream", tracks[0].path)
    }

    @Test
    fun parseTituloComVirgulaNaoEhCortado() {
        val tracks = M3u.parse("#EXTINF:10,Artista - Só eu, você\n/a.mp3")
        assertEquals("Artista", tracks[0].artist)
        assertEquals("Só eu, você", tracks[0].title)
    }

    @Test
    fun parseToleraCrlfComentariosELinhasVazias() {
        val text = "#EXTM3U\r\n\r\n# um comentário\r\n#EXTINF:1,A - B\r\n/a.mp3\r\n"
        val tracks = M3u.parse(text)
        assertEquals(1, tracks.size)
        assertEquals("/a.mp3", tracks[0].path)
    }

    @Test
    fun parseDescartaExtinfOrfaoERotaAbsoluta() {
        // `#EXTINF` que não é seguido de caminho não vira faixa; e a rota absoluta é o que
        // um "Servidor" M3U às vezes traz — não é media, então não vira caminho.
        val text = "#EXTINF:0,Órfã\n#EXTINF:1,C - D\n/c.mp3"
        val tracks = M3u.parse(text)
        assertEquals(1, tracks.size)
        assertEquals("/c.mp3", tracks[0].path)
    }

    @Test
    fun parseSemExtinfUsaSoOCaminho() {
        val tracks = M3u.parse("/só/o/caminho.mp3")
        assertEquals(1, tracks.size)
        assertEquals("/só/o/caminho.mp3", tracks[0].path)
        assertTrue(tracks[0].title.isEmpty())
    }
}
