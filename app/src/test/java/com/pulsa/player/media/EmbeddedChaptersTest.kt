package com.pulsa.player.media

import com.pulsa.player.podcast.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Os capítulos embutidos no arquivo — Matroska/MKV e MP4.
 *
 * Os bytes aqui são **reconstruídos no código** com o mesmo formato que o FFmpeg grava
 * (a estrutura foi conferida contra arquivos reais gerados com `ffmpeg`, com os mesmos
 * três capítulos, incluindo acento e travessão no rótulo). O teste não depende de
 * arquivo binário no repositório, e ainda assim pega erro de byte, de endian e de unidade
 * de tempo — que é onde esse tipo de parser costuma errar.
 */
class EmbeddedChaptersTest {

    // ---------- Matroska / MKV ----------

    @Test
    fun mkvLeCapitulosComRotuloEAcento() {
        val bytes = mkv(
            listOf(
                Chapter(0L, "Abertura"),
                Chapter(5_000L, "Parte 1 - Aconstrução"),
                Chapter(12_000L, "Final - Créditos")
            )
        )
        val chapters = EmbeddedChapters.fromBytes(bytes)
        assertEquals(3, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        assertEquals("Abertura", chapters[0].title)
        assertEquals(5_000L, chapters[1].startMs)
        assertEquals("Parte 1 - Aconstrução", chapters[1].title)
        assertEquals(12_000L, chapters[2].startMs)
        assertEquals("Final - Créditos", chapters[2].title)
    }

    @Test
    fun mkvGuardaOInstanteEmNanossegundo() {
        // Medido em arquivo real: mudar o TimecodeScale do MKV **não** muda o instante do
        // capítulo. O ffmpeg (e o mkvmerge) gravam ChapterTimeStart em nanossegundos.
        val bytes = mkv(listOf(Chapter(0L, "A"), Chapter(5_000L, "B")), scale = 100L)
        val chapters = EmbeddedChapters.fromBytes(bytes)
        assertEquals(2, chapters.size)
        assertEquals(5_000L, chapters[1].startMs)
    }

    @Test
    fun mkvComCapituloEmUnidadesDeScaleUsaADuracaoDoArquivo() {
        // Arquivo escrito pela letra da spec: instante em unidades de TimecodeScale
        // (5.000 = 5 s com scale 1 ms). A duração do próprio arquivo (30 s) é a régua.
        val bytes = mkv(
            listOf(Chapter(0L, "A"), Chapter(5_000L, "B")),
            rawStarts = listOf(0L, 5_000L),
            durationMs = 30_000L
        )
        val chapters = EmbeddedChapters.fromBytes(bytes)
        assertEquals(2, chapters.size)
        assertEquals(5_000L, chapters[1].startMs)
    }

    @Test
    fun mkvIgnoraCapituloEscondido() {
        val bytes = mkv(listOf(Chapter(0L, "A")), hidden = setOf(0))
        assertTrue(EmbeddedChapters.fromBytes(bytes).isEmpty())
    }

    @Test
    fun mkvSemCapitulosDevolveVazio() {
        val bytes = mkv(emptyList())
        assertTrue(EmbeddedChapters.fromBytes(bytes).isEmpty())
    }

    @Test
    fun arquivoNaoEbookmNemMp4DevolveVazio() {
        assertTrue(EmbeddedChapters.fromBytes(ByteArray(64) { (it % 251).toByte() }).isEmpty())
        assertTrue(EmbeddedChapters.fromBytes(ByteArray(0)).isEmpty())
    }

    // ---------- MP4 ----------

    @Test
    fun mp4LeCapitulosDoAtomoChpl() {
        val bytes = mp4(
            listOf(
                Chapter(0L, "Abertura"),
                Chapter(5_000L, "Parte 1 - Aconstrução"),
                Chapter(12_000L, "Final - Créditos")
            )
        )
        val chapters = EmbeddedChapters.fromBytes(bytes)
        assertEquals(3, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        assertEquals("Abertura", chapters[0].title)
        assertEquals(5_000L, chapters[1].startMs)
        assertEquals("Parte 1 - Aconstrução", chapters[1].title)
        assertEquals(12_000L, chapters[2].startMs)
        assertEquals("Final - Créditos", chapters[2].title)
    }

    @Test
    fun mp4ComInstanteDeQuatroBytes() {
        // Formato antigo: instante em 100 µs (4 bytes) em vez de 100 ns (8 bytes).
        val bytes = mp4(listOf(Chapter(0L, "A"), Chapter(7_500L, "B")), wide = false)
        val chapters = EmbeddedChapters.fromBytes(bytes)
        assertEquals(2, chapters.size)
        assertEquals(7_500L, chapters[1].startMs)
    }

    @Test
    fun mp4SemChplDevolveVazio() {
        val bytes = mp4(emptyList())
        assertTrue(EmbeddedChapters.fromBytes(bytes).isEmpty())
    }

    // ---------- Arquivo em disco ----------

    @Test
    fun leCapitulosDeArquivoMkveMp4() {
        val mkvFile = File.createTempFile("pulsa-cap", ".mkv")
        val mp4File = File.createTempFile("pulsa-cap", ".mp4")
        try {
            mkvFile.writeBytes(mkv(listOf(Chapter(0L, "A"), Chapter(3_000L, "B"))))
            mp4File.writeBytes(mp4(listOf(Chapter(0L, "A"), Chapter(3_000L, "B"))))
            assertEquals(2, ChapterFiles.read(mkvFile).size)
            assertEquals(3_000L, ChapterFiles.read(mkvFile)[1].startMs)
            assertEquals(2, ChapterFiles.read(mp4File).size)
            assertEquals(3_000L, ChapterFiles.read(mp4File)[1].startMs)
        } finally {
            mkvFile.delete()
            mp4File.delete()
        }
    }

    @Test
    fun arquivoInexistenteDevolveVazio() {
        assertTrue(ChapterFiles.read(File("/tmp/pulsa-nao-existe.mkv")).isEmpty())
    }

    // ---------- construtores de bytes ----------

    private fun mkv(
        chapters: List<Chapter>,
        scale: Long = 1_000_000L,
        rawStarts: List<Long> = chapters.map { it.startMs * 1_000_000L },
        durationMs: Long = 30_000L,
        hidden: Set<Int> = emptySet()
    ): ByteArray {
        // O Duration do Info está em unidades de timecode scale e é **ponto flutuante**
        // (é assim que o Matroska define — e é assim que o ffmpeg grava).
        val durationRaw = durationMs * scale / 1_000_000.0
        val info = elem(
            ID_INFO,
            elem(ID_TIMECODE_SCALE, uint(scale, 3)) + elem(ID_DURATION, double(durationRaw))
        )
        val atoms = chapters.mapIndexed { index, chapter ->
            val display = elem(ID_DISPLAY, elem(ID_CHAP_STRING, chapter.title.toByteArray(Charsets.UTF_8)))
            var payload = elem(ID_TIME_START, uint(rawStarts[index], 8))
            payload += display
            if (index in hidden) payload += elem(ID_FLAG_HIDDEN, byteArrayOf(1))
            elem(ID_CHAPTER_ATOM, payload)
        }
        val chaptersElement = elem(ID_CHAPTERS, elem(ID_EDITION, atoms.fold(ByteArray(0)) { a, b -> a + b }))
        val segment = elem(ID_SEGMENT, info + chaptersElement)
        return elem(ID_EBML, ByteArray(0)) + segment
    }

    private fun mp4(chapters: List<Chapter>, wide: Boolean = true): ByteArray {
        val entries = chapters.fold(ByteArray(0)) { acc, chapter ->
            val title = chapter.title.toByteArray(Charsets.UTF_8)
            val start = if (wide) chapter.startMs * 10_000L else chapter.startMs / 100L
            acc + (if (wide) be64(start) else be32(start)) + byteArrayOf(title.size.toByte()) + title
        }
        val chpl = byteArrayOf(1, 0, 0, 0, 0) + be32(chapters.size.toLong()) + entries
        val moov = atom("moov", atom("udta", atom("chpl", chpl)))
        return atom("ftyp", "isom".toByteArray(Charsets.ISO_8859_1)) + atom("mdat", ByteArray(16)) + moov
    }

    private fun elem(id: ByteArray, payload: ByteArray): ByteArray = id + ebmlSize(payload.size) + payload

    private fun ebmlSize(value: Int): ByteArray = when {
        value < 0x7F -> byteArrayOf((0x80 or value).toByte())
        value < 0x3FFF -> byteArrayOf((0x40 or (value shr 8)).toByte(), (value and 0xFF).toByte())
        else -> byteArrayOf(
            (0x20 or (value shr 16)).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    private fun double(value: Double): ByteArray {
        val out = ByteArray(8)
        val bits = java.lang.Double.doubleToLongBits(value)
        for (i in 0 until 8) out[7 - i] = ((bits shr (8 * i)) and 0xFF).toByte()
        return out
    }

    private fun uint(value: Long, bytes: Int): ByteArray {
        val out = ByteArray(bytes)
        for (i in 0 until bytes) out[bytes - 1 - i] = ((value shr (8 * i)) and 0xFF).toByte()
        return out
    }

    private fun atom(type: String, payload: ByteArray): ByteArray =
        be32((payload.size + 8).toLong()) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private fun be32(value: Long): ByteArray = uint(value, 4)

    private fun be64(value: Long): ByteArray = uint(value, 8)

    private companion object {
        val ID_EBML = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
        val ID_SEGMENT = byteArrayOf(0x18.toByte(), 0x53.toByte(), 0x80.toByte(), 0x67.toByte())
        val ID_INFO = byteArrayOf(0x15.toByte(), 0x49.toByte(), 0xA9.toByte(), 0x66.toByte())
        val ID_TIMECODE_SCALE = byteArrayOf(0x2A.toByte(), 0xD7.toByte(), 0xB1.toByte())
        val ID_CHAPTERS = byteArrayOf(0x10.toByte(), 0x43.toByte(), 0xA7.toByte(), 0x70.toByte())
        val ID_EDITION = byteArrayOf(0x45.toByte(), 0xB9.toByte())
        val ID_CHAPTER_ATOM = byteArrayOf(0xB6.toByte())
        val ID_TIME_START = byteArrayOf(0x91.toByte())
        val ID_DISPLAY = byteArrayOf(0x80.toByte())
        val ID_CHAP_STRING = byteArrayOf(0x85.toByte())
        val ID_FLAG_HIDDEN = byteArrayOf(0x45.toByte(), 0xFD.toByte())
        val ID_DURATION = byteArrayOf(0x44.toByte(), 0x89.toByte())
    }
}
