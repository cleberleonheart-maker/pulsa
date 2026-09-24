package com.pulsa.player.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DjCommanderTest {

    @Test
    fun norm_removes_accents() {
        assertEquals(
            "campeira sertanejo parana coracao",
            DjCommander.norm("Campeira Sertanejo Paraná Coração")
        )
    }

    @Test
    fun action_pause_explicit_phrases() {
        assertEquals("pause", DjCommander.action(DjCommander.norm("virgin, para a musica")))
        assertEquals("pause", DjCommander.action(DjCommander.norm("virgin, para o som")))
        assertEquals("pause", DjCommander.action(DjCommander.norm("pausa")))
        assertEquals("pause", DjCommander.action(DjCommander.norm("virgin, pare")))
        assertEquals("pause", DjCommander.action(DjCommander.norm("para")))
    }

    @Test
    fun action_preposition_para_does_not_pause() {
        assertEquals("suggest", DjCommander.action(DjCommander.norm("virgin, manda uma sugestao para mim")))
        assertEquals("scan", DjCommander.action(DjCommander.norm("virgin, busca para mim")))
        assertNull(DjCommander.action(DjCommander.norm("virgin, o que voce faria para melhorar hoje")))
        assertEquals("mood_wild", DjCommander.action(DjCommander.norm("virgin, o que voce faria para animar")))
    }

    @Test
    fun action_volume_is_not_prev() {
        assertNull(DjCommander.action(DjCommander.norm("virgin, aumenta o volume")))
        assertEquals("prev", DjCommander.action(DjCommander.norm("virgin, volta")))
    }

    @Test
    fun action_confirm_beats_delete() {
        assertEquals("confirm", DjCommander.action(DjCommander.norm("pode apagar")))
        assertEquals("confirm", DjCommander.action(DjCommander.norm("sim")))
        assertEquals("delete", DjCommander.action(DjCommander.norm("apaga essa musica")))
    }

    @Test
    fun action_basic_navigation() {
        assertEquals("next", DjCommander.action(DjCommander.norm("proxima")))
        assertEquals("skip", DjCommander.action(DjCommander.norm("pula")))
        assertEquals("play", DjCommander.action(DjCommander.norm("toca")))
        assertEquals("hello", DjCommander.action(DjCommander.norm("oi")))
    }

    @Test
    fun action_resume_beats_prev_and_play() {
        assertEquals("resume", DjCommander.action(DjCommander.norm("virgin, volta pra musica")))
        assertEquals("resume", DjCommander.action(DjCommander.norm("virgin, volta a tocar")))
        assertEquals("resume", DjCommander.action(DjCommander.norm("virgin, continua de onde parou")))
        assertEquals("resume", DjCommander.action(DjCommander.norm("virgin, retoma a musica")))
        assertEquals("resume", DjCommander.action(DjCommander.norm("virgi, recomeca a musica")))
        assertEquals("resume", DjCommander.action(DjCommander.norm("virgin, de onde eu parei")))
    }

    @Test
    fun action_volta_alone_still_prev() {
        assertEquals("prev", DjCommander.action(DjCommander.norm("virgin, volta")))
        assertEquals("prev", DjCommander.action(DjCommander.norm("virgin, volta a anterior")))
    }

    @Test
    fun ambient_volume_up() {
        assertEquals(true, DjCommander.ambientVolume(DjCommander.norm("virgi, chuva mais alta"))?.up)
        assertEquals(true, DjCommander.ambientVolume(DjCommander.norm("aumenta a chuva"))?.up)
        assertEquals(true, DjCommander.ambientVolume(DjCommander.norm("virgi, deixa o oceano mais alto"))?.up)
        assertEquals("ambient_vol", DjCommander.action(DjCommander.norm("virgi, chuva mais alta")))
    }

    @Test
    fun ambient_volume_down() {
        assertEquals(false, DjCommander.ambientVolume(DjCommander.norm("virgi, abaixa o oceano"))?.up)
        assertEquals(false, DjCommander.ambientVolume(DjCommander.norm("ambiente mais baixo"))?.up)
        assertEquals("ambient_vol", DjCommander.action(DjCommander.norm("virgi, fogueira mais baixa")))
    }

    @Test
    fun ambient_volume_not_triggered() {
        assertNull(DjCommander.ambientVolume(DjCommander.norm("virgin, aumenta o volume")))
        assertNull(DjCommander.ambientVolume(DjCommander.norm("virgin, toca chuva")))
        assertNull(DjCommander.ambientVolume(DjCommander.norm("virgin, tudo mais alto que isso")))
    }
}