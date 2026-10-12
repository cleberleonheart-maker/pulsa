package com.pulsa.player.core

import com.pulsa.player.model.Song
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regressão do "continuar de onde parei" ciente do tipo: o dia da sessão tem que ser
 * contado por calendário (23h de ontem não é "hoje") e a posição tem que virar minutos
 * e segundos legíveis. Sem isso o card e a fala voltariam a chamar todo mundo de música.
 */
class ResumeInfoTest {

    private fun song(path: String) = Song(
        id = 1L,
        title = "T",
        artist = "A",
        album = "B",
        albumId = 0L,
        durationMs = 0L,
        path = path,
        year = 0
    )

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun tipoDeCadaMidia() {
        assertEquals(ResumeInfo.Kind.MUSIC, ResumeInfo.kindOf(song("content://audio/1")))
        assertEquals(ResumeInfo.Kind.VIDEO, ResumeInfo.kindOf(song(Song.VIDEO_PREFIX + "7")))
        assertEquals(ResumeInfo.Kind.RADIO, ResumeInfo.kindOf(song(Song.RADIO_PREFIX + "http://a")))
        assertEquals(ResumeInfo.Kind.STREAM, ResumeInfo.kindOf(song(Song.STREAM_PREFIX + "http://a")))
        assertEquals(ResumeInfo.Kind.PODCAST, ResumeInfo.kindOf(song(Song.PODCAST_PREFIX + "3")))
        assertEquals(ResumeInfo.Kind.PODCAST, ResumeInfo.kindOf(song(Song.PODCAST_PREFIX + "v3")))
    }

    @Test
    fun relogioDePosicao() {
        assertEquals("0:00", ResumeInfo.clock(0L))
        assertEquals("0:42", ResumeInfo.clock(42_000L))
        assertEquals("1:02", ResumeInfo.clock(62_500L))
        assertEquals("34:12", ResumeInfo.clock(34L * 60_000L + 12_000L))
    }

    @Test
    fun diasPorCalendarioNaoPor24h() {
        fun at(y: Int, m: Int, d: Int, h: Int) =
            java.util.Calendar.getInstance(utc).apply {
                clear()
                set(y, m - 1, d, h, 0, 0)
            }.timeInMillis

        val ontem237h = at(2026, 10, 11, 23)
        val hoje1h = at(2026, 10, 12, 1)
        assertEquals(1, ResumeInfo.daysAgo(ontem237h, hoje1h, utc))

        val mesmodia = at(2026, 10, 12, 0)
        val maisTarde = at(2026, 10, 12, 23)
        assertEquals(0, ResumeInfo.daysAgo(mesmodia, maisTarde, utc))

        val tresDias = at(2026, 10, 9, 12)
        assertEquals(3, ResumeInfo.daysAgo(tresDias, maisTarde, utc))
    }

    @Test
    fun tempoAusenteViraPassadoDistante() {
        assertEquals(Int.MAX_VALUE, ResumeInfo.daysAgo(0L, 1_700_000_000_000L, utc))
    }
}
