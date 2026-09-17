package com.pulsa.player.util

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer

object AudioFx {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var karaoke = false

    private val PRESETS = mapOf(
        Settings.QUALITY_BASS to intArrayOf(-4, -2, 0, 3, 5),
        Settings.QUALITY_VOICES to intArrayOf(-3, 2, 6, 3, -2),
        Settings.QUALITY_TREBLE to intArrayOf(-5, -3, 0, 4, 7),
        Settings.QUALITY_ROCK to intArrayOf(4, 2, -1, 2, 5),
        Settings.QUALITY_DANCE to intArrayOf(1, -3, -4, 0, 6),
        Settings.QUALITY_POP to intArrayOf(-2, 0, 3, 2, -1),
        Settings.QUALITY_JAZZ to intArrayOf(3, 2, -1, 2, 4)
    )

    private val GENRE_PATTERNS = listOf(
        Regex("rock|metal|punk|grunge|heavy|hard|indie|alternative|screamo|emo|post") to Settings.QUALITY_ROCK,
        Regex("dance|electro|edm|electronic|eletronic|eletronico|techno|house|trance|dubstep|club|disco") to Settings.QUALITY_DANCE,
        Regex("pop") to Settings.QUALITY_POP,
        Regex("jazz|blues|bossa|soul|funk|r&b|rb|rhythm|swing|lounge|smooth") to Settings.QUALITY_JAZZ,
        Regex("rap|hiphop|hip hop|trap|reggae|dub|dancehall|bass|drill|grime|rnb") to Settings.QUALITY_BASS,
        Regex("gospel|louvor|vocal|acappella|a cappella|worship|adorac") to Settings.QUALITY_VOICES
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
        val profile = when {
            karaoke -> intArrayOf(-2, -6, -6, -3, -1)
            customLevels.isNotEmpty() && customLevels.size == 5 -> customLevels
            else -> PRESETS[key] ?: intArrayOf(0, 0, 0, 0, 0)
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