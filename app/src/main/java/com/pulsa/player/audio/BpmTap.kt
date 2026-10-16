package com.pulsa.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Item 130 (fase 2) — o "ouvido" do crossfade por batida.
 *
 * Entra na cadeia de áudio (ver [SpatialRenderersFactory]) e **só espia**: copia o PCM pra um
 * rascunho, mede a energia por quadro e devolve o áudio intacto. Nunca altera uma amostra —
 * o pior caso de um erro aqui é o BPM não sair, jamais o som quebrar.
 *
 * **Por que processador e não decodificar o arquivo.** Decodificar à parte (MediaExtractor +
 * MediaCodec) custa CPU e bateria e é específico de formato; o áudio já passa decodificado
 * por aqui de graça, tocando de verdade. É a mesma ideia do [SilenceSkipper] e do
 * [SpatialAudio], e reaproveita o fato de a cadeia já existir ([SpatialRenderersFactory]).
 *
 * **Onde a fase nasce.** O relógio é o contador de quadros desde o último `onFlush` — que o
 * Media3 dispara ao trocar de faixa/seek. Então `nowMs = quadros * [HOP_MS]` é o tempo de
 * áudio desde o começo da faixa, que é o que a [Bpm.msToNextBeat] precisa para dizer quando
 * bate a próxima batida.
 *
 * O estado é de instância única (`object`) porque a fábrica instala sempre o mesmo
 * processador nas duas instâncias de player — igual ao [SilenceSkipper]. Como só se lê o
 * envelope **antes** do crossfade começar (a faixa que está saindo), a mistura momentânea dos
 * dois lados durante a sobreposição não atrapalha.
 */
@UnstableApi
object BpmTap {

    /** Tamanho do quadro de energia. 20 ms dá resolução boa de batida sem custo relevante. */
    const val HOP_MS = 20f

    /** Quantos quadros guardar: 30 s de passado, folga de sobra para um período de BPM. */
    private const val CAPACITY = 1500

    /** Ligado pelo `PlaybackService` só quando o crossfade está ativo. */
    @Volatile
    var enabled = false

    private val lock = Any()

    /** Energia (RMS) por quadro, em ordem cronológica. */
    private val energy = FloatArray(CAPACITY)
    private var writeIdx = 0
    private var filled = 0
    private var hopIndex = 0L

    private val processorImpl = TapProcessor()

    fun audioProcessor(): AudioProcessor = processorImpl

    /** Zera o que foi acumulado (troca manual de faixa, pausa longa). */
    fun reset() {
        synchronized(lock) { clearLocked() }
    }

    /**
     * Quanto falta para a próxima batida, ou `null` se ainda não dá pra confiar.
     *
     * Chamado do thread principal no `PlaybackService`; a leitura do envelope é copiada sob
     * trava, então o thread de áudio não trava por causa da análise.
     */
    fun msToNextBeat(): Float? {
        val snapshot: FloatArray
        val nowMs: Float
        synchronized(lock) {
            if (filled < 64) return null
            snapshot = chronologicalLocked()
            nowMs = hopIndex * HOP_MS
        }
        val beats = Bpm.analyze(snapshot, HOP_MS) ?: return null
        // Confiança baixa = batida provável de ruído; melhor não alinhar.
        if (beats.confidence < MIN_CONFIDENCE) return null
        return Bpm.msToNextBeat(beats, nowMs)
    }

    private fun clearLocked() {
        energy.fill(0f)
        writeIdx = 0
        filled = 0
        hopIndex = 0L
    }

    /** Copia o ring buffer em ordem cronológica (mais antigo → mais novo). */
    private fun chronologicalLocked(): FloatArray {
        val out = FloatArray(filled)
        if (filled < CAPACITY) {
            System.arraycopy(energy, 0, out, 0, filled)
        } else {
            val tail = CAPACITY - writeIdx
            System.arraycopy(energy, writeIdx, out, 0, tail)
            System.arraycopy(energy, 0, out, tail, writeIdx)
        }
        return out
    }

    private fun pushLocked(value: Float) {
        energy[writeIdx] = value
        writeIdx = (writeIdx + 1) % CAPACITY
        if (filled < CAPACITY) filled++
        hopIndex++
    }

    /** Abaixo disto o envelope é ruído demais para a autocorrelação valer. */
    private const val MIN_CONFIDENCE = 0.15f

    private class TapProcessor : BaseAudioProcessor() {

        private var supported = false
        private var format = 0
        private var channels = 2
        private var hopFrames = 1
        private var framesInHop = 0
        private var sumSq = 0.0

        private var scratch = ByteBuffer.allocate(0)

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            val enc = inputAudioFormat.encoding
            supported = enc == C.ENCODING_PCM_16BIT || enc == C.ENCODING_PCM_FLOAT
            format = enc
            channels = inputAudioFormat.channelCount.coerceAtLeast(1)
            hopFrames = (inputAudioFormat.sampleRate * BpmTap.HOP_MS / 1000f).toInt().coerceAtLeast(1)
            framesInHop = 0
            sumSq = 0.0
            // Formato novo (nova faixa no mesmo player) começa o envelope do zero.
            if (BpmTap.enabled) BpmTap.reset()
            return inputAudioFormat
        }

        override fun isActive(): Boolean = supported

        override fun onFlush() {
            framesInHop = 0
            sumSq = 0.0
            if (BpmTap.enabled) BpmTap.reset()
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val size = inputBuffer.remaining()
            ensureScratch(size)
            scratch.clear()
            scratch.put(inputBuffer)
            scratch.flip()

            if (BpmTap.enabled && supported) accumulate(scratch.duplicate())

            val out = replaceOutputBuffer(size)
            out.put(scratch)
            out.flip()
        }

        /** Só lê: percorre as amostras do rascunho somando o quadrado (energia). */
        private fun accumulate(view: ByteBuffer) {
            view.order(ByteOrder.nativeOrder())
            val totalFrames = view.remaining() / (channels * bytesPerSample())
            var f = 0
            while (f < totalFrames) {
                var c = 0
                while (c < channels) {
                    val sample = readSample(view)
                    sumSq += sample * sample
                    c++
                }
                framesInHop++
                if (framesInHop >= hopFrames) {
                    val rms = sqrt(sumSq / (hopFrames * channels)).toFloat()
                    synchronized(lock) { pushLocked(rms) }
                    framesInHop = 0
                    sumSq = 0.0
                }
                f++
            }
        }

        private fun bytesPerSample(): Int =
            if (format == C.ENCODING_PCM_FLOAT) 4 else 2

        private fun readSample(view: ByteBuffer): Double {
            return if (format == C.ENCODING_PCM_FLOAT) {
                view.float.toDouble()
            } else {
                view.short.toDouble()
            }
        }

        private fun ensureScratch(size: Int) {
            if (scratch.capacity() >= size) return
            scratch = ByteBuffer.allocate(size + 16)
            scratch.order(ByteOrder.nativeOrder())
        }
    }
}
