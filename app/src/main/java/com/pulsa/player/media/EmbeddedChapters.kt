package com.pulsa.player.media

import com.pulsa.player.podcast.Chapter
import java.io.File
import java.io.RandomAccessFile

/**
 * Uma fonte de bytes com posição — o que o parser de capítulo precisa para não ler o
 * arquivo inteiro.
 *
 * Um MKV de 4 GB com 3 capítulos não pode ser carregado na memória para descobrir onde
 * estão os capítulos: eles ficam no **cabeçalho** do Matroska (logo depois do `Info`), e
 * o resto do arquivo é `Cluster` (vídeo). A mesma coisa no MP4: o `moov` pode estar no
 * fim do arquivo, depois de um `mdat` de vários gigabytes.
 *
 * Por isso a leitura é por **deslocamento**: o parser pula o que não interessa
 * (`seek`) e lê só os poucos KB que importam.
 */
interface ChapterBytes {
    val size: Long

    /** Lê até [length] bytes a partir de [offset]; devolve menos bytes se o arquivo acabar. */
    fun read(offset: Long, length: Int): ByteArray
}

/**
 * Os capítulos **embutidos no arquivo** — o índice que o MKV e o MP4 carregam dentro de si.
 *
 * Este é o lado que faltava do item "capítulos e marcadores" do `IDEIAS.md`: até aqui o
 * app só sabia ler o índice que o **feed de podcast** publica na descrição, e o vídeo
 * local (MKV/MP4 baixado) não tinha índice nenhum. Agora o capítulo embutido vira o mesmo
 * `Chapter` do podcast — a folha de capítulos, a voz e o item da lista não precisam saber
 * de onde o capítulo veio.
 *
 * **Por que dois formatos, e por que os dois foram verificados em arquivo de verdade:**
 * - **Matroska/MKV**: o capítulo vive no elemento `Chapters` (EBML), com o instante em
 *   unidades de *timecode scale* (1 ms por padrão) e o rótulo em `ChapString` (UTF-8).
 * - **MP4**: o capítulo vive no átomo `chpl` (`moov/udta/chpl`), que o FFmpeg e o
 *   QuickTime gravam: um contador e, para cada capítulo, o instante em 100 ns e o rótulo.
 *
 * Ambos os formatos foram testados contra arquivos gerados com `ffmpeg` (MKV e MP4 com os
 * mesmos três capítulos, incluindo acento e travessão no rótulo) — o teste unitário
 * reconstrói os mesmos bytes no código, e a verificação com os arquivos reais foi feita
 * durante o desenvolvimento.
 *
 * Tudo aqui é **puro**: lê bytes, devolve capítulos. Nenhum Android, nenhum banco, nenhum
 * arquivo aberto por conta própria — quem abre o arquivo é [ChapterFiles].
 */
object EmbeddedChapters {

    /** Os capítulos embutidos de uma fonte de bytes (detecta o formato pelo cabeçalho). */
    fun from(source: ChapterBytes): List<Chapter> {
        val head = source.read(0L, 16)
        if (head.size < 8) return emptyList()
        return when {
            isMatroska(head) -> MatroskaChapters.parse(source)
            isMp4(head) -> Mp4Chapters.parse(source)
            else -> emptyList()
        }
    }

    /** Os capítulos embutidos de um bloco de bytes (o arquivo inteiro em memória). */
    fun fromBytes(bytes: ByteArray): List<Chapter> = from(ByteArraySource(bytes))

    /** `1A 45 DF A3` — a assinatura do EBML/Matroska. */
    fun isMatroska(head: ByteArray): Boolean =
        head.size >= 4 &&
            head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
            head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()

    /** `....ftyp` — o QuickTime/MP4 sempre começa com o átomo `ftyp`. */
    fun isMp4(head: ByteArray): Boolean =
        head.size >= 8 && head[4] == 'f'.code.toByte() &&
            head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() &&
            head[7] == 'p'.code.toByte()
}

/** Fonte de bytes que mora na memória (testes e arquivos pequenos). */
class ByteArraySource(private val bytes: ByteArray) : ChapterBytes {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset >= bytes.size || length <= 0) return ByteArray(0)
        val from = offset.toInt()
        val to = minOf(bytes.size, from + length)
        if (to <= from) return ByteArray(0)
        return bytes.copyOfRange(from, to)
    }
}

