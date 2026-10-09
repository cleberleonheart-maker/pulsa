package com.pulsa.player.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3 · os tipos de item de podcast dentro da [Song].
 *
 * O caminho é um texto só (`podcast:7` para áudio, `podcast:v7` para vídeo) porque é ele que
 * viaja na fila salva e no `MediaItem`. Isso significa que toda a diferença entre "tocar pelo
 * ExoPlayer normal" e "abrir a tela de vídeo" está no teste do prefixo — e o modo de falha é
 * silencioso: o item toca, só que sem imagem. Estes testes existem para o `v` não se perder.
 */
class SongPodcastFlagsTest {

    private fun episode(path: String, id: Long) = Song(
        id = 900_000L + id,
        title = "Episódio de teste",
        artist = "Canal de teste",
        album = "Assinatura",
        albumId = -1L,
        durationMs = 1_800_000L,
        path = path,
        year = 0
    )

    @Test
    fun `episodio normal de podcast`() {
        val song = episode("podcast:7", 7L)
        assertTrue(song.isPodcast)
        assertFalse(song.isPodcastVideo)
        assertEquals(7L, song.podcastId)
        assertFalse(song.needsVideoScreen)
    }

    @Test
    fun `episodio em video de podcast`() {
        val song = episode("podcast:v8", 8L)
        assertTrue(song.isPodcast)
        assertTrue(song.isPodcastVideo)
        assertEquals(8L, song.podcastId)
        // É o ponto do teste: sem isto, o episódio em vídeo toca sem imagem nenhuma.
        assertTrue(song.needsVideoScreen)
    }

    @Test
    fun `so o prefixo v conta como video`() {
        // `podcast:v` sem número não é item de podcast nenhum: não há id para consultar.
        assertFalse(episode("podcast:v", 0L).isPodcastVideo)
        assertFalse(episode("podcast:v ", 0L).isPodcastVideo)
        assertNull(episode("podcast:v", 0L).podcastId)
    }

    @Test
    fun `musica e video do MidiaStore seguem precisando da tela de video`() {
        // A regra não pode ter sido estreitada demais: os dois caminhos antigos já existiam.
        assertTrue(episode("video:/storage/emulated/0/Movies/a.mp4", 1L).isVideo)
        assertTrue(episode("video:/storage/emulated/0/Movies/a.mp4", 1L).needsVideoScreen)
        assertTrue(episode("stream:https://cdn.exemplo/hls.m3u8", 1L).isStream)
        assertTrue(episode("stream:https://cdn.exemplo/hls.m3u8", 1L).needsVideoScreen)
    }

    @Test
    fun `musica comum nao precisa de tela de video`() {
        assertFalse(episode("/storage/emulated/0/Music/a.mp3", 1L).needsVideoScreen)
    }

    @Test
    fun `id de podcast so existe para item de podcast`() {
        // `podcastId` vem do caminho; para um item normal não pode "vazar" número nenhum,
        // senão o resume do player tenta salvar posição de um id que não existe no banco.
        assertNull(episode("/storage/emulated/0/Music/a.mp3", 1L).podcastId)
    }
}