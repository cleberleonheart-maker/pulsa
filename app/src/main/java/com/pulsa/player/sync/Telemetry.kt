package com.pulsa.player.sync
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Telemetry {

    fun log(context: Context, msg: String) {
        ThreadPool.post {
            val payload = URLEncoder.encode(msg, "UTF-8")
            val token = Settings.telemetryToken(context)
            val device = Settings.deviceId(context)
            val hosts = Settings.serverCandidates(context).map { "$it/telem" }
            for (base in hosts) {
                try {
                    val conn = URL(base).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 800
                    conn.readTimeout = 800
                    conn.doOutput = true
                    if (token.isNotBlank()) conn.setRequestProperty("X-Pulsa-Token", token)
                    if (device.isNotBlank()) conn.setRequestProperty("X-Pulsa-Device", device)
                    conn.setFixedLengthStreamingMode(payload.toByteArray().size)
                    conn.outputStream.use { it.write(payload.toByteArray()) }
                    conn.inputStream.close()
                    return@post
                } catch (t: Throwable) {
                }
            }
        }
    }
}
