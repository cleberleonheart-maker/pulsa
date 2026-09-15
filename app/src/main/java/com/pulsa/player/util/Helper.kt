package com.pulsa.player.util

object Helper {
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSec = ms / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "%d:%02d".format(minutes, seconds)
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