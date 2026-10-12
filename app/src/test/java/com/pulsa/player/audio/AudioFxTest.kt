package com.pulsa.player.audio

import com.pulsa.player.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F5 · Modo podcast no EQ.
 *
 * Só a escolha do preset é testada (a aplicação no `Equalizer` é hardware). O que importa:
 * o podcast ganha o preset de voz no modo automático **antes** do gênero — o gênero de um feed
 * não descreve a mixagem da fala — e a escolha manual do usuário nunca é sobrescrita.
 */
class AudioFxTest {

    @Test
    fun `no automatico o podcast pega o preset de voz`() {
        assertEquals(
            Settings.QUALITY_PODCAST,
            AudioFx.presetKey(Settings.QUALITY_AUTO, genre = null, isPodcast = true)
        )
    }

    @Test
    fun `o podcast vence o genero do feed`() {
        // "jazz" levaria a um preset de música, mas é fala: tem que ser voz.
        assertEquals(
            Settings.QUALITY_PODCAST,
            AudioFx.presetKey(Settings.QUALITY_AUTO, genre = "jazz", isPodcast = true)
        )
    }

    @Test
    fun `no automatico a musica segue o genero`() {
        assertEquals(
            Settings.QUALITY_ROCK,
            AudioFx.presetKey(Settings.QUALITY_AUTO, genre = "rock", isPodcast = false)
        )
    }

    @Test
    fun `no automatico sem genero cai no default`() {
        assertEquals(
            Settings.QUALITY_DEFAULT,
            AudioFx.presetKey(Settings.QUALITY_AUTO, genre = null, isPodcast = false)
        )
    }

    @Test
    fun `escolha manual sempre ganha`() {
        assertEquals(
            Settings.QUALITY_BASS,
            AudioFx.presetKey(Settings.QUALITY_BASS, genre = "rock", isPodcast = true)
        )
        assertEquals(
            Settings.QUALITY_VOICES,
            AudioFx.presetKey(Settings.QUALITY_VOICES, genre = null, isPodcast = true)
        )
    }

    @Test
    fun `podcast escolhido a mao vale para musica tambem`() {
        assertEquals(
            Settings.QUALITY_PODCAST,
            AudioFx.presetKey(Settings.QUALITY_PODCAST, genre = "rock", isPodcast = false)
        )
    }
}
