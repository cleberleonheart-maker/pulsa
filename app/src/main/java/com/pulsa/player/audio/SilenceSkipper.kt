package com.pulsa.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Corta os silêncios longos do áudio — pensado para o **podcast**, não para a música.
 *
 * Episódio costuma ter pausas de segundos entre blocos (e, no meio, o respiro do apresentador
 * antes da propaganda). Numa música, pausa é parte da obra: cortar acabaria com o silêncio
 * cênico e o "respiro" entre faixas. Por isso o processador entra na cadeia (ver
 * [SpatialRenderersFactory]) mas só age quando [SilenceSkipper.enabled] está ligado — e quem
 * liga é o `PlaybackService`, apenas enquanto um episódio toca.
 *
 * O gatilho de liga/desliga é lido **por buffer** no [SilenceSkippingProcessor.queueInput],
 * igual ao [SpatialAudio]: o `isActive()` da cadeia só é reconsultado em configure/flush, então
 * usá-lo como interruptor prenderia o efeito ao momento em que o player nasceu.
 *
 * Só mexe em PCM 16-bit; qualquer outro formato (float, surround) passa reto. Nesses casos o
 * recurso simplesmente não atua — degradar em silêncio é melhor que estourar exceção e derrubar
 * a cadeia inteira (o `AudioProcessingPipeline` não captura `UnhandledAudioFormatException`).
 */
@UnstableApi
object SilenceSkipper {

    /** Ligado pelo [com.pulsa.player.playback.PlaybackService] enquanto um podcast toca. */
    @Volatile
    var enabled = false

    private val instance = SilenceSkippingProcessor()

    fun audioProcessor(): AudioProcessor = instance

    /** Silêncio = nenhuma amostra do quadro passa de [THRESHOLD] em módulo. */
    private const val THRESHOLD = 500

    /** Só corta pausas **mais longas** que isto; abaixo disso é respiração normal da fala. */
    private const val MIN_SILENCE_MS = 500L

    /** Quanto de silêncio manter na borda do corte, para não decepar a cauda de uma palavra. */
    private const val KEEP_MS = 200L

    /**
     * O quadro é silencioso? Lê PCM 16-bit little-endian (a ordem nativa no Android).
     *
     * Pura e testável: recebe os bytes e o deslocamento do quadro, sem estado.
     */
    internal fun frameIsSilent(bytes: ByteArray, offset: Int, channels: Int, threshold: Int): Boolean {
        var c = 0
        while (c < channels) {
            val lo = bytes[offset + c * 2].toInt() and 0xFF
            val hi = bytes[offset + c * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            if (abs(sample) > threshold) return false
            c++
        }
        return true
    }

    @UnstableApi
    private class SilenceSkippingProcessor : BaseAudioProcessor() {

        private var supported = false
        private var channels = 2
        private var frameBytes = 4
        private var minSilenceFrames = 1
        private var keepFrames = 0

        /** Frames silenciosos ainda **não decididos** (o corte precisa saber se a pausa continua). */
        private var pending = ByteArray(0)
        private var pendingFrames = 0

        /** Já confirmou uma pausa longa: descarta os silêncios até voltar som. */
        private var inSilence = false

        /** Último estado do interruptor, para limpar a pausa pendente ao ligar/desligar. */
        private var lastEnabled = false

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
            if (!supported) return inputAudioFormat
            channels = inputAudioFormat.channelCount.coerceAtLeast(1)
            frameBytes = inputAudioFormat.bytesPerFrame.coerceAtLeast(2)
            minSilenceFrames = (inputAudioFormat.sampleRate * MIN_SILENCE_MS / 1000L).toInt().coerceAtLeast(1)
            keepFrames = (inputAudioFormat.sampleRate * KEEP_MS / 1000L).toInt().coerceIn(0, minSilenceFrames)
            val size = minSilenceFrames * frameBytes
            if (pending.size != size) pending = ByteArray(size)
            resetState()
            return inputAudioFormat
        }

        override fun isActive(): Boolean = supported

        override fun onFlush() = resetState()

        override fun onReset() {
            resetState()
            supported = false
        }

        private fun resetState() {
            pendingFrames = 0
            inSilence = false
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val size = inputBuffer.remaining()
            // Copiar para fora antes de escrever: o Media3 recicla os buffers e o de entrada pode
            // ser o mesmo objeto que o `BaseAudioProcessor` devolve como saída — mexer nele
            // depois estoura "The source buffer is this buffer" (ver SpatialAudio).
            val bytes = ByteArray(size)
            inputBuffer.get(bytes)

            // Limpa a pausa pendente quando o interruptor muda: senão um estado a meio caminho
            // (pausa começada com o efeito ligado) vazaria para quando ele voltasse.
            if (SilenceSkipper.enabled != lastEnabled) {
                lastEnabled = SilenceSkipper.enabled
                resetState()
            }

            if (!supported || !SilenceSkipper.enabled || size < frameBytes) {
                val out = replaceOutputBuffer(size)
                out.put(bytes)
                out.flip()
                return
            }

            // Pior caso da saída: todos os frames desta entrada (nada cortado) **mais** o que
            // estava pendente de buffers anteriores, que agora pode ser liberado.
            val capacity = size + pendingFrames * frameBytes
            val out = replaceOutputBuffer(capacity)

            var i = 0
            while (i + frameBytes <= size) {
                if (frameIsSilent(bytes, i, channels, THRESHOLD)) {
                    if (inSilence) {
                        // pausa longa já confirmada: descarta
                    } else {
                        System.arraycopy(bytes, i, pending, pendingFrames * frameBytes, frameBytes)
                        pendingFrames++
                        if (pendingFrames >= minSilenceFrames) {
                            inSilence = true
                            val keep = minOf(keepFrames, pendingFrames)
                            if (keep > 0) out.put(pending, 0, keep * frameBytes)
                            pendingFrames = 0
                        }
                    }
                } else {
                    if (inSilence) {
                        inSilence = false
                    } else if (pendingFrames > 0) {
                        // A pausa foi curta: não era corte, então escorre tudo.
                        out.put(pending, 0, pendingFrames * frameBytes)
                        pendingFrames = 0
                    }
                    out.put(bytes, i, frameBytes)
                }
                i += frameBytes
            }
            // Sobra um pedaço menor que um quadro: sai como está, sem tentar julgar.
            if (i < size) out.put(bytes, i, size - i)
            out.flip()
        }

        override fun onQueueEndOfStream() {
            // Fim do episódio com uma pausa pendente que nunca terminou: ela era real, libera.
            if (!supported || pendingFrames == 0) return
            val out = replaceOutputBuffer(pendingFrames * frameBytes)
            out.put(pending, 0, pendingFrames * frameBytes)
            out.flip()
            pendingFrames = 0
        }
    }
}
