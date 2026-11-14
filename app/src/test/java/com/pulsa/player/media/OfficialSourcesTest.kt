package com.pulsa.player.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A camada pura das fontes oficiais: descobrir de onde veio a URL, montar a chamada da API e
 * ler a resposta. Sem rede e sem aparelho — é o que dá para testar de verdade.
 */
class OfficialSourcesTest {

    @Test
    fun `reconhece a fonte pelo host`() {
        assertEquals(
            OfficialSources.Source.APPLE,
            OfficialSources.sourceOf("https://music.apple.com/us/album/x/1440857781")
        )
        assertEquals(
            OfficialSources.Source.DEEZER,
            OfficialSources.sourceOf("https://www.deezer.com/track/123456")
        )
        assertEquals(
            OfficialSources.Source.ARCHIVE,
            OfficialSources.sourceOf("https://archive.org/details/algum-item")
        )
        assertNull(OfficialSources.sourceOf("https://exemplo.com/musica.mp3"))
        assertNull(OfficialSources.sourceOf("nao-e-url"))
    }

    @Test
    fun `apple pega o id da faixa pelo i`() {
        // O link que o app da Apple compartilha é o `?i=`: é a faixa, não o álbum.
        val url = "https://music.apple.com/us/album/brave-heart/1440857781?i=1440857790"
        assertEquals("1440857790", OfficialSources.appleLookupId(url))
        assertTrue(!OfficialSources.appleIsCollection(url))
    }

    @Test
    fun `apple pega o id do album sem confundir com o numero da faixa`() {
        // O link de álbum termina na posição da faixa ("/17"), e o id do álbum é o número
        // grande do meio. Pegar o último dízimo pegaria o "17" e a busca não achava nada.
        val url = "https://music.apple.com/us/album/brave-heart/1440857781/17"
        assertEquals("1440857781", OfficialSources.appleLookupId(url))
        assertTrue(OfficialSources.appleIsCollection(url))
    }

    @Test
    fun `apple sem id nao inventa`() {
        assertNull(OfficialSources.appleLookupId("https://music.apple.com/us/playlist/x/pl.u-abc"))
    }

    @Test
    fun `apple acha o primeiro preview da resposta`() {
        val json = """
            {"results":[
              {"wrapperType":"collection","collectionName":"Álbum"},
              {"trackName":"Faixa","artistName":"Artista","previewUrl":"https://cdn/preview.m4a"}
            ]}
        """.trimIndent()
        val (preview, name) = OfficialSources.applePreview(json)!!
        assertEquals("https://cdn/preview.m4a", preview)
        assertEquals("Artista - Faixa", name)
    }

    @Test
    fun `apple sem preview devolve nada`() {
        val json = """{"results":[{"collectionName":"Álbum","trackName":"Faixa"}]}"""
        assertNull(OfficialSources.applePreview(json))
        assertNull(OfficialSources.applePreview("isto não é json"))
    }

    @Test
    fun `deezer separa faixa de album`() {
        assertEquals("123456" to false, OfficialSources.deezerId("https://www.deezer.com/track/123456"))
        assertEquals("789" to true, OfficialSources.deezerId("https://www.deezer.com/album/789"))
        assertNull(OfficialSources.deezerId("https://www.deezer.com/artist/42"))
    }

    @Test
    fun `deezer acha o preview da faixa`() {
        val json = """
            {"title":"Faixa","preview":"https://cdns-preview.dzcdn.net/stream.mp3",
             "artist":{"name":"Artista"}}
        """.trimIndent()
        val (preview, name) = OfficialSources.deezerPreview(json)!!
        assertEquals("https://cdns-preview.dzcdn.net/stream.mp3", preview)
        assertEquals("Artista - Faixa", name)
    }

    @Test
    fun `deezer acha o preview da primeira faixa do album`() {
        // A forma real da resposta de álbum: o preview não está na raiz, está em
        // `tracks.data[0]`. Pegando a raiz, o álbum dava sempre "sem preview" — e o álbum
        // era justamente o link que a pessoa cola.
        val json = """
            {"id":302127,"title":"Discovery","nb_tracks":14,
             "tracks":{"data":[
               {"id":3135556,"title":"One More Time",
                "preview":"https://cdnt-preview.dzcdn.net/api/1/1/f/8/c/0/f8c5dc38.mp3",
                "artist":{"name":"Daft Punk"}}
             ]}}
        """.trimIndent()
        val (preview, name) = OfficialSources.deezerPreview(json)!!
        assertEquals("https://cdnt-preview.dzcdn.net/api/1/1/f/8/c/0/f8c5dc38.mp3", preview)
        assertEquals("Daft Punk - One More Time", name)
    }

    @Test
    fun `deezer sem preview devolve nada`() {
        assertNull(OfficialSources.deezerPreview("""{"title":"Faixa"}"""))
    }

    @Test
    fun `archive pega o identificador so na pagina de detalhes`() {
        assertEquals(
            "algum-item",
            OfficialSources.archiveIdentifier("https://archive.org/details/algum-item")
        )
        // A URL de download já é o arquivo: não há o que resolver.
        assertNull(
            OfficialSources.archiveIdentifier("https://archive.org/download/algum-item/audio.mp3")
        )
    }

    @Test
    fun `archive prefere o arquivo que ninguem deriva`() {
        // A forma real: o campo `original` de um derivado é o **nome** do arquivo-base
        // ("AcousticPodcast.mp3"), não uma flag "true". O arquivo que não tem `original` é o
        // de melhor qualidade — e a 64 kbps aponta para a VBR.
        val json = """
            {"files":[
              {"name":"AcousticPodcast_64kb.mp3","format":"64Kbps MP3","size":"2561171",
               "original":"AcousticPodcast.mp3","title":"Acoustic podcast"},
              {"name":"AcousticPodcast.mp3","format":"VBR MP3","size":"5123378",
               "title":"Acoustic podcast"}
            ]}
        """.trimIndent()
        val (url, title) = OfficialSources.archiveAudio("APWW011508", json)!!
        assertEquals("https://archive.org/download/APWW011508/AcousticPodcast.mp3", url)
        assertEquals("Acoustic podcast", title)
    }

    @Test
    fun `archive ignora o zip que tem mp3 no rotulo`() {
        // O item traz "64Kbps MP3 ZIP": o rótulo tem MP3, o arquivo é um zip. Escolher o
        // maior sem filtrar a extensão baixava um zip marcado como música.
        val json = """
            {"files":[
              {"name":"pacote_64kb_mp3.zip","format":"64Kbps MP3 ZIP","size":"9000000"},
              {"name":"audio.mp3","format":"VBR MP3","size":"5123378"}
            ]}
        """.trimIndent()
        val (url, _) = OfficialSources.archiveAudio("APWW011508", json)!!
        assertEquals("https://archive.org/download/APWW011508/audio.mp3", url)
    }

    @Test
    fun `archive sem original pega o maior`() {
        val json = """
            {"files":[
              {"name":"pequeno.mp3","format":"64K MP3","size":"1000"},
              {"name":"grande.mp3","format":"VBR MP3","size":"9000000"},
              {"name":"nota.txt","format":"Text","size":"10"}
            ]}
        """.trimIndent()
        val (url, _) = OfficialSources.archiveAudio("algum-item", json)!!
        assertEquals("https://archive.org/download/algum-item/grande.mp3", url)
    }

    @Test
    fun `archive sem audio devolve nada`() {
        assertNull(OfficialSources.archiveAudio("x", """{"files":[{"name":"a.pdf"}]}"""))
        assertNull(OfficialSources.archiveAudio("x", "não é json"))
    }
}
