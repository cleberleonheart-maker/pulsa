package com.pulsa.player.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.pulsa.player.core.Settings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Áudio 3D e surround de verdade, no PCM.
 *
 * O 8D antigo (`PlaybackService`) só mexe no volume do player, porque o ExoPlayer não expõe
 * pan estéreo. Aqui o problema é resolvido na origem: um `AudioProcessor` que gira o par
 * (L, R) como um vetor no plano estéreo e amplia a imagem por meio/side.
 *
 * Por que não `android.media.audiofx.Virtualizer`: o Virtualizer é no-op na maioria dos ROMs
 * (só o do Google chegou a implementar de verdade) e não tem pan rotativo. O DSP abaixo roda
 * em qualquer aparelho, porque é só aritmética sobre os samples.
 *
 * Ligação com o resto do áudio: o processador entra por [SpatialRenderersFactory] e não
 * trocando o `AudioProcessorChain` inteiro — o `setAudioProcessors` do `DefaultAudioSink`
 * embrulha o processador na cadeia padrão, o que mantém o Sonic (velocidade/pitch do modo dance
 * e do vídeo) e o resampler. O `audioSessionId` continua sendo criado pelo `DefaultAudioSink`,
 * então `AudioFx` (equalizador, bass boost, karaokê) e o `MusicVisualizer` seguem idênticos.
 *
 * A aritmética está em [SpatialMath], que é Kotlin puro e tem teste.
 */
@UnstableApi
object SpatialAudio {

    private const val DEFAULT_DEPTH = 0.6f
    private const val DEFAULT_INTENSITY = 0.5f

    /** 3D ligado: o som gira em volta da cabeça. */
    @Volatile
    var threeD = false

    /** Amplitude do giro, 0f (nada) a 1f (rotação completa de 360 graus). */
    @Volatile
    var depth = DEFAULT_DEPTH

    /** Surround ligado: amplia a imagem estéreo. */
    @Volatile
    var surround = false

    /** Quanto o surround abre a imagem, 0f a 1f. */
    @Volatile
    var intensity = DEFAULT_INTENSITY

    /**
 * Instância única e viva. A UI muda os parâmetros por cima enquanto a música toca, sem
     * recriar o player: os `@Volatile` são lidos uma vez por buffer na thread de áudio, então
     * o efeito entra e sai no meio da faixa.
     */
    private val instance = SpatialProcessor()

    /**
     * O processador só vale a pena rodar com pelo menos um dos dois ligado.
     *
     * Isso **não** decide se ele está na cadeia (a [SpatialRenderersFactory] sempre instala):
     * decide se o `queueInput` faz a conta ou só copia o buffer.
     */
    val enabled: Boolean get() = threeD || surround

    /** Lê o que está gravado nos ajustes e joga nos parâmetros vivos. */
    fun sync(context: Context) {
        threeD = Settings.spatial3d(context)
        depth = Settings.spatial3dDepth(context) / 100f
        surround = Settings.surround(context)
        intensity = Settings.surroundIntensity(context) / 100f
    }

    internal fun audioProcessor(): AudioProcessor = instance
}

/**
 * Fábrica de renderers que acrescenta o processador espacial ao `DefaultAudioSink`.
 *
 * O `buildAudioSink` do `DefaultRenderersFactory` é exatamente esta cadeia, então
 * sobrescrever aqui não perde nada do comportamento padrão (float output, playback params).
 *
 * O processador entra **sempre**, mesmo com os dois efeitos desligados. A alternativa —
 * instalar só quando já houver efeito ligado — prendia o botão ao momento em que o player
 * foi criado: como `isActive()` só é reconsultado em `configure`/`flush`, ligar o 3D no
 * meio da faixa não entraria em momento nenhum. Com ele sempre na cadeia, quem decide é o
 * `queueInput`, que olha o efeito a cada buffer.
 */
@UnstableApi
class SpatialRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(SpatialAudio.audioProcessor()))
        .build()
}

/**
 * Gira e amplia o sinal estéreo.
 *
 * Só mexe em PCM 16-bit. Para qualquer outro formato (FLAC 24 bits, float, surround de vídeo)
 * devolve `NOT_SET`, o que deixa o processador inativo na cadeia em vez de estourar exceção:
 * o `AudioProcessingPipeline` do Media3 **não** captura `UnhandledAudioFormatException`, e o
 * `DefaultAudioSink` cairia no bypass de toda a cadeia, derrubando o Sonic junto.
 */
