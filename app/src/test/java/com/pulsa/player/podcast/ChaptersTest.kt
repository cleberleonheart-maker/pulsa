package com.pulsa.player.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {

    @Test
    fun le_o_indice_com_minutos_e_segundos() {
        val desc = "00:00 Abertura\n03:20 Sobre o café\n12:45 Encerramento"
        val chapters = Chapters.parse(desc)
        assertEquals(3, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        assertEquals("Abertura", chapters[0].title)
        assertEquals((3 * 60 + 20) * 1000L, chapters[1].startMs)
        assertEquals("Sobre o café", chapters[1].title)
        assertEquals((12 * 60 + 45) * 1000L, chapters[2].startMs)
        assertEquals("Encerramento", chapters[2].title)
    }

    @Test
    fun aceita_hora_cheia_no_carimbo() {
        val desc = "00:00 Intro\n1:02:30 O convidado\n2:15:00 Fim"
        val chapters = Chapters.parse(desc)
        assertEquals(3, chapters.size)
        assertEquals((1 * 3600 + 2 * 60 + 30) * 1000L, chapters[1].startMs)
        assertEquals((2 * 3600 + 15 * 60) * 1000L, chapters[2].startMs)
    }

    @Test
    fun sem_dois_carimbos_nao_e_capitulo() {
        assertTrue(Chapters.parse("O programa vai ao ar às 2:30 da tarde.").isEmpty())
        assertTrue(Chapters.parse("").isEmpty())
        assertTrue(Chapters.parse("   ").isEmpty())
    }

    @Test
    fun descarta_lista_fora_de_ordem() {
        // Dois carimbos, mas o segundo volta no tempo: não é índice, é conversa.
        assertTrue(Chapters.parse("10:00 Depois e então 05:00 Antes").isEmpty())
    }

    @Test
    fun limpa_parenteses_e_separadores_do_rotulo() {
        // Sem quebras de linha (tags viraram espaços no stripHtml): o corte é pelo carimbo.
        val chapters = Chapters.parse("(00:00) Abertura (05:00) - Tema do dia")
        assertEquals(2, chapters.size)
        assertEquals("Abertura", chapters[0].title)
        assertEquals("Tema do dia", chapters[1].title)
    }

    @Test
    fun ignora_carimbo_invalido_como_minuto_60() {
        assertTrue(Chapters.parse("00:00 Um\n60:00 Dois").isEmpty())
    }

    @Test
    fun ultimo_rotulo_para_na_quebra_de_linha() {
        val desc = "00:00 Abertura\n03:20 O assunto do dia\nA gente conversou muito depois disso."
        val chapters = Chapters.parse(desc)
        assertEquals(2, chapters.size)
        assertEquals("O assunto do dia", chapters[1].title)
    }
}
