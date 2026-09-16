package com.pulsa.player.util

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random

object Ambient {

    const val NIGHT = "night"
    const val RAIN = "rain"
    const val OCEAN = "ocean"
    const val WIND = "wind"
    const val FOREST = "forest"
    const val WHITE = "white"
    const val PINK = "pink"
    const val BROWN = "brown"

    private const val SAMPLE_RATE = 44100
    private const val FRAME = 4410
    private const val OUTPUT_GAIN = 2.0f

    @Volatile private var running = false
    @Volatile private var mode: String? = null
    @Volatile private var volume = 0.6f

    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private val rand = Random()

    data class State(val modeName: String?, val volume: Float)

    @Synchronized
    fun start(ambient: String, vol: Float) {
        if (running && mode == ambient) {
            setVolume(vol)
            return
        }
        stopInternal()
        mode = ambient
        volume = vol.coerceIn(0.0f, 1.0f)
        running = true
        thread = Thread { loop(ambient) }.apply {
            name = "pulsa-ambient"
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (running) stopInternal()
    }

    @Synchronized
    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0.0f, 1.0f)
        runCatching { track?.setVolume(volume) }
    }

    fun isOn(): Boolean = running

    fun currentMode(): String? = mode

    fun state(): State = State(mode, volume)

    private fun stopInternal() {
        running = false
        thread?.let { t ->
            try { t.join(800) } catch (_: InterruptedException) {}
        }
        thread = null
        runCatching { stopPlayer() }
        track = null
        mode = null
    }

    private fun stopPlayer() {
        val t = track ?: return
        runCatching {
            t.pause()
            t.flush()
            t.stop()
            t.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop(ambient: String) {
        var t: AudioTrack? = null
        val fade = FloatArray(SAMPLE_RATE / 10)
        for (i in fade.indices) fade[i] = i.toFloat() / fade.size
        try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(FRAME * 4 * 4)
            t = builder.build()
            track = t
            t.setVolume(volume)
            t.play()
            val gen = when (ambient) {
                PINK -> ::genPink
                BROWN -> ::genBrown
                RAIN -> ::genRain
                OCEAN -> ::genOcean
                WIND -> ::genWind
                FOREST -> ::genForest
                NIGHT -> ::genNight
                else -> ::genWhite
            }
            val buf = ShortArray(FRAME)
            var written = 0
            while (running) {
                gen.invoke(buf, buf.size)
                if (written < fade.size) {
                    val f = fade[written].coerceAtMost(1f)
                    for (i in buf.indices) buf[i] = (buf[i] * f).toInt().toShort()
                    written += buf.size
                }
                t.write(buf, 0, buf.size)
            }
            for (i in 0 until buf.size.coerceAtMost(SAMPLE_RATE / 10)) buf[i] = 0
            runCatching { t.write(buf, 0, buf.size) }
        } catch (_: Exception) {
            // silencioso — encerra junto com o app
        } finally {
            runCatching { (t ?: track)?.let { it.pause(); it.flush(); it.release() } }
            track = null
        }
    }

    private inline fun emit(buf: ShortArray, n: Int, next: () -> Float) {
        var i = 0
        while (i < n) {
            val sample = next() * OUTPUT_GAIN
            val v = (sample * 32767f).toInt()
            buf[i] = if (v > 32767) 32767 else if (v < -32768) -32768 else v.toShort()
            i++
        }
    }

    private fun genWhite(buf: ShortArray, n: Int) = emit(buf, n) { rand.nextFloat() * 2f - 1f }    @Suppress("LocalVariableName")
    private fun genPink(buf: ShortArray, n: Int) {
        var b0 = 0f; var b1 = 0f; var b2 = 0f; var b3 = 0f; var b4 = 0f; var b5 = 0f; var b6 = 0f
        emit(buf, n) {
            val w = rand.nextFloat() * 2f - 1f
            b0 = 0.99886f * b0 + w * 0.0555179f
            b1 = 0.99332f * b1 + w * 0.0750759f
            b2 = 0.96900f * b2 + w * 0.1538520f
            b3 = 0.86650f * b3 + w * 0.3104856f
            b4 = 0.55000f * b4 + w * 0.5329522f
            b5 = -0.7616f * b5 - w * 0.0168980f
            (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f) * 0.11f
        }
    }

    @Suppress("LocalVariableName")
    private fun genBrown(buf: ShortArray, n: Int) {
        var last = 0f
        emit(buf, n) {
            val w = rand.nextFloat() * 2f - 1f
            last = (last + 0.02f * w) / 1.02f
            last * 3.5f
        }
    }

    @Suppress("LocalVariableName")
    private fun genRain(buf: ShortArray, n: Int) {
        var lp = 0f
        emit(buf, n) {
            val w = rand.nextFloat() * 2f - 1f
            lp += 0.12f * (w - lp)
            (lp * 2.2f + w * 0.08f + (if (rand.nextFloat() < 0.00045f) 2.0f else 0f))
        }
    }

    @Suppress("LocalVariableName")
    private fun genOcean(buf: ShortArray, n: Int) {
        var lp = 0f
        var phase = rand.nextFloat() * 6.283f
        var swell = 0f
        emit(buf, n) {
            phase += 0.0009f
            swell = 0.5f + 0.5f * kotlin.math.sin(phase)
            val w = rand.nextFloat() * 2f - 1f
            lp += 0.012f * (w - lp)
            lp * 2.6f * (0.4f + swell)
        }
    }

    @Suppress("LocalVariableName")
    private fun genWind(buf: ShortArray, n: Int) {
        var lp = 0f
        var phase = rand.nextFloat() * 6.283f
        emit(buf, n) {
            phase += 0.0012f
            val gust = 0.55f + 0.45f * kotlin.math.sin(phase)
            val w = rand.nextFloat() * 2f - 1f
            lp += 0.03f * (w - lp)
            lp * 3.2f * gust
        }
    }

    private fun genForest(buf: ShortArray, n: Int) {
        var chirp = 0f
        var chirpFreq = 0f
        var chirpEnv = 0f
        var untilNext = 12000f
        var pinkP = 0f
        emit(buf, n) {
            val w = rand.nextFloat() * 2f - 1f
            pinkP += 0.02f * (w - pinkP)
            chirpEnv *= 0.995f
            if (chirpEnv < 0.01f) {
                untilNext -= 1f
                if (untilNext <= 0f) {
                    untilNext = 36000f + rand.nextFloat() * 100000f
                    chirpFreq = 2600f + rand.nextFloat() * 1400f
                    chirpEnv = 1f
                }
            }
            chirp = kotlin.math.sin(chirpFreq * 0.0014f) * chirpEnv
            val t = pinkP * 1.6f + chirp * 0.35f
            if (t > 1f) 1f else if (t < -1f) -1f else t
        }
    }

    private fun genNight(buf: ShortArray, n: Int) {
        var phase = rand.nextFloat() * 6.283f
        var untilChirp = 0f
        var chirp = 0f
        emit(buf, n) {
            phase += 0.0022f
            val cricketPulse = if (kotlin.math.sin(phase) > 0.98f) 0.8f else 0f
            untilChirp -= 1f
            if (untilChirp <= 0f) {
                untilChirp = 1000f + rand.nextFloat() * 3000f
                chirp = 0.5f
            }
            chirp *= 0.985f
            val tone = kotlin.math.sin(phase * 0.35f) * chirp
            (cricketPulse * 0.25f + tone * 0.18f)
        }
    }
}
