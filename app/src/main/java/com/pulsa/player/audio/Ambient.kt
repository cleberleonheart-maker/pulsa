package com.pulsa.player.audio

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
    const val STORM = "storm"
    const val FIRE = "fire"
    const val RIVER = "river"
    const val BIRDS = "birds"

    private const val SAMPLE_RATE = 44100
    private const val FRAME = 4410
    private const val OUTPUT_GAIN = 2.0f

    @Volatile private var running = false
    @Volatile private var mode: String? = null
    @Volatile private var volume = 0.6f
    @Volatile private var duckFactor = 1f

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
        runCatching { track?.setVolume(volume * duckFactor) }
    }

    /** Reduz temporariamente o volume (0..1) sem alterar o volume base do usuário. */
    @Synchronized
    fun setDuck(factor: Float) {
        duckFactor = factor.coerceIn(0.0f, 1.0f)
        runCatching { track?.setVolume(volume * duckFactor) }
    }

    fun duckFactor(): Float = duckFactor

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
            t.setVolume(volume * duckFactor)
            t.play()
            val gen = when (ambient) {
                PINK -> ::genPink
                BROWN -> ::genBrown
                RAIN -> ::genRain
                OCEAN -> ::genOcean
                WIND -> ::genWind
                FOREST -> ::genForest
                NIGHT -> ::genNight
                STORM -> ::genStorm
                FIRE -> ::genFire
                RIVER -> ::genRiver
                BIRDS -> ::genBirds
                else -> ::genWhite
            }
            val buf = ShortArray(FRAME)
            var written = 0
            while (running) {
                gen.invoke(buf, buf.size)
                if (written < fade.size) {
                for (i in buf.indices) {
                    val idx = written + i
                    if (idx >= fade.size) break
                    buf[i] = (buf[i] * fade[idx]).toInt().toShort()
                }
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
        var bed = 0f
        var tickPhase = 0f
        var tickStep = 0f
        var tickAmp = 0f
        var untilTick = 0f
        emit(buf, n) {
            val w = rand.nextFloat() * 2f - 1f
            bed += 0.18f * (w - bed)
            untilTick -= 1f
            if (untilTick <= 0f) {
                untilTick = 60f + rand.nextFloat() * 380f
                tickAmp = 0.7f + rand.nextFloat() * 0.9f
                tickStep = 0.06f + rand.nextFloat() * 0.24f
                tickPhase = rand.nextFloat() * 6.283f
            }
            val tick: Float = if (tickAmp > 0.01f) {
                tickPhase += tickStep
                kotlin.math.sin(tickPhase) * tickAmp
            } else 0f
            tickAmp *= 0.94f
            bed * 1.0f + tick * 0.45f + w * 0.06f
        }
    }

    @Suppress("LocalVariableName")
    private fun genOcean(buf: ShortArray, n: Int) {
        var low = 0f
        var hiss = 0f
        var swellPhase = 0f
        emit(buf, n) {
            swellPhase += 0.000017f
            val swell = 0.5f + 0.5f * kotlin.math.sin(swellPhase)
            val w = rand.nextFloat() * 2f - 1f
            low += 0.015f * (w - low)
            hiss += 0.07f * (w - hiss)
            (low * 1.25f + hiss * 0.55f) * (0.30f + swell * 1.05f)
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

    @Suppress("LocalVariableName")
    private fun genStorm(buf: ShortArray, n: Int) {
        var bed = 0f
        var tickPhase = 0f
        var tickStep = 0f
        var tickAmp = 0f
        var untilTick = 0f
        var untilThunder = 40000f
        var thunder = 0f
        var rumblePhase = 0f
        var rumbleStep = 0.05f
        emit(buf, n) {
            val w = rand.nextFloat() * 2f - 1f
            bed += 0.16f * (w - bed)
            untilTick -= 1f
            if (untilTick <= 0f) {
                untilTick = 50f + rand.nextFloat() * 320f
                tickAmp = 0.6f + rand.nextFloat() * 0.8f
                tickStep = 0.05f + rand.nextFloat() * 0.22f
                tickPhase = rand.nextFloat() * 6.283f
            }
            val tick: Float = if (tickAmp > 0.01f) {
                tickPhase += tickStep
                kotlin.math.sin(tickPhase) * tickAmp
            } else 0f
            tickAmp *= 0.94f
            untilThunder -= 1f
            if (untilThunder <= 0f) {
                untilThunder = 70000f + rand.nextFloat() * 140000f
                thunder = 1f
                rumbleStep = 0.035f + rand.nextFloat() * 0.05f
            }
            thunder *= 0.9998f
            rumblePhase += rumbleStep
            val rumble = kotlin.math.sin(rumblePhase) * thunder * 0.8f +
                kotlin.math.sin(rumblePhase * 0.47f + 1.3f) * thunder * 0.55f
            val v = bed * 1.1f + tick * 0.42f + rumble * 1.1f
            if (v > 1f) 1f else if (v < -1f) -1f else v
        }
    }

    @Suppress("LocalVariableName")
    private fun genFire(buf: ShortArray, n: Int) {
        var bed = 0f
        var crack = 0f
        var crackEnv = 0f
        var untilCrack = 0f
        emit(buf, n) {
            val w = rand.nextFloat() * 2f - 1f
            bed += 0.02f * (w - bed)
            untilCrack -= 1f
            if (untilCrack <= 0f) {
                untilCrack = 220f + rand.nextFloat() * 3600f
                crackEnv = 0.6f + rand.nextFloat() * 0.9f
                crack = w
            }
            crackEnv *= 0.87f
            val v = bed * 2.0f + crack * crackEnv * 0.45f + w * 0.10f
            if (v > 1f) 1f else if (v < -1f) -1f else v
        }
    }

    @Suppress("LocalVariableName")
    private fun genRiver(buf: ShortArray, n: Int) {
        var flow = 0f
        var bubble = 0f
        var bubbleEnv = 0f
        var bubbleStep = 0f
        var bubblePhase = 0f
        var untilBubble = 0f
        emit(buf, n) {
            val w = rand.nextFloat() * 2f - 1f
            flow += 0.035f * (w - flow)
            untilBubble -= 1f
            if (untilBubble <= 0f) {
                untilBubble = 900f + rand.nextFloat() * 2600f
                bubbleEnv = 0.4f + rand.nextFloat() * 0.6f
                bubbleStep = 0.10f + rand.nextFloat() * 0.20f
                bubblePhase = rand.nextFloat() * 6.283f
            }
            bubbleEnv *= 0.94f
            bubblePhase += bubbleStep
            bubble = kotlin.math.sin(bubblePhase) * bubbleEnv * 0.25f
            val v = flow * 2.2f + bubble
            if (v > 1f) 1f else if (v < -1f) -1f else v
        }
    }

    @Suppress("LocalVariableName")
    private fun genBirds(buf: ShortArray, n: Int) {
        var bed = 0f
        var chirp = 0f
        var chirpFreq = 0f
        var chirpEnv = 0f
        var untilNext = 3000f
        emit(buf, n) {
            val w = rand.nextFloat() * 2f - 1f
            bed += 0.03f * (w - bed)
            untilNext -= 1f
            if (untilNext <= 0f) {
                untilNext = 500f + rand.nextFloat() * 5200f
                chirpFreq = 1700f + rand.nextFloat() * 2400f
                chirpEnv = 0.7f + rand.nextFloat() * 0.9f
            }
            chirpEnv *= 0.985f
            chirp = kotlin.math.sin(chirpFreq * 0.0016f) * chirpEnv
            val v = bed * 1.3f + chirp * 0.5f
            if (v > 1f) 1f else if (v < -1f) -1f else v
        }
    }
}