/**
 * Os capítulos do **Matroska/MKV** (EBML).
 *
 * A estrutura que importa:
 * ```
 * Segment (0x18538067)
 *   Info (0x1549A966)
 *     TimecodeScale (0x2AD7B1)   → nanossegundos por unidade (padrão: 1 000 000 = 1 ms)
 *   Chapters (0x1043A770)
 *     EditionEntry (0x45B9)
 *       ChapterAtom (0xB6)
 *         ChapterTimeStart (0x91)  → instante, nas unidades do scale
 *         ChapterDisplay (0x80)
 *           ChapString (0x85)      → rótulo em UTF-8
 *           ChapLanguage (0x437C)
 * ```
 *
 * A leitura é por deslocamento: o `Cluster` (o vídeo, gigabytes) é pulado pelo tamanho
 * declarado, e o `Chapters` é lido na memória só quando aparece.
 */
object MatroskaChapters {

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_EDITION = 0x45B9L
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_TIME_START = 0x91L
    private const val ID_DISPLAY = 0x80L
    private const val ID_CHAP_STRING = 0x85L
    private const val ID_FLAG_HIDDEN = 0x45FDL
    private const val ID_DURATION = 0x4489L

    /** Scale padrão do Matroska: 1 ms por unidade de timecode. */
    private const val DEFAULT_TIMECODE_SCALE = 1_000_000L

    /** Limite do `Chapters` lido na memória (8 MB): um índice de capítulos é minúsculo. */
    private const val MAX_CHAPTERS_BYTES = 8 * 1024 * 1024

    fun parse(source: ChapterBytes): List<Chapter> {
        var pos = 0L
        while (pos < source.size) {
            val id = readId(source, pos) ?: return emptyList()
            val size = readSize(source, pos + id.second) ?: return emptyList()
            val payload = pos + id.second + size.second
            if (id.first == ID_SEGMENT) {
                return parseSegment(source, payload, size.first)
            }
            if (size.first < 0L || payload + size.first > source.size) return emptyList()
            pos = payload + size.first
        }
        return emptyList()
    }

    private fun parseSegment(source: ChapterBytes, start: Long, size: Long): List<Chapter> {
        val end = if (size < 0L) source.size else minOf(source.size, start + size)
        var pos = start
        var scale = DEFAULT_TIMECODE_SCALE
        var durationRaw: Double? = null
        val raw = ArrayList<Pair<Long, String>>()
        while (pos < end) {
            val id = readId(source, pos) ?: break
            val elementSize = readSize(source, pos + id.second) ?: break
            val payload = pos + id.second + elementSize.second
            when (id.first) {
                ID_INFO -> {
                    val info = readAll(source, payload, elementSize.first, MAX_CHAPTERS_BYTES)
                    if (info != null) {
                        val found = findTimecodeScale(info, 0, info.size)
                        if (found > 0L) scale = found
                        durationRaw = findDuration(info, 0, info.size)
                    }
                }
                ID_CHAPTERS -> {
                    val chapters = readAll(source, payload, elementSize.first, MAX_CHAPTERS_BYTES)
                    if (chapters != null) raw.addAll(parseChapters(chapters))
                }
            }
            if (elementSize.first < 0L || payload + elementSize.first > source.size) break
            pos = payload + elementSize.first
        }
        if (raw.isEmpty()) return emptyList()
        val useScaleUnits = usesScaleUnits(raw, scale, durationRaw)
        return raw
            .map { (rawTime, title) ->
                Chapter(
                    if (useScaleUnits) scaleToMs(rawTime, scale) else rawTime / 1_000_000L,
                    title
                )
            }
            .sortedBy { it.startMs }
    }

