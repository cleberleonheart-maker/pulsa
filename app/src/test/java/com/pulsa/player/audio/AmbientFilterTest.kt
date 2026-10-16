package com.pulsa.player.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 76 — o passa-banda do som ambiente. O que importa aqui: (1) o tom neutro passa reto,
 * para não colorir nada; (2) o lado "fechado" corta agudo; (3) o lado "presença" corta grave;
 * e (4) os biquads não explodem (NaN) num trecho longo de áudio.
 */
class AmbientFilterTest {

    private val fs = 44100f

    /** RMS em regime permanente de um seno de [freq] Hz passando pelo filtro [configure]. */
    private fun rms(freq: Float, samples: Int = 44100, configure: (Biquad) -> Unit): Float {
        val bq = Biquad()
        configure(bq)
        var acc = 0.0
        var n = 0
        for (i in 0 until samples) {
            val x = sin(2.0 * PI * freq * i / fs).toFloat()
            val y = bq.process(x)
            if (i > samples / 4) {
                acc += (y * y).toDouble()
                n++
            }
        }
        return sqrt((acc / n).toFloat())
    }

    private fun rmsThrough(filter: BandPass, freq: Float, samples: Int = 44100): Float {
        var acc = 0.0
        var n = 0
        for (i in 0 until samples) {
            val x = sin(2.0 * PI * freq * i / fs).toFloat()
            val y = filter.process(x)
            if (i > samples / 4) {
                acc += (y * y).toDouble()
                n++
            }
        }
        return sqrt((acc / n).toFloat())
    }

    @Test
    fun cortesDoTom() {
        assertEquals(AmbientFilter.LOW_HZ to AmbientFilter.HIGH_HZ, AmbientFilter.cutoffs(0.5f))
        assertEquals(AmbientFilter.LOW_HZ to AmbientFilter.MUFFLED_HZ, AmbientFilter.cutoffs(0f))
        assertEquals(AmbientFilter.PRESENCE_HZ to AmbientFilter.HIGH_HZ, AmbientFilter.cutoffs(1f))

        // Abaixo do neutro o teto só desce; acima, o piso só sobe.
        val hi25 = AmbientFilter.cutoffs(0.25f).second
        val hi50 = AmbientFilter.cutoffs(0.5f).second
        assertTrue("fechar deve baixar o teto", hi25 < hi50)

        val lo75 = AmbientFilter.cutoffs(0.75f).first
        val lo50 = AmbientFilter.cutoffs(0.5f).first
        assertTrue("abrir deve subir o piso", lo75 > lo50)
    }

    @Test
    fun neutroPassaReto() {
        assertTrue(AmbientFilter.isNeutral(0.5f))
        val bp = BandPass()
        bp.configure(fs, 0.5f)
        assertEquals(0.3f, bp.process(0.3f), 0f)
        assertEquals(-0.7f, bp.process(-0.7f), 0f)
    }

    @Test
    fun passaBaixasDeixaPassarGraveECortaAgudo() {
        assertTrue("100 Hz passa", rms(100f) { it.lowPass(fs, 1000f) } > 0.5f)
        assertTrue("10 kHz corta", rms(10000f) { it.lowPass(fs, 1000f) } < 0.1f)
    }

    @Test
    fun passaAltasDeixaPassarAgudoECortaGrave() {
        assertTrue("10 kHz passa", rms(10000f) { it.highPass(fs, 1000f) } > 0.5f)
        assertTrue("100 Hz corta", rms(100f) { it.highPass(fs, 1000f) } < 0.1f)
    }

    @Test
    fun tomFechadoAbafaOsAgudos() {
        val bp = BandPass()
        bp.configure(fs, 0f)
        assertTrue("agudo abafado", rmsThrough(bp, 5000f) < 0.2f)
    }

    @Test
    fun tomAbertoCortaOGrave() {
        val bp = BandPass()
        bp.configure(fs, 1f)
        assertTrue("grave cortado", rmsThrough(bp, 100f) < 0.4f)
        assertTrue("agudo preservado", rmsThrough(bp, 5000f) > 0.5f)
    }

    @Test
    fun naoExplodeEmTrechoLongo() {
        val bp = BandPass()
        bp.configure(fs, 0f)
        var last = 0f
        for (i in 0 until 200_000) {
            val x = sin(2.0 * PI * 60.0 * i / fs).toFloat()
            last = bp.process(x)
        }
        assertTrue("vira NaN/Inf", last.isFinite())
    }
}
