package com.pulsa.player.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A curiosidade do MusicBrainz saía sempre com a mesma cara: o banco devolve os mesmos
 * campos para o mesmo artista, e a frase era montada com um único modelo de frase — só o
 * nome, o lugar e o ano mudavam.
 *
 * Aqui a garantia é que os dados do artista rendam **mais de uma** frase, todas em português,
 * e que nenhum artista sem dado nenhum invente frase.
 */
class DjFactsTest {

    private val ivete = DjFacts.ArtistInfo(
        name = "Ivete Sangalo",
        type = "Person",
        area = "Brasil",
        beginArea = "Juazeiro",
        year = 1972,
        genres = listOf("latin pop", "axé"),
        legalName = "Ivete Maria Dias de Sangalo Cady"
    )

    @Test
    fun `a mesma artista rende mais de uma curiosidade`() {
        val facts = DjFacts.factsFor(ivete)
        assertTrue("esperava várias frases, veio ${facts.size}", facts.size >= 4)
        assertEquals("nenhuma frase repetida", facts.size, facts.distinct().size)
        assertTrue(facts.all { it.startsWith("Segundo o MusicBrainz,") })
    }

    @Test
    fun `as frases usam os dados que o MusicBrainz devolve`() {
        val facts = DjFacts.factsFor(ivete)
        assertTrue(facts.any { it.contains("Juazeiro") })
        assertTrue(facts.any { it.contains("1972") })
        assertTrue(facts.any { it.contains("Ivete Maria Dias de Sangalo Cady") })
        assertTrue(facts.any { it.contains("latin pop e axé") })
    }

    @Test
    fun `pais entra com o artigo certo e cidade sem artigo`() {
        val banda = DjFacts.ArtistInfo(
            name = "Chitãozinho & Xororó",
            type = "Group",
            area = "Brasil",
            year = 1970,
            genres = listOf("sertanejo")
        )
        val facts = DjFacts.factsFor(banda)
        assertTrue(facts.any { it.contains("do Brasil") })
        assertTrue(facts.none { it.contains("em Brasil") })
        // "começou no Brasil" — a preposição do lugar, não o nome solto.
        assertTrue(facts.any { it.contains("no Brasil, em 1970") })
    }

    @Test
    fun `artista sem dado nenhum nao inventa frase`() {
        assertTrue(DjFacts.factsFor(DjFacts.ArtistInfo(name = "X")).isEmpty())
    }

    @Test
    fun `a banda que parou conta que parou`() {
        val facts = DjFacts.factsFor(
            DjFacts.ArtistInfo(name = "Uma Banda", type = "Group", endYear = 2011)
        )
        assertTrue(facts.any { it.contains("parou em 2011") })
    }

    @Test
    fun `as curiosidades locais continuam de pe`() {
        assertTrue(DjFacts.hasCuriosity("Coldplay"))
        assertTrue(DjFacts.hasCuriosity("chitãozinho e xororó"))
        assertTrue(!DjFacts.hasCuriosity("artista que nao existe no mapa"))
    }

    @Test
    fun `o acento do nome da biblioteca nao derruba a curiosidade local`() {
        // O "Beyoncé" da biblioteca tem acento; a chave do mapa não tinha. A busca exata
        // falhava e o artista caía no MusicBrainz — a mesma frase para todo mundo.
        assertTrue(DjFacts.hasCuriosity("Beyoncé"))
        assertTrue(DjFacts.hasCuriosity("Marília Mendonça"))
        assertTrue(DjFacts.hasCuriosity("wesley safadão"))
    }

    @Test
    fun `palavra parecida nao conta a historia de outro artista`() {
        // "silva" (a banda de forró) casava dentro de "silvana": a Virgin contava a mesma
        // história para artistas diferentes.
        assertTrue(!DjFacts.hasCuriosity("Silvana"))
        assertTrue(DjFacts.hasCuriosity("Silva"))
    }
}
