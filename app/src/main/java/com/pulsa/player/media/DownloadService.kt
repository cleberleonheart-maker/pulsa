package com.pulsa.player.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.pulsa.player.MainActivity
import com.pulsa.player.R
import com.pulsa.player.core.Settings
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * F2 — baixa os vídeos do PeerTube para assistir sem internet.
 *
 * **Serviço em foreground com fila própria, e não o `ThreadPool.postNetwork`**, por dois
 * motivos. O primeiro é o mesmo que separou o pool de rede do local: download é transferência
 * longa, e ocupar as threads de rede faria o rádio e a letra da faixa sumirem — que foi
 * exatamente o bug do pool saturado. O segundo é o ciclo de vida: um `post` é uma tarefa
 * solta, morre com o processo, e não segura o foreground; sem isso o sistema mata o
 * download no meio, que é a pior coisa que um downloadcenter pode fazer.
 *
 * Retomar é por `Range`: o parcial fica em `<arquivo>.part` e a próxima tentativa pede
 * `bytes=<já temos>-`. Só funciona se o servidor aceitar; quando devolve `200` em vez de
 * `206`, o código detecta e recomeça do zero — senão os bytes virariam lixo colado na
 * frente de um arquivo válido.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_ENQUEUE = "com.pulsa.player.download.ENQUEUE"
        const val ACTION_CANCEL = "com.pulsa.player.download.CANCEL"
        const val ACTION_PAUSE = "com.pulsa.player.download.PAUSE"
        const val ACTION_DELETE = "com.pulsa.player.download.DELETE"
        const val ACTION_RETRY = "com.pulsa.player.download.RETRY"

        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"
        const val EXTRA_PAGE_URL = "pageUrl"
        const val EXTRA_FILE_NAME = "fileName"

        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 11

        /**
         * Ao mesmo tempo. Duas é o número que faz sentido aqui: um celular não ganha
         * banda com mais de duas transferências e o usuário não consegue ver a diferença
         * entre uma e três, mas sente quando o download de um vídeo derruba o de outro.
         */
        private const val PARALLEL = 2

        private const val TIMEOUT_MS = 20_000
        private const val CHUNK = 64 * 1024
        private const val MAX_REDIRECTS = 5

        /**
         * Folga que tem que sobrar no aparelho além do tamanho do arquivo. Baixar até o
         * último byte livre deixa o celular sem espaço para o próprio sistema respirar; um
         * download que enche o cartão é pior do que um download recusado.
         */
        private const val MIN_FREE = 32L * 1024L * 1024L

        private val running = AtomicBoolean(false)

        fun enqueue(
            context: Context,
            id: String,
            title: String,
            url: String,
            pageUrl: String,
            fileName: String
        ) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_ENQUEUE)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_PAGE_URL, pageUrl)
                    .putExtra(EXTRA_FILE_NAME, fileName)
            )
        }

        fun cancel(context: Context, id: String) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_ID, id)
            )
        }

        fun pause(context: Context, id: String) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_PAUSE)
                    .putExtra(EXTRA_ID, id)
            )
        }

        fun delete(context: Context, id: String) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_DELETE)
                    .putExtra(EXTRA_ID, id)
            )
        }

        fun retry(context: Context, id: String) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_RETRY)
                    .putExtra(EXTRA_ID, id)
            )
        }
    }

    /**
     * Threads da fila. Criadas uma vez e reutilizadas: `Executors.newFixedThreadPool` a cada
     * `enqueue` descartaria as threads, e cada thread nova custa alguns milissegundos de
     * setup que se somam em uma fila de dez vídeos.
     */
    private val workers = Executors.newFixedThreadPool(PARALLEL)

    /** Ids que o usuário mandou cancelar; a thread de transferência verifica a cada bloco. */
    private val cancelled = HashSet<String>()

    /**
     * Ids pausados pelo usuário. Separado de [cancelled] de propósito: cancelar e pausar
     * têm o mesmo efeito na transferência (interromper no próximo bloco, guardar o `.part`),
     * mas destinos diferentes — o cancelado vira `CANCELLED` e o pausado `PAUSED`, e é esse
     * estado que decide qual aviso a tela mostra.
     */
    private val paused = HashSet<String>()

    /** Ids entregues a uma thread da fila, para não entregar duas vezes ao ressurgindo Wi‑Fi. */
    private val submitted = HashSet<String>()

    /**
     * F2 · "Só no Wi‑Fi": quando a transferência está esperando rede não medida, a chegada
     * dela dispara a fila. Sem isto o item ficaria QUEUED para sempre — o callback é a única
     * coisa que acorda o download da espera.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = pump()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) pump()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        DownloadStore.ensureLoaded(this)
        registerNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ENQUEUE) {
            val item = DownloadStore.Item(
                id = intent.getStringExtra(EXTRA_ID).orEmpty(),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                url = intent.getStringExtra(EXTRA_URL).orEmpty(),
                pageUrl = intent.getStringExtra(EXTRA_PAGE_URL).orEmpty(),
                fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty(),
                status = DownloadStore.Status.QUEUED,
                addedAt = System.currentTimeMillis()
            )
            if (item.id.isNotBlank() && item.url.isNotBlank()) {
                DownloadStore.put(this, item)
            }
        } else {
            val id = intent?.getStringExtra(EXTRA_ID).orEmpty()
            when (intent?.action) {
                ACTION_CANCEL -> {
                    synchronized(cancelled) { cancelled.add(id) }
                    synchronized(paused) { paused.remove(id) }
                    val item = DownloadStore.byId(id)
                    // Se ainda estava na fila (ou esperando Wi‑Fi), não há thread para notar a
                    // flag: o cancelamento tem que dar certo agora, e não quando a fila chegar nele.
                    if (item != null && item.status == DownloadStore.Status.QUEUED) {
                        DownloadStore.put(
                            this, item.copy(status = DownloadStore.Status.CANCELLED, speed = 0L)
                        )
                    }
                }
                ACTION_PAUSE -> {
                    synchronized(paused) { paused.add(id) }
                    val item = DownloadStore.byId(id)
                    // Sem thread rodando, a pausa não tem quem interromper: o estado já cai
                    // direto. A thread em andamento vê a flag no próximo bloco e se pausa.
                    if (item != null && item.status == DownloadStore.Status.QUEUED) {
                        DownloadStore.put(
                            this, item.copy(status = DownloadStore.Status.PAUSED, speed = 0L)
                        )
                    }
                }
                ACTION_DELETE -> {
                    synchronized(cancelled) { cancelled.add(id) }
                    synchronized(paused) { paused.remove(id) }
                    DownloadStore.remove(this, id)
                }
                ACTION_RETRY -> {
                    synchronized(cancelled) { cancelled.remove(id) }
                    synchronized(paused) { paused.remove(id) }
                    DownloadStore.byId(id)?.let {
                        DownloadStore.put(
                            this, it.copy(status = DownloadStore.Status.QUEUED, error = null, speed = 0L)
                        )
                    }
                }
            }
        }
        // A fila entra ou desbloqueia: entrega os QUEUED às threads, se a rede deixar.
        pump()
        // O foreground sobe no primeiro `onStartCommand` de verdade, e não na criação: uma
        // notificação de "0 downloads" que aparece ao abrir o app é ruído.
        ensureForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        workers.shutdownNow()
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        }
        synchronized(cancelled) { cancelled.clear() }
        synchronized(paused) { paused.clear() }
        synchronized(submitted) { submitted.clear() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- transferência

    private fun runItem(original: DownloadStore.Item) {
        val id = original.id
        // A thread pode subir com a fila bloqueada ("só no Wi‑Fi" e rede medida no ar):
        // o item volta para a fila e a thread cede. Quem reentregá-lo é o callback de rede
        // quando a rede não medida chegar — sem essa devolução ele ficaria preso até o app
        // reabrir. O `finish` também desocupa o `submitted`, que é o que permite a reentrega.
        if (!networkAllowsDownload()) {
            DownloadStore.put(this, original.copy(status = DownloadStore.Status.QUEUED, speed = 0L))
            finish(id)
            return
        }
        if (isCancelled(id)) {
            DownloadStore.put(
                this,
                original.copy(status = DownloadStore.Status.CANCELLED, speed = 0L),
                persist = false
            )
            finish(id)
            return
        }
        if (isPaused(id)) {
            // Pausado enquanto a thread esperava a vez: vira PAUSED sem chegar a tocar a rede.
            DownloadStore.put(this, original.copy(status = DownloadStore.Status.PAUSED, speed = 0L))
            finish(id)
            return
        }
        // Antes de tocar a rede: sem um mínimo de espaço livre não vale nem tentar, porque um
        // vídeo de tamanho desconhecido encheria o cartão no meio do caminho.
        if (!hasRoomFor(0L)) {
            DownloadStore.put(
                this,
                original.copy(status = DownloadStore.Status.FAILED, error = getString(R.string.downloads_no_space)),
                persist = false
            )
            finish(id)
            return
        }
        // Limite do Download Center: o teto é do que já está em disco (arquivos + `.part`).
        // Um item novo que já passaria dele é recusado antes de gastar um byte de rede — o
        // aviso aqui é mais barato que o aviso no meio do download, e é o que deixa o
        // cartão protegido de propósito em vez de por sorte.
        if (limitExceeded(0L)) {
            DownloadStore.put(
                this,
                original.copy(status = DownloadStore.Status.FAILED, error = getString(R.string.downloads_limit_reached)),
                persist = false
            )
            finish(id)
            return
        }
        DownloadStore.put(
            this,
            original.copy(status = DownloadStore.Status.RUNNING, error = null, speed = 0L),
            persist = false
        )
        val part = File(DownloadStore.dir(this), "${original.fileName}.part")
        var failure: String? = null
        try {
            var url = original.url
            var redirects = 0
            var response: Int
            var startingAt = 0L
            var totalAnunciado = 0L
            // `conn` é um `val` de escopo do `try` e não o `var connection` de antes: uma
            // `var` capturada e reassignada no laço de redirect perde o smart cast, e o
            // compilador passa a pedir `?.` em cada `contentLength`.
            val conn: HttpURLConnection
            while (true) {
                val aberta = open(url, part.length())
                response = aberta.responseCode
                if (response == HttpURLConnection.HTTP_PARTIAL && part.length() > 0L) {
                    startingAt = part.length()
                    totalAnunciado = totalFromRange(aberta)
                    // `206` não garante que seja o **mesmo** arquivo: o dono pode ter
                    // reenviado o vídeo, e aí o servidor honestamente devolve o pedaço novo
                    // a partir do offset pedido. Emendar as duas versões dá um MP4 que
                    // passa em toda checagem de tamanho e só falha na hora de tocar — e
                    // falha longe do ponto do erro. Size diferente entre a tentativa
                    // anterior e agora é a assinatura disso.
                    val anterior = original.total
                    if (anterior > 0L && totalAnunciado > 0L && totalAnunciado != anterior) {
                        part.delete()
                        startingAt = 0L
                    }
                    conn = aberta
                    break
                }
                // `200` com um parcial já em disco significa que o servidor **ignorou** o
                // Range (ou o arquivo mudou). Anexar nesse caso produziria um arquivo
                // corrompido, então o parcial é descartado e o download recomeça.
                if (part.length() > 0L) part.delete()
                totalAnunciado = 0L
                if (response in 300..399 && redirects < MAX_REDIRECTS) {
                    val next = aberta.getHeaderField("Location")
                        ?: throw IllegalStateException("redirect sem Location")
                    url = URL(URL(url), next).toString()
                    aberta.disconnect()
                    redirects++
                    continue
                }
                conn = aberta
                break
            }
            if (response !in 200..299) throw IllegalStateException("HTTP $response")

            // Com `206` o `Content-Length` é só o resto do arquivo; somar com o que já
            // temos é o que faz a barra chegar a 100% em vez de estourar. O `Content-Range`
            // é melhor ainda, porque traz o total real em vez de reconstruí-lo por soma.
            val restante = conn.contentLength.toLong().takeIf { it > 0L } ?: -1L
            val total = when {
                totalAnunciado > 0L -> totalAnunciado
                startingAt > 0L && restante > 0L -> startingAt + restante
                else -> restante
            }

            // Agora o tamanho real é conhecido: a primeira checagem foi às cegas, esta é a
            // definitiva. Só conta o que falta baixar — o que já está no `.part` não ocupa.
            val need = if (total > 0L) (total - startingAt).coerceAtLeast(0L) else 0L
            if (!hasRoomFor(need)) {
                throw IllegalStateException(getString(R.string.downloads_no_space))
            }
            // A checagem definitiva do limite também: com o total real, dá para recusar o
            // download que estouraria o teto de propósito sem depender do tamanho conhecido
            // antes de abrir a conexão.
            if (total > 0L && limitExceeded(need)) {
                throw IllegalStateException(getString(R.string.downloads_limit_reached))
            }

            DownloadStore.put(
                this,
                original.copy(status = DownloadStore.Status.RUNNING, bytes = part.length(), total = total, speed = 0L),
                persist = false
            )

            conn.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(startingAt)
                    val buffer = ByteArray(CHUNK)
                    // `lastUi` evita `put` a cada bloco: o download de um vídeo grande gera
                    // milhares de chunks de 64k, e cada `put` acorda a UI e redesenha a linha.
                    var lastUi = SystemClock.elapsedRealtime()
                    var lastBytes = startingAt
                    var n: Int
                    while (input.read(buffer).also { n = it } > 0) {
                        if (isCancelled(id)) {
                            out.fd.sync()
                            throw Cancelled()
                        }
                        if (isPaused(id)) {
                            // O `.part` fica de propósito: é ele que faz "continuar" retomar
                            // pelo `Range` sem perder o que já baixou — o mesmo trato do cancel.
                            out.fd.sync()
                            throw Paused()
                        }
                        out.write(buffer, 0, n)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUi > 400L) {
                            // Velocidade pelo intervalo real, não pelo nominal do chunk: a
                            // conta usa `out.length()` porque é o que o `put` vai reportar.
                            val speed = ((out.length() - lastBytes) * 1000L) / (now - lastUi).coerceAtLeast(1L)
                            lastUi = now
                            lastBytes = out.length()
                            // Tamanho desconhecido escorrega: sem total não dá para recusar
                            // antes de começar, então o `.part` em crescimento é que acusa a
                            // passagem do teto. O custo é uma checagem por 400 ms, o mesmo
                            // batimento do `put`.
                            if (limitExceeded(0L)) {
                                out.fd.sync()
                                throw IllegalStateException(getString(R.string.downloads_limit_reached))
                            }
                            DownloadStore.put(
                                this,
                                original.copy(
                                    status = DownloadStore.Status.RUNNING,
                                    bytes = out.length(),
                                    total = total,
                                    speed = speed
                                ),
                                persist = false
                            )
                        }
                    }
                    out.fd.sync()
                    if (total > 0L && out.length() < total) {
                        throw IllegalStateException("download incompleto (${out.length()}/$total)")
                    }
                }
            }
            conn.disconnect()
            val destino = File(DownloadStore.dir(this), original.fileName)
            // `renameTo` falha se o destino já existe, e um retry de item já baixado cai
            // exatamente nisso: o arquivo antigo é apagado antes.
            runCatching { destino.delete() }
            if (part.renameTo(destino)) {
                DownloadStore.put(
                    this,
                    original.copy(
                        status = DownloadStore.Status.DONE,
                        bytes = destino.length(),
                        total = if (total > 0L) total else destino.length(),
                        error = null
                    )
                )
            } else {
                failure = "não foi possível gravar o arquivo"
            }
        } catch (e: Paused) {
            // Diferente do cancel, o aviso é "Pausado" (fila esperando, não tentativa morta):
            // a tela mostra "Continuar" como primeira ação e não o cachorro de lixo.
            val current = DownloadStore.byId(id)
            if (current != null) {
                DownloadStore.put(this, current.copy(status = DownloadStore.Status.PAUSED, speed = 0L))
            }
        } catch (e: Cancelled) {
            // O parcial fica de propósito: é ele que faz "continuar" funcionar.
            val current = DownloadStore.byId(id)
            if (current != null) {
                DownloadStore.put(this, current.copy(status = DownloadStore.Status.CANCELLED, speed = 0L), persist = false)
            }
        } catch (t: Throwable) {
            failure = t.message ?: t.javaClass.simpleName
            val current = DownloadStore.byId(id)
            if (current != null) {
                DownloadStore.put(this, current.copy(status = DownloadStore.Status.FAILED, error = failure, speed = 0L))
            }
        } finally {
            finish(id)
        }
    }

    private class Cancelled : Exception()

    private class Paused : Exception()

    private fun isCancelled(id: String): Boolean =
        synchronized(cancelled) { cancelled.contains(id) }

    private fun isPaused(id: String): Boolean =
        synchronized(paused) { paused.contains(id) }

    /**
     * Entrega os itens que estão na fila às threads, se a rede deixar.
     *
     * É o único ponto que entrega trabalho: `enqueue`, `retry` e o callback de rede passam
     * todos por aqui. O `submitted` impede o mesmo item de ser entregue duas vezes — que é o
     * que aconteceria se o callback de Wi‑Fi disparar `pump` enquanto `enqueue` ainda está
     * entregando.
     */
    private fun pump() {
        if (!networkAllowsDownload()) return
        val toRun = DownloadStore.list().filter {
            it.status == DownloadStore.Status.QUEUED &&
                synchronized(submitted) { submitted.add(it.id) }
        }
        toRun.forEach { workers.submit { runItem(it) } }
    }

    /**
     * F2 · "Só no Wi‑Fi": rede não medida (Wi‑Fi ou cabo), ou a preferência desligada.
     *
     * `NET_CAPABILITY_NOT_METERED` e não "tem Wi‑Fi", porque é a pergunta certa: um Wi‑Fi com
     * limite de dados onde o usuário marcou "medido" é tão caro quanto o 4G, e uma rede 4G com
     * franquia ilimitada não deveria bloquear. O usuário decide por transporte ou por tarifa.
     */
    private fun networkAllowsDownload(): Boolean {
        if (!Settings.downloadWifiOnly(this)) return true
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Registra o callback que acorda a fila quando uma rede não medida aparece.
     *
     * É o par de [networkAllowsDownload]: sem o callback, um item bloqueado na espera do Wi‑Fi
     * fica para sempre DE QUEUED até o app reabrir. `registerNetworkCallback` funciona desde a
     * API 21, então não precisa do `registerDefaultNetworkCallback` (que exige 24).
     */
    private fun registerNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, networkCallback) }
    }

    /**
     * Espaço livre para um download, com a folga de [MIN_FREE] sobrando de verdade.
     *
     * `usableSpace` pode dar `0` em alguns file systems; sem um número confiável não bloqueia
     * — quem avisa nessas horas é o `IllegalStateException("download incompleto")` do fim.
     */
    private fun hasRoomFor(need: Long): Boolean {
        val free = runCatching { DownloadStore.dir(this).usableSpace }.getOrDefault(0L)
        if (free <= 0L) return true
        return free >= need + MIN_FREE
    }

    /**
     * O teto do Download Center (`Settings.downloadLimitMb`) está estourado por `extra` bytes.
     *
     * `0` quando o limite não está definido — sem limite, não há nada a recusar. O `extra` é
     * sempre o que o download vai **adicionar** ao disco (nunca o total dele), porque o
     * `.part` de um item que retoma já está contado dentro de [DownloadStore.usedBytes].
     */
    private fun limitExceeded(extra: Long): Boolean {
        val limitMb = Settings.downloadLimitMb(this)
        if (limitMb <= 0) return false
        val limit = limitMb * 1024L * 1024L
        return DownloadStore.usedBytes(this) + extra > limit
    }

    /** Encerra a corrida de um id: limpa as flags e deixa o foreground decidir se continua vivo. */
    private fun finish(id: String) {
        synchronized(cancelled) { cancelled.remove(id) }
        synchronized(paused) { paused.remove(id) }
        synchronized(submitted) { submitted.remove(id) }
        ensureForeground()
    }

    /**
     * Total real do arquivo em uma resposta `206`, lido do `Content-Range: bytes a-b/total`.
     * Devolve `0` quando o servidor não manda o cabeçalho.
     */
    private fun totalFromRange(conn: HttpURLConnection): Long =
        runCatching {
            conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: 0L
        }.getOrDefault(0L)

    private fun open(url: String, from: Long): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.instanceFollowRedirects = false
        if (from > 0L) c.setRequestProperty("Range", "bytes=$from-")
        c.setRequestProperty("Accept-Encoding", "identity")
        return c
    }

    // ---------------------------------------------------------------- foreground

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.downloads_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    /**
     * Entra e sai do foreground.
     *
     * A ordem importa e não é descuidada: **sempre** chamamos `startForeground` antes de
     * qualquer `stopSelf`. O serviço é iniciado com `startForegroundService`, e o Android
     * mata o processo com `ForegroundServiceDidNotStartInTimeException` se `startForeground`
     * não vier em 5 s — e um "apagar" ou "cancelar" chega aqui sem nenhum download
     * ativo, que é justamente o caso em que a primeira versão deste método saía do
     * foreground sem nunca ter entrado.
     */
    private fun ensureForeground() {
        val active = DownloadStore.list().filter { it.isActive() }
        if (active.isEmpty() && !running.get()) {
            // Nada em andamento e nunca chegamos a subir: só encerrar.
            stopSelf()
            return
        }
        if (running.compareAndSet(false, true)) {
            startForeground(NOTIFICATION_ID, buildNotification(active), foregroundType())
        } else {
            updateNotification(active)
        }
        if (active.isEmpty()) {
            running.set(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun updateNotification(active: List<DownloadStore.Item>) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(active)) }
    }

    private fun buildNotification(active: List<DownloadStore.Item>): Notification {
        val one = active.firstOrNull()
        val title = if (active.size > 1) {
            getString(R.string.downloads_active_count, active.size)
        } else {
            getString(R.string.downloads_channel)
        }
        val text = one?.title ?: getString(R.string.downloads_idle)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        val f = one?.fraction()
        if (f != null) builder.setProgress(100, (f * 100).toInt(), false) else builder.setProgress(0, 0, true)
        return builder.build()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
}
