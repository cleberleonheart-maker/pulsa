package com.pulsa.player.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A matemática do 3D e do surround, sem Android e sem Media3.
 *
 * Fica separada do [SpatialProcessor] pelo mesmo motivo do `PlaybackSpeeds`: o que aqui é
 * difícil de acertar é aritmética — índice circular, normalização de energia, curva de
 * limitador — e testar isso pelo `AudioProcessor` exigiria subir um `AudioTrack` de verdade.
 * Aqui dá para testar em JVM puro.
 */
object SpatialMath {

    /** A partir daqui o limitador age. */
    const val KNEE = 0.85f

    /** A alimentação cruzada entre canais, em segundos de atraso. */
    const val HAAS_SECONDS = 0.0003f

    /** 2 * PI. */
    const val TWO_PI = 6.2831855f

    /** Abre a imagem por meio/side. Em `surround` desligado vale 1f: identidade. */
    fun widthFor(intensity: Float, surround: Boolean): Float =
        if (surround) 1f + 0.5f * intensity else 1f

    /** Peso da alimentação cruzada (Haas). Zero quando não há surround, para ser identidade. */
    fun crossGainFor(intensity: Float, surround: Boolean): Float =
        if (surround) 0.25f * intensity else 0f

    /**
     * Compensa a energia que a abertura joga fora.
     *
     * Atrelada à intensidade, e não fixa: assim surround em zero é identidade bit a bit, e
     * o caso mais embiçado não passa de ~+2 dB, que o limitador resolve.
     */
    fun trimFor(intensity: Float, surround: Boolean): Float =
        if (surround) 1f - 0.18f * intensity else 1f

    /** Menos de um ciclo quanto mais forte o 3D: o mesmo botão dá amplitude e vivacidade. */
    fun rotationPeriodSeconds(depth: Float): Float = 12f - 6f * depth.coerceIn(0f, 1f)

    /**
     * Radianos por amostra do giro.
     *
     * Sai do `depth` de **agora**, e não de um valor guardado no `onConfigure`: o ajuste é
     * lido enquanto a música toca, e guardar o passo só faria a rotação mudar de velocidade
     * na faixa seguinte.
     */
    fun angleStepFor(depth: Float, sampleRate: Int): Float {
        if (sampleRate <= 0) return 0f
        return TWO_PI / (rotationPeriodSeconds(depth) * sampleRate)
    }

    /**
     * Comprimento da linha de atraso circular, em índices do array entrelaçado.
     *
     * A conta sai em FRAMES e o comprimento guardado é o dobro: `writeIndex` anda 2 por
     * frame, então voltar [delayLength] índices recua [delayLength] / 2 frames. Pedir o
     * dobro é o que faz o atraso sair nos [HAAS_SECONDS] de verdade em vez da metade.
     *
     * O resultado é sempre par: um comprimento ímpar desalinharia L e R, porque o
     * `readIndex` cairia no índice do outro canal.
     */
    fun delayLengthFor(sampleRate: Int): Int =
        max(1, (sampleRate * HAAS_SECONDS).roundToInt()) * 2

    /**
     * Tamanho do array, potência de dois para o `and` virar máscara.
     *
     * Quatro vezes o comprimento é folga de sobra para a linha não "dar a volta" dentro do
     * próprio atraso — se ela desse, a saída leria o que acabou de escrever.
     */
    fun delaySizeFor(delayLength: Int): Int {
        var size = 8
        while (size < delayLength * 4) size = size shl 1
        return size
    }

    /**
     * Limitador suave, transparente até [KNEE] e assintótico a 1.
     *
     * Precisa ser suave e não um teto: girar o par (L, R) conserva a energia, mas quando a
     * rotação alinha os dois canais em fase o pico chega a +3 dB — o dobro do sinal numa saída
     * só. Com teto duro isso estourava em material já comprimido; com esta curva o overshoot
     * é comprimido. O joelho é alto de propósito (0,85): abaixo disso ele mexeria nos picos
     * de qualquer música normal, e o ganho do 3D já vem compensado em [panGains].
     */
    fun softClip(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= KNEE) return value
        val over = (magnitude - KNEE) / (1f - KNEE)
        // `tanh` pela aproximação de Padé: erro máximo ~0,024 (2,6% perto de `over` = 1,5),
        // imperceptível nesta curva, e saturação exata em 1 a partir de `over` = 3.
        val shaped = if (over >= 3f) 1f else over * (27f + over * over) / (27f + 9f * over * over)
        val y = KNEE + (1f - KNEE) * shaped
        return if (value < 0f) -y else y
    }

    /**
     * Preenche os ganhos de pan dos próximos [count] samples, começando em [from].
     *
     * `c` e `s` saem de uma rotação verdadeira puxados para 1 e 0 conforme a profundidade: em
     * 1f é a matriz de rotação cheia, em 0f é a identidade. Depois divide pela raiz da energia
     * (c² + s²), que para uma rotação é constante — sem isso a volta do 3D fica audível em
     * volume, porque na parte do ângulo em que os canais se igualam o sinal perde quase 3 dB.
     *
     * Só um `cos`/`sin` por bloco, e não por sample: o ângulo anda poucos graus entre dois
     * blocos vizinhos, então interpolar linearmente o ganho é indistinguível e muito mais
     * barato. Os últimos [count] - 1 intervalos pesam o mesmo, e o bloco inteiro dá uma volta
     * de [angleStep] * [count].
     */
    fun panGains(cos: FloatArray, sin: FloatArray, count: Int, angleStep: Float, depth: Float, from: Float) {
        val rawCosStart = 1f + (cos(from) - 1f) * depth
        val rawSinStart = sin(from) * depth
        val target = from + angleStep * count
        val rawCosEnd = 1f + (cos(target) - 1f) * depth
        val rawSinEnd = sin(target) * depth
        val normStart = 1f / sqrt(rawCosStart * rawCosStart + rawSinStart * rawSinStart)
        val normEnd = 1f / sqrt(rawCosEnd * rawCosEnd + rawSinEnd * rawSinEnd)
        val span = if (count > 1) 1f / (count - 1) else 0f
        var c = rawCosStart * normStart
        var s = rawSinStart * normStart
        val cStep = (rawCosEnd * normEnd - c) * span
        val sStep = (rawSinEnd * normEnd - s) * span
        for (i in 0 until count) {
            cos[i] = c
            sin[i] = s
            c += cStep
            s += sStep
        }
    }
}