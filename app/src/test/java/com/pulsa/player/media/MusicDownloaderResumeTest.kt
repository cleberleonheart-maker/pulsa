package com.pulsa.player.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A retomada do download por URL. O risco de errar aqui é silencioso: anexar bytes no lugar
 * errado produz um arquivo que passa em toda checagem de tamanho e só falha na hora de tocar,
 * então os testes cobram o **quando** retomar (só com `206` e com parcial) e o isolamento entre
 * downloads diferentes.
 */
class MusicDownloaderResumeTest {

    @Test
    fun semParcialNaoPedeRange() {
        assertNull(MusicDownloader.resumeRangeHeader(0L))
        assertNull("tamanho negativo não é um parcial válido", MusicDownloader.resumeRangeHeader(-5L))
    }

    @Test
    fun comParcialPedeRangeDoByteBaixado() {
        assertEquals("bytes=1024-", MusicDownloader.resumeRangeHeader(1024L))
    }

    @Test
    fun soAnexaQuandoServidorConfirma206ComParcial() {
        assertTrue(MusicDownloader.shouldAppend(206, 500L))
        assertFalse("200 é o arquivo inteiro: anexar duplicaria o começo", MusicDownloader.shouldAppend(200, 500L))
        assertFalse("206 sem parcial é download do zero", MusicDownloader.shouldAppend(206, 0L))
    }

    @Test
    fun totalVemDoContentRange() {
        assertEquals(1000L, MusicDownloader.parseContentRangeTotal("bytes 100-199/1000"))
    }

    @Test
    fun contentRangeAusenteOuInvalidoNaoTemTotal() {
        assertNull(MusicDownloader.parseContentRangeTotal(null))
        assertNull(MusicDownloader.parseContentRangeTotal("bytes 100-199/*"))
        assertNull(MusicDownloader.parseContentRangeTotal(""))
        assertNull(MusicDownloader.parseContentRangeTotal("bytes 0-0/0"))
    }

    @Test
    fun nomeDoParcialEReconhecivelESemSeparadorDeCaminho() {
        val n = MusicDownloader.partFileName("https://x/y.mp3", "a/b:c")
        assertTrue(n.endsWith(".part"))
        assertFalse("barra viraria subpasta no caminho do parcial", n.contains("/"))
        assertFalse(n.contains(":"))
    }

    @Test
    fun mesmoDownloadTemSempreOMesmoParcial() {
        val a = MusicDownloader.partFileName("https://x/y.mp3", "Minha Faixa")
        val b = MusicDownloader.partFileName("https://x/y.mp3", "Minha Faixa")
        assertEquals(a, b)
    }

    @Test
    fun urlsDiferentesNaoCompartilhamParcial() {
        val a = MusicDownloader.partFileName("https://x/a.mp3", "Faixa")
        val b = MusicDownloader.partFileName("https://x/b.mp3", "Faixa")
        assertFalse("retomar com o parcial de outra URL colaria bytes de outro arquivo", a == b)
    }

    @Test
    fun parcialCurtoNaoPodeIrPraBiblioteca() {
        assertFalse("a conexão caiu no meio: salvar assim seria arquivo corrompido", MusicDownloader.isComplete(500L, 1000L))
    }

    @Test
    fun parcialMaiorQueOTotalTambemERejeitado() {
        assertFalse("bytes a mais colados: o arquivo não é o anunciado", MusicDownloader.isComplete(1500L, 1000L))
    }

    @Test
    fun parcialCompletoPassa() {
        assertTrue(MusicDownloader.isComplete(1000L, 1000L))
    }

    @Test
    fun semTotalConhecidoNaoTravaODownload() {
        assertTrue("servidor sem Content-Length (chunked) não pode ser julgado", MusicDownloader.isComplete(500L, -1L))
        assertTrue(MusicDownloader.isComplete(500L, 0L))
    }
}
