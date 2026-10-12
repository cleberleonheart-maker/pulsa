package com.pulsa.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverFetcherTest {

    @Test
    fun `searchUrl junta artista e album no termo`() {
        val url = CoverFetcher.searchUrl("Pink Floyd", "The Wall", "US")
        assertEquals(
            "https://itunes.apple.com/search?media=music&entity=album&limit=5&country=US&term=Pink+Floyd+The+Wall",
            url
        )
    }

    @Test
    fun `searchUrl sem artista usa so o album`() {
        val url = CoverFetcher.searchUrl("", "Ao Vivo", "BR")
        assertEquals(
            "https://itunes.apple.com/search?media=music&entity=album&limit=5&country=BR&term=Ao+Vivo",
            url
        )
    }

    @Test
    fun `countryFor segue o idioma do app`() {
        assertEquals("BR", CoverFetcher.countryFor("pt"))
        assertEquals("ES", CoverFetcher.countryFor("es"))
        assertEquals("US", CoverFetcher.countryFor("en"))
        assertEquals("US", CoverFetcher.countryFor(""))
        assertEquals("BR", CoverFetcher.countryFor("PT"))
    }

    @Test
    fun `pickArtwork pega o artworkUrl100 e sobe para 600`() {
        val json = """
            {"resultCount":1,"results":[
              {"wrapperType":"collection","artworkUrl60":"https://x/60x60bb.jpg",
               "artworkUrl100":"https://x/100x100bb.jpg"}]}
        """.trimIndent()
        assertEquals("https://x/600x600bb.jpg", CoverFetcher.pickArtwork(json))
    }

    @Test
    fun `pickArtwork cai no artworkUrl60 quando nao ha 100`() {
        val json = """{"results":[{"artworkUrl60":"https://x/60x60bb.jpg"}]}"""
        assertEquals("https://x/600x600bb.jpg", CoverFetcher.pickArtwork(json))
    }

    @Test
    fun `pickArtwork devolve null quando nao ha arte`() {
        assertNull(CoverFetcher.pickArtwork("""{"resultCount":0,"results":[]}"""))
        assertNull(CoverFetcher.pickArtwork("") )
    }

    @Test
    fun `upscale troca qualquer tamanho bb por 600`() {
        assertEquals("https://x/600x600bb.jpg", CoverFetcher.upscale("https://x/170x170bb.jpg"))
        assertEquals("https://x/600x600bb.jpg", CoverFetcher.upscale("https://x/60x60bb.jpg"))
    }
}
