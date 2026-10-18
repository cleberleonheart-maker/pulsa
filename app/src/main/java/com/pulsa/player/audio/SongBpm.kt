package com.pulsa.player.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Item "BPM nas cenas" — o lado offline do andamento por faixa.
 *
 * O [BpmTap] só mede o que está **tocando** e só enquanto o crossfade está ligado; para a
 * cena "toca pra malhar" escolher por andamento de verdade é preciso o BPM de quem ainda
 * não tocou, então aqui o arquivo local é decodificado à parte (MediaExtractor + MediaCodec),
 * o envelope de energia é montado no mesmo formato do [BpmTap] (quadro de 20 ms, RMS) e o
 * `Bpm.analyze` faz o resto — a autocorrelação já testada do crossfade.
 *
 * **Por que em lote e não na abertura do app.** Decodificar 32 s de áudio custa CPU; o
 * orçamento vive onde o resultado é usado: cada pedido de cena analisa um lote pequeno do
 * próprio pool (`SceneBpm.LOTE`), de trás pra frente, e a fila melhora a cada uso — a
 * primeira cena funciona exatamente como antes (gênero + intensidade), sem atraso nenhum.
 *
 * Puro no miolo ([Acumulador] e [deEnvelope], testados com cliques sintéticos em
 * `SongBpmTest`); a decodificação é casca fina de framework, sem regra de negócio dentro.
 */
object SongBpm {

    /** Mesmo passo do [BpmTap] — o `Bpm.analyze` é calibrado para esse quadro. */
    private const val HOP_MS = BpmTap.HOP_MS

    /** 32 s de áudio: a janela do [BpmTap] (30 s) + folga para o período do BPM mais lento (70). */
    private const val ANALISE_MS = 32_000f

    /** Mesmo piso do [BpmTap]: abaixo disso a autocorrelação confunde batida com ruído. */
    private const val MIN_CONFIANCA = 0.15f

    /**
     * Envelope de energia montado quadro a quadro (um RMS por `hopFrames` de amostra).
     *
     * Puro de verdade: só bytes de PCM entrelaçados (16 bits ou float) → um valor por
     * quadro — o mesmo cálculo do processador do [BpmTap], reimplementado aqui para o
     * teste sintetizar cliques sem depender de aparelho nem de Media3.
     */
    class Acumulador(private val canais: Int, private val hopFrames: Int) {

        private val valores = ArrayList<Float>()
        private var framesNoHop = 0
        private var somaSq = 0.0

        /** Quantos quadros de energia já foram fechados (o incompleto no fim é descartado). */
        var quadros = 0
            private set

        fun push(buffer: ByteBuffer, encoding: Int) {
            val bytesPorAmostra = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
            val view = buffer.duplicate().order(ByteOrder.nativeOrder())
            val totalFrames = view.remaining() / (canais * bytesPorAmostra)
            var f = 0
            while (f < totalFrames) {
                var c = 0
                while (c < canais) {
                    val amostra = if (encoding == C.ENCODING_PCM_FLOAT) {
                        view.float.toDouble()
                    } else {
                        view.short.toDouble()
                    }
                    somaSq += amostra * amostra
                    c++
                }
                framesNoHop++
                if (framesNoHop >= hopFrames) {
                    valores.add(sqrt(somaSq / (hopFrames * canais)).toFloat())
                    quadros++
                    framesNoHop = 0
                    somaSq = 0.0
                }
                f++
            }
        }

        fun envelope(): FloatArray = valores.toFloatArray()
    }

    /**
     * BPM do envelope (20 ms/quadro), ou `null` quando não dá pra confiar.
     *
     * Silêncio e arranhão sem batida caem aqui — para a cena, `null` vira o carimbo de
     * "analisado, inconclusivo" para não decodificar a mesma faixa em todo pedido.
     */
    fun deEnvelope(energy: FloatArray): Float? {
        val beats = Bpm.analyze(energy, HOP_MS) ?: return null
        if (beats.confidence < MIN_CONFIANCA) return null
        return beats.bpm
    }

    /**
     * BPM da faixa em [path]: > 0 (achou), 0 (analisado sem batida confiável ou arquivo
     * ilegível — o chamador grava o 0 para não tentar de novo). Nunca lança: a cena não
     * pode morrer porque um arquivo está estragado.
     */
    fun analisarArquivo(path: String): Float = runCatching { decodificar(path) }.getOrDefault(0f)

    /**
     * Decodifica os primeiros [ANALISE_MS] da trilha de áudio, monta o envelope e analisa.
     *
     * Dois cortes de segurança que não são otimização: o teto de quadros evita decodificar
     * música de 10 minutos inteira, e o prazo de parede evita loop infinito em codec
     * encrenqueiro (o finally devolve o extractor mesmo no meio do caminho).
     */
    private fun decodificar(path: String): Float {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            var pista = -1
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val formato = extractor.getTrackFormat(i)
                val m = formato.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    pista = i
                    mime = m
                    break
                }
            }
            if (pista < 0) return 0f
            extractor.selectTrack(pista)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(extractor.getTrackFormat(pista), null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var acum: Acumulador? = null
            var encoding = C.ENCODING_PCM_16BIT
            var entradaFim = false
            var pronto = false
            val tetoQuadros = (ANALISE_MS / HOP_MS).toInt()
            val prazo = System.nanoTime() + 25_000_000_000L
            while (!pronto && System.nanoTime() < prazo) {
                if (!entradaFim) {
                    val bi = decoder.dequeueInputBuffer(10_000)
                    if (bi >= 0) {
                        val buf = decoder.getInputBuffer(bi)
                        val tam = if (buf == null) -1 else extractor.readSampleData(buf, 0)
                        if (tam < 0) {
                            decoder.queueInputBuffer(bi, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            entradaFim = true
                        } else {
                            decoder.queueInputBuffer(bi, 0, tam, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val bi = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    bi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val saida = decoder.outputFormat
                        encoding = if (saida.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            saida.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            C.ENCODING_PCM_16BIT
                        }
                        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return 0f
                        val canais = saida.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        val sampleRate = saida.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                        val hopFrames = (sampleRate * HOP_MS / 1000f).toInt().coerceAtLeast(1)
                        acum = Acumulador(canais, hopFrames)
                    }
                    bi >= 0 -> {
                        if (info.size > 0 && acum != null) {
                            val out = decoder.getOutputBuffer(bi)
                            if (out != null) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                acum.push(out, encoding)
                            }
                        }
                        decoder.releaseOutputBuffer(bi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) pronto = true
                        if (acum != null && acum.quadros >= tetoQuadros) pronto = true
                    }
                }
            }
            val energy = acum?.envelope() ?: return 0f
            return deEnvelope(energy) ?: 0f
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