    /**
     * Em qual unidade o `ChapterTimeStart` deste arquivo foi gravado.
     *
     * **O que é fato, medido em arquivo real:** o `ffmpeg` (e o `mkvmerge`) gravam o
     * instante do capítulo em **nanossegundos**, e mudar o `TimecodeScale` do MKV não
     * muda o instante lido — logo, o valor **não** está em unidades de scale.
     *
     * **Mas a letra da especificação diz outra coisa** ("em unidades de TimecodeScale"),
     * e existem arquivos por aí escritos assim. Então o app não adivinha: ele usa a
     * **duração do próprio arquivo** (`Info/Duration`, que está em unidades de scale) como
     * régua. Se os capítulos em nanossegundo cabem na duração, é nanossegundo; se não
     * cabem mas os em escala cabem, é escala. Sem duração no arquivo, fica o nanossegundo
     * (o que o mundo grava) e, só quando o resultado é absurdo (mais de 24 h), a leitura
     * por escala é a única que faz sentido.
     */
    private fun usesScaleUnits(
        raw: List<Pair<Long, String>>,
        scale: Long,
        durationRaw: Double?
    ): Boolean {
        if (raw.isEmpty()) return false
        val durationMs = durationRaw?.let { if (scale > 0L) it * scale / 1_000_000.0 else null }
        val nsMax = raw.maxOf { it.first / 1_000_000L }
        val scaleMax = raw.maxOf { scaleToMs(it.first, scale) }
        if (durationMs != null && durationMs > 0L) {
            val tolerance = 1_000L
            val nsFits = nsMax <= durationMs + tolerance
            val scaleFits = scaleMax <= durationMs + tolerance
            return when {
                // As duas cabem: vale a que chega mais perto do fim do vídeo — é a que
                // spreade os capítulos pela linha do tempo em vez de amontoá-los no zero.
                nsFits && scaleFits -> scaleMax > nsMax
                nsFits -> false
                scaleFits -> true
                else -> false
            }
        }
        val nsSane = nsMax <= MAX_SANE_MS
        val scaleSane = scaleMax <= MAX_SANE_MS
        return !nsSane && scaleSane
    }

    /** De unidades de *timecode scale* para milissegundo. */
    private fun scaleToMs(raw: Long, scale: Long): Long {
        if (scale <= 0L) return raw
        return raw * scale / 1_000_000L
    }

    /** 24 h: acima disso, o instante não é de um capítulo de vídeo. */
    private const val MAX_SANE_MS = 24L * 60L * 60L * 1000L

