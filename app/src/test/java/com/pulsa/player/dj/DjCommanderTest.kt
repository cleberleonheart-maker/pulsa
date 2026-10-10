package com.pulsa.player.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun action_video_open() {
        assertEquals("video_open", DjCommander.action(DjCommander.norm("virgin, mostra o video")))
        assertEquals("video_open", DjCommander.action(DjCommander.norm("virgin, abre o video")))
        assertEquals("video_open", DjCommander.action(DjCommander.norm("virgin, volta pro filme")))
        assertEquals("video_open", DjCommander.action(DjCommander.norm("virgin, abre o episodio")))
    }

    @Test
    fun action_video_play() {
        // "continua"/"retoma"/"toca" RETOMAM, não abrem tela: `showPlaying` chama
        // `startActivity`, que do fundo o Android 10+ bloqueia, então abrir a tela ao
        // fundo só produzia a resposta "Abrindo X" sem abrir nada.
        assertEquals("video_play", DjCommander.action(DjCommander.norm("virgin, continua o filme")))
        assertEquals("video_play", DjCommander.action(DjCommander.norm("virgin, retoma o filme")))
        assertEquals("video_play", DjCommander.action(DjCommander.norm("virgin, toca o filme")))
        assertEquals("video_play", DjCommander.action(DjCommander.norm("virgin, continua o video")))
        // Acento: só bate depois do `norm`.
        assertEquals("video_play", DjCommander.action(DjCommander.norm("Virgin, continua o vídeo")))
        // "continua" sem citar o que é continua sendo o play da música.
        assertEquals("play", DjCommander.action(DjCommander.norm("virgin, continua")))
    }

    @Test
    fun action_video_by_name() {
        // Com nome depois do verbo+artigo+palavra de vídeo, vira busca na biblioteca/histórico
        // em vez de retomar o que já está tocando.
        assertEquals("video_by_name", DjCommander.action(DjCommander.norm("virgin, toca o filme matrix")))
        assertEquals("video_by_name", DjCommander.action(DjCommander.norm("virgin, abre o filme matrix reloaded")))
        assertEquals("video_by_name", DjCommander.action(DjCommander.norm("virgin, mostra o video do show")))
        // Sem nome sobra retomar/abrir: a palavra de vídeo sozinha não é um nome.
        assertEquals("video_play", DjCommander.action(DjCommander.norm("virgin, toca o filme")))
        assertEquals("video_open", DjCommander.action(DjCommander.norm("virgin, mostra o video")))
    }

    @Test
    fun videoQuery_extrai_o_nome() {
        assertEquals("matrix", DjCommander.videoQuery(DjCommander.norm("virgin, toca o filme matrix")))
        assertEquals(
            "matrix reloaded",
            DjCommander.videoQuery(DjCommander.norm("virgin, abre o filme matrix reloaded"))
        )
        assertNull(DjCommander.videoQuery(DjCommander.norm("virgin, toca o filme")))
        assertNull(DjCommander.videoQuery(DjCommander.norm("virgin, pausa o filme")))
        assertNull(DjCommander.videoQuery(DjCommander.norm("virgin, pausa a musica")))
    }

    @Test
    fun action_video_back() {
        assertEquals("video_back", DjCommander.action(DjCommander.norm("virgin, volta 30 segundos do filme")))
        assertEquals("video_back", DjCommander.action(DjCommander.norm("virgin, volta pra tras no video")))
        assertEquals("video_back", DjCommander.action(DjCommander.norm("virgin, recua o filme")))
        // Acento: "vídeo" só bate depois do `norm`.
        assertEquals("video_back", DjCommander.action(DjCommander.norm("Virgin, volta o vídeo")))
    }

    @Test
    fun video_back_sem_citar_o_filme() {
        // "atrás" não é palavra de comando de música, então "manda pra trás" não precisa
        // dizer o nome do vídeo para não cair em prev pelo "volta" genérico.
        assertEquals("video_back", DjCommander.action(DjCommander.norm("virgin, manda pra tras")))
        assertEquals("video_back", DjCommander.action(DjCommander.norm("virgin, manda pra trás")))
        assertEquals("video_back", DjCommander.action(DjCommander.norm("virgin, volta pra tras")))
        assertEquals("video_back", DjCommander.action(DjCommander.norm("virgin, volta atras")))
        assertEquals("video_back", DjCommander.action(DjCommander.norm("virgin, volta para tras")))
        // Limite conhecido, e é o teste que trava ele: "volta 30 segundos" sem citar o que é
        // fica com a música, porque "volta" puro é trilha anterior. Só a palavra de
        // direção ("atrás") dispensa citar o filme; número de segundos não.
        assertEquals("prev", DjCommander.action(DjCommander.norm("virgin, volta 30 segundos")))
        // Abrir a tela continua funcionando sem citar o que abrir: "volta pro filme" e
        // "volta pra trás" só se separam pela palavra de direção.
        assertEquals("video_open", DjCommander.action(DjCommander.norm("virgin, volta pro filme")))
        assertEquals("video_open", DjCommander.action(DjCommander.norm("virgin, volta ao video")))
    }

    @Test
    fun action_video_pause() {
        assertEquals("video_pause", DjCommander.action(DjCommander.norm("virgin, pausa o filme")))
        assertEquals("video_pause", DjCommander.action(DjCommander.norm("virgin, para o video")))
    }

    @Test
    fun action_queue_move() {
        assertEquals("queue_move", DjCommander.action(DjCommander.norm("virgin, joga o video pro fim")))
        assertEquals("queue_move", DjCommander.action(DjCommander.norm("virgin, manda o episodio pro fim da fila")))
        assertEquals("queue_move", DjCommander.action(DjCommander.norm("virgin, bota essa musica por ultimo")))
        assertEquals("queue_move", DjCommander.action(DjCommander.norm("virgin, coloca essa no final da fila")))
        assertEquals("queue_move", DjCommander.action(DjCommander.norm("virgin, move to the end da fila")))
    }

    @Test
    fun action_queue_move_beats_video_pause_and_play() {
        // O "para" de "joga o vídeo **para** o fim" não é pausa: a reordenação vem antes.
        assertEquals("queue_move", DjCommander.action(DjCommander.norm("virgin, joga o video para o fim")))
        // Sem verbo de mover, o sinal de fim sozinho não é reordenação — cai no resto.
        assertNull(DjCommander.action(DjCommander.norm("virgin, pro fim")))
        // "volta pro filme" continua abrindo o vídeo, não virando reordenação.
        assertEquals("video_open", DjCommander.action(DjCommander.norm("virgin, volta pro filme")))
    }

    @Test
    fun queue_move_query_tipo() {
        val video = DjCommander.moveToEndQuery(DjCommander.norm("joga o video pro fim"))
        assertNotNull(video)
        assertEquals(DjCommander.QueueMove(video = true, episode = false), video)
        val episodio = DjCommander.moveToEndQuery(DjCommander.norm("manda o episodio pro fim da fila"))
        assertEquals(DjCommander.QueueMove(video = false, episode = true), episodio)
        val generico = DjCommander.moveToEndQuery(DjCommander.norm("coloca essa no final da fila"))
        assertEquals(DjCommander.QueueMove(video = false, episode = false), generico)
        assertTrue(generico!!.any)
        assertNull(DjCommander.moveToEndQuery("fim do video"))
    }

    @Test
    fun action_queue_history() {
        assertEquals("queue_history", DjCommander.action(DjCommander.norm("virgin, o que ja toceu de video hoje")))
        assertEquals("queue_history", DjCommander.action(DjCommander.norm("virgin, quais podcasts tocaram hoje")))
        assertEquals("queue_history", DjCommander.action(DjCommander.norm("virgin, o que ja passou hoje")))
        assertEquals("queue_history", DjCommander.action(DjCommander.norm("virgin, o que tocou de musica")))
        assertEquals("queue_history", DjCommander.action(DjCommander.norm("virgin, what played today")))
    }

    @Test
    fun history_query_tipo() {
        assertEquals("video", DjCommander.historyQuery(DjCommander.norm("o que ja toceu de video hoje")))
        assertEquals("video", DjCommander.historyQuery(DjCommander.norm("o que toceu de filme")))
        assertEquals("podcast", DjCommander.historyQuery(DjCommander.norm("quais podcasts tocaram hoje")))
        assertEquals("music", DjCommander.historyQuery(DjCommander.norm("o que toceu de musica")))
        assertEquals("all", DjCommander.historyQuery(DjCommander.norm("o que ja passou hoje")))
        assertNull(DjCommander.historyQuery(DjCommander.norm("toca uma musica")))
        assertNull(DjCommander.historyQuery(DjCommander.norm("virgin, o que voce acha disso")))
    }

    @Test
    fun history_query_nao_rouba_yesterday() {
        // "o que toquei"/"toquei ontem" é o yesterday, com "toquei" (1ª pessoa); o histórico
        // usa "tocou"/"tocar". Misturar faria "o que toquei ontem" cair no histórico de hoje.
        assertEquals("yesterday", DjCommander.action(DjCommander.norm("virgin, o que toquei ontem")))
        assertEquals("yesterday", DjCommander.action(DjCommander.norm("virgin, que toquei ontem")))
    }

    @Test
    fun action_download_episode() {
        // F4 · download por voz: verbo de baixar + alvo de episódio/podcast.
        assertEquals("download_episode", DjCommander.action(DjCommander.norm("virgin, baixa esse episodio pra ouvir no carro")))
        assertEquals("download_episode", DjCommander.action(DjCommander.norm("virgi, baixar o episodio novo")))
        assertEquals("download_episode", DjCommander.action(DjCommander.norm("virgin, baixa esse podcast")))
        assertEquals("download_episode", DjCommander.action(DjCommander.norm("virgi, baixe o episodio")))
    }

    @Test
    fun download_query_sem_episodio_nao_e_download() {
        // Verbo sozinho não basta: "baixa essa música X" não é este comando, e o alvo sem
        // verbo ("o episódio" com toca/abre) continua sendo play/vídeo.
        assertFalse(DjCommander.downloadQuery(DjCommander.norm("baixa essa musica")))
        assertFalse(DjCommander.downloadQuery(DjCommander.norm("episodio novo do carro")))
        // O bloco de vídeo continua em pé: "toca o episodio" sem nome vira video_play
        // (retoma), não download.
        assertEquals("video_play", DjCommander.action(DjCommander.norm("virgin, toca o episodio")))
    }

    @Test
    fun action_clean_space() {
        // F4 · limpeza por voz: frases fixas, porque "limpa"/"apaga"/"memoria" sozinhos
        // pertencem a outros ramos (limpar meme, delete de música, pendrive).
        assertEquals("clean_space", DjCommander.action(DjCommander.norm("virgin, limpa o que ta ocupando espaco")))
        assertEquals("clean_space", DjCommander.action(DjCommander.norm("virgi, libera espaco")))
        assertEquals("clean_space", DjCommander.action(DjCommander.norm("virgin, limpa os downloads")))
        assertEquals("clean_space", DjCommander.action(DjCommander.norm("virgin, apaga os downloads")))
        assertEquals("clean_space", DjCommander.action(DjCommander.norm("virgi, clean up space")))
        // Nada de roubar: apagar a música continua sendo delete, a memória continua pendrive.
        assertEquals("delete", DjCommander.action(DjCommander.norm("virgin, apaga essa musica")))
        assertEquals("pendrive", DjCommander.action(DjCommander.norm("virgin, le memoria usb")))
    }

    @Test
    fun video_back_sem_video_cai_em_prev_no_handler() {
        // O parser devolve `video_back` para a palavra de direção sozinha, e quem desvia
        // para a faixa anterior é o handler (`virgVideoBack`), que é quem sabe o que está
        // tocando. Este teste trava essa divisão: o fallback está em
        // MainVirgin/DjSession, não no DjCommander.
        assertEquals("video_back", DjCommander.action(DjCommander.norm("virgin, volta atras da musica")))
        assertEquals("video_back", DjCommander.action(DjCommander.norm("virgin, para tras")))
        assertEquals("video_back", DjCommander.action(DjCommander.norm("virgin, deixa pra tras")))
    }

    @Test
    fun video_nao_rouba_comando_da_musica() {
        // O gate `videoWord` é o que impede "volta"/"pausa"/"continua" de virarem comando
        // de vídeo no meio de uma música — a Virgin falaria de vídeo sem existir vídeo.
        assertEquals("prev", DjCommander.action(DjCommander.norm("virgin, volta")))
        assertEquals("prev", DjCommander.action(DjCommander.norm("virgin, volta a anterior")))
        assertEquals("pause", DjCommander.action(DjCommander.norm("virgin, pausa")))
        assertEquals("play", DjCommander.action(DjCommander.norm("virgin, continua")))
        assertEquals("resume", DjCommander.action(DjCommander.norm("virgin, volta pra musica")))
        assertEquals("resume", DjCommander.action(DjCommander.norm("virgin, retoma a musica")))
        // "pula" no vídeo continua sendo skip, não retrocesso: sem palavra de trás, é pulo.
        assertEquals("skip", DjCommander.action(DjCommander.norm("virgin, pula o filme")))
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

    @Test
    fun playlist_create_action() {
        assertEquals(
            "playlist_new",
            DjCommander.action(DjCommander.norm("virgin, cria uma playlist chamada batidinhas"))
        )
        assertEquals(
            "playlist_new",
            DjCommander.action(DjCommander.norm("virgin, faz uma nova playlist pra carro"))
        )
        assertEquals(
            "playlist_new",
            DjCommander.action(DjCommander.norm("virgin, cria a playlist"))
        )
    }

    @Test
    fun playlist_create_extracts_name() {
        assertEquals(
            "batidinhas",
            DjCommander.playlistName(DjCommander.norm("cria uma playlist chamada batidinhas"))
        )
        assertEquals(
            "pra carro",
            DjCommander.playlistName(DjCommander.norm("faz uma nova playlist pra carro"))
        )
        assertEquals(
            "minha lista boa",
            DjCommander.playlistName(
                DjCommander.norm("cria a playlist de minha lista boa, por favor")
            )
        )
        assertEquals(
            "foco",
            DjCommander.playlistName(DjCommander.norm("create a playlist called foco"))
        )
    }

    @Test
    fun playlist_play_action() {
        assertEquals(
            "playlist_play",
            DjCommander.action(DjCommander.norm("virgin, toca a playlist batidinhas"))
        )
        assertEquals(
            "playlist_play",
            DjCommander.action(DjCommander.norm("virgin, abre minha playlist de foco"))
        )
        assertEquals(
            "batidinhas",
            DjCommander.playlistName(DjCommander.norm("toca a playlist batidinhas"))
        )
    }

    @Test
    fun playlist_play_not_confused_with_daily_set() {
        // "playlist do dia" e set diario desde sempre: nao pode virar playlist chamada "do dia".
        assertEquals(
            "daily_set",
            DjCommander.action(DjCommander.norm("virgin, toca a playlist do dia"))
        )
        assertEquals(
            "daily_set",
            DjCommander.action(DjCommander.norm("toca a playlist de hoy"))
        )
    }

    @Test
    fun playlist_create_not_stealing_dynamic_queue() {
        // "monta uma lista de rock" ja era fila dinamica antes de existir playlist por voz.
        assertEquals(
            "dynq",
            DjCommander.action(DjCommander.norm("virgin, monta uma lista de rock"))
        )
        assertEquals(
            "dynq",
            DjCommander.action(DjCommander.norm("toca rock que nao ouco ha 2 meses"))
        )
    }

    @Test
    fun playlist_commands_need_the_word_playlist() {
        assertNull(DjCommander.action(DjCommander.norm("virgin, cria uma colecao chamada batidinhas")))
        assertNull(DjCommander.playlistName(DjCommander.norm("virgin, cria uma colecao")))
        // Sem a palavra "playlist" continua sendo só um comando de tocar qualquer coisa.
        assertEquals("play", DjCommander.action(DjCommander.norm("virgin, toca batidinhas")))
    }

    @Test
    fun playlist_name_ignores_politeness_and_punctuation() {
        assertEquals(
            "festa",
            DjCommander.playlistName(DjCommander.norm("cria playlist festa, por favor."))
        )
        // O nome é lido ("dia"), mas o comando NÃO vira playlist: quem manda é o set diário.
        assertEquals("dia", DjCommander.playlistName(DjCommander.norm("toca a playlist do dia")))
        assertEquals("daily_set", DjCommander.action(DjCommander.norm("toca a playlist do dia")))
    }
}
