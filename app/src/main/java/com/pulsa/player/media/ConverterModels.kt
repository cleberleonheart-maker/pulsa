package com.pulsa.player.media

import java.util.Locale

enum class ConverterOutput(val extension: String, val mime: String, val isVideo: Boolean) {
    MP4("mp4", "video/mp4", true),
    M4A("m4a", "audio/mp4", false)
}

enum class ConverterResolution(val height: Int?) {
    ORIGINAL(null),
    P480(480),
    P720(720),
    P1080(1080)
}

enum class ConverterQuality {
    AUTOMATIC,
    ECONOMY,
    BALANCED,
    HIGH
}

enum class ConverterSourceType {
    URL,
    LOCAL
}

data class ConverterOptions(
    val output: ConverterOutput = ConverterOutput.MP4,
    val resolution: ConverterResolution = ConverterResolution.ORIGINAL,
    val quality: ConverterQuality = ConverterQuality.BALANCED,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val removeAudio: Boolean = false
)

object ConverterRules {

    private val mediaExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "3gp", "m4a", "mp3",
        "aac", "ogg", "opus", "wav", "flac", "amr", "3gpp"
    )

    fun parseTime(input: String): Long? {
        val text = input.trim()
        if (text.isEmpty()) return 0L
        val parts = text.split(':')
        if (parts.size !in 1..3) return null
        val numbers = parts.map { it.trim().toLongOrNull() ?: return null }
        if (numbers.any { it < 0L }) return null
        return when (numbers.size) {
            1 -> numbers[0] * 1000L
            2 -> numbers[0] * 60_000L + numbers[1] * 1000L
            else -> numbers[0] * 3_600_000L + numbers[1] * 60_000L + numbers[2] * 1000L
        }
    }

    fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun outputName(raw: String, output: ConverterOutput): String {
        var value = raw.trim().replace(Regex("[\\/:*?\"<>|\\u0000-\\u001F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val extension = value.substringAfterLast('.', "").lowercase()
        if (extension in mediaExtensions || extension == output.extension) {
            value = value.substringBeforeLast('.').trim()
        }
        value = value.trim('.', ' ')
        if (value.isEmpty()) value = "convertido"
        return value.take(100)
    }

    fun targetBitrate(resolution: ConverterResolution, quality: ConverterQuality): Int? =
        targetBitrateForHeight(resolution.height, quality)

    /**
     * O bitrate a partir da altura **efetiva** do vídeo.
     *
     * Com a resolução em "original", a altura do vídeo é a da fonte — sem isso a escolha de
     * qualidade não mudava nada quando ninguém mexia na resolução.
     */
    fun targetBitrateForHeight(height: Int?, quality: ConverterQuality): Int? {
        if (quality == ConverterQuality.AUTOMATIC) return null
        val value = height?.takeIf { it > 0 } ?: return null
        return when (quality) {
            ConverterQuality.ECONOMY -> when {
                value <= 480 -> 1_200_000
                value <= 720 -> 2_500_000
                else -> 4_000_000
            }
            ConverterQuality.BALANCED -> when {
                value <= 480 -> 2_000_000
                value <= 720 -> 4_500_000
                else -> 8_000_000
            }
            ConverterQuality.HIGH -> when {
                value <= 480 -> 3_500_000
                value <= 720 -> 7_000_000
                else -> 12_000_000
            }
            ConverterQuality.AUTOMATIC -> null
        }
    }

    /** A fonte é em pé (vídeo gravado no celular, com o telefone em pé). */
    fun isPortraitSource(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && height > width

    /**
     * A caixa da saída para um vídeo em pé: a altura escolhida vira a **largura**
     * (720 de largura por 1280 de altura), que é como todo mundo chama "720p" num vídeo
     * de celular. Escalar pela altura daria um vídeo estreito (405x720).
     */
    fun portraitBox(resolution: ConverterResolution): Pair<Int, Int>? {
        val height = resolution.height ?: return null
        return height to (height * 16 / 9)
    }

    fun estimatedBytes(durationMs: Long, options: ConverterOptions, sourceHeight: Int = 0): Long {
        if (durationMs <= 0L) return 0L
        val height = options.resolution.height ?: sourceHeight.takeIf { it > 0 }
        val videoBitrate = if (options.output.isVideo) {
            targetBitrateForHeight(height, options.quality) ?: 4_000_000
        } else {
            0
        }
        val audioBitrate = if (options.output.isVideo && options.removeAudio) 0 else 128_000
        return ((videoBitrate + audioBitrate).toLong() * durationMs / 8000L) + 1_048_576L
    }
}
