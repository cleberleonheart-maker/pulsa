package com.pulsa.player.sync
import com.pulsa.player.core.Blacklist
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.pulsa.player.R
import com.pulsa.player.data.Library
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Espelho de sessão "ouvir juntos": enquanto uma sessão está ativa, consulta o
 * estado do anfitrião (/session?code=...) e sincroniza faixa, posição e
 * play/pause no aparelho convidado. Requer que os dois tenham a música na
 * biblioteca (casa por id, depois por título+artista).
 */
object MirrorSync {

    private const val INTERVAL_MS = 3_000L

    /** Desvio tolerado antes de reposicionar a faixa. Menor que isto causa glitches; maior deixa audível a defasagem. */
    private const val SEEK_THRESHOLD_MS = 500L

    /** Limite de sanidade ao projetar a posição do host pra agora (relógios diferentes viram lixo). */
    private const val MAX_AGE_MS = 10_000L

    private fun hosts(): List<String> {
        val ctx = appContext
        return if (ctx != null) Settings.serverCandidates(ctx) else
            listOf("http://192.168.100.7:8081", "http://127.0.0.1:8081")
    }

    @Volatile
    private var running = false
    private var appContext: Context? = null
    private var lastSeekPos = -1L
    private var lastMissing = ""

    /** Id do dispositivo anfitrião, descoberto em GET /session (item 133: pedidos por voz). */
    @Volatile
    private var hostDevice = ""

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            ThreadPool.postNetwork { runCatching { step() } }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    val active: Boolean get() = running

    fun start(context: Context) {
        val code = Settings.mirrorCode(context)
        if (code.isBlank()) return
        if (running) return
        running = true
        appContext = context.applicationContext
        lastSeekPos = -1L
        hostDevice = ""
        handler.removeCallbacks(tick)
        handler.post(tick)
        Telemetry.log(context, "MIRROR inicio code=$code")
    }

    fun stop() {
        running = false
        hostDevice = ""
        handler.removeCallbacks(tick)
        appContext?.let { Telemetry.log(it, "MIRROR parado") }
    }

    /**
     * Convidado pede uma faixa na sessão (item 133): enfileira `play_song` no host.
     * O host resolve o nome na própria biblioteca (RemoteSync.execute) e toca; o
     * espelho então faz este aparelho seguir. O callback roda na thread principal.
     */
    fun request(songName: String, onResult: ((Boolean) -> Unit)? = null) {
        val ctx = appContext
        val name = songName.trim()
        val target = hostDevice
        if (ctx == null || name.isEmpty() || target.isBlank() || Settings.mirrorHost(ctx)) {
            onResult?.let { handler.post { it(false) } }
            return
        }
        val body = RemoteProtocol.cmdJson("play_song", name)
        ThreadPool.postNetwork {
            val ok = postCmd(ctx, target, body)
            if (ok) Telemetry.log(ctx, "MIRROR pedido '$name' -> $target")
            onResult?.let { handler.post { it(ok) } }
        }
    }

    private fun postCmd(ctx: Context, target: String, body: String): Boolean {
        val payload = body.toByteArray()
        for (base in hosts()) {
            try {
                val conn = URL("$base/cmd").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 1500
                conn.readTimeout = 2000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("X-Pulsa-Device", target)
                conn.setRequestProperty("X-Pulsa-Token", Settings.telemetryToken(ctx))
                conn.setFixedLengthStreamingMode(payload.size)
                conn.outputStream.use { it.write(payload) }
                conn.responseCode
                runCatching { conn.inputStream.close() }
                return true
            } catch (t: Throwable) {
            }
        }
        return false
    }

    private fun step() {
        val ctx = appContext ?: return
        val code = Settings.mirrorCode(ctx)
        if (code.isBlank()) {
            stop()
            return
        }
        Blacklist.refreshIfStale(ctx)
        if (Blacklist.isBanned(ctx)) return
        val info = fetch(ctx, code) ?: return
        if (!info.optBoolean("ok", false)) {
            stop()
            return
        }
        val host = info.optString("host")
        if (host.isNotBlank()) hostDevice = host
        // A busca roda na thread de rede, mas o ExoPlayer foi criado no `onCreate` do service
        // (thread principal): aplicar o estado (play/pause/seek/start) de qualquer outra thread
        // estoura "wrong thread" e a música fica parada. Então a decisão é tomada na main.
        handler.post {
            if (running) applyInfo(ctx, info)
        }
    }

