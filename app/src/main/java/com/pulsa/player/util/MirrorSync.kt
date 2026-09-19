package com.pulsa.player.util

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

    private const val INTERVAL_MS = 4_000L
    private const val MAX_DRIFT_MS = 6_000L

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

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            ThreadPool.post { runCatching { step() } }
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
        handler.removeCallbacks(tick)
        handler.post(tick)
        Telemetry.log(context, "MIRROR inicio code=$code")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        appContext?.let { Telemetry.log(it, "MIRROR parado") }
    }

    private fun step() {
        val ctx = appContext ?: return
        val code = Settings.mirrorCode(ctx)
        if (code.isBlank()) {
            stop()
            return
        }
        val info = fetch(ctx, code) ?: return
        if (!info.optBoolean("ok", false)) {
            stop()
            return
        }
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
        // Mesma faixa: sincroniza posição e play/pause.
        if (hostPos >= 0L && abs(Playback.position - hostPos) > MAX_DRIFT_MS) {
            if (abs(hostPos - lastSeekPos) > MAX_DRIFT_MS) {
                Playback.seekTo(hostPos)
                lastSeekPos = hostPos
            }
        }
        if (hostPlaying && !Playback.isPlaying) {
            Playback.toggle()
        } else if (!hostPlaying && Playback.isPlaying) {
            Playback.pause()
        }
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