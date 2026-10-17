package com.pulsa.player.data

import com.pulsa.player.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * As regras de playlist automática são a parte pura do "preeencher sozinho" — não tocam em
 * Context, MediaStore nem banco, então rodam na JVM. É onde está o que quebra em silêncio:
 * acento/caixa que não casam, valor vazio que casaria tudo, e o OU que decide se o título
 * "Opening" **ou** o álbum "anime" já bastam.
 */
class AutoRulesTest {

    private fun song(
        title: String = "Faixa",
        artist: String = "Artista",
        album: String = "Álbum"
    ) = Song(
        id = 1L, title = title, artist = artist, album = album,
        albumId = 0L, durationMs = 0L, path = "", year = 0
    )

    private fun rule(field: String, value: String, id: Long = 1L) =
        AutoRule(id = id, playlistId = 1L, field = field, value = value)

    @Test
    fun normalizeTiraAcentoEColocaMinusculas() {
        assertEquals("album", AutoRules.normalize("Álbum"))
        assertEquals("opening", AutoRules.normalize("OPENING"))
        assertEquals("anime", AutoRules.normalize("AnImE"))
    }

    @Test
    fun casarTituloIgnoraCaixaEAcento() {
        val s = song(title = "Opening de Ação")
        assertTrue(AutoRules.matches(rule(AutoRuleField.TITLE, "opening"), s, null))
        assertTrue(AutoRules.matches(rule(AutoRuleField.TITLE, "AÇÃO"), s, null))
        assertFalse(AutoRules.matches(rule(AutoRuleField.TITLE, "ending"), s, null))
    }

    @Test
    fun casarAlbum() {
        val s = song(album = "Trilhas de Anime Vol. 1")
        assertTrue(AutoRules.matches(rule(AutoRuleField.ALBUM, "anime"), s, null))
        assertFalse(AutoRules.matches(rule(AutoRuleField.ALBUM, "rock"), s, null))
    }

    @Test
    fun casarGeneroUsaOGenericoPassado() {
        val s = song()
        assertTrue(AutoRules.matches(rule(AutoRuleField.GENRE, "j-pop"), s, "J-Pop"))
        assertFalse(AutoRules.matches(rule(AutoRuleField.GENRE, "samba"), s, "J-Pop"))
        // Sem gênero (o MediaStore nem sempre tem), a regra de gênero não pode casar.
        assertFalse(AutoRules.matches(rule(AutoRuleField.GENRE, "j-pop"), s, null))
    }

    @Test
    fun casarArtista() {
        val s = song(artist = "Anime Sound Team")
        assertTrue(AutoRules.matches(rule(AutoRuleField.ARTIST, "sound"), s, null))
    }

    @Test
    fun campoDesconhecidoCaiNoTitulo() {
        val s = song(title = "Opening 1")
        assertTrue(AutoRules.matches(rule("qualquercoisa", "opening"), s, null))
    }

    @Test
    fun valorVazioNuncaCasa() {
        val s = song(title = "Qualquer coisa")
        assertFalse(AutoRules.matches(rule(AutoRuleField.TITLE, ""), s, null))
        assertFalse(AutoRules.matches(rule(AutoRuleField.TITLE, "   "), s, null))
    }

    @Test
    fun matchesAnyEOuEntreRegras() {
        val s = song(title = "Ending suave", album = "Anime Best")
        val rules = listOf(
            rule(AutoRuleField.TITLE, "opening"),
            rule(AutoRuleField.ALBUM, "anime", id = 2L)
        )
        // Casa pela regra do álbum (OU), mesmo não casando pela do título.
        assertTrue(AutoRules.matchesAny(rules, s, null))
    }

    @Test
    fun filterDevolveSoAsQueCasam() {
        val anime = song(title = "Opening de Anime")
        val outra = song(title = "Samba do Verão")
        val rules = listOf(rule(AutoRuleField.TITLE, "anime"))
        val result = AutoRules.filter(rules, listOf(anime, outra)) { null }
        assertEquals(listOf(anime), result)
    }

    @Test
    fun filterSemRegrasDevolveVazio() {
        val s = song(title = "Opening")
        val result = AutoRules.filter(emptyList(), listOf(s)) { null }
        assertTrue(result.isEmpty())
    }
}
