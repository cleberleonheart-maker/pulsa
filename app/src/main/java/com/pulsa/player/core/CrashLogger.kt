package com.pulsa.player.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {

    private const val MAX_BYTES = 250_000
    private const val LOG_NAME = "pulsa.log"
    private val lock = Any()

    fun writeLog(context: Context, text: String) {
        synchronized(lock) {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "\n== $stamp ==\n$text\n"
            val prior: String = try {
                val external = context.getExternalFilesDir(null)
                val file = external?.let { File(it, LOG_NAME) }
                if (file != null && file.exists()) file.readText() else ""
            } catch (t: Throwable) {
                ""
            }
            val full = prior + line
            val trimmed = if (full.length > MAX_BYTES) full.substring(full.length - MAX_BYTES) else full
            try {
                val external = context.getExternalFilesDir(null) ?: return
                File(external, LOG_NAME).writeText(trimmed)
            } catch (t: Throwable) {
            }
        }
    }
}
