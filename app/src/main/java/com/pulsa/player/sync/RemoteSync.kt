package com.pulsa.player.sync
import com.pulsa.player.core.Blacklist
import com.pulsa.player.dj.DjLearn
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool

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

    // O sync pesado roda fora daqui, em background. O lock mantem os contadores coerentes
    // porque o WorkManager pode ter uma passada ainda em voo quando a proxima e agendada --
    // sem ele, dois pushes da mesma biblioteca sairiam ao mesmo tempo.
    private val heavyLock = Any()

    @Volatile
    private var lastSongsPush = 0L

    @Volatile
    private var lastSongsHash = ""

    @Volatile
    private var lastDjSync = 0L

    @Volatile
    private var lastDjPull = 0L
    private var busy = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!busy) {
                busy = true
                // O estado do player (isPlaying/currentSong/queue) tem de ser lido na MAIN.
                // O ExoPlayer do Media3 exige a thread que o criou, e ler de uma thread do
                // pool estoura IllegalStateException ("Player is accessed on the wrong
                // thread"). Isso nao era problema do proprio sync: ele derrubava a thread do
                // pool, e tudo que usasse o mesmo pool junto morria -- inclusive o
                // favoritar, que por acaso roda no mesmo pool. Coleta-se o estado aqui, na
                // main, e so entao o trabalho de rede vai para o pool.
                val snapshot = runCatching { capturePlayerState() }.getOrNull()
                ThreadPool.postNetwork {
                    try {
                        val ctx = appContext ?: return@postNetwork
                        Blacklist.refreshIfStale(ctx)
                        if (Blacklist.isBanned(ctx)) return@postNetwork
                        if (snapshot != null) pushState(ctx, snapshot)
                        pollCommands(ctx)
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

    /**
     * Uma passada do sync **pesado**: biblioteca e aprendizado do DJ.
     *
     * Fica separado do tick de 3 s de propósito. Push de biblioteca e DjLearn são dezenas de KB
     * de JSON e eram o que dava peso ao polling; aqui rodam de 15 em 15 minutos, com o SO
     * reexecutando mesmo com o app fechado. O tick fica só com o que o web player espera em
     * tempo real (estado + comandos).
     *
     * Chamar de novo é barato e não duplica nada: [maybePushSongs] só reenvia quando o hash da
     * biblioteca muda e o DjLearn só quando está sujo ou vencido. [lastSongsHash] só é
     * gravado **depois** que o servidor aceitou, senão uma falha de rede na hora errada
     * apagaria a biblioteca do espelho até o app reiniciar.
     *
     * @return false só quando a rede falhou de verdade, para o WorkManager repetir depois.
     */
    fun syncHeavy(ctx: Context): Boolean {
        val context = ctx.applicationContext
        // Barrido nao adianta: o tick para de empurrar estado e de aceitar comandos, mas o
        // playback local continua -- e nao ha como matar o processo de dentro daqui.
        if (Blacklist.isBanned(context)) return true
        return synchronized(heavyLock) {
            val songs = maybePushSongs(context)
            val dj = maybeSyncDjLearn(context)
            songs && dj
        }
    }

    /** Estado do player lido na MAIN, antes de o trabalho sair para o pool. */
    private data class PlayerState(
        val playing: Boolean,
        val positionMs: Long,
        val current: Song?,
        val queue: List<Song>
    )

    private fun capturePlayerState(): PlayerState = PlayerState(
        playing = Playback.isPlaying,
        positionMs = Playback.position,
        current = Playback.currentSong,
        queue = Playback.queue
    )

    private fun pushState(ctx: Context, state: PlayerState) {
        val body = JSONObject().apply {
            put("playing", state.playing)
            put("positionMs", state.positionMs)
            put("volume", streamVolume(ctx))
            put("app", versionName(ctx))
            put("ts", System.currentTimeMillis())
            put("current", state.current?.let { songJson(it) } ?: JSONObject.NULL)
            put("queue", JSONArray().apply {
                for (s in state.queue) put(songJson(s))
            })
        }.toString()
        postJson(ctx, "/state", body)
    }

    private fun maybePushSongs(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSongsPush < SONGS_MS && lastSongsHash.isNotEmpty()) return true
        lastSongsPush = now
        val songs = Library.allSongs(ctx)
        if (songs.isEmpty()) return true
        val hash = songsHash(songs)
        if (hash == lastSongsHash) return true
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
        if (!postJson(ctx, "/songs", body)) return false
        // So grava o hash depois do sucesso: se o push falhar, a proxima passada tenta de novo
        // em vez de achar que o espelho ja esta em dia.
        lastSongsHash = hash
        Telemetry.log(ctx, "SYNC songs=${songs.size}")
        return true
    }

    /** Empurra e puxa os dados de aprendizagem (DjLearn) pro servidor. */
    private fun maybeSyncDjLearn(ctx: Context): Boolean {
        var ok = true
        val now = System.currentTimeMillis()
        if (DjLearn.dirty || now - lastDjSync > 10 * 60_000L) {
            if (!pushDjLearn(ctx)) ok = false
        }
        if (now - lastDjPull > 5 * 60_000L) {
            lastDjPull = now
            if (!pullDjLearn(ctx)) ok = false
        }
        return ok
    }

    private fun pushDjLearn(ctx: Context): Boolean {
        val body = DjLearn.snapshot(ctx).toString()
        if (postJson(ctx, "/djlearn", body)) {
            DjLearn.markSynced()
            lastDjSync = System.currentTimeMillis()
            Telemetry.log(ctx, "DJLEARN push")
            return true
        }
        return false
    }

    private fun pullDjLearn(ctx: Context): Boolean {
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
                if (text.isBlank()) return true
                val json = JSONObject(text)
                if (DjLearn.mergeRemote(ctx, json)) {
                    Telemetry.log(ctx, "DJLEARN pull")
                }
                return true
            } catch (t: Throwable) {
            }
        }
        return false
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
                "play_song" -> {
                    val song = resolveSong(ctx, value?.toString() ?: "")
                    if (song != null) Playback.start(listOf(song), 0)
                }
                "play_rewind" -> {
                    val list = mutableListOf<Song>()
                    when (value) {
                        is JSONArray -> for (i in 0 until value.length()) {
                            resolveSong(ctx, value.optString(i))?.let { list.add(it) }
                        }
                        else -> resolveSong(ctx, value?.toString() ?: "")?.let { list.add(it) }
                    }
                    if (list.isNotEmpty()) Playback.start(list, 0)
                }
            }
            Telemetry.log(ctx, "CMD exec=$cmd")
        } catch (t: Throwable) {
        }
    }

    private fun norm(s: String?): String = s?.let {
        java.text.Normalizer.normalize(it.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").trim()
    } ?: ""

    private fun resolveSong(ctx: Context, raw: String): Song? {
        val songs = Library.allSongs(ctx)
        if (songs.isEmpty()) return null
        val q = raw.trim()
        if (q.isEmpty()) return null
        val normQ = norm(q)
        var artistPart = ""
        var titlePart = normQ
        if (q.contains(" - ")) {
            val i = q.lastIndexOf(" - ")
            artistPart = q.substring(0, i)
            titlePart = q.substring(i + 3)
        }
        val a = norm(artistPart)
        val t = norm(titlePart)
        if (a.isNotEmpty()) {
            songs.firstOrNull { norm(it.artist) == a && norm(it.title) == t }?.let { return it }
            songs.firstOrNull { norm(it.artist) == a }?.let { return it }
        }
        songs.firstOrNull { norm(it.title) == t }?.let { return it }
        if (t.isNotEmpty()) {
            songs.firstOrNull { norm(it.title).contains(t) }?.let { return it }
        }
        return null
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
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
