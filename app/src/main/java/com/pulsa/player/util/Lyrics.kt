package com.pulsa.player.util

import com.pulsa.player.model.Song
import java.io.File

/**
 * Letras sincronizadas no formato LRC (.lrc ao lado do arquivo de música).
 * Suporta múltiplos timestamps por linha e timestamps [mm:ss.xx].
 */
object Lyrics {

    data class Line(val timeMs: Long, val text: String)

    private val TIME_RE = Regex("\\[(\\d{1,3}):(\\d{1,2}(?:\\.\\d{1,3})?)]")

    fun fileFor(song: Song): File? {
        val f = File(song.path)
        val lrc = File(f.parent, f.name.substringBeforeLast('.') + ".lrc")
        return lrc.takeIf { it.exists() && it.isFile }
    }

    fun loadFor(song: Song): List<Line>? {
        val file = fileFor(song) ?: return null
        return parse(file.readText())
    }

    fun parse(text: String): List<Line> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Line>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val times = TIME_RE.findAll(line).toList()
            if (times.isEmpty()) return@forEach
            val body = line.replace(TIME_RE, "").trim()
            if (body.isEmpty() && !raw.startsWith("[")) return@forEach
            times.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: return@forEach
                val sec = m.groupValues[2].toDoubleOrNull() ?: return@forEach
                val ms = min * 60_000L + (sec * 1000).toLong()
                out.add(Line(ms, body))
            }
        }
        out.sortBy { it.timeMs }
        return out
    }

    fun lineAt(lines: List<Line>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var idx = lines.binarySearch {
            (it.timeMs - positionMs).compareTo(0)
        }
        if (idx < 0) idx = -idx - 2
        return idx.coerceIn(0, lines.lastIndex)
    }
}