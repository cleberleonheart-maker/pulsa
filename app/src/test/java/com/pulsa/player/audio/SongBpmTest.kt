package com.pulsa.player.audio

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * O caminho PCM → envelope → BPM é a alma do "BPM nas cenas": os cliques sintéticos a cada
 * 500 ms são 120 BPM exatos, então qualquer erro de contagem de quadro (canal, sobra no fim)
 * derruba o número e o teste pega.
 */
class SongBpmTest {

    private val sampleRate = 44100
    private val hopFrames = (sampleRate * BpmTap.HOP_MS / 1000f).toInt()

    /** [segundos] de PCM16 estéreo com clipe de 40 ms a cada 500 ms — 120 BPM. */
    private fun cliquesPcm16(segundos: Int = 20): ByteBuffer {
        val total = sampleRate * segundos
        val clipe = sampleRate / 25
        val buf = ByteBuffer.allocate(total * 2 * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until total) {
            val amp: Short = if (i % (sampleRate / 2) < clipe) 16000 else 0
            buf.putShort(amp)
            buf.putShort(amp)
        }
        buf.flip()
        return buf
    }

    @Test
    fun pcmDeCliquesVira120Bpm() {
        val acum = SongBpm.Acumulador(2, hopFrames)
        acum.push(cliquesPcm16(), C.ENCODING_PCM_16BIT)
        val energy = acum.envelope()
        assertTrue(energy.size >= 64)
        val bpm = SongBpm.deEnvelope(energy)
        assertNotNull(bpm)
        assertEquals(120f, bpm!!, 1f)
    }

    @Test
    fun float32TambemVira120Bpm() {
        val clipe = sampleRate / 25
        val total = sampleRate * 20
        val buf = ByteBuffer.allocate(total * 2 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until total) {
            val amp = if (i % (sampleRate / 2) < clipe) 0.488f else 0f
            buf.putFloat(amp)
            buf.putFloat(amp)
        }
        buf.flip()
        val acum = SongBpm.Acumulador(2, hopFrames)
        acum.push(buf, C.ENCODING_PCM_FLOAT)
        assertEquals(120f, SongBpm.deEnvelope(acum.envelope())!!, 1f)
    }

    @Test
    fun silencioNaoTemBatida() {
        val buf = ByteBuffer.allocate(sampleRate * 3 * 2 * 2).order(ByteOrder.nativeOrder())
        repeat(sampleRate * 3 * 2) { buf.putShort(0) }
        buf.flip()
        val acum = SongBpm.Acumulador(2, hopFrames)
        acum.push(buf, C.ENCODING_PCM_16BIT)
        assertNull(SongBpm.deEnvelope(acum.envelope()))
    }

    @Test
    fun acumuladorContaQuadrosEIgnoraASobra() {
        // 10 quadros exatos + 100 frames de sobra: a sobra não fecha quadro e não conta.
        val frames = hopFrames * 10 + 100
        val buf = ByteBuffer.allocate(frames * 2 * 2).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { buf.putShort(8000) }
        buf.flip()
        val acum = SongBpm.Acumulador(2, hopFrames)
        acum.push(buf, C.ENCODING_PCM_16BIT)
        assertEquals(10, acum.quadros)
        assertEquals(10, acum.envelope().size)
    }
}
