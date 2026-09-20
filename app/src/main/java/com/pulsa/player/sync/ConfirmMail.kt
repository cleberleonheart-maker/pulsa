package com.pulsa.player.sync
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ConfirmMail {

    /** Resultado do envio: ok indica entrega aceita; smtp online se o servidor
     *  confirmou que a mensagem saiu via SMTP sem erro (535/536 → offline). */
    data class SendResult(val ok: Boolean, val smtpOnline: Boolean, val detail: String? = null)

    private val main = Handler(Looper.getMainLooper())

    private fun hosts(context: Context): List<String> = Settings.serverCandidates(context)

    fun send(context: Context, to: String, name: String, onResult: (SendResult) -> Unit) {
        ThreadPool.post {
            val result = sendSync(context, to, name)
            Telemetry.log(context, "SENDMAIL ok=${result.ok} smtp=${result.smtpOnline} ${result.detail ?: ""}".trim())
            main.post { onResult(result) }
        }
    }

    private fun sendSync(context: Context, to: String, name: String): SendResult {
        var last: SendResult? = null
        for (base in hosts(context)) {
            try {
                val params = "to=${URLEncoder.encode(to, "UTF-8")}&name=${URLEncoder.encode(name, "UTF-8")}"
                val conn = URL("$base/sendmail").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(params.toByteArray().size)
                conn.outputStream.use { it.write(params.toByteArray()) }
                val code = conn.responseCode
                val text = runCatching {
                    conn.inputStream.bufferedReader().use { it.readText() }
                }.getOrDefault("")
                conn.inputStream.close()
                return parse(code, text)
            } catch (t: Throwable) {
                last = SendResult(false, false, t.message)
            }
        }
        return last ?: SendResult(false, false, "unreachable")
    }

    private fun parse(code: Int, text: String): SendResult {
        val body = runCatching { JSONObject(text) }.getOrNull()
        val ok = when {
            body != null && body.has("ok") -> body.optBoolean("ok")
            else -> code in 200..299
        }
        val error = body?.optString("error", "")?.takeIf { it.isNotEmpty() }
        val smtpDown = error?.let {
            Regex("535|536|(5[0-9]{2})\\s+.*(auth|credentials|username|password)", RegexOption.IGNORE_CASE)
                .containsMatchIn(it) || it.contains("SMTP", true)
        } ?: (code >= 500 && code != 501)
        return SendResult(ok, !(!ok || smtpDown), error)
    }

    /** Verifica o status do servidor de e-mail (GET /smtp/status). */
fun smtpStatus(context: Context, onStatus: (String) -> Unit) {
        ThreadPool.post {
            var status = "?"
            for (base in hosts(context)) {
                try {
                    val conn = URL("$base/smtp/status").openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2500
                    val code = conn.responseCode
                    if (code == 200) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        status = runCatching { JSONObject(text) }
                            .getOrDefault(JSONObject())
                            .optString("status", text)
                    } else if (code == 404) {
                        status = "?"
                    }
                    conn.inputStream.close()
                    break
                } catch (t: Throwable) {
                }
            }
            Telemetry.log(context, "SMTP status=$status")
            main.post { onStatus(status) }
        }
    }
}
