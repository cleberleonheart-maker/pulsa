package com.pulsa.player.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlDownloaderTest {

    @Test
    fun `id e estavel para o mesmo par url e nome`() {
        assertEquals(
            UrlDownloader.itemId("https://x/a.mp3", "Faixa"),
            UrlDownloader.itemId("https://x/a.mp3", "Faixa")
        )
    }

    @Test
    fun `nome ou url diferente geram itens diferentes`() {
        assertNotEquals(
            UrlDownloader.itemId("https://x/a.mp3", "Faixa"),
            UrlDownloader.itemId("https://x/b.mp3", "Faixa")
        )
        assertNotEquals(
            UrlDownloader.itemId("https://x/a.mp3", "Faixa"),
            UrlDownloader.itemId("https://x/a.mp3", "Outra")
        )
    }

    @Test
    fun `id nao vira caminho nem nome de arquivo`() {
        val id = UrlDownloader.itemId("https://x/a b.mp3?x=1", "a/b:c")
        assertTrue(id.startsWith("url-"))
        assertTrue(id.none { it == '/' || it == ':' || it == ' ' })
    }
}
