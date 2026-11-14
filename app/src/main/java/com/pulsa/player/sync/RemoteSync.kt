package com.pulsa.player.sync
import com.pulsa.player.core.Blacklist
import com.pulsa.player.core.CrashLogger
import com.pulsa.player.core.Permissions
import com.pulsa.player.dj.DjLearn
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistBackup
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.media.DownloadStore
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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

    private const val TAG = "RemoteSync"

    /** Lado maior da miniatura de capa publicada no estado. Pequena de propósito: trafega a cada push. */
    private const val COVER_PX = 96

    private fun hosts(): List<String> {
        val ctx = appContext
        // Sem contexto, o servidor público em HTTPS primeiro: cair direto no claro do LAN
        // mandaria o token e a biblioteca da pessoa sem criptografia.
        return if (ctx != null) Settings.serverCandidates(ctx) else
            listOf(Settings.DEFAULT_SERVER_URL, Settings.LAN_SERVER_URL, Settings.LOCAL_SERVER_URL)
    }

    @Volatile
    private var running = false
    /** Escrito na main e lido nas threads de rede — `@Volatile` pela visibilidade entre elas. */
    @Volatile
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
    /**
     * Trava o tick: escrito na main ao despachar e resetado na thread de rede no fim. Sem
     * `@Volatile` a main podia nunca ver o `false` e o sync remoto parava de vez até o
     * processo morrer — sintoma "o controle pelo navegador parou e não volta".
     */
    @Volatile
    private var busy = false

    // Item 290 (Nuvem de midia): push/pull do blob de playlists/favoritas/progresso.
    // Mesma cadencia do DjLearn -- dezenas de KB que nao precisam ir a cada tick de 3 s.
    @Volatile
    private var lastCloudPush = 0L

    @Volatile
    private var lastCloudPull = 0L

    @Volatile
    private var lastCloudHash = ""

    /** O hash do blob que já foi aplicado aqui: o mesmo blob não é aplicado duas vezes. */
    @Volatile
    private var lastCloudPullHash = ""

    /**
     * Até quando a nuvem fica sem ser tentada, depois de o servidor recusar o token.
     *
     * Uma hora é o suficiente para a pessoa configurar o token (ou o servidor ser
     * consertado) sem que o app fique martelando o servidor a cada 5 min.
     */
    @Volatile
    private var cloudBlockedUntil = 0L

    private const val CLOUD_PUSH_MS = 10 * 60_000L
    private const val CLOUD_PULL_MS = 5 * 60_000L
    private const val CLOUD_BLOCKED_MS = 60 * 60_000L

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
            val cloud = maybeSyncCloud(context)
            songs && dj && cloud
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
            // A faixa atual leva junto a capa (miniatura base64), para o "ouvir juntos" na web
            // mostrar a arte e não só o título. Vai só no current: a fila inteira pesaria demais
            // a cada push. Cacheada por id, então só a troca de faixa paga o custo de codificar.
            put("current", state.current?.let { cur ->
                songJson(cur).apply { coverFor(ctx, cur)?.let { c -> put("cover", c) } }
            } ?: JSONObject.NULL)
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
        var unauthorized = false
        for (base in hosts()) {
            try {
                val conn = URL("$base/djlearn").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 800
                conn.readTimeout = 1500
                conn.setRequestProperty("X-Pulsa-Device", device)
                // `/djlearn` é a mesma família de `/state` e `/cmd`: usa o token normal.
                // O token da nuvem é o segredo de `/cloud` — ele não é aceito aqui, e mandá-lo
                // fazia o pull ser recusado enquanto o push (que usa o token normal) funcionava.
                conn.setRequestProperty("X-Pulsa-Token", Settings.telemetryToken(ctx))
                Net.user(ctx, conn)
                val code = conn.responseCode
                val text = if (code == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else ""
                Net.close(conn)
                if (code == 401 || code == 403) {
                    // Token recusado **por este** host: os outros podem aceitar.
                    unauthorized = true
                    continue
                }
                if (code != 200) continue
                if (text.isBlank()) return true
                val json = JSONObject(text)
                if (DjLearn.mergeRemote(ctx, json)) {
                    Telemetry.log(ctx, "DJLEARN pull")
                }
                return true
            } catch (t: Throwable) {
                CrashLogger.writeLog(ctx.applicationContext, "SYNC: puxar aprendizado falhou -> $t")
            }
        }
        if (unauthorized) {
            Telemetry.log(ctx, "CLOUD pull recusado: token nao aceito pelo servidor")
            CrashLogger.writeLog(ctx, "CLOUD pull 401: token recusado em /djlearn")
            cloudBlockedUntil = System.currentTimeMillis() + CLOUD_BLOCKED_MS
        }
        return false
    }

    /**
     * Item 290 (Nuvem de midia): empurra playlists/favoritas/progresso e puxa de volta.
     *
     * O blob e exatamente o que o [PlaylistBackup] ja exporta pra um arquivo -- caminhos, nunca
     * ids, porque o id do MediaStore nao sobrevive a um celular novo. O progresso (resume) entra
     * junto pra a nuvem lembrar onde a pessoa parou, nao so o que ela curtiu.
     *
     * O pull so aplica quando o blob da nuvem e mais novo e o local esta vazio (celular novo).
     * Puxar por cima de playlists locais duplicaria trabalho manual -- o merge do backup casa por
     * nome, mas restaurar sem precisar faria a pessoa perder a ordem que ela arrumou aqui.
     */
    private fun maybeSyncCloud(ctx: Context): Boolean {
        var ok = true
        val now = System.currentTimeMillis()
        // Token recusado: não insiste a cada 5 min. A pessoa tem uma hora para resolver (o
        // token da nuvem nas Configurações, ou o servidor consertado).
        if (now < cloudBlockedUntil) return false
        if (now - lastCloudPush > CLOUD_PUSH_MS || lastCloudHash.isEmpty()) {
            if (!pushCloud(ctx)) ok = false
        }
        if (now - lastCloudPull > CLOUD_PULL_MS) {
            lastCloudPull = now
            if (!pullCloud(ctx)) ok = false
        }
        return ok
    }

    private fun pushCloud(ctx: Context): Boolean {
        val db = PlaylistDb.get(ctx)
        val export = runCatching { PlaylistBackup.export(ctx, db) }.getOrNull() ?: return false
        // Progresso por faixa: o `resume_*` do Settings e um "onde parei" de uma unica faixa.
        // Vai junto pra a nuvem saber o que retomar num aparelho novo.
        export.put("resume", JSONObject().apply {
            put("songId", Settings.resumeSongId(ctx))
            put("positionMs", Settings.resumePosition(ctx))
            put("title", Settings.resumeSongTitle(ctx))
            put("artist", Settings.resumeSongArtist(ctx))
        })
        // Mapa caminho -> nome. O blob do backup so tem caminhos, mas o web player toca por
        // nome ("artista - titulo" via play_song). Sem isto a web lia a nuvem e mostraria uma
        // lista de caminhos que nao da pra tocar. Resolve uma unica vez, em lote.
        val paths = mutableSetOf<String>()
        export.optJSONArray("playlists")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optJSONArray("paths")?.let { p ->
                    for (j in 0 until p.length()) p.optString(j)?.takeIf { it.isNotBlank() }?.let { paths += it }
                }
            }
        }
        export.optJSONArray("favorites")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("path")?.takeIf { it.isNotBlank() }?.let { paths += it }
        }
        if (paths.isNotEmpty()) {
            val resolved = runCatching { Library.songsByPaths(ctx, paths.toList()) }.getOrNull().orEmpty()
            val names = JSONObject()
            for (s in resolved) {
                if (s.path.isBlank()) continue
                names.put(s.path, JSONObject().apply {
                    put("title", s.title)
                    put("artist", s.artist)
                })
            }
            export.put("names", names)
        }
        // O que está baixado. O arquivo não viaja — o servidor guarda o blob, não o vídeo —,
        // mas a lista sim: no aparelho novo a pessoa descobre o que precisa baixar de novo
        // em vez de lembrar do nada.
        export.put("downloads", downloadsJson())
        val body = export.toString()
        val hash = body.hashCode().toString()
        if (hash == lastCloudHash) return true
        val sent = postJsonResult(ctx, "/cloud", body, Settings.cloudToken(ctx))
        if (sent != PostResult.OK) {
            // O 401 é o caso que importa: o servidor recusou o token. Ficar tentando de novo
            // a cada 5 min, sem falar, é a definição de defeito silencioso — e a nuvem é
            // justamente o recurso que ninguém percebe que não está indo.
            if (sent == PostResult.UNAUTHORIZED) {
                Telemetry.log(ctx, "CLOUD push recusado: token nao aceito pelo servidor")
                CrashLogger.writeLog(ctx, "CLOUD push 401: token recusado em /cloud")
                cloudBlockedUntil = System.currentTimeMillis() + CLOUD_BLOCKED_MS
            }
            return false
        }
        // So grava o hash depois do sucesso: uma falha de rede na hora errada nao pode fazer o
        // app achar que a nuvem ja esta em dia com uma versao que o servidor nem viu.
        lastCloudHash = hash
        lastCloudPush = System.currentTimeMillis()
        Telemetry.log(ctx, "CLOUD push playlists=${export.optJSONArray("playlists")?.length() ?: 0}")
        return true
    }

    /**
     * A fila de download como ela viaja na nuvem: título, origem, tamanho e estado.
     *
     * Só o que **terminou** entra: um download pela metade não é "baixado", e a lista da
     * nuvem é a lista do que existe offline.
     *
     * A URL vai **sem a query**: é onde mora o segredo (link de enclosure assinado, token
     * temporário). O blob da nuvem fica no servidor — e o log do app é espelhado num arquivo
     * que qualquer app com permissão de armazenamento lê. Com a query inteira, a credencial
     * do download ia parar lá.
     */
    private fun downloadsJson(): JSONArray {
        val array = JSONArray()
        for (item in DownloadStore.list()) {
            if (!item.isDone()) continue
            array.put(
                JSONObject().apply {
                    put("title", item.title)
                    put("url", CrashLogger.urlSemQuery(item.url))
                    put("kind", item.kind.name)
                    put("mime", item.mediaMime)
                    put("bytes", item.bytes)
                }
            )
        }
        return array
    }

    private fun pullCloud(ctx: Context): Boolean {
        val device = Settings.deviceId(ctx)
        var unauthorized = false
        for (base in hosts()) {
            try {
                val conn = URL("$base/cloud").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 800
                conn.readTimeout = 1500
                conn.setRequestProperty("X-Pulsa-Device", device)
                conn.setRequestProperty("X-Pulsa-Token", Settings.cloudToken(ctx))
                Net.user(ctx, conn)
                val code = conn.responseCode
                val text = if (code == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else ""
                Net.close(conn)
                if (code == 401 || code == 403) {
                    // Token recusado **por este** host: os outros podem aceitar.
                    unauthorized = true
                    continue
                }
                if (code != 200) continue
                if (text.isBlank()) return true
                val cloud = JSONObject(text).optJSONObject("cloud")
                if (cloud != null) {
                    // O blob que não mudou não precisa ser aplicado de novo: a fusão é
                    // idempotente, mas repetir o trabalho a cada 5 min seria varredura de
                    // biblioteca sem motivo. O hash só é gravado **depois** de aplicar, para
                    // que uma aplicação falha tente de novo na próxima vez.
                    val hash = text.hashCode().toString()
                    if (hash != lastCloudPullHash) {
                        if (applyCloud(ctx, cloud) == CloudApply.APPLIED) {
                            lastCloudPullHash = hash
                        }
                    }
                }
                return true
            } catch (t: Throwable) {
                CrashLogger.writeLog(ctx.applicationContext, "SYNC: puxar nuvem falhou -> $t")
            }
        }
        if (unauthorized) {
            Telemetry.log(ctx, "CLOUD pull recusado: token nao aceito pelo servidor")
            CrashLogger.writeLog(ctx, "CLOUD pull 401: token recusado em /cloud")
            cloudBlockedUntil = System.currentTimeMillis() + CLOUD_BLOCKED_MS
        }
        return false
    }

    /**
     * O que aconteceu com o blob da nuvem.
     *
     * O hash do pull só é gravado quando o blob foi **aplicado**: sem isso, uma aplicação que
     * falhou (ou que nem pôde rodar, por falta de permissão de mídia) marcava o blob como
     * visto e a nuvem nunca mais era tentada — o defeito ficava silencioso para sempre.
     */
    private enum class CloudApply { APPLIED, NO_PERMISSION, FAILED }

    /**
     * Aplica o blob da nuvem.
     *
     * **Aparelho novo é restauração; aparelho com conteúdo é fusão.** A regra antiga aplicava
     * o blob só quando a máquina local estava vazia, porque o restore de biblioteca somava por
     * nome e podia duplicar ou reordenar o trabalho manual. Com a identidade portátil
     * (`SongKey`) e o modo de fusão, a nuvem pode ser puxada a qualquer hora: as playlists se
     * somam pelo nome, as favoritas se somam, e **nada do que está aqui é apagado, reordenado
     * ou sobrescrito** — a correção de nome que esta máquina tem continua valendo.
     */
    private fun applyCloud(ctx: Context, cloud: JSONObject): CloudApply {
        if (!Permissions.hasAccess(ctx)) return CloudApply.NO_PERMISSION
        val db = PlaylistDb.get(ctx)
        val localPlaylists = db.backupPlaylists().count { it.paths.isNotEmpty() }
        val localFavorites = db.backupFavorites().size
        val mode = if (localPlaylists > 0 || localFavorites > 0) {
            PlaylistBackup.Mode.MERGE
        } else {
            PlaylistBackup.Mode.RESTORE
        }
        val report = runCatching { PlaylistBackup.import(ctx, db, cloud, mode) }.getOrNull()
            ?: return CloudApply.FAILED
        if (report.changed) {
            Telemetry.log(
                ctx,
                "CLOUD pull ${mode.name.lowercase()} playlists=${report.playlists} " +
                    "favoritas=${report.favorites} musicas=${report.songs} " +
                    "progresso=${report.progress} faltando=${report.missing}"
            )
        }
        logDownloadsMissing(ctx, cloud)
        return CloudApply.APPLIED
    }

    /**
     * O que está baixado na outra máquina e não está nesta.
     *
     * O arquivo em si não viaja — o servidor guarda o blob, não o vídeo —, mas a **lista**
     * viaja: quem trocou de aparelho sabe o que precisa baixar de novo, e a lista deixa de
     * ser um mistério ("eu tinha baixado aquele filme").
     */
    private fun logDownloadsMissing(ctx: Context, cloud: JSONObject) {
        val array = cloud.optJSONArray("downloads") ?: return
        if (array.length() == 0) return
        // As duas pontas são comparadas sem a query: é assim que a URL viaja na nuvem.
        val localUrls = DownloadStore.list()
            .filter { it.status == DownloadStore.Status.DONE }
            .map { CrashLogger.urlSemQuery(it.url) }
            .toHashSet()
        var missing = 0
        for (i in 0 until array.length()) {
            val url = array.optJSONObject(i)?.optString("url").orEmpty()
            if (url.isNotBlank() && url !in localUrls) missing++
        }
        if (missing > 0) {
            Telemetry.log(ctx, "CLOUD downloads ausentes aqui=$missing")
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
                conn.setRequestProperty("X-Pulsa-Token", Settings.telemetryToken(ctx))
                Net.user(ctx, conn)
                val code = conn.responseCode
                val text = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
                Net.close(conn)
                if (code != 200) continue
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    // `optJSONObject` em vez de `getJSONObject`: um item `null`/string na fila
                    // nao pode lancar e abortar os comandos seguintes (a excecao subia ate o
                    // catch de fora e descartava o resto, sem log).
                    arr.optJSONObject(i)?.let { execute(ctx, it) }
                }
                return
            } catch (t: Throwable) {
                Log.w(TAG, "poll de comandos falhou -> $t")
            }
        }
    }

    private fun execute(ctx: Context, o: JSONObject) {
        val cmd = o.optString("cmd", "").lowercase()
        val value = o.opt("value")
        // Os comandos que mexem no ExoPlayer têm de rodar na main (o player nasceu no
        // `onCreate` do service), então são despachados de volta pra lá. Mas a RESOLUÇÃO da
        // faixa (`resolveSong` → `Library.allSongs`, varredura do MediaStore) é caro e pode
        // vir de um `play_rewind` com fila inteira: resolve AQUI, na thread de rede, e para
        // a main vai só o resultado. Antes isso tudo rodava dentro do `handler.post` e um
        // play_rewind de fila cheia travava a main (ANR).
        val songs: List<Song> = when (cmd) {
            "play_song" -> resolveSong(ctx, value?.toString() ?: "")?.let { listOf(it) } ?: emptyList()
            "play_rewind" -> {
                val list = mutableListOf<Song>()
                when (value) {
                    is JSONArray -> for (i in 0 until value.length()) {
                        resolveSong(ctx, value.optString(i))?.let { list.add(it) }
                    }
                    else -> resolveSong(ctx, value?.toString() ?: "")?.let { list.add(it) }
                }
                list
            }
            else -> emptyList()
        }
        handler.post {
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
                    "play_song", "play_rewind" -> if (songs.isNotEmpty()) Playback.start(songs, 0)
                    // Item 289 (handoff): outro aparelho assumiu a sessão. A gente era o dono
                    // e agora é o liberado — pausa e para de espelhar, senão o MirrorSync
                    // continuaria seguindo (e tocando) quem acabou de tomar o lugar.
                    "handoff_release" -> {
                        if (Playback.isPlaying) Playback.pause()
                        MirrorSync.stop()
                        Telemetry.log(ctx, "HANDOFF release code=${value ?: ""}")
                    }
                }
                Telemetry.log(ctx, "CMD exec=$cmd")
            } catch (t: Throwable) {
                CrashLogger.writeLog(ctx.applicationContext, "SYNC: comando '$cmd' falhou -> $t")
            }
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

    /**
     * O resultado de um POST: o que importa não é só "deu", é **por que** não deu.
     *
     * O `401` tem um significado próprio: o servidor recusou o token. Sem isso, o app
     * repetia a chamada a cada 5 minutos, para sempre, sem nunca dizer que a nuvem não
     * estava sincronizando.
     */
    private enum class PostResult { OK, UNAUTHORIZED, FAILED }

    private fun postJson(
        ctx: Context,
        path: String,
        body: String,
        token: String = Settings.telemetryToken(ctx)
    ): Boolean {
        return postJsonResult(ctx, path, body, token) == PostResult.OK
    }

    private fun postJsonResult(
        ctx: Context,
        path: String,
        body: String,
        token: String
    ): PostResult {
        val device = Settings.deviceId(ctx)
        val payload = body.toByteArray()
        var unauthorized = false
        for (base in hosts()) {
            try {
                val conn = URL("$base$path").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 800
                conn.readTimeout = 1500
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("X-Pulsa-Device", device)
                conn.setRequestProperty("X-Pulsa-Token", token)
                Net.user(ctx, conn)
                conn.setFixedLengthStreamingMode(payload.size)
                conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                Net.close(conn)
                if (code in 200..299) return PostResult.OK
                // O token recusado é do **host** que respondeu: o outro host pode ter outro
                // segredo. Por isso o `401` não encerra a tentativa — só o `OK` encerra.
                if (code == 401 || code == 403) {
                    unauthorized = true
                    continue
                }
            } catch (t: Throwable) {
                Log.w(TAG, "post $path falhou -> $t")
            }
        }
        return if (unauthorized) PostResult.UNAUTHORIZED else PostResult.FAILED
    }

    private fun songJson(s: Song) = JSONObject().apply {
        put("id", s.id)
        put("title", s.title)
        put("artist", s.artist)
        put("album", s.album)
        put("durationMs", s.durationMs)
    }

    @Volatile private var coverKey: Long = Long.MIN_VALUE
    @Volatile private var coverCache: String? = null

    /**
     * Capa da faixa como data URI. Memoriza por id: o push de estado roda a cada poucos
     * segundos, mas a codificação só acontece quando a faixa muda — então [ctx] e [s] só
     * são usados a cada troca, o que mantém barato o caminho repetido.
     */
    private fun coverFor(ctx: Context, s: Song): String? {
        if (s.id == coverKey) return coverCache
        val data = runCatching { encodeCover(ctx, s) }.getOrNull()
        coverKey = s.id
        coverCache = data
        return data
    }

    /** Miniatura JPEG. Rádio/stream/vídeo/podcast não têm arte local — devolvem `null` sem tentar rede. */
    private fun encodeCover(ctx: Context, s: Song): String? {
        if (s.isRadio || s.isStream || s.isVideo || s.isPodcast) return null
        if (s.path.isBlank() || s.path.startsWith("http", ignoreCase = true)) return null
        val art = ArtLoader.decode(ctx, s.albumId, s.path) ?: return null
        val scale = COVER_PX.toFloat() / maxOf(art.width, art.height).coerceAtLeast(1)
        val w = (art.width * scale).toInt().coerceAtLeast(1)
        val h = (art.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(art, w, h, true)
        val bytes = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
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