    private fun parseChapters(bytes: ByteArray): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        var pos = 0
        while (pos < bytes.size) {
            val id = readId(bytes, pos) ?: break
            val size = readSize(bytes, pos + id.second) ?: break
            val payload = pos + id.second + size.second
            if (payload + size.first > bytes.size || size.first < 0) break
            if (id.first == ID_EDITION) {
                out.addAll(parseEdition(bytes, payload, payload + size.first.toInt()))
            }
            pos = payload + size.first.toInt()
        }
        return out
    }

    private fun parseEdition(bytes: ByteArray, start: Int, end: Int): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        var pos = start
        while (pos < end) {
            val id = readId(bytes, pos) ?: break
            val size = readSize(bytes, pos + id.second) ?: break
            val payload = pos + id.second + size.second
            if (size.first < 0 || payload + size.first > bytes.size) break
            if (id.first == ID_CHAPTER_ATOM) {
                parseChapterAtom(bytes, payload, payload + size.first.toInt())?.let { out.add(it) }
            }
            pos = payload + size.first.toInt()
        }
        return out
    }

    /**
     * Um `ChapterAtom`: o instante ([ID_TIME_START]) e o rótulo (o primeiro `ChapString`).
     *
     * Capítulo marcado como escondido ([ID_FLAG_HIDDEN]) é ignorado — é assim que o
     * Matroska esconde capítulo de "advertência" sem apagar.
     */
    private fun parseChapterAtom(bytes: ByteArray, start: Int, end: Int): Pair<Long, String>? {
        var time: Long? = null
        var title = ""
        var hidden = false
        var pos = start
        while (pos < end) {
            val id = readId(bytes, pos) ?: break
            val size = readSize(bytes, pos + id.second) ?: break
            val payload = pos + id.second + size.second
            if (size.first < 0 || payload + size.first > bytes.size) break
            when (id.first) {
                ID_TIME_START -> time = readUnsigned(bytes, payload, payload + size.first.toInt())
                ID_FLAG_HIDDEN -> hidden = (readUnsigned(bytes, payload, payload + size.first.toInt()) ?: 0L) != 0L
                ID_DISPLAY -> {
                    if (title.isEmpty()) {
                        title = parseDisplay(bytes, payload, payload + size.first.toInt())
                    }
                }
            }
            pos = payload + size.first.toInt()
        }
        if (hidden) return null
        val at = time ?: return null
        return at to title
    }

    private fun parseDisplay(bytes: ByteArray, start: Int, end: Int): String {
        var title = ""
        var pos = start
        while (pos < end) {
            val id = readId(bytes, pos) ?: break
            val size = readSize(bytes, pos + id.second) ?: break
            val payload = pos + id.second + size.second
            if (size.first < 0 || payload + size.first > bytes.size) break
            if (id.first == ID_CHAP_STRING && title.isEmpty()) {
                title = String(bytes, payload, size.first.toInt(), Charsets.UTF_8)
            }
            pos = payload + size.first.toInt()
        }
        return title
    }

    /**
     * O `Duration` dentro do `Info` (em unidades de timecode scale).
     *
     * **Atenção: o `Duration` do Matroska é ponto flutuante**, não inteiro — `float` (4
     * bytes) ou `double` (8 bytes). Ler como inteiro dá um número absurdo (nos arquivos
     * reais, `4.673988195547873280e18` milissegundos), e esse número absurdamente grande
     * fazia o app aceitar a leitura errada do instante do capítulo.
     */
    private fun findDuration(bytes: ByteArray, start: Int, end: Int): Double? {
        var pos = start
        while (pos < end) {
            val id = readId(bytes, pos) ?: break
            val size = readSize(bytes, pos + id.second) ?: break
            val payload = pos + id.second + size.second
            if (size.first < 0 || payload + size.first > bytes.size) break
            if (id.first == ID_DURATION) {
                val endPayload = payload + size.first.toInt()
                return when (size.first.toInt()) {
                    4 -> java.nio.ByteBuffer.wrap(bytes, payload, 4)
                        .order(java.nio.ByteOrder.BIG_ENDIAN).float.toDouble()
                    8 -> java.nio.ByteBuffer.wrap(bytes, payload, 8)
                        .order(java.nio.ByteOrder.BIG_ENDIAN).double
                    else -> readUnsigned(bytes, payload, endPayload)?.toDouble()
                }
            }
            pos = payload + size.first.toInt()
        }
        return null
    }

    /** O `TimecodeScale` dentro do `Info` (o primeiro que aparecer). */
    private fun findTimecodeScale(bytes: ByteArray, start: Int, end: Int): Long {
        var pos = start
        while (pos < end) {
            val id = readId(bytes, pos) ?: break
            val size = readSize(bytes, pos + id.second) ?: break
            val payload = pos + id.second + size.second
            if (size.first < 0 || payload + size.first > bytes.size) break
            if (id.first == ID_TIMECODE_SCALE) {
                return readUnsigned(bytes, payload, payload + size.first.toInt()) ?: DEFAULT_TIMECODE_SCALE
            }
            pos = payload + size.first.toInt()
        }
        return DEFAULT_TIMECODE_SCALE
    }

    private fun readAll(source: ChapterBytes, offset: Long, size: Long, limit: Int): ByteArray? {
        if (size < 0L || size > limit) return null
        val bytes = source.read(offset, size.toInt())
        return if (bytes.size.toLong() == size) bytes else null
    }

    /** O ID EBML: 1 a 4 bytes, com o bit de marca **mantido** no valor. */
    private fun readId(source: ChapterBytes, pos: Long): Pair<Long, Int>? {
        val first = source.read(pos, 1)
        if (first.isEmpty()) return null
        val b = first[0].toInt() and 0xFF
        if (b == 0) return null
        var length = 1
        var mask = 0x80
        while ((b and mask) == 0) {
            length++
            mask = mask shr 1
        }
        if (length > 4) return null
        val bytes = source.read(pos, length)
        if (bytes.size < length) return null
        var id = 0L
        for (i in 0 until length) id = (id shl 8) or (bytes[i].toLong() and 0xFF)
        return id to length
    }

    /** O tamanho EBML: 1 a 8 bytes, sem o bit de marca. `-1` = tamanho desconhecido. */
    private fun readSize(source: ChapterBytes, pos: Long): Pair<Long, Int>? {
        val first = source.read(pos, 1)
        if (first.isEmpty()) return null
        val b = first[0].toInt() and 0xFF
        if (b == 0) return null
        var length = 1
        var mask = 0x80
        while ((b and mask) == 0) {
            length++
            mask = mask shr 1
        }
        if (length > 8) return null
        val bytes = source.read(pos, length)
        if (bytes.size < length) return null
        var value = (b and (mask - 1)).toLong()
        for (i in 1 until length) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        val allOnes = (1L shl (7 * length)) - 1L
        return (if (value == allOnes) -1L else value) to length
    }

    private fun readId(bytes: ByteArray, pos: Int): Pair<Long, Int>? {
        if (pos >= bytes.size) return null
        val b = bytes[pos].toInt() and 0xFF
        if (b == 0) return null
        var length = 1
        var mask = 0x80
        while ((b and mask) == 0) {
            length++
            mask = mask shr 1
        }
        if (length > 4 || pos + length > bytes.size) return null
        var id = 0L
        for (i in 0 until length) id = (id shl 8) or (bytes[pos + i].toLong() and 0xFF)
        return id to length
    }

    private fun readSize(bytes: ByteArray, pos: Int): Pair<Long, Int>? {
        if (pos >= bytes.size) return null
        val b = bytes[pos].toInt() and 0xFF
        if (b == 0) return null
        var length = 1
        var mask = 0x80
        while ((b and mask) == 0) {
            length++
            mask = mask shr 1
        }
        if (length > 8 || pos + length > bytes.size) return null
        var value = (b and (mask - 1)).toLong()
        for (i in 1 until length) value = (value shl 8) or (bytes[pos + i].toLong() and 0xFF)
        val allOnes = (1L shl (7 * length)) - 1L
        return (if (value == allOnes) -1L else value) to length
    }

    /** Inteiro sem sinal big-endian (o Matroska usa 1, 2, 4 ou 8 bytes). */
    private fun readUnsigned(bytes: ByteArray, start: Int, end: Int): Long? {
        if (end <= start || end > bytes.size) return null
        var value = 0L
        for (i in start until end) {
            value = (value shl 8) or (bytes[i].toLong() and 0xFF)
            if (value < 0L) return null
        }
        return value
    }
}

