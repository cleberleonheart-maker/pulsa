package com.pulsa.player.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ConfirmMail {

    private val main = Handler(Looper.getMainLooper())

    fun send(context: Context, to: String, name: String, onResult: (Boolean) -> Unit) {
        ThreadPool.post {
            var ok = false
            for (host in listOf("http://192.168.100.7:8081/sendmail", "http://127.0.0.1:8081/sendmail")) {
                try {
                    val params = "to=${URLEncoder.encode(to, "UTF-8")}&name=${URLEncoder.encode(name, "UTF-8")}"
                    val conn = URL(host).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 2500
                    conn.readTimeout = 2500
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(params.toByteArray().size)
                    conn.outputStream.use { it.write(params.toByteArray()) }
                    val code = conn.responseCode
                    conn.inputStream.close()
                    if (code == 200) ok = true
                    break
                } catch (t: Throwable) {
                }
            }
            main.post { onResult(ok) }
        }
    }
}