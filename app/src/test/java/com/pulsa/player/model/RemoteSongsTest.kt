package com.pulsa.player.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A forma do `Song` de rádio e de stream é o que a fila reconhece: o `QueueKey` deriva a
 * chave do `path`, e o `id` vem da URL para o item ser o mesmo em qualquer aparelho e na
 * próxima sessão. Se um canto da tela inventar outra forma, o item entra na fila e o restore
 * não reconhece — sem erro nenhum, só um item que "some" depois.
 */
class RemoteSongsTest {

    private fun musica() = Song(
        id = 1L,
        title = "Música",
        artist = "Artista",
        album = "Álbum",
        albumId = 0L,
        durationMs = 1000L,
        path = "/sdcard/Music/a.mp3",
        year = 0
    )

    @Test
    fun `radio e stream tem o path que a fila reconhece`() {
        val radio = RemoteSongs.radio("https://a.com/stream", "Rádio A", "Hits", "Rádio")
        assertEquals("radio:https://a.com/stream", radio.path)
        assertTrue(radio.isRadio)

        val stream = RemoteSongs.stream("https://b.com/video.m3u8", "Vídeo B", "Vídeos")
        assertEquals("stream:https://b.com/video.m3u8", stream.path)
        assertTrue(stream.isStream)
    }

    @Test
    fun `a mesma url da o mesmo item`() {
        val a = RemoteSongs.radio("https://a.com/stream", "Rádio A", "Hits", "Rádio")
        val b = RemoteSongs.radio("https://a.com/stream", "Rádio A", "Hits", "Rádio")
        // O id vem da URL e não de um id do banco: o item tem que ser o mesmo no aparelho
        // novo e depois de um restart.
        assertEquals(a.id, b.id)
        assertEquals(a.path, b.path)
    }

    @Test
    fun `so gira as estacoes quando a fila e toda de radio`() {
        val radio = RemoteSongs.radio("https://a.com/stream", "Rádio A", "Hits", "Rádio")
        assertTrue(RemoteSongs.isRadioOnly(listOf(radio)))
        assertTrue(RemoteSongs.isRadioOnly(listOf(radio, radio)))
        // Fila misturada: o botão de avançar anda no que a pessoa montou. Trocar a fila
        // inteira pela lista de estações perderia as músicas que estavam depois.
        assertFalse(RemoteSongs.isRadioOnly(listOf(musica(), radio)))
        assertFalse(RemoteSongs.isRadioOnly(listOf(radio, musica())))
        assertFalse(RemoteSongs.isRadioOnly(emptyList()))
    }
}
