package com.pulsa.player.audio

import android.media.audiofx.Visualizer
import android.os.HandlerThread
import kotlin.math.sqrt

object MusicVisualizer {

    val bars = FloatArray(24)
    @Volatile var on = false
        private set
    @Volatile var active = false
        private set

    private const val CAPTURE_RATE = 22000
    private var visualizer: Visualizer? = null
    private var captureThread: HandlerThread? = null

    fun attach(sessionId: Int) {
        detach()
        if (sessionId <= 0) return
        val vt = HandlerThread("pulsa_visualizer").apply { start() }
        captureThread = vt
        val vis = try {
            Visualizer(sessionId).apply { enabled = true }
        } catch (t: Throwable) {
            null
        } ?: run {
            vt.quitSafely()
            captureThread = null
            on = false
            return
        }
        try {
            val max = Visualizer.getMaxCaptureRate()
            vis.setCaptureSize(1024)
            vis.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {}

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        onFft(fft)
                    }
                },
                (max / 2).coerceIn(10000, CAPTURE_RATE),
                false,
                true
            )
            on = true
        } catch (t: Throwable) {
            on = false
        }
        visualizer = vis
        active = true
    }

    fun detach() {
        active = false
        on = false
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
        captureThread?.quitSafely()
        captureThread = null
        bars.fill(0f)
    }

    private fun onFft(fft: ByteArray?) {
        val data = fft ?: return
        if (data.size < 8) return
        val n = data.size / 2
        val out = FloatArray(24)
        val boost = floatArrayOf(1.15f, 1.05f, 1.0f)
        for (b in out.indices) {
            val start = if (b == 0) 1 else {
                val r = (b.toFloat() / 24f) * n.toFloat()
                r.toInt().coerceIn(1, n - 1)
            }
            val end = if (b == out.lastIndex) n else {
                val r = ((b + 1).toFloat() / 24f) * n.toFloat()
                r.toInt().coerceIn(start + 1, n)
            }
            var sum = 0.0
            var count = 0
            var i = start
            while (i < end) {
                val re = data[2 * i].toDouble()
                val im = data[2 * i + 1].toDouble()
                sum += sqrt(re * re + im * im)
                count++
                i++
            }
            val mag = if (count > 0) (sum / count) else 0.0
            val idx = if (b < 3) b else 2
            out[b] = mag.toFloat() * boost[idx]
        }
        val max = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
        for (i in out.indices) {
            out[i] = (out[i] / max).coerceIn(0f, 1f)
        }
        val smooth = bars
        for (i in smooth.indices) {
            val target = out[i]
            smooth[i] = if (smooth[i] > 0f) smooth[i] + (target - smooth[i]) * 0.35f else target
            smooth[i] = smooth[i].coerceIn(0f, 1f)
        }
    }

    fun peak(): Float {
        if (!on) return 0f
        return (bars.maxOrNull() ?: 0f)
    }
}
