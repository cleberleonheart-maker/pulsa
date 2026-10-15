package com.pulsa.player.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A classificação de quadro do [SilenceSkipper], que é a parte pura do processador.
 *
 * O corte em si roda na thread de áudio e depende do Media3; o que dá para garantir sem
 * aparelho é a leitura do PCM 16-bit little-endian e a decisão de "silencio ou não".
 */
class SilenceSkipperTest {

    /** Escreve uma amostra 16-bit little-endian na posição do quadro. */
    private fun putSample(bytes: ByteArray, index: Int, sample: Int) {
        bytes[index] = (sample and 0xFF).toByte()
        bytes[index + 1] = ((sample shr 8) and 0xFF).toByte()
    }

    /** Um quadro estéreo (L, R) a partir de dois valores. */
    private fun stereoFrame(left: Int, right: Int): ByteArray {
        val b = ByteArray(4)
        putSample(b, 0, left)
        putSample(b, 2, right)
        return b
    }

    @Test
    fun silencio_total_e_silencio() {
        assertTrue(SilenceSkipper.frameIsSilent(stereoFrame(0, 0), 0, 2, 500))
    }

    @Test
    fun amostra_baixa_ainda_e_silencio() {
        assertTrue(SilenceSkipper.frameIsSilent(stereoFrame(400, -300), 0, 2, 500))
    }

    @Test
    fun amostra_acima_do_limite_nao_e_silencio() {
        assertFalse(SilenceSkipper.frameIsSilent(stereoFrame(0, 9000), 0, 2, 500))
    }

    @Test
    fun negativo_lido_corretamente_abaixo_do_limite() {
        // -500 com sinal certo: se o byte alto não fosse assinado, -500 viraria ~65000 e passaria.
        assertTrue(SilenceSkipper.frameIsSilent(stereoFrame(-500, 0), 0, 2, 500))
    }

    @Test
    fun amostra_negativa_forte_nao_e_silencio() {
        assertFalse(SilenceSkipper.frameIsSilent(stereoFrame(-6000, 0), 0, 2, 500))
    }

    @Test
    fun le_o_quadro_a_partir_do_deslocamento() {
        val bytes = byteArrayOf(9, 9, 9, 9) + stereoFrame(2000, 0)
        assertFalse(SilenceSkipper.frameIsSilent(bytes, 4, 2, 500))
        // O primeiro quadro (ruído de preenchimento) não influencia o segundo.
        putSample(bytes, 0, 0)
        putSample(bytes, 2, 0)
        assertTrue(SilenceSkipper.frameIsSilent(bytes, 0, 2, 500))
    }
}
