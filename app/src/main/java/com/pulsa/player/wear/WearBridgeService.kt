package com.pulsa.player.wear

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.pulsa.player.core.Permissions
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.dj.DjCommander
import com.pulsa.player.dj.VirginMedia
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import org.json.JSONObject

/**
 * Ponte entre o relógio (Wear OS) e o motor de reprodução do celular.
 *
 * O relógio não consegue abrir a `PlaybackService` por Bluetooth: a sessão Media3 de um
 * aparelho não é visível no outro. Por isso o caminho é o **Data Layer** do Play Services,
 * que acorda este serviço no celular mesmo com o app fechado. Daqui em diante tudo é a
 * fachada [Playback] normal — o relógio não conhece ExoPlayer, fila nem biblioteca.
 *
 * Dois limites de propósito:
 *
 * 1. Comando não abre a `MainActivity`. O pulso serve para o que dá para fazer sem tela;
 *    "tocar X" resolve a biblioteca aqui mesmo (o `MainVirgin` exige uma `Activity` viva).
 * 2. O estado é **puxado** pelo relógio (a cada 2 s enquanto a tela está acesa), e não
 *    empurrado pelo celular. Um `Player.Listener` daqui precisaria de um serviço em
 *    primeiro plano só para falar com o pulso — e ficaria gastando bateria do celular
 *    quando ninguém está olhando o relógio.
 */
class WearBridgeService : WearableListenerService() {