/**
 * Os capítulos do **MP4/QuickTime** (átomo `chpl`).
 *
 * `moov/udta/chpl` é o que o FFmpeg e o QuickTime gravam: `versão + flags`, um byte
 * reservado, o número de capítulos e, para cada um, o instante e o rótulo em UTF-8.
 *
 * **Duas camadas, e o motivo é honesto:** o instante pode ser gravado em 8 bytes (100 ns,
 * o que o FFmpeg usa) ou em 4 bytes (100 µs, o formato mais antigo). O parser tenta as
 * duas e **só aceita a que consome o átomo inteiro** — sem isso, um arquivo com capítulos
 * em 4 bytes seria lido com instante errado, e capítulo errado é pior do que capítulo
 * nenhum (a pessoa pularia para o lugar errado achando que o índice era esse).
 */
object Mp4Chapters {

    /** Limite do `moov` lido na memória (64 MB): cabe o índice, não o vídeo. */
    private const val MAX_MOOV_BYTES = 64 * 1024 * 1024

    fun parse(source: ChapterBytes): List<Chapter> {
        var pos = 0L
        while (pos < source.size) {
            val atom = readAtom(source, pos) ?: return emptyList()
            if (atom.type == "moov") {
                val moov = readAll(source, atom.payload, atom.size, MAX_MOOV_BYTES) ?: return emptyList()
                val chpl = findChpl(moov, 0, moov.size, 0) ?: return emptyList()
                return parseChpl(chpl)
            }
            if (atom.size < 0L || atom.payload + atom.size > source.size) return emptyList()
            pos = atom.payload + atom.size
        }
        return emptyList()
    }

