package com.pulsa.player.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertEquals("resume", DjCommander.action(DjCommander.norm("virgin, recomeca a musica")))
        assertEquals("resume", DjCommander.action(DjCommander.norm("virgin, de onde eu parei")))
    }

    @Test
    fun action_volta_alone_still_prev() {
        assertEquals("prev", DjCommander.action(DjCommander.norm("virgin, volta")))
        assertEquals("prev", DjCommander.action(DjCommander.norm("virgin, volta a anterior")))
    }

    @Test
    fun ambient_volume_up() {
        assertEquals(true, DjCommander.ambientVolume(DjCommander.norm("virgin, chuva mais alta"))?.up)
        assertEquals(true, DjCommander.ambientVolume(DjCommander.norm("aumenta a chuva"))?.up)
        assertEquals(true, DjCommander.ambientVolume(DjCommander.norm("virgin, deixa o oceano mais alto"))?.up)
        assertEquals("ambient_vol", DjCommander.action(DjCommander.norm("virgin, chuva mais alta")))
    }

    @Test
    fun ambient_volume_down() {
        assertEquals(false, DjCommander.ambientVolume(DjCommander.norm("virgin, abaixa o oceano"))?.up)
        assertEquals(false, DjCommander.ambientVolume(DjCommander.norm("ambiente mais baixo"))?.up)
        assertEquals("ambient_vol", DjCommander.action(DjCommander.norm("virgin, fogueira mais baixa")))
    }

    @Test
    fun ambient_volume_not_triggered() {
        assertNull(DjCommander.ambientVolume(DjCommander.norm("virgin, aumenta o volume")))
        assertNull(DjCommander.ambientVolume(DjCommander.norm("virgin, toca chuva")))
        assertNull(DjCommander.ambientVolume(DjCommander.norm("virgin, tudo mais alto que isso")))
    }

    @Test
    fun dynq_genre_and_age() {
        val q = DjCommander.dynamicQuery(DjCommander.norm("virgin, toca rock que nao toco ha 2 meses"))
        assertNotNull(q)
        assertEquals(listOf("rock"), q!!.genres)
        assertEquals(Integer.valueOf(60), q.maxAgeDays)
    }

    @Test
    fun dynq_default_age_when_nao_toco() {
        val q = DjCommander.dynamicQuery(DjCommander.norm("toca sertanejo que nao toco"))
        assertNotNull(q)
        assertEquals(Integer.valueOf(30), q!!.maxAgeDays)
    }

    @Test
    fun dynq_plays_less_than() {
        val q = DjCommander.dynamicQuery(DjCommander.norm("virgin, monta uma fila de pagode que toquei menos de 5 vezes"))
        assertNotNull(q)
        assertEquals(listOf("pagode"), q!!.genres)
        assertEquals(Integer.valueOf(5), q.playsLessThan)
    }

    @Test
    fun dynq_skips_less_than() {
        val q = DjCommander.dynamicQuery(DjCommander.norm("toca as que pulei menos de 3 vezes"))
        assertNotNull(q)
        assertEquals(Integer.valueOf(3), q!!.skipsLessThan)
    }

    @Test
    fun dynq_favorites_only() {
        val q = DjCommander.dynamicQuery(DjCommander.norm("minhas favoritas de samba"))
        assertNotNull(q)
        assertEquals(true, q!!.favoritesOnly)
        assertEquals(listOf("samba"), q.genres)
    }

    @Test
    fun dynq_single_genre_word() {
        val q = DjCommander.dynamicQuery(DjCommander.norm("rock"))
        assertNotNull(q)
        assertEquals(listOf("rock"), q!!.genres)
    }

    @Test
    fun dynq_pop_rock_dedupes_pop() {
        val q = DjCommander.dynamicQuery(DjCommander.norm("toca pop rock"))
        assertNotNull(q)
        assertEquals(listOf("pop rock"), q!!.genres)
    }

    @Test
    fun dynq_not_triggered_without_intent() {
        assertNull(DjCommander.dynamicQuery(DjCommander.norm("virgin bomba rock na festa")))
        assertNull(DjCommander.dynamicQuery(DjCommander.norm("qual musica esta tocando")))
        assertNull(DjCommander.dynamicQuery(DjCommander.norm("curti essa")))
    }

    @Test
    fun dyng_action_beats_mix_and_skip() {
        assertEquals("dynq", DjCommander.action(DjCommander.norm("monta um mix de rock que nao ouco")))
        assertEquals("dynq", DjCommander.action(DjCommander.norm("toca as que pulei menos de 3 vezes")))
        assertEquals("mix", DjCommander.action(DjCommander.norm("virgin, um mix")))
    }

    @Test
    fun dynq_genre_match() {
        assertEquals(true, DjCommander.matchesGenre("Rock", "rock"))
        assertEquals(true, DjCommander.matchesGenre("Pop Rock", "rock"))
        assertEquals(true, DjCommander.matchesGenre("Hip-Hop", "hip hop"))
        assertEquals(false, DjCommander.matchesGenre(null, "rock"))
        assertEquals(false, DjCommander.matchesGenre("Jazz", "rock"))
    }

    @Test
    fun decade_two_digits() {
        assertEquals(Integer.valueOf(1980), DjCommander.decadeQuery(DjCommander.norm("virgin, toca anos 80")))
        assertEquals(Integer.valueOf(1990), DjCommander.decadeQuery(DjCommander.norm("decada de 90")))
        assertEquals(Integer.valueOf(1970), DjCommander.decadeQuery(DjCommander.norm("anos 70")))
        assertEquals(Integer.valueOf(1980), DjCommander.decadeQuery(DjCommander.norm("anos 80s")))
    }

    @Test
    fun decade_spoken_words() {
        assertEquals(Integer.valueOf(1980), DjCommander.decadeQuery(DjCommander.norm("virgin, toca anos oitenta")))
        assertEquals(Integer.valueOf(1990), DjCommander.decadeQuery(DjCommander.norm("toca decada de noventa")))
    }

    @Test
    fun decade_four_digits() {
        assertEquals(Integer.valueOf(2000), DjCommander.decadeQuery(DjCommander.norm("virgin, toca anos 2000")))
    }

    @Test
    fun decade_not_triggered_on_unrelated_dates() {
        assertNull(DjCommander.decadeQuery(DjCommander.norm("virgin, a musica daqueles anos")))
        assertNull(DjCommander.decadeQuery(DjCommander.norm("virgin, qual musica esta tocando")))
    }

    @Test
    fun decade_action() {
        assertEquals("decade", DjCommander.action(DjCommander.norm("virgin, toca anos 80")))
        assertEquals("decade", DjCommander.action(DjCommander.norm("virgin, toca decada de 90")))
    }

    @Test
    fun scene_query_maps_presets() {
        assertEquals(DjCommander.SCENE_MALHAR, DjCommander.sceneQuery(DjCommander.norm("virgin, toca pra malhar")))
        assertEquals(DjCommander.SCENE_MALHAR, DjCommander.sceneQuery(DjCommander.norm("ativa o modo treino")))
        assertEquals(DjCommander.SCENE_ESTUDAR, DjCommander.sceneQuery(DjCommander.norm("virgin, toca pra estudar")))
        assertEquals(DjCommander.SCENE_VIAJAR, DjCommander.sceneQuery(DjCommander.norm("radio de estrada para viajar")))
        assertEquals(DjCommander.SCENE_DIRIGIR, DjCommander.sceneQuery(DjCommander.norm("virgin, toca pra dirigir")))
        assertEquals(DjCommander.SCENE_DIRIGIR, DjCommander.sceneQuery(DjCommander.norm("modo drive")))
    }

    @Test
    fun scene_not_triggered_without_intent() {
        assertNull(DjCommander.sceneQuery(DjCommander.norm("vou malhar mais tarde")))
        assertNull(DjCommander.sceneQuery(DjCommander.norm("estudo muito")))
    }

    @Test
    fun scene_action_beats_play() {
        assertEquals("scene", DjCommander.action(DjCommander.norm("virgin, toca pra malhar")))
        assertEquals("scene", DjCommander.action(DjCommander.norm("virgin, toca pra estudar")))
        assertEquals("play", DjCommander.action(DjCommander.norm("virgin, toca")))
    }

    @Test
    fun weekly_summary_phrases() {
        assertEquals("weekly", DjCommander.action(DjCommander.norm("virgin, resumo da minha semana")))
        assertEquals("weekly", DjCommander.action(DjCommander.norm("virgin, o que ouvi essa semana")))
        assertEquals("weekly", DjCommander.action(DjCommander.norm("o que eu ouvi essa semana")))
        assertEquals("weekly", DjCommander.action(DjCommander.norm("como foi minha semana")))
    }

    @Test
    fun weekly_not_triggered_by_dynq_weeks() {
        assertEquals("dynq", DjCommander.action(DjCommander.norm("virgin, toca rock que nao toco ha 2 semanas")))
    }

    @Test
    fun alarm_query_digits() {
        val a = DjCommander.alarmQuery(DjCommander.norm("virgin, me acorda as 7h"))
        assertEquals(7, a!!.hour)
        assertEquals(0, a.minute)
        assertNull(a.ambient)
        val b = DjCommander.alarmQuery(DjCommander.norm("virgin, me acorda as 7:30 com chuva"))
        assertEquals(7, b!!.hour)
        assertEquals(30, b.minute)
        assertEquals("rain", b.ambient)
        val c = DjCommander.alarmQuery(DjCommander.norm("acorda as 9 horas da noite"))
        assertEquals(21, c!!.hour)
    }

    @Test
    fun alarm_query_spoken_words() {
        val a = DjCommander.alarmQuery(DjCommander.norm("virgin, acorda as sete e meia com trovoada"))
        assertEquals(7, a!!.hour)
        assertEquals(30, a.minute)
        assertEquals("storm", a.ambient)
    }

    @Test
    fun alarm_not_triggered_without_time() {
        assertNull(DjCommander.alarmQuery(DjCommander.norm("virgin, como foi a noite")))
        assertNull(DjCommander.alarmQuery(DjCommander.norm("que horas sao")))
    }

    @Test
    fun alarm_action() {
        assertEquals("alarm", DjCommander.action(DjCommander.norm("virgin, me acorda as 6h")))
        assertEquals("alarm_cancel", DjCommander.action(DjCommander.norm("virgin, cancela o alarme")))
        assertEquals("alarm_cancel", DjCommander.action(DjCommander.norm("desliga o despertador")))
    }

    @Test
    fun sleep_timer_parsing() {
        assertEquals(20, DjCommander.sleepTimerQuery(DjCommander.norm("virgin, para em 20 min")))
        assertEquals(45, DjCommander.sleepTimerQuery(DjCommander.norm("para em 45 minutos")))
        assertEquals(120, DjCommander.sleepTimerQuery(DjCommander.norm("parar em 2 horas")))
        assertEquals(30, DjCommander.sleepTimerQuery(DjCommander.norm("pausa em meia hora")))
    }

    @Test
    fun sleep_timer_not_confused_with_pause() {
        assertEquals("sleeptimer", DjCommander.action(DjCommander.norm("virgin, para em 20 min")))
        assertEquals("pause", DjCommander.action(DjCommander.norm("virgin, para a musica")))
        assertEquals("pause", DjCommander.action(DjCommander.norm("virgin, para")))
    }
}