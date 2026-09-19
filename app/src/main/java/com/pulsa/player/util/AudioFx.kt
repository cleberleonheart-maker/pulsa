package com.pulsa.player.util

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer

object AudioFx {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var karaoke = false

    /** Faixa audível usada para posicionar frequências no curve padrão. */
    private const val F_MIN = 20f
    private const val F_MAX = 20000f

    private val PRESETS = mapOf(
        Settings.QUALITY_BASS to intArrayOf(-4, -2, 0, 3, 5),
        Settings.QUALITY_VOICES to intArrayOf(-3, 2, 6, 3, -2),
        Settings.QUALITY_TREBLE to intArrayOf(-5, -3, 0, 4, 7),
        Settings.QUALITY_ROCK to intArrayOf(4, 2, -1, 2, 5),
        Settings.QUALITY_DANCE to intArrayOf(1, -3, -4, 0, 6),
        Settings.QUALITY_POP to intArrayOf(-2, 0, 3, 2, -1),
        Settings.QUALITY_JAZZ to intArrayOf(3, 2, -1, 2, 4),
        Settings.QUALITY_ACOUSTIC to intArrayOf(-1, 3, 4, 3, 2),
        Settings.QUALITY_CLASSICAL to intArrayOf(3, 2, 0, -2, -1),
        Settings.QUALITY_LOUDNESS to intArrayOf(3, 1, 0, 1, 3)
    )

    private val GENRE_PATTERNS = listOf(
        Regex("rock|metal|punk|grunge|heavy|hard|indie|alternative|screamo|emo|post") to Settings.QUALITY_ROCK,
        Regex("dance|electro|edm|electronic|eletronic|eletronico|techno|house|trance|dubstep|club|disco") to Settings.QUALITY_DANCE,
        Regex("pop") to Settings.QUALITY_POP,
        Regex("jazz|blues|bossa|soul|funk|r&b|rb|rhythm|swing|lounge|smooth|classical|classic|orquestra|sinfonia") to Settings.QUALITY_JAZZ,
        Regex("acustico|acoustic|unplugged|folk|sertanejo|violao|vocal") to Settings.QUALITY_VOICES,
        Regex("rap|hiphop|hip hop|trap|reggae|dub|dancehall|bass|drill|grime|rnb") to Settings.QUALITY_BASS,
        Regex("gospel|louvor|worship|adorac") to Settings.QUALITY_VOICES
    )

    /** Normaliza texto para casar gênero (ignora acentos/caixa). */
    private fun norm(s: String): String = s.lowercase()
        .replace("[áàâãä]".toRegex(), "a")
        .replace("[éèêë]".toRegex(), "e")
        .replace("[íìîï]".toRegex(), "i")
        .replace("[óòôõö]".toRegex(), "o")
        .replace("[úùûü]".toRegex(), "u")
        .replace('ç', 'c')

    /** Chave de preset sugerida pelo gênero da faixa (ou default se desconhecido). */
    fun presetForGenre(genre: String?): String {
        val g = genre?.let { norm(it) } ?: return Settings.QUALITY_DEFAULT
        for ((pattern, quality) in GENRE_PATTERNS) {
            if (pattern.containsMatchIn(g)) return quality
        }
        return Settings.QUALITY_DEFAULT
    }

    fun apply(context: Context, sessionId: Int, genre: String? = null) {
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
        val selected = Settings.audioQuality(context)
        val key = if (selected == Settings.QUALITY_AUTO) presetForGenre(genre) else selected
        applyPreset(eq, key, if (Settings.customEqOn(context)) customBands(context) else IntArray(0))
        bb?.let {
            runCatching { it.setStrength(if (key == Settings.QUALITY_DANCE) 900 else 500) }
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
        val base = when {
            karaoke -> intArrayOf(-2, -6, -6, -3, -1)
            customLevels.size > 1 -> customLevels
            else -> PRESETS[key] ?: intArrayOf(0, 0, 0, 0, 0)
        }
        val range = eq.bandLevelRange ?: return
        if (range.size < 2) return
        val min = range[0].toInt()
        val max = range[1].toInt()
        eq.numberOfBands.takeIf { it > 0 } ?: return
        val levels = scaleToDevice(eq, base)
        for (i in levels.indices) {
            val level = (levels[i] * 100).coerceIn(min, max)
            runCatching { eq.setBandLevel(i.toShort(), level.toShort()) }
        }
    }

    /** Expande um curve (5 ou N pontos) para o número real de bandas do aparelho,
     *  usando a frequência de cada banda como guia (escala logarítmica). */
    private fun scaleToDevice(eq: Equalizer, base: IntArray): IntArray {
        val deviceBands = eq.numberOfBands.toInt()
        if (base.size == deviceBands) return base
        if (deviceBands <= 1 || base.size <= 1) return IntArray(deviceBands)
        val out = IntArray(deviceBands)
        for (i in 0 until deviceBands) {
            val freq = eq.getCenterFreq(i.toShort()).toFloat().coerceIn(F_MIN, F_MAX)
            val pos = logPos(freq) * (base.size - 1)
            val lo = pos.toInt().coerceIn(0, base.size - 1)
            val hi = (lo + 1).coerceAtMost(base.size - 1)
            val frac = pos - lo
            out[i] = (base[lo] + (base[hi] - base[lo]) * frac).toInt()
        }
        return out
    }

    private fun logPos(freq: Float): Float {
        val lf = Math.log(freq.toDouble()).toFloat()
        val lMin = Math.log(F_MIN.toDouble()).toFloat()
        val lMax = Math.log(F_MAX.toDouble()).toFloat()
        return ((lf - lMin) / (lMax - lMin)).coerceIn(0f, 1f)
    }

    /** Frequências centrais reais das bandas do aparelho (vazio se indisponível). */
    fun deviceFrequencies(sessionId: Int): IntArray {
        if (sessionId <= 0) return intArrayOf()
        return try {
            Equalizer(0, sessionId).apply { enabled = false }.useEqualizer { eq ->
                if (eq.numberOfBands <= 0) intArrayOf()
                else IntArray(eq.numberOfBands.toInt()) { eq.getCenterFreq(it.toShort()) }
            }
        } catch (t: Throwable) {
            intArrayOf()
        }
    }

    /** Reamostra N pontos para M usando escala logarítmica (para diálogo de EQ). */
    fun rescale(base: IntArray, toCount: Int): IntArray {
        if (base.isEmpty()) return IntArray(toCount)
        if (base.size == toCount) return base
        if (toCount <= 1) return intArrayOf(base.first())
        val out = IntArray(toCount)
        for (i in 0 until toCount) {
            val pos = (i.toFloat() / (toCount - 1)) * (base.size - 1)
            val lo = pos.toInt().coerceIn(0, base.size - 1)
            val hi = (lo + 1).coerceAtMost(base.size - 1)
            out[i] = (base[lo] + (base[hi] - base[lo]) * (pos - lo)).toInt()
        }
        return out
    }

    fun customBands(context: Context): IntArray =
        Settings.customEqBands(context).split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()

    fun formatFreq(hz: Int): String = when {
        hz >= 1000 -> "%.1fk".format(hz / 1000f)
        else -> "$hz"
    }

    private inline fun <T> Equalizer.useEqualizer(block: (Equalizer) -> T): T {
        return try {
            block(this)
        } finally {
            runCatching { release() }
        }
    }
}