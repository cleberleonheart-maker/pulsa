package com.pulsa.player.audio

import kotlin.math.sqrt

/**
 * Item 130 (fase 2) — o DSP que descobre o BPM e a fase da batida.
 *
 * **Por que puro.** O resto da fase 2 (o [BpmTap], o agendamento no `PlaybackService`) depende
 * do Media3 e de um aparelho; o cálculo em si não. Isolando aqui, o miolo vira testável com
 * cliques sintéticos (`BpmTest`) em vez de só confiar no ouvido.
 *
 * **Como estima.** O envelope de entrada é a energia por quadro (o [BpmTap] entrega). O tempo
 * sai da **autocorrelação** desse envelope: um envelope com batida regular repete a cada
 * período, então o atraso (lag) onde a autocorrelação tem o pico é o intervalo entre batidas.
 * A **fase** sai depois, testando deslocamentos da grade e escolhendo o que deixa a energia
 * cair em cima das batidas — o BPM sozinho não diz *onde* elas estão.
 *
 * Puro: nada de Android, nada de estado global.
 */
object Bpm {

    /** Faixa de trabalho. Abaixo de 70 o "meio-tempo" some; acima de 185 vira o dobro. */
    const val MIN_BPM = 70f
    const val MAX_BPM = 185f

    /**
     * O que a análise descobriu.
     *
     * [phaseMs] é o tempo, desde o começo do áudio capturado, onde cai a primeira batida da
     * grade. Com ele a grade inteira é `phaseMs + k*intervalMs`.
     */
    data class Beats(val intervalMs: Float, val phaseMs: Float, val confidence: Float) {
        val bpm: Float get() = if (intervalMs > 0f) 60000f / intervalMs else 0f
    }

    /**
     * Analisa um envelope de energia (um valor por quadro, de [hopMs] ms cada).
     *
     * Devolve `null` quando não há material suficiente ou o sinal é ruidoso demais para
     * arriscar um palpite — melhor não mexer no crossfade do que alinhar numa batida falsa.
     */
    fun analyze(energy: FloatArray, hopMs: Float): Beats? {
        if (hopMs <= 0f) return null
        val n = energy.size
        // Menos de ~3 s de envelope não dá um período confiável nem para o mais rápido da faixa.
        if (n < 64) return null

        val mean = energy.sum() / n
        val x = FloatArray(n) { energy[it] - mean }

        // Lags que correspondem a [MIN_BPM, MAX_BPM]. `maxLag` limitado a n/2 para a
        // autocorrelação ter sobreposição suficiente.
        val minLag = (60000f / MAX_BPM / hopMs).toInt().coerceAtLeast(1)
        val maxLag = (60000f / MIN_BPM / hopMs).toInt().coerceAtMost(n / 2)
        if (maxLag <= minLag) return null

        var bestLag = -1
        var best = 0f
        for (lag in minLag..maxLag) {
            var cross = 0f
            var e0 = 0f
            var e1 = 0f
            var i = 0
            while (i < n - lag) {
                val a = x[i]
                val b = x[i + lag]
                cross += a * b
                e0 += a * a
                e1 += b * b
                i++
            }
            val denom = sqrt(e0 * e1)
            if (denom <= 1e-6f) continue
            val r = cross / denom
            if (r > best) {
                best = r
                bestLag = lag
            }
        }
        if (bestLag < 0) return null

        val intervalMs = bestLag * hopMs
        val phaseMs = bestPhase(energy, bestLag) * hopMs
        return Beats(intervalMs, phaseMs, best)
    }

    /**
     * Deslocamento da grade (em quadros) que mais aproxima uma batida dos picos de energia.
     *
     * Para cada deslocamento possível dentro de um período, soma a energia dos quadros que
     * caem na grade (`offset + k*period`) e fica com a média maior — o pico do envelope
     * (a batida) alinhando com a grade é o que caracteriza a fase certa.
     */
    private fun bestPhase(energy: FloatArray, period: Int): Int {
        if (period <= 0) return 0
        var bestOffset = 0
        var bestScore = -1f
        var offset = 0
        while (offset < period) {
            var sum = 0f
            var count = 0
            var i = offset
            while (i < energy.size) {
                sum += energy[i]
                count++
                i += period
            }
            if (count > 0) {
                val avg = sum / count
                if (avg > bestScore) {
                    bestScore = avg
                    bestOffset = offset
                }
            }
            offset++
        }
        return bestOffset
    }

    /**
     * Quanto falta, a partir de [nowMs], para a próxima batida da grade.
     *
     * O caso de uso é agendar o crossfade: em vez de começar o fade no instante em que a faixa
     * cruza o limiar de "perto do fim", começa na batida seguinte — a faixa nova entra no
     * compasso. Devolve um valor em `(0, intervalMs]`.
     */
    fun msToNextBeat(beats: Beats, nowMs: Float): Float {
        val interval = beats.intervalMs
        if (interval <= 0f) return 0f
        var t = (nowMs - beats.phaseMs) % interval
        if (t < 0f) t += interval
        return interval - t
    }
}
