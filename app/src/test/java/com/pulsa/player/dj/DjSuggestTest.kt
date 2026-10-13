package com.pulsa.player.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjSuggestTest {

    @Test
    fun le_o_formato_pedido_com_pipe() {
        val s = DjSuggest.parse("Evidências | Charlie Brown Jr. | Combina com a sua noite")
        assertNotNull(s)
        assertEquals("Evidências", s!!.title)
        assertEquals("Charlie Brown Jr.", s.artist)
        assertTrue(s.reason.contains("noite"))
    }

    @Test
    fun ignora_preambulo_e_acha_a_linha_da_resposta() {
        val raw = """
            Certo, vou escolher algo da sua biblioteca.
            O melhor agora é:
            Evidências | Charlie Brown Jr. | Combina com a sua noite
        """.trimIndent()
        val s = DjSuggest.parse(raw)
        assertNotNull(s)
        assertEquals("Evidências", s!!.title)
    }

    @Test
    fun limpa_markdown_aspas_e_mantem_o_ponto_do_artista() {
        val s = DjSuggest.parse("**Evidências** | *Charlie Brown Jr.* | *Combina.*")
        assertNotNull(s)
        assertEquals("Evidências", s!!.title)
        // O ponto do "Jr." e o do fim do motivo ficam: cortar pontuacao final
        // quebrava abreviacao de artista e nao casava mais com a biblioteca.
        assertEquals("Charlie Brown Jr.", s.artist)
        assertEquals("Combina.", s.reason)
    }

    @Test
    fun travessao_separa_titulo_artista_e_motivo() {
        val s = DjSuggest.parse("Evidências - Charlie Brown Jr. - Combina com a sua noite")
        assertNotNull(s)
        assertEquals("Evidências", s!!.title)
        assertEquals("Charlie Brown Jr.", s.artist)
        assertTrue(s.reason.contains("noite"))
    }

    @Test
    fun resposta_so_com_titulo_nao_e_descartada() {
        val s = DjSuggest.parse("Evidências |")
        assertNotNull(s)
        assertEquals("Evidências", s!!.title)
        assertEquals("", s.artist)
    }

    @Test
    fun texto_sem_separador_nao_vira_sugestao() {
        assertNull(DjSuggest.parse("Nao consigo decidir nada hoje, desculpe"))
        assertNull(DjSuggest.parse(""))
        assertNull(DjSuggest.parse("   \n  "))
    }

    @Test
    fun fala_sem_artista_nao_diz_de_ponto() {
        val fala = DjSuggest.toSpeech(DjSuggest.Suggestion("Evidências", "", "Combina"))
        assertTrue(fala.startsWith("Evidências"))
        assertTrue(!fala.contains("de ."))
        assertTrue(fala.contains("Evidências"))
    }

    @Test
    fun fala_com_artista_mantem_o_montado() {
        val fala = DjSuggest.toSpeech(DjSuggest.Suggestion("Evidências", "Charlie Brown Jr.", "Combina"))
        assertEquals("Evidências, de Charlie Brown Jr.. Combina", fala)
    }

    @Test
    fun descarta_o_raciocinio_e_fica_com_a_resposta() {
        val parts = listOf(
            DjSuggest.Part("Vou pensar: a melhor e uma musica de 2003", thought = true),
            DjSuggest.Part("Evidências | Charlie Brown Jr. | Combina", thought = false)
        )
        val answer = DjSuggest.answerFrom(parts)
        assertNotNull(answer)
        assertEquals("Evidências | Charlie Brown Jr. | Combina", answer)
        assertEquals("Evidências", DjSuggest.parse(answer!!)!!.title)
    }

    @Test
    fun junta_varias_partes_de_resposta() {
        val parts = listOf(
            DjSuggest.Part("Evidências | Charlie Brown Jr.", thought = false),
            DjSuggest.Part("| Combina com a sua noite", thought = false)
        )
        val answer = DjSuggest.answerFrom(parts)
        assertNotNull(answer)
        assertEquals("Charlie Brown Jr.", DjSuggest.parse(answer!!)!!.artist)
    }

    @Test
    fun so_raciocinio_sem_resposta_devolve_nada() {
        val parts = listOf(DjSuggest.Part("Estou analisando a biblioteca...", thought = true))
        assertNull(DjSuggest.answerFrom(parts))
    }

    @Test
    fun resposta_vazia_devolve_nada() {
        assertNull(DjSuggest.answerFrom(emptyList()))
        assertNull(DjSuggest.answerFrom(listOf(DjSuggest.Part("   ", thought = false))))
    }

    // -- F5 · Sumário falado de episódio ------------------------------------------------

    @Test
    fun limpa_tags_e_entidades_da_descricao_do_feed() {
        val raw = "<p>Bom dia &amp; boa sorte!</p> <a href=\"x\">link</a> &#39;Veja&#39;"
        val clean = DjSuggest.cleanDescription(raw)
        assertEquals("Bom dia & boa sorte! link 'Veja'", clean)
    }

    @Test
    fun resumo_offline_pega_as_tres_primeiras_frases_sem_html() {
        val desc = "<p>Primeira coisa importante aconteceu ontem.</p>" +
            "<p>Depois falamos sobre cafe e sobre a chuva que caiu.</p>" +
            "<p>Terceira ideia veio do ouvinte e rendeu debate.</p>" +
            "<p>Uma quarta frase que nao deve aparecer no resumo.</p>"
        val out = DjSuggest.offlineSummary(desc)
        assertNotNull(out)
        assertTrue(!out!!.contains("<"))
        assertTrue(out.contains("Primeira coisa importante"))
        assertTrue(out.contains("Terceira ideia"))
        assertTrue(!out.contains("quarta frase"))
    }

    @Test
    fun resumo_offline_sem_descricao_devolve_nada() {
        assertNull(DjSuggest.offlineSummary(""))
        assertNull(DjSuggest.offlineSummary("   \n  "))
        assertNull(DjSuggest.offlineSummary("<p></p>"))
    }

    @Test
    fun prompt_do_resumo_leva_titulo_e_descricao_e_pede_tres_frases() {
        val prompt = DjSuggest.summaryPrompt("Episodio 12", "<p>Falamos de tudo um pouco.</p>")
        assertTrue(prompt.contains("Episodio 12"))
        assertTrue(prompt.contains("Falamos de tudo um pouco."))
        assertTrue(!prompt.contains("<p>"))
        assertTrue(prompt.contains("três frases"))
    }
}
