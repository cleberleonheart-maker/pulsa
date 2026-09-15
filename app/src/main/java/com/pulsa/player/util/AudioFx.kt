package com.pulsa.player.util

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer

object AudioFx {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var karaoke = false

    fun apply(context: Context, sessionId: Int) {
        release()
        karaoke = Settings.karaokeOn(context)
        if (sessionId <= 0) return
        if (!Settings.equalizerOn(context) && !karaoke) return
        val eq = try {
            Equalizer(0, sessionId).apply { enabled = true }
        } catch (t: Throwable) {
            return
        }
        val bb = try {
            BassBoost(0, sessionId).apply { enabled = true }
        } catch (t: Throwable) {
            null
        }
        applyPreset(eq, Settings.audioQuality(context), customBands(context))
        bb?.let {
            runCatching { it.setStrength(if (Settings.audioQuality(context) == Settings.QUALITY_DANCE) 900 else 500) }
        }
        equalizer = eq
        bassBoost = bb
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
    }

    private fun applyPreset(eq: Equalizer, key: String, customLevels: IntArray) {
        val profile = when {
            karaoke -> intArrayOf(-2, -6, -6, -3, -1)
            customLevels.isNotEmpty() && customLevels.size == 5 -> customLevels
            else -> when (key) {
                Settings.QUALITY_BASS -> intArrayOf(-4, -2, 0, 3, 5)
                Settings.QUALITY_VOICES -> intArrayOf(-3, 2, 6, 3, -2)
                Settings.QUALITY_TREBLE -> intArrayOf(-5, -3, 0, 4, 7)
                Settings.QUALITY_ROCK -> intArrayOf(4, 2, -1, 2, 5)
                Settings.QUALITY_DANCE -> intArrayOf(1, -3, -4, 0, 6)
                else -> intArrayOf(0, 0, 0, 0, 0)
            }
        }
        val bands = eq.numberOfBands
        if (bands <= 0) return
        val range = eq.bandLevelRange ?: return
        if (range.size < 2) return
        val min = range[0].toInt()
        val max = range[1].toInt()
        for (i in 0 until bands) {
            val idx = (i.toFloat() / (bands - 1).coerceAtLeast(1) * 4f).toInt().coerceIn(0, 4)
            val level = (profile[idx] * 100).coerceIn(min, max)
            runCatching { eq.setBandLevel(i.toShort(), level.toShort()) }
        }
    }

    fun customBands(context: Context): IntArray =
        Settings.customEqBands(context).split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()
}