    companion object {
        private const val TAG = "PulsaWear"
        private const val CMD_PATH = "/pulsa/cmd"
        private const val STATE_PATH = "/pulsa/state"
        private const val SEEK_STEP_MS = 15_000L
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onMessageReceived(event: MessageEvent) {
        val node = event.sourceNodeId ?: return
        val cmd = runCatching { String(event.data, Charsets.UTF_8) }.getOrDefault("")
        if (cmd.isEmpty()) return
        main.post {
            when {
                cmd == "state" -> replyState(node)
                cmd == "toggle" -> control(node) { Playback.toggle() }
                cmd == "play" -> control(node) { Playback.play() }
                cmd == "pause" -> control(node) { Playback.pause() }
                cmd == "next" -> control(node) { Playback.next() }
                cmd == "prev" -> control(node) { Playback.prev() }
                cmd == "seekfwd" -> control(node) {
                    Playback.seekBy(SEEK_STEP_MS, Playback.currentSong?.durationMs ?: 0L)
                }
                cmd == "seekback" -> control(node) {
                    Playback.seekBy(-SEEK_STEP_MS, Playback.currentSong?.durationMs ?: 0L)
                }
                cmd == "fav" -> toggleFavorite(node)
                cmd.startsWith("play:") -> playQuery(node, cmd.removePrefix("play:"))
            }
        }
    }

    // ------------------------------------------------------------------ comandos

    private fun control(node: String, block: () -> Unit) {
        withPlayback {
            block()
            main.postDelayed({ replyState(node) }, 250)
        }
    }

    /**
     * Executa `block` com o motor ligado.
     *
     * Quando o serviço ainda não está no ar, `Playback.connect` faz o bind (o `startService`
     * lá dentro pode falhar em background e é silencioso de propósito). O bind é liberado
     * depois do comando e nunca fica pendurado: sem o timeout, um serviço que não sobe
     * deixaria a contagem de binds presa e a próxima tela do app não conseguiria se soltar.
     */
    private fun withPlayback(block: () -> Unit) {
        if (Playback.isReady) {
            block()
            return
        }
        var released = false
        var bind: Playback.Bind? = null
        val timeout = Runnable {
            if (!released) {
                released = true
                bind?.let { Playback.release(it) }
            }
        }
        bind = Playback.connect(this) {
            if (released) return@connect
            released = true
            main.removeCallbacks(timeout)
            block()
            // O serviço ainda está terminando de preparar a fila; soltar o bind na hora
            // poderia desligá-lo antes do `start` chegar ao ExoPlayer.
            main.postDelayed({ bind?.let { Playback.release(it) } }, 1500)
        }
        main.postDelayed(timeout, 6000)
    }

    private fun toggleFavorite(node: String) {
        val song = Playback.currentSong
        if (song == null || !canFavorite(song)) {
            replyState(node, "idle")
            return
        }
        ThreadPool.post {
            val db = PlaylistDb.get(applicationContext)
            db.setFavorite(song, !db.isFavorite(song.id))
            replyState(node)
        }
    }

    /**
     * "Tocar X" no pulso, sem passar pela `MainVirgin`.
     *
     * O texto vem do reconhecedor do relógio e pode chegar como "tocar bohemian rhapsody"
     * ou só "bohemian rhapsody". `requestQuery` tira o verbo; o resto é resolvido com o
     * mesmo fuzzy de nome do resto do app.
     *
     * Se o melhor resultado casou pelo **título**, toca a faixa. Se casou só pelo artista
     * ("toca Legião Urbana"), expande para as músicas do artista em ordem alfabética — tocar
     * uma faixa aleatória de uma discografia seria adivinhação.
     */
    private fun playQuery(node: String, text: String) {
        val ctx = applicationContext
        ThreadPool.post {
            if (!Permissions.hasAccess(ctx)) {
                replyState(node, "no_permission")
                return@post
            }
            val norm = DjCommander.norm(text)
            val query = (DjCommander.requestQuery(norm) ?: norm).trim()
            if (query.isEmpty()) {
                replyState(node, "no_queue")
                return@post
            }
            val song = VirginMedia.findSong(ctx, query)
            val title = song?.let { DjCommander.norm(it.title) }.orEmpty()
            val titleHit = title.isNotEmpty() && (title.contains(query) || query.contains(title))
            if (song != null && titleHit) {
                startSongs(node, listOf(song))
                return@post
            }
            val artist = VirginMedia.findArtist(ctx, query)
            if (artist != null) {
                val key = DjCommander.norm(artist)
                val songs = runCatching { Library.allSongs(ctx) }
                    .getOrDefault(emptyList())
                    .filter { DjCommander.norm(it.artist) == key }
                    .sortedBy { it.title.lowercase() }
                if (songs.isNotEmpty()) {
                    startSongs(node, songs)
                    return@post
                }
            }
            if (song != null) startSongs(node, listOf(song)) else replyState(node, "not_found", query)
        }
    }

    private fun startSongs(node: String, songs: List<Song>) {
        main.post {
            withPlayback {
                Playback.start(songs, 0)
                main.postDelayed({ replyState(node) }, 900)
            }
        }
    }

    // -------------------------------------------------------------------- estado

    private fun replyState(node: String, msg: String = "", arg: String = "") {
        main.post {
            val song = Playback.currentSong
            val ready = Playback.isReady
            val playing = Playback.isPlaying
            val position = Playback.position
            if (!ready) {
                sendState(node, false, song, false, 0L, null, msg.ifEmpty { "idle" }, arg)
                return@post
            }
            ThreadPool.post {
                val favorite = song?.takeIf { canFavorite(it) }
                    ?.let { PlaylistDb.get(applicationContext).isFavorite(it.id) }
                sendState(node, true, song, playing, position, favorite, msg, arg)
            }
        }
    }

    private fun sendState(
        node: String,
        ready: Boolean,
        song: Song?,
        playing: Boolean,
        position: Long,
        favorite: Boolean?,
        msg: String,
        arg: String
    ) {
        val json = JSONObject()
            .put("ready", ready)
            .put("playing", playing)
            .put("title", song?.title.orEmpty())
            .put("artist", song?.artist.orEmpty())
            .put("pos", position)
            .put("dur", song?.durationMs ?: 0L)
            .put("fav", if (favorite == null) -1 else if (favorite) 1 else 0)
            .put("msg", msg)
            .put("arg", arg)
        Wearable.getMessageClient(this)
            .sendMessage(node, STATE_PATH, json.toString().toByteArray())
            .addOnFailureListener { e -> Log.w(TAG, "falha ao responder ao relogio", e) }
    }

    /**
     * Favoritar é coisa de música. Vídeo, rádio, stream e episódio dividem o espaço de ids
     * com as músicas (ou não têm id de MediaStore), então gravar favorita com eles
     * sobrescreveria a favorita de uma faixa de mesmo número — o mesmo motivo pelo qual o
     * `QueueKey` é tipado.
     */
    private fun canFavorite(song: Song): Boolean =
        !song.isRadio && !song.isVideo && !song.isStream && !song.isPodcast && song.id >= 0
}
