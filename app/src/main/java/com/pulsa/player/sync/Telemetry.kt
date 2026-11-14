package com.pulsa.player.sync
import com.pulsa.player.core.Blacklist
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Telemetry {

    private const val TAG = "Telemetry"

    fun log(context: Context, msg: String) {
        ThreadPool.postNetwork {
            if (Blacklist.isBanned(context)) return@postNetwork
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
                    Net.user(context, conn)
                    conn.setFixedLengthStreamingMode(payload.toByteArray().size)
                    conn.outputStream.use { it.write(payload.toByteArray()) }
                    conn.responseCode
                    Net.close(conn)
                    return@postNetwork
                } catch (t: Throwable) {
                    Log.w(TAG, "enviar telemetria falhou -> $t")
                }
            }
        }
    }
}