    private fun applyInfo(ctx: Context, info: JSONObject) {
        val state = info.optJSONObject("state") ?: return
        val hostPlaying = state.optBoolean("playing", false)
        val current = state.optJSONObject("current")
        val hostPos = state.optLong("positionMs", 0L)

        if (current == null || current.isNull("id")) {
            if (Playback.isPlaying) Playback.pause()
            return
        }
        val hostId = current.optLong("id", -1L)
        val mine = Playback.currentSong
        val sameTrack = mine != null && (hostId <= 0L || mine.id == hostId)
        if (!sameTrack) {
            playHostSong(ctx, current, state.optJSONArray("queue"))
            return
        }
        // Mesma faixa: alinha posição e play/pause. A posição do host vem com carimbo de
        // tempo (ts), então projetamos pra agora — sem isso o celular fica alguns segundos
        // à frente/atrás por causa do tempo entre o host publicar e a gente consultar.
        val hostNow = hostPositionNow(state, hostPos, hostPlaying)
        if (hostPlaying) {
            if (!Playback.isPlaying) {
                // Começa já alinhado, em vez de tocar do zero e só corrigir no próximo tick.
                if (hostNow >= 0L) Playback.seekTo(hostNow)
                Playback.toggle()
                lastSeekPos = hostNow
            } else if (hostNow >= 0L && abs(Playback.position - hostNow) > SEEK_THRESHOLD_MS) {
                if (abs(hostNow - lastSeekPos) > SEEK_THRESHOLD_MS) {
                    Playback.seekTo(hostNow)
                    lastSeekPos = hostNow
                }
            }
        } else if (Playback.isPlaying) {
            Playback.pause()
        }
    }

    /** Posição em que o host deve estar agora: soma o tempo decorrido desde o ts do estado. */
    private fun hostPositionNow(state: JSONObject, hostPos: Long, hostPlaying: Boolean): Long {
        if (hostPos < 0L) return -1L
        if (!hostPlaying) return hostPos
        val ts = state.optLong("ts", 0L)
        if (ts <= 0L) return hostPos
        val age = System.currentTimeMillis() - ts
        return if (age in 0..MAX_AGE_MS) hostPos + age else hostPos
    }

    private fun playHostSong(ctx: Context, current: JSONObject, queueJson: JSONArray?) {
        val target = findSong(ctx, current)
        if (target == null) {
            val title = current.optString("title")
            if (title.isNotEmpty() && title != lastMissing) {
                lastMissing = title
                Toast.makeText(ctx, ctx.getString(R.string.mirror_not_found, title), Toast.LENGTH_LONG).show()
            }
            if (Playback.isPlaying) Playback.pause()
            return
        }
        lastMissing = ""
        val queue = mirrorQueue(ctx, queueJson)
        val startIdx = queue.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
        Playback.start(if (queue.isEmpty()) listOf(target) else queue, startIdx)
        lastSeekPos = -1L
        Telemetry.log(ctx, "MIRROR troca para ${target.title}")
    }

    private fun mirrorQueue(ctx: Context, queueJson: JSONArray?): List<Song> {
        if (queueJson == null) return emptyList()
        val out = ArrayList<Song>(queueJson.length())
        for (i in 0 until queueJson.length()) {
            queueJson.optJSONObject(i)?.let { findSong(ctx, it) }?.let { out.add(it) }
        }
        return out
    }

    private fun findSong(ctx: Context, o: JSONObject): Song? {
        val songs = Library.allSongs(ctx)
        val id = o.optLong("id", -1L)
        if (id > 0L) songs.firstOrNull { it.id == id }?.let { return it }
        val title = norm(o.optString("title"))
        val artist = norm(o.optString("artist"))
        if (title.isEmpty() && artist.isEmpty()) return null
        songs.firstOrNull {
            norm(it.title) == title && (artist.isEmpty() || norm(it.artist).contains(artist))
        }?.let { return it }
        return if (title.isNotEmpty()) songs.firstOrNull { norm(it.title).contains(title) } else null
    }

    private fun norm(s: String): String = s.lowercase()
        .replace("[áàâãä]".toRegex(), "a")
        .replace("[éèêë]".toRegex(), "e")
        .replace("[íìîï]".toRegex(), "i")
        .replace("[óòôõö]".toRegex(), "o")
        .replace("[úùûü]".toRegex(), "u")
        .replace('ç', 'c')
        .trim()

    private fun fetch(ctx: Context, code: String): JSONObject? {
        val device = Settings.deviceId(ctx)
        val encoded = URLEncoder.encode(code, "UTF-8")
        for (base in hosts()) {
            try {
                val conn = URL("$base/session?code=$encoded").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 800
                conn.readTimeout = 1500
                conn.setRequestProperty("X-Pulsa-Device", device)
                conn.setRequestProperty("X-Pulsa-Token", Settings.telemetryToken(ctx))
                val codeHttp = conn.responseCode
                val text = if (codeHttp == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else ""
                runCatching { conn.inputStream.close() }
                if (codeHttp != 200 || text.isBlank()) continue
                return JSONObject(text)
            } catch (t: Throwable) {
            }
        }
        return null
    }
}
