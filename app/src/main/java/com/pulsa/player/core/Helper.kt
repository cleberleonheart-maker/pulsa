package com.pulsa.player.core

object Helper {
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSec = ms / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "%d:%02d".format(minutes, seconds)
    }

    /**
     * Tempo total ouvido em formato curto e legível: "2h 15min", "45min" ou "3h".
     * Devolve string vazia quando não há nada ouvido.
     */
    fun formatListenTime(ms: Long): String {
        if (ms <= 0L) return ""
        val totalMin = ms / 60000L
        val hours = totalMin / 60L
        val minutes = totalMin % 60L
        return when {
            hours <= 0L -> "$minutes min"
            minutes <= 0L -> "${hours}h"
            else -> "${hours}h ${minutes}min"
        }
    }

    fun trackCount(n: Int, res: android.content.res.Resources): String {
        return if (n == 1) {
            res.getString(com.pulsa.player.R.string.one_song)
        } else {
            res.getString(com.pulsa.player.R.string.n_songs, n)
        }
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^\\p{L}\\p{Nd} _-]"), "").trim()
        return cleaned.ifEmpty { "pulsa" }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}
