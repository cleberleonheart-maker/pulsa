package com.pulsa.player.core

import com.pulsa.player.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 293 — a triagem do modo família/kids. O casamento por palavra precisa aguentar acento e
 * caixa ("Café" vs "CAFE") e não pode dar falso positivo no meio de uma palavra maior.
 */
class KidsFilterTest {

    private fun song(
        id: Long = 1L,
        title: String = "Canção limpa",
        artist: String = "Artista",
        album: String = "Álbum",
        path: String = "content://audio/1"
    ) = Song(id, title, artist, album, 1L, 180_000L, path, 2020)

    @Test
    fun normalizaAcentoECaixa() {
        assertTrue(KidsFilter.isBlocked("Só EXPLÍCITO"))
        assertTrue(KidsFilter.isBlocked("censored explicit"))
        assertTrue(KidsFilter.isBlocked("Pornografia mix"))
    }

    @Test
    fun naoDaFalsoPositivoNoMeioDaPalavra() {
        // "explicitamente" contém "explicit" — casa de propósito (marcador). Já "capital"
        // contém "puta"? Não: o token é a palavra inteira, então não casa.
        assertFalse(KidsFilter.isBlocked("Capital inicial"))
        assertFalse(KidsFilter.isBlocked("Acompanhamento"))
        assertFalse(KidsFilter.isBlocked("Reputação"))
    }

    @Test
    fun foraDoModoKidsTudoPassa() {
        val blocked = song(title = "Faixa explícita")
        assertTrue(KidsFilter.allow(blocked, kids = false))
        assertTrue(KidsFilter.filter(listOf(blocked), kids = false).size == 1)
    }

    @Test
    fun noModoKidsBarraRadioEStream() {
        val radio = song(path = "radio:http://exemplo/stream.mp3")
        val stream = song(path = "stream:http://exemplo/live.m3u8")
        val local = song()
        assertFalse(KidsFilter.allow(radio, kids = true))
        assertFalse(KidsFilter.allow(stream, kids = true))
        assertTrue(KidsFilter.allow(local, kids = true))
    }

    @Test
    fun noModoKidsBarraPorPalavra() {
        val bad = song(title = "Festa Foda", artist = "MC")
        val good = song(title = "Boa Demais", artist = "Artista")
        val kept = KidsFilter.filter(listOf(bad, good), kids = true)
        assertTrue(kept.size == 1)
        assertTrue(kept[0].title == "Boa Demais")
    }

    @Test
    fun preservaAOrdemAoFiltrar() {
        val a = song(id = 1, title = "Um")
        val b = song(id = 2, title = "Sexo explícito")
        val c = song(id = 3, title = "Três")
        val kept = KidsFilter.filter(listOf(a, b, c), kids = true)
        assertTrue(kept.map { it.id } == listOf(1L, 3L))
    }
}
