package com.pulsa.player.sync

import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.pulsa.player.data.Library
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Item 289 (handoff): este aparelho assume a sessão "ouvir juntos" de verdade.
 *
 * O modelo antigo era anfitrião/convidado fixo: o convidado espelhava o anfitrião
 * ([MirrorSync]) e mandava comandos ([RemoteControl]). Handoff é o inverso — em vez de
 * seguir o outro, eu **viro a fonte** e o dono antigo pausa. É o "começa no carro, volta
 * pro celular".
 *
 * O servidor guarda o dono da sessão (campo `owner`). [assume] manda `{"assume": code}`,
 * o servidor troca o dono e enfileira `handoff_release` no dono antigo (pausa + deixa de
 * espelhar); na resposta vem o **estado do dono antigo**, e é esse estado que a gente toca
 * aqui — assim quem assume continua de onde o outro parou, não do início.
 *
 * Os três modos do 289 reusam este mesmo primitivo: o botão "Assumir controle", o
 * automático ao entrar na sessão e o de conectar no carro (Bluetooth).
 */
object Handoff {

    private val handler = Handler(Looper.getMainLooper())

    interface Listener {
        fun onResult(ok: Boolean, message: String)
    }

    /**
     * Assume a sessão e toca o que o dono antigo estava tocando, na posição em que estava.
     *
     * O callback roda na thread principal (o ExoPlayer exige a thread que o criou). [code]
     * é o código da sessão "ouvir juntos".
     */
    fun assume(context: Context, code: String, listener: Listener? = null) {
        val ctx = context.applicationContext
        val c = code.trim().uppercase()
        if (c.isBlank()) {
            notify(listener, false, "código vazio")
            return
        }
        ThreadPool.postNetwork {
            val resp = postAssume(ctx, c)
            if (resp == null) {
                handler.post { notify(listener, false, "sem conexão com o servidor") }
                return@postNetwork
            }
            if (!resp.optBoolean("ok", false)) {
                val erro = resp.optString("erro") ?: ""
                handler.post { notify(listener, false, erro.ifBlank { "sessão inexistente" }) }
                return@postNetwork
            }
            // Já éramos o dono: nada a tocar de novo (evita reiniciar a faixa toda vez que o
            // botão é clicado ou o Bluetooth reconnecta no meio da sessão).
            if (resp.optBoolean("already", false)) {
                handler.post { notify(listener, true, "você já é o dono da sessão") }
                return@postNetwork
            }
            val state = resp.optJSONObject("state")
            // Para de espelhar: agora nós somos a origem, não o seguidor. Sem isto o MirrorSync
            // tentaria nos puxar de volta pro dono antigo no próximo tick.
            MirrorSync.stop()
            // A busca das faixas (MediaStore) é caríssima e vem de fora da main — resolve aqui
            // na thread de rede e manda só o resultado já pronto pra main, que é quem pode
            // mexer no ExoPlayer. Antes o scan rodava dentro do `handler.post` e a main
            // travava durante o handoff (ANR).
            val resolved = resolveState(ctx, state)
            handler.post {
                val started = playState(resolved)
                Telemetry.log(ctx, "HANDOFF assume code=$c started=$started")
                notify(listener, started, if (started) "assumei a sessão" else "sessão assumida (nada tocando)")
            }
        }
    }

    private fun notify(l: Listener?, ok: Boolean, msg: String) {
        l?.onResult(ok, msg)
    }

    private fun postAssume(ctx: Context, code: String): JSONObject? {
        val device = Settings.deviceId(ctx)
        val body = JSONObject().put("assume", code).toString().toByteArray()
        for (base in Settings.serverCandidates(ctx)) {
            try {
                val conn = URL("$base/session").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 1500
                conn.readTimeout = 2500
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("X-Pulsa-Device", device)
                conn.setRequestProperty("X-Pulsa-Token", Settings.telemetryToken(ctx))
                Net.user(ctx, conn)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                val http = conn.responseCode
                val text = if (http == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
                runCatching { conn.inputStream.close() }
                if (http != 200 || text.isBlank()) continue
                return JSONObject(text)
            } catch (t: Throwable) {
            }
        }
        return null
    }

    /** O estado já resolvido na thread de rede: a faixa-alvo, a fila e a posição a retomar. */
    private class Resolved(val target: Song?, val queue: List<Song>, val pos: Long)

    /** Resolve faixa + fila do estado na thread de rede (uma varredura do MediaStore). */
    private fun resolveState(ctx: Context, state: JSONObject?): Resolved {
        if (state == null) return Resolved(null, emptyList(), 0L)
        val current = state.optJSONObject("current") ?: return Resolved(null, emptyList(), 0L)
        if (current.isNull("id") && current.optString("title").isBlank()) {
            return Resolved(null, emptyList(), 0L)
        }
        val songs = Library.allSongs(ctx)
        val target = findSong(songs, current)
        val queue = findQueue(songs, state.optJSONArray("queue"))
        return Resolved(target, queue, state.optLong("positionMs", 0L))
    }

    /** Toca o estado já resolvido (faixa + fila + posição). Devolve false se nada casou. */
    private fun playState(r: Resolved): Boolean {
        val target = r.target ?: return false
        val queue = r.queue
        val startIdx = queue.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
        Playback.start(if (queue.isEmpty()) listOf(target) else queue, startIdx)
        // Reposiciona pro ponto em que o dono antigo estava. Sem isso a faixa recomeçaria do
        // zero — e é exatamente o "continuar de onde parou" que o handoff promete.
        if (r.pos > 0L) Playback.seekTo(r.pos)
        if (!Playback.isPlaying) Playback.toggle()
        return true
    }

    private fun findQueue(songs: List<Song>, queueJson: org.json.JSONArray?): List<Song> {
        if (queueJson == null) return emptyList()
        val out = ArrayList<Song>(queueJson.length())
        for (i in 0 until queueJson.length()) {
            queueJson.optJSONObject(i)?.let { findSong(songs, it) }?.let { out.add(it) }
        }
        return out
    }

    /**
     * Casa a faixa do estado na biblioteca **já carregada** por quem chamou: o scan do
     * MediaStore é caro e reusar a lista evita varrer a biblioteca uma vez por item da fila.
     */
    private fun findSong(songs: List<Song>, o: JSONObject): Song? {
        if (songs.isEmpty()) return null
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
}
