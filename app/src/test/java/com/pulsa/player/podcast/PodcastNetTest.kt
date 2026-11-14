package com.pulsa.player.podcast

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A parte pura da busca do Podcast Index: a assinatura que a API exige e a leitura do
 * resultado. Sem rede e sem aparelho.
 */
class PodcastNetTest {

    @Test
    fun `a assinatura e o bearer do md5 em maiusculas`() {
        // A API do Podcast Index compara a assinatura do cabeçalho `Authorization` com o
        // MD5 de chave + segredo + data, em maiúsculas. Letra minúscula é 401.
        val header = PodcastNet.indexAuthHeader("abc123", "secret", 1_700_000_000L)
        assertEquals("Bearer 9B1A23710D55B5F7146688BDEF7763A1", header)
    }

    @Test
    fun `a assinatura muda com a data`() {
        val a = PodcastNet.indexAuthHeader("abc123", "secret", 1_700_000_000L)
        val b = PodcastNet.indexAuthHeader("abc123", "secret", 1_700_000_001L)
        assertTrue(a != b)
    }

    @Test
    fun `a url da busca escapa o termo`() {
        // O termo vai codificado: um "c & d" não pode virar outro parâmetro.
        val url = PodcastNet.indexSearchUrl("c & d")
        assertTrue(url.startsWith("https://api.podcastindex.org/api/1.0/search/byterm?"))
        assertTrue(url.contains("q=c+%26+d") || url.contains("q=c%20%26%20d"))
    }

    @Test
    fun `le o resultado do indice`() {
        val hit = JSONObject()
            .put("title", "Meu Podcast")
            .put("author", "Quem Faz")
            .put("feedUrl", "https://exemplo.com/feed.xml")
            .put("image", "https://exemplo.com/capa.jpg")
            .put("episodeCount", 42)
        val found = PodcastNet.fromIndexHit(hit)!!
        assertEquals("https://exemplo.com/feed.xml", found.feedUrl)
        assertEquals("Meu Podcast", found.title)
        assertEquals("Quem Faz", found.author)
        assertEquals("https://exemplo.com/capa.jpg", found.artworkUrl)
        assertEquals(42, found.episodeCount)
    }

    @Test
    fun `resultado sem feed url e descartado`() {
        // Sem `feedUrl` não há o que assinar: o resultado entra na lista e a assinatura falha.
        val hit = JSONObject().put("title", "Meu Podcast")
        assertNull(PodcastNet.fromIndexHit(hit))
        assertNull(PodcastNet.fromIndexHit(null))
    }
}
