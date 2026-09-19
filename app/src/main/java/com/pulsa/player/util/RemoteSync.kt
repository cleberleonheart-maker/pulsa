package com.pulsa.player.util

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.pulsa.player.data.Library
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Espelho da biblioteca + estado + remote control (via servidor de telemetria).
 *
 * Enquanto o processo vive, a cada poucos segundos: empurra o estado de
 * reprodução pro servidor (/state), consulta comandos pendentes (/cmd) e
 * reenvia a lista de músicas quando ela muda (/songs). O web player consome
 * tudo isso e manda comandos de volta (play/pause/next/volume...).
 */
object RemoteSync {

    private const val INTERVAL_MS = 3_000L
    private const val SONGS_MS = 60_000L

    private fun hosts(): List<String> {
        val ctx = appContext
        return if (ctx != null) Settings.serverCandidates(ctx) else
            listOf("http://192.168.100.7:8081", "http://127.0.0.1:8081")
    }

    @Volatile
    private var running = false
    private var appContext: Context? = null
    private var lastSongsPush = 0L
    private var lastSongsHash = ""
    private var lastDjSync = 0L
    private var lastDjPull = 0L
    private var busy = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!busy) {
                busy = true
                ThreadPool.post {
                    try {
                        val ctx = appContext ?: return@post
                        pushState(ctx)
                        pollCommands(ctx)
                        maybePushSongs(ctx)
                        maybeSyncDjLearn(ctx)
                    } finally {
                        busy = false
                    }
                }
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start(context: Context) {
        if (running) return
        running = true
        appContext = context.applicationContext
        handler.post(tick)
    }

    private fun pushState(ctx: Context) {
        val body = JSONObject().apply {
            put("playing", Playback.isPlaying)
            put("positionMs", Playback.position)
            put("volume", streamVolume(ctx))
            put("app", versionName(ctx))
            put("ts", System.currentTimeMillis())
            val song = Playback.currentSong
            put("current", song?.let { songJson(it) } ?: JSONObject.NULL)
            put("queue", JSONArray().apply {
                for (s in Playback.queue) put(songJson(s))
            })
        }.toString()
        postJson(ctx, "/state", body)
    }

    private fun maybePushSongs(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastSongsPush < SONGS_MS && lastSongsHash.isNotEmpty()) return
        lastSongsPush = now
        val songs = Library.allSongs(ctx)
        if (songs.isEmpty()) return
        val hash = songsHash(songs)
        if (hash == lastSongsHash) return
        lastSongsHash = hash
        val body = JSONObject().apply {
            put("hash", hash)
            put("songs", JSONArray().apply {
                for (s in songs) {
                    put(JSONObject().apply {
                        put("id", s.id)
                        put("title", s.title)
                        put("artist", s.artist)
                        put("album", s.album)
                        put("durationMs", s.durationMs)
                    })
                }
            })
        }.toString()
        if (postJson(ctx, "/songs", body)) {
            Telemetry.log(ctx, "SYNC songs=${songs.size}")
        }
    }

    /** Empurra e puxa os dados de aprendizagem (DjLearn) pro servidor. */
    private fun maybeSyncDjLearn(ctx: Context) {
        val now = System.currentTimeMillis()
        if (DjLearn.dirty || now - lastDjSync > 10 * 60_000L) {
            pushDjLearn(ctx)
        }
        if (now - lastDjPull > 5 * 60_000L) {
            lastDjPull = now
            pullDjLearn(ctx)
        }
    }

    private fun pushDjLearn(ctx: Context) {
        val body = DjLearn.snapshot(ctx).toString()
        if (postJson(ctx, "/djlearn", body)) {
            DjLearn.markSynced()
            lastDjSync = System.currentTimeMillis()
            Telemetry.log(ctx, "DJLEARN push")
        }
    }

    private fun pullDjLearn(ctx: Context) {
        val device = Settings.deviceId(ctx)
        for (base in hosts()) {
            try {
                val conn = URL("$base/djlearn").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 800
                conn.readTimeout = 1500
                conn.setRequestProperty("X-Pulsa-Device", device)
                val code = conn.responseCode
                val text = if (code == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else ""
                runCatching { conn.inputStream.close() }
                if (code != 200) continue
                if (text.isBlank()) return
                val json = JSONObject(text)
                if (DjLearn.mergeRemote(ctx, json)) {
                    Telemetry.log(ctx, "DJLEARN pull")
                }
                return
            } catch (t: Throwable) {
            }
        }
    }

    private fun pollCommands(ctx: Context) {
        val device = Settings.deviceId(ctx)
        for (base in hosts()) {
            try {
                val conn = URL("$base/cmd").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 800
                conn.readTimeout = 1500
                conn.setRequestProperty("X-Pulsa-Device", device)
                val code = conn.responseCode
                val text = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
                conn.inputStream.close()
                if (code != 200) continue
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    execute(ctx, arr.getJSONObject(i))
                }
                return
            } catch (t: Throwable) {
            }
        }
    }

    private fun execute(ctx: Context, o: JSONObject) {
        val cmd = o.optString("cmd", "").lowercase()
        val value = o.opt("value")
        try {
            when (cmd) {
                "toggle" -> Playback.toggle()
                "play" -> if (!Playback.isPlaying) Playback.toggle()
                "pause" -> if (Playback.isPlaying) Playback.toggle()
                "next" -> Playback.next()
                "prev" -> Playback.prev()
                "seek" -> Playback.seekTo((value as? Number)?.toLong() ?: 0L)
                "shuffle" -> Playback.setShuffle(toBool(value))
                "repeat" -> Playback.cycleRepeat()
                "volume" -> setStreamVolume(ctx, toFloat01(value))
            }
            Telemetry.log(ctx, "CMD exec=$cmd")
        } catch (t: Throwable) {
        }
    }

    private fun postJson(ctx: Context, path: String, body: String): Boolean {
        val device = Settings.deviceId(ctx)
        val payload = body.toByteArray()
        for (base in hosts()) {
            try {
                val conn = URL("$base$path").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 800
                conn.readTimeout = 1500
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("X-Pulsa-Device", device)
                conn.setFixedLengthStreamingMode(payload.size)
                conn.outputStream.use { it.write(payload) }
                conn.inputStream.close()
                return true
            } catch (t: Throwable) {
            }
        }
        return false
    }

    private fun songJson(s: Song) = JSONObject().apply {
        put("id", s.id)
        put("title", s.title)
        put("artist", s.artist)
        put("album", s.album)
        put("durationMs", s.durationMs)
    }

    private fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    } catch (t: Throwable) {
        "?"
    }

    private fun streamVolume(ctx: Context): Int {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
        val max = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        if (max <= 0) return 0
        val cur = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        return (cur.toFloat() / max * 100).toInt().coerceIn(0, 100)
    }

    private fun setStreamVolume(ctx: Context, frac: Float) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        if (max <= 0) return
        val level = (frac.coerceIn(0f, 1f) * max).toInt().coerceIn(0, max)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
    }

    private fun toBool(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", true) || value == "1" || value.equals("on", true)
        else -> false
    }

    private fun toFloat01(value: Any?): Float = when (value) {
        is Number -> value.toFloat() / 100f
        is String -> value.toFloatOrNull()?.div(100f) ?: 0f
        else -> 0f
    }

    private fun songsHash(songs: List<Song>): String {
        val sb = StringBuilder(songs.size * 12)
        for (s in songs) sb.append(s.id).append(':').append(s.durationMs).append(';')
        val bytes = MessageDigest.getInstance("MD5").digest(sb.toString().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}