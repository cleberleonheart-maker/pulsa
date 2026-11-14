package com.pulsa.player.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConverterModelsTest {

    @Test
    fun tempoVazioValeZero() {
        assertEquals(0L, ConverterRules.parseTime(""))
        assertEquals(0L, ConverterRules.parseTime("   "))
    }

    @Test
    fun tempoAceitaSegundosMinutosEHoras() {
        assertEquals(30_000L, ConverterRules.parseTime("30"))
        assertEquals(90_000L, ConverterRules.parseTime("1:30"))
        assertEquals(3_723_000L, ConverterRules.parseTime("01:02:03"))
    }

    @Test
    fun tempoInvalidoVoltaNulo() {
        assertNull(ConverterRules.parseTime("abc"))
        assertNull(ConverterRules.parseTime("-1"))
        assertNull(ConverterRules.parseTime("1:2:3:4"))
    }

    @Test
    fun tempoFormatadoVoltaParaTexto() {
        assertEquals("0:00", ConverterRules.formatTime(0L))
        assertEquals("1:30", ConverterRules.formatTime(90_000L))
        assertEquals("1:02:03", ConverterRules.formatTime(3_723_000L))
        assertEquals("0:00", ConverterRules.formatTime(-5L))
    }

    @Test
    fun nomeTiraExtensaoDeMidia() {
        assertEquals("Vídeo", ConverterRules.outputName("Vídeo.mp4", ConverterOutput.MP4))
        assertEquals("audio", ConverterRules.outputName("  audio.m4a  ", ConverterOutput.M4A))
        assertEquals("arquivo", ConverterRules.outputName("arquivo", ConverterOutput.MP4))
    }

    @Test
    fun nomeVazioUsaPadrao() {
        assertEquals("convertido", ConverterRules.outputName("   ", ConverterOutput.MP4))
    }

    @Test
    fun nomeRemoveCaractereProibido() {
        assertTrue(!ConverterRules.outputName("a/b:c", ConverterOutput.MP4).contains(":"))
        assertTrue(!ConverterRules.outputName("a/b:c", ConverterOutput.MP4).contains("/"))
    }

    @Test
    fun bitrateAutomaticoNaoFixaValor() {
        assertNull(ConverterRules.targetBitrate(ConverterResolution.P720, ConverterQuality.AUTOMATIC))
        assertNull(ConverterRules.targetBitrate(ConverterResolution.ORIGINAL, ConverterQuality.BALANCED))
    }

    @Test
    fun bitrateSobeComAQualidade() {
        val eco = ConverterRules.targetBitrate(ConverterResolution.P720, ConverterQuality.ECONOMY)!!
        val equi = ConverterRules.targetBitrate(ConverterResolution.P720, ConverterQuality.BALANCED)!!
        val alta = ConverterRules.targetBitrate(ConverterResolution.P720, ConverterQuality.HIGH)!!
        assertTrue(eco < equi)
        assertTrue(equi < alta)
    }

    @Test
    fun tamanhoEstimadoUsaDuracaoEOpc() {
        val video = ConverterOptions(
            output = ConverterOutput.MP4,
            resolution = ConverterResolution.P720,
            quality = ConverterQuality.BALANCED
        )
        assertEquals(35_758_576L, ConverterRules.estimatedBytes(60_000L, video))
        assertEquals(34_798_576L, ConverterRules.estimatedBytes(60_000L, video.copy(removeAudio = true)))
        assertEquals(2_008_576L, ConverterRules.estimatedBytes(60_000L, video.copy(output = ConverterOutput.M4A)))
        assertEquals(0L, ConverterRules.estimatedBytes(0L, video))
    }
}