private class SpatialProcessor : BaseAudioProcessor() {

    /** O formato de entrada é dos que o DSP sabe tratar. Só muda em `onConfigure`. */
    private var supported = false

    /** Taxa do PCM, para o passo do giro. Vem do `onConfigure` porque é a única do código. */
    private var sampleRate = 0

    /** Ângulo do giro em radianos, 0 a 2PI. Cruza buffers de propósito: a volta é contínua. */
    private var angle = 0f

    /**
     * Linha de atraso circular, estéreo entrelaçado (L, R, L, R...). Guarda o sinal **seco**
     * para a alimentação cruzada não pegar a própria saída e criar realimentação.
     */
    private var delayBuffer = FloatArray(0)
    private var delayMask = 0
    private var delayLength = 0
    private var writeIndex = 0

    /** Ganhos de pan calculados por bloco de [BLOCK] samples e interpolados dentro dele. */
    private val blockCos = FloatArray(BLOCK)
    private val blockSin = FloatArray(BLOCK)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2
        if (!supported) return AudioProcessor.AudioFormat.NOT_SET

        // A linha de atraso é alocada por FORMATO, não por "efeito ligado": o `queueInput`
        // decide se o efeito entra, e ele precisa de uma linha pronta para ler.
        sampleRate = inputAudioFormat.sampleRate
        prepareDelay(sampleRate)
        return inputAudioFormat
    }

    /**
     * Ativo para todo formato estéreo de 16 bits, **independente do efeito estar ligado**.
     *
     * É o que permite o botão funcionar no meio da faixa: `isActive()` só é reconsultado em
     * `configure`/`flush`, então usá-lo como interruptor prenderia o 3D ao momento em que a
     * faixa começou. O interruptor de verdade está no `queueInput`.
     */
    override fun isActive(): Boolean = supported

    override fun onFlush() {
        // O atraso ainda guarda o áudio da faixa anterior: zerar, senão o primeiro buffer da
        // música nova puxaria som da música velha na alimentação cruzada.
        delayBuffer.fill(0f)
        writeIndex = 0
    }

    /**
     * Rascunho do DSP, de uso exclusivo da cadeia.
     *
     * Sem ele o `output.put(inputBuffer)` estoura: o Media3 recicla os buffers do
     * `AudioProcessingPipeline`, e o de entrada pode voltar a ser exatamente o objeto que o
     * `BaseAudioProcessor` segura como saída — daí o `IllegalArgumentException: The source
     * buffer is this buffer` que derrubava a faixa com o 3D ligado.
     */
    private var scratch = ByteBuffer.allocate(0)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        ensureScratch(size)

        // Copia para fora antes de qualquer escrita: o buffer de entrada não pode ser tocado.
        scratch.clear()
        scratch.put(inputBuffer)
        scratch.flip()

        if (SpatialAudio.enabled && supported) {
            render(scratch)
        }

        val output = replaceOutputBuffer(size)
        output.put(scratch)
        output.flip()
    }

    private fun ensureScratch(size: Int) {
        if (scratch.capacity() >= size) return
        scratch = ByteBuffer.allocate(size + FRAME_BYTES * BLOCK)
        scratch.order(ByteOrder.nativeOrder())
    }

    /** Aplica o DSP no buffer em posição, amostra a amostra. */
    private fun render(buffer: ByteBuffer) {
        buffer.order(ByteOrder.nativeOrder())
        val bytes = buffer.remaining()
        val doPan = SpatialAudio.threeD && sampleRate > 0
        val doSurround = SpatialAudio.surround
        val depth = SpatialAudio.depth.coerceIn(0f, 1f)
        val intensity = SpatialAudio.intensity.coerceIn(0f, 1f)

        // O surround abre a imagem por meio/side — o meio não muda, a diferença entre os canais
        // é que cresce — e soma uma alimentação cruzada bem curta, que é o que cria a sensação
        // de som ao redor no fone.
        // `width` tem de voltar a 1 com o surround desligado: deixar a largura valendo faria o
        // 3D sozinho abrir a imagem, e o `trim` cortaria volume sem ninguém ter pedido.
        val width = SpatialMath.widthFor(intensity, doSurround)
        val crossGain = SpatialMath.crossGainFor(intensity, doSurround)
        val trim = SpatialMath.trimFor(intensity, doSurround)
        // O limitador só é ligado quando algum efeito está somando ganho de verdade. Com o
        // surround em zero e o 3D desligado não há ganho nenhum, e limitador aqui só
        // comprimiria os picos do material original sem ninguém ter pedido.
        // O teste é no surround LIGADO, e não na intensidade: esta é o valor bruto do
        // ajuste, que fica em 50 mesmo com o surround desligado — testá-lo aqui meteria o
        // limitador em toda música do app.
        // E no `delayBuffer` antes de indexar: sem a linha alocada não há o que ler.
        val doCrossFeed = doSurround && intensity > 0f && delayBuffer.isNotEmpty()
        val limitar = doPan || doCrossFeed

        val angleStep = if (doPan) SpatialMath.angleStepFor(depth, sampleRate) else 0f

        var position = 0
        var remaining = bytes
        var currentAngle = angle
        while (remaining >= FRAME_BYTES) {
            val count = minOf(BLOCK, remaining / FRAME_BYTES)
            if (doPan) {
                SpatialMath.panGains(blockCos, blockSin, count, angleStep, depth, currentAngle)
            }

            for (i in 0 until count) {
                val left = buffer.getShort(position).toInt() / 32768f
                val right = buffer.getShort(position + 2).toInt() / 32768f

                val mid = (left + right) * 0.5f
                val side = (left - right) * 0.5f

                var outLeft = mid + side * width
                var outRight = mid - side * width

                if (doCrossFeed) {
                    val readIndex = (writeIndex - delayLength) and delayMask
                    // O `+ 1` também passa pela máscara: `readIndex` pode ser o último índice,
                    // e sem isto o acesso ao canal direito estoura o array.
                    outLeft += crossGain * delayBuffer[readIndex]
                    outRight += crossGain * delayBuffer[(readIndex + 1) and delayMask]
                    delayBuffer[writeIndex] = left
                    delayBuffer[writeIndex + 1] = right
                    writeIndex = (writeIndex + 2) and delayMask
                }

                if (doPan) {
                    // Rotação do vetor (L, R) pela matriz [[c, s], [-s, c]]. Como c e s já vêm
                    // corrigidos, o ângulo some da conta e o volume não fica pulsando na volta.
                    val c = blockCos[i]
                    val s = blockSin[i]
                    val rotatedLeft = outLeft * c + outRight * s
                    val rotatedRight = outRight * c - outLeft * s
                    outLeft = rotatedLeft
                    outRight = rotatedRight
                }

                var sampleLeft = outLeft * trim
                var sampleRight = outRight * trim
                if (limitar) {
                    sampleLeft = SpatialMath.softClip(sampleLeft)
                    sampleRight = SpatialMath.softClip(sampleRight)
                } else {
                    // Sem ganho somado o sinal já cabe em `Short`; o limite aqui é só rede de
                    // segurança, porque um `Short` estourado dá um estalo horrível.
                    if (sampleLeft > 1f) sampleLeft = 1f else if (sampleLeft < -1f) sampleLeft = -1f
                    if (sampleRight > 1f) sampleRight = 1f else if (sampleRight < -1f) sampleRight = -1f
                }
                buffer.putShort(position, (sampleLeft * 32767f).toInt().toShort())
                buffer.putShort(position + 2, (sampleRight * 32767f).toInt().toShort())

                position += FRAME_BYTES
            }
            remaining -= count * FRAME_BYTES
            if (doPan) {
                currentAngle += angleStep * count
                if (currentAngle >= SpatialMath.TWO_PI) currentAngle -= SpatialMath.TWO_PI
            }
        }
        angle = currentAngle
    }

    /** Aloca (só uma vez por taxa) a linha de atraso do cross-feed. Ver [SpatialMath]. */
    private fun prepareDelay(sampleRate: Int) {
        val wanted = SpatialMath.delayLengthFor(sampleRate)
        if (delayLength == wanted && delayBuffer.isNotEmpty()) return
        delayLength = wanted
        val size = SpatialMath.delaySizeFor(wanted)
        delayBuffer = FloatArray(size)
        delayMask = size - 1
        writeIndex = 0
    }

    override fun onReset() {
        supported = false
        angle = 0f
        sampleRate = 0
        delayBuffer = FloatArray(0)
        delayLength = 0
        delayMask = 0
        scratch = ByteBuffer.allocate(0)
    }

    private companion object {
        const val BLOCK = 64
        const val FRAME_BYTES = 4
    }
}