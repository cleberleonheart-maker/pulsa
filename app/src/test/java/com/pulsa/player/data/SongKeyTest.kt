package com.pulsa.player.data

import com.pulsa.player.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A identidade portátil da faixa é o que faz a nuvem e o backup resolverem uma música em outro
 * aparelho. O teste cobre as três coisas que podem dar errado: a normalização (que decide se
 * "Banda" e "Banda" são a mesma banda), a desambiguação por duração (quando duas faixas têm o
 * mesmo nome) e a recusa de adivinhar (quando o título sozinho não basta).
 */
class SongKeyTest {

    private fun song(
        id: Long,
        artist: String,
        title: String,
        durationMs: Long = 180_000L
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        album = "Álbum",
        albumId = 1L,
        durationMs = durationMs,
        path = "/sdcard/Music/$id.mp3",
        year = 2020,
        dateAdded = 0L
    )

    @Test
    fun normalizacaoTiraAcentoCaixaEEspacoDuplo() {
        // A mesma faixa pode estar com a tag em NFC num aparelho e em NFD no outro, e com o
        // espaço digitado de um jeito e de outro. Se a chave mudasse por isso, a faixa nunca
        // casaria entre aparelhos.
        assertEquals("banda", SongKey.norm("  Banda  "))
        assertEquals("banda", SongKey.norm("Banda"))
        assertEquals("rock and roll", SongKey.norm("Rock  and   Roll"))
        assertEquals("", SongKey.norm("   "))
    }

    @Test
    fun chaveEEstavelParaOMesmoArtistaETitulo() {
        val a = SongKey.of("Banda", "Musica")
        val b = SongKey.of(" banda ", "musica")
        assertEquals(a, b)
    }

    @Test
    fun chaveGuardaArtistaETitulo() {
        val key = SongKey.of("Banda", "Musica")
        assertEquals("banda", SongKey.artistOf(key))
        assertEquals("musica", SongKey.titleOf(key))
    }

    @Test
    fun indiceAchaPeloArtistaETitulo() {
        val index = SongIndex(listOf(song(1, "Banda", "Musica")))
        val achada = index.find(SongKey.of("Banda", "Musica"), 180_000L)
        assertEquals(1L, achada?.id)
    }

    @Test
    fun indiceAchaMesmaFaixaComAcentoEComaixaDiferentes() {
        val index = SongIndex(listOf(song(1, "Sérgio", "Alma")))
        val achada = index.find(SongKey.of("Sergio", "Alma"), 180_000L)
        assertEquals(1L, achada?.id)
    }

    @Test
    fun duracaoDesempataDuasFaixasComOMesmoNome() {
        // A mesma música em dois álbuns (uma faixa de 3 minutos e um remix de 6) é o caso em
        // que artista e título não bastam: a duração diz qual é a que a pessoa tinha.
        val index = SongIndex(
            listOf(
                song(1, "Banda", "Musica", durationMs = 180_000L),
                song(2, "Banda", "Musica", durationMs = 360_000L)
            )
        )
        assertEquals(2L, index.find(SongKey.of("Banda", "Musica"), 360_000L)?.id)
        assertEquals(1L, index.find(SongKey.of("Banda", "Musica"), 180_000L)?.id)
    }

    @Test
    fun tituloSoSoValeParaFaixaSemArtista() {
        val index = SongIndex(listOf(song(1, "", "Desconhecido")))
        // Artista vazio é o caso da tag sem artista: aqui o título sozinho identifica.
        val achada = index.findByTitle("Desconhecido", 180_000L)
        assertEquals(1L, achada?.id)
    }

    @Test
    fun tituloAmbiguoNaoEscolhe() {
        val index = SongIndex(
            listOf(
                song(1, "Beatles", "Yesterday"),
                song(2, "Guns", "Yesterday")
            )
        )
        // Duas faixas com o mesmo título e artistas diferentes: escolher uma seria adivinhação.
        // O degrau do título só existe para faixa **sem** artista.
        assertNull(index.findByTitle("Yesterday", 180_000L))
    }

    @Test
    fun chaveVaziaNaoEncontraNada() {
        val index = SongIndex(listOf(song(1, "Banda", "Musica")))
        assertNull(index.find("", 180_000L))
        assertTrue(index.findByTitle("", 0L) == null)
    }
}
