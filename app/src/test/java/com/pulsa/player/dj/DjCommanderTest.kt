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
}