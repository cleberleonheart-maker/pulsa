package com.pulsa.player.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Item 76 — filtro passa-banda do som ambiente.
 *
 * O som ambiente nasce já "cru" (ruído filtrado por médias simples dentro de cada
 * [Ambient]`gen*`). Aqui entra um passa-banda de verdade — um passa-altas em série com um
 * passa-baixas — para o usuário **fechar** (chuva mais abafada) ou **abrir** (oceano com mais
 * presença) a ambiência sem trocar de modo.
 *
 * O controle é um "tom" de 0 a 1: `0` fecha tudo (só o rumor grave passa), `1` abre (sai o
 * grave, sobra o brilho) e `0.5` é **neutro — passa reto**, sem colorir nada. Isso é o que
 * permite ligar o recurso sem mudar o som de quem nunca mexeu nele.
 *
 * Tudo aqui é Kotlin puro (sem `AudioTrack` nem `Context`), então a matemática dos filtros é
 * testada na JVM — ver `AmbientFilterTest`.
 */
object AmbientFilter {

    /** Tom neutro: banda inteira aberta, sem filtragem. */
    const val NEUTRAL = 0.5f
    const val MIN = 0f
    const val MAX = 1f

    /** Piso do corte inferior (abaixo disto nada é cortado no lado grave). */
    const val LOW_HZ = 20f

    /** Teto do corte superior (acima disto nada é cortado no lado agudo). */
    const val HIGH_HZ = 20000f

    /** Corte superior com o tom todo fechado (~abafa os agudos: chuva "mais fechada"). */
    const val MUFFLED_HZ = 300f

    /** Corte inferior com o tom todo aberto (~tira o rumor grave: "mais presença"). */
    const val PRESENCE_HZ = 1200f

    /** Margem em torno de [NEUTRAL] em que o filtro é pulado por completo. */
    const val NEUTRAL_BAND = 0.01f

    fun isNeutral(tone: Float): Boolean =
        kotlin.math.abs(tone.coerceIn(MIN, MAX) - NEUTRAL) <= NEUTRAL_BAND

    /**
     * Cortes (inferior, superior) em Hz para o tom dado.
     *
     * Abaixo do neutro o corte superior desce de [HIGH_HZ] até [MUFFLED_HZ] (fecha os agudos);
     * acima, o corte inferior sobe de [LOW_HZ] até [PRESENCE_HZ] (abre tirando o grave). No
     * neutro devolve a banda inteira, o que equivale a passar reto.
     */
    fun cutoffs(tone: Float): Pair<Float, Float> {
        val t = tone.coerceIn(MIN, MAX)
        return when {
            t < NEUTRAL -> {
                val k = t / NEUTRAL
                LOW_HZ to lerp(MUFFLED_HZ, HIGH_HZ, k)
            }
            t > NEUTRAL -> {
                val k = (t - NEUTRAL) / (MAX - NEUTRAL)
                lerp(LOW_HZ, PRESENCE_HZ, k) to HIGH_HZ
            }
            else -> LOW_HZ to HIGH_HZ
        }
    }

    private fun lerp(a: Float, b: Float, k: Float): Float = a + (b - a) * k.coerceIn(0f, 1f)
}

/**
 * Biquad de Robert Bristow-Johnson (o mesmo dos livros de DSP), no formato de seção direta I.
 * Só o que o [AmbientFilter.BandPass] precisa: passa-baixas e passa-altas de segunda ordem.
 *
 * Estado por instância — cada [AmbientFilter.BandPass] tem as duas suas, e o `reset` zera a
 * memória (evita estalo ao trocar o tom no meio do áudio).
 */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    /** Coeficientes do passa-baixas em [f0] Hz ([q] padrão de Butterworth, sem ressonância). */
    fun lowPass(sampleRate: Float, f0: Float, q: Float = 0.70710678f) {
        val (_, alpha, c) = omega(sampleRate, f0, q)
        val a0 = 1f + alpha
        b0 = ((1f - c) / 2f) / a0
        b1 = (1f - c) / a0
        b2 = b0
        a1 = (-2f * c) / a0
        a2 = (1f - alpha) / a0
    }

    /** Coeficientes do passa-altas em [f0] Hz. */
    fun highPass(sampleRate: Float, f0: Float, q: Float = 0.70710678f) {
        val (_, alpha, c) = omega(sampleRate, f0, q)
        val a0 = 1f + alpha
        b0 = ((1f + c) / 2f) / a0
        b1 = (-(1f + c)) / a0
        b2 = b0
        a1 = (-2f * c) / a0
        a2 = (1f - alpha) / a0
    }

    private fun omega(sampleRate: Float, f0: Float, q: Float): Triple<Float, Float, Float> {
        // Mantém o corte bem abaixo de Nyquist para o `sin/cos` não degradar perto do limite.
        val fc = f0.coerceIn(20f, sampleRate * 0.45f)
        val w0 = 2f * PI.toFloat() * fc / sampleRate
        val alpha = sin(w0) / (2f * q.coerceAtLeast(0.01f))
        return Triple(w0, alpha, cos(w0))
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }
}

/**
 * O passa-banda do [Ambient]: passa-altas(lo) → passa-baixas(hi). No tom neutro fica
 * **desligado** (passa reto), o que evita gastar CPU e, mais importante, não colore o som de
 * quem nunca mexeu no tom.
 */
class BandPass {
    private val hp = Biquad()
    private val lp = Biquad()
    private var active = false

    fun configure(sampleRate: Float, tone: Float) {
        if (AmbientFilter.isNeutral(tone)) {
            active = false
            return
        }
        val (lo, hi) = AmbientFilter.cutoffs(tone)
        hp.highPass(sampleRate, lo)
        lp.lowPass(sampleRate, hi)
        active = true
    }

    fun process(x: Float): Float = if (!active) x else lp.process(hp.process(x))

    fun reset() {
        hp.reset()
        lp.reset()
    }
}
