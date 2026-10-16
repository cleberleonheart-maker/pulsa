package com.pulsa.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O DSP do crossfade por batida (item 130, fase 2).
 *
 * Só a parte pura é testável sem aparelho: o [BpmTap] e o agendamento no `PlaybackService`
 * dependem do Media3 e de um dispositivo. Aqui entra o miolo — a autocorrelação que acha o
 * BPM e a fase — com envelopes sintéticos de batida regular.
 */
class BpmTest {

    private val hopMs = 20f

    /**
     * Envelope com um pico (batida) a cada `period` quadros, começando em `offset`.
     *
     * É o mínimo que representa uma faixa de batida regular: energia alta no bumbum, baixa no
     * resto. Ruído leve para não cair num caso degenerado (envelope todo igual).
     */
    private fun clickTrain(size: Int, period: Int, offset: Int): FloatArray {
        val env = FloatArray(size)
        var i = offset
        while (i < size) {
            env[i] = 1f
            i += period
        }
        return env
    }

    @Test
    fun detecta_120_bpm_sem_fase() {
        // 120 BPM = 500 ms = 25 quadros de 20 ms.
        val env = clickTrain(size = 200, period = 25, offset = 0)
        val beats = Bpm.analyze(env, hopMs)
        assertNotNull(beats)
        assertEquals(25f * hopMs, beats!!.intervalMs, 0.01f)
        assertEquals(120f, beats.bpm, 0.5f)
        assertEquals(0f, beats.phaseMs, 0.01f)
    }

    @Test
    fun detecta_100_bpm_com_fase_deslocada() {
        // 100 BPM = 600 ms = 30 quadros; batida começando no quadro 7.
        val env = clickTrain(size = 240, period = 30, offset = 7)
        val beats = Bpm.analyze(env, hopMs)
        assertNotNull(beats)
        assertEquals(30f * hopMs, beats!!.intervalMs, 0.01f)
        assertEquals(100f, beats.bpm, 0.5f)
        assertEquals(7f * hopMs, beats.phaseMs, 0.01f)
    }

    @Test
    fun silencio_nao_inventa_batida() {
        val env = FloatArray(200) { 0f }
        assertNull(Bpm.analyze(env, hopMs))
    }

    @Test
    fun amostra_curta_demais_devolve_nulo() {
        val env = clickTrain(size = 40, period = 25, offset = 0)
        assertNull(Bpm.analyze(env, hopMs))
    }

    @Test
    fun ms_ate_a_proxima_batida_conta_a_partir_da_fase() {
        val beats = Bpm.Beats(intervalMs = 500f, phaseMs = 0f, confidence = 1f)
        // Batidas em 0, 500, 1000... logo de 100 ms faltam 400 ms; em cima da batida, 500.
        assertEquals(400f, Bpm.msToNextBeat(beats, 100f), 0.01f)
        assertEquals(500f, Bpm.msToNextBeat(beats, 0f), 0.01f)
        assertEquals(100f, Bpm.msToNextBeat(beats, 400f), 0.01f)
    }

    @Test
    fun fase_deslocada_ainda_conta_certo() {
        val beats = Bpm.Beats(intervalMs = 500f, phaseMs = 200f, confidence = 1f)
        // Batidas em 200, 700, 1200... de 300 ms faltam 400 ms; de 200, uma volta inteira.
        assertEquals(400f, Bpm.msToNextBeat(beats, 300f), 0.01f)
        assertEquals(500f, Bpm.msToNextBeat(beats, 200f), 0.01f)
    }

    @Test
    fun fora_da_faixa_de_bpm_nao_e_escolhido() {
        // Batida a cada 5 quadros = 100 ms = 600 BPM: acima do teto. A autocorrelação vai
        // achar um múltiplo dentro da faixa (o "meio-tempo"), nunca o valor absurdo.
        val env = clickTrain(size = 200, period = 5, offset = 0)
        val beats = Bpm.analyze(env, hopMs) ?: return
        assertTrue(beats.bpm in Bpm.MIN_BPM..Bpm.MAX_BPM)
    }
}