    /** Procura o `chpl` dentro do `moov` (normalmente em `udta`). */
    private fun findChpl(bytes: ByteArray, start: Int, end: Int, depth: Int): ByteArray? {
        if (depth > 4) return null
        var pos = start
        while (pos + 8 <= end) {
            val size = readUInt32(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            var length = size.toLong()
            if (size == 1L) {
                if (pos + 16 > end) return null
                length = readUInt64(bytes, pos + 8)
                header = 16
            } else if (size == 0L) {
                length = (end - pos).toLong()
            }
            if (length < 8) return null
            val payload = pos + header
            val payloadEnd = pos + length
            if (payloadEnd > end) return null
            if (type == "chpl") {
                return bytes.copyOfRange(payload, payloadEnd.toInt())
            }
            if (type == "udta" || type == "trak" || type == "mdia" || type == "minf") {
                val found = findChpl(bytes, payload, payloadEnd.toInt(), depth + 1)
                if (found != null) return found
            }
            pos = payloadEnd.toInt()
        }
        return null
    }

    /**
     * O `chpl`: versão, flags, byte reservado, contagem e as entradas.
     *
     * A entrada é `instante + tamanho do rótulo + rótulo`. O instante é de 8 bytes
     * (100 ns) ou de 4 bytes (100 µs) — as duas tentativas são validadas pelo tamanho
     * total do átomo.
     */
    private fun parseChpl(payload: ByteArray): List<Chapter> {
        if (payload.size < 9) return emptyList()
        val version = payload[0].toInt() and 0xFF
        val count = readUInt32(payload, 5).toInt()
        if (count <= 0 || count > 10_000) return emptyList()
        val wide = parseChplEntries(payload, count, 8)
        if (wide != null && wide.second == payload.size) return wide.first
        val narrow = parseChplEntries(payload, count, 4)
        if (narrow != null && narrow.second == payload.size) return narrow.first
        return emptyList()
    }

    /** `bytes por instante` = 8 (100 ns) ou 4 (100 µs). */
    private fun parseChplEntries(payload: ByteArray, count: Int, width: Int): Pair<List<Chapter>, Int>? {
        val out = ArrayList<Chapter>()
        var pos = 9
        for (i in 0 until count) {
            if (pos + width + 1 > payload.size) return null
            val raw = if (width == 8) readUInt64(payload, pos) else readUInt32(payload, pos)
            pos += width
            val len = payload[pos].toInt() and 0xFF
            pos++
            if (len > 0 && pos + len > payload.size) return null
            val title = if (len > 0) String(payload, pos, len, Charsets.UTF_8) else ""
            pos += len
            val ms = if (width == 8) raw / 10_000L else raw * 100L
            out.add(Chapter(ms, title))
        }
        return out to pos
    }

    private class Atom(val type: String, val payload: Long, val size: Long)

    private fun readAtom(source: ChapterBytes, pos: Long): Atom? {
        val head = source.read(pos, 16)
        if (head.size < 8) return null
        val size = readUInt32(head, 0)
        val type = String(head, 4, 4, Charsets.ISO_8859_1)
        var header = 8
        var length = size
        if (size == 1L) {
            if (head.size < 16) return null
            length = readUInt64(head, 8)
            header = 16
        } else if (size == 0L) {
            length = source.size - pos
        }
        if (length < header) return null
        return Atom(type, pos + header, length - header)
    }

    private fun readAll(source: ChapterBytes, offset: Long, size: Long, limit: Int): ByteArray? {
        if (size < 0L || size > limit) return null
        val bytes = source.read(offset, size.toInt())
        return if (bytes.size.toLong() == size) bytes else null
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    private fun readUInt64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }
}

/**
 * Lê os capítulos embutidos de um **arquivo em disco**.
 *
 * É a ponte entre o parser puro e o app: abre o arquivo, deixa o parser pular o que é
 * vídeo e devolve a lista de capítulos. Falhou a leitura (arquivo sumiu, permissão, formato
 * estranho), devolve lista vazia — capítulo faltando não pode derrubar a tela de vídeo.
 */
object ChapterFiles {

    /** Lê os capítulos embutidos de um arquivo local (MKV ou MP4). */
    fun read(file: File): List<Chapter> {
        if (!file.exists() || !file.isFile || !file.canRead()) return emptyList()
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                EmbeddedChapters.from(FileSource(raf, raf.length()))
            }
        }.getOrDefault(emptyList())
    }

    /** Fonte de bytes sobre um arquivo aberto (o `RandomAccessFile` fica com o `use`). */
    private class FileSource(
        private val raf: RandomAccessFile,
        override val size: Long
    ) : ChapterBytes {
        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0L || length <= 0 || offset >= size) return ByteArray(0)
            val want = minOf(length.toLong(), size - offset).toInt()
            val bytes = ByteArray(want)
            raf.seek(offset)
            raf.readFully(bytes)
            return bytes
        }
    }
}
