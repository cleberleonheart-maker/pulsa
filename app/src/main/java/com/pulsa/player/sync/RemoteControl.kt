package com.pulsa.player.sync

import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Controle de sessão pelo celular: entra numa sessão pelo código, mostra o que o
 * anfitrião (outro Pulsa ou a web) está tocando e manda play/pause/next/seek/...
 *
 * O servidor guarda a fila de comandos POR dispositivo (CMD_QUEUE[dev]); o
 * convidado descobre o id do anfitrião em GET /session?code= e enfileira os
 * comandos com esse id no header X-Pulsa-Device.
 */
object RemoteControl {

    private const val INTERVAL_MS = 3_000L

    interface Listener {
        fun onState(snap: RemoteProtocol.Snap)
    }

    @Volatile
    private var running = false
    /** Escrito na main e lido nas threads de rede — `@Volatile` pela visibilidade entre elas. */
    @Volatile
    private var appContext: Context? = null
    private var code = ""
    private var host = ""
    private var listener: Listener? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            ThreadPool.postNetwork { runCatching { step() } }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    val active: Boolean get() = running

    fun setListener(l: Listener?) {
        listener = l
    }

    fun start(context: Context, sessionCode: String) {
        val c = sessionCode.trim().uppercase()
        if (c.isBlank()) return
        appContext = context.applicationContext
        code = c
        host = ""
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
        Telemetry.log(context, "REMOTE controle start code=$c")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        appContext?.let { Telemetry.log(it, "REMOTE controle parado") }
    }

    private fun step() {
        val ctx = appContext ?: return
        val snapshot = fetch(ctx, code)
            ?: RemoteProtocol.Snap(false, code, "", false, false, "", "", "", 0L, 0L, -1)
        if (snapshot.host.isNotBlank()) host = snapshot.host
        val l = listener ?: return
        handler.post { if (running) l.onState(snapshot) }
    }

    private fun fetch(ctx: Context, c: String): RemoteProtocol.Snap? {
        val device = Settings.deviceId(ctx)
        val encoded = URLEncoder.encode(c, "UTF-8")
        for (base in hosts(ctx)) {
            try {
                val conn = URL("$base/session?code=$encoded").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 1000
                conn.readTimeout = 1800
                conn.setRequestProperty("X-Pulsa-Device", device)
                conn.setRequestProperty("X-Pulsa-Token", Settings.telemetryToken(ctx))
                Net.user(ctx, conn)
                val http = conn.responseCode
                val text = if (http == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
                Net.close(conn)
                if (http != 200 || text.isBlank()) continue
                return RemoteProtocol.parseSession(text)
            } catch (t: Throwable) {
            }
        }
        return null
    }

    /** Enfileira um comando para o anfitrião da sessão atual. */
    fun send(cmd: String, value: Any? = null) {
        val ctx = appContext ?: return
        val target = host
        if (target.isBlank()) return
        val body = RemoteProtocol.cmdJson(cmd, value)
        ThreadPool.postNetwork {
            for (base in hosts(ctx)) {
                try {
                    val conn = URL("$base/cmd").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 1500
                    conn.readTimeout = 2000
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("X-Pulsa-Device", target)
                    conn.setRequestProperty("X-Pulsa-Token", Settings.telemetryToken(ctx))
                    Net.user(ctx, conn)
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(body.toByteArray().size)
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    conn.responseCode
                    Net.close(conn)
                    return@postNetwork
                } catch (t: Throwable) {
                }
            }
        }
    }

    private fun hosts(ctx: Context): List<String> = Settings.serverCandidates(ctx)
}
