package com.pulsa.player.media.tv

import android.content.Context
import android.net.wifi.WifiManager
import android.net.Uri
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A2 — o servidor DLNA embutido: SSDP (descoberta) + HTTP (descrição, SOAP Browse, mídia).
 *
 * **Por que mão própria.** O projeto não tem NanoHTTPD nem qualquer lib de servidor, e
 * introduzir uma para três endpoints seria mais superfície de ataque do que o próprio
 * código. O servidor é ~300 linhas de socket no estilo do resto do app.
 *
 * **Ciclo de vida.** [start] sobe tudo, [stop] derruba e anuncia `ssdp:byebye` (sem isso a
 * TV fica com o servidor fantasma na lista). Sessão é curta de propósito: só existe enquanto
 * o usuário escolheu "tocar na TV" — servidor que fica rodando sozinho é bateria e porta
 * aberta à toa.
 *
 * **MulticastLock.** O Wi-Fi do Android filtra pacote multicast por padrão, e o SSDP é
 * multicast — sem o lock o M-SEARCH da TV **nunca chega** e o servidor "não aparece" mesmo
 * respondendo certo. É o defeito número um de servidor DLNA em Android, e a permissão
 * `CHANGE_WIFI_MULTICAST_STATE` está no manifest justamente para isto.
 *
 * **Threads.** Um pool de 4 para HTTP (a TV abre poucas conexões; mídia longa segura uma
 * thread só durante o transfer) e uma thread própria para o SSDP (loop bloqueante de
 * receive, não pode competir com o pool).
 */
class DlnaServer(
    context: Context,
    private val uuid: String,
    /** Árvore imutável da sessão — reconstruída a cada "tocar na TV", não durante. */
    private val nodes: Map<String, ContentTree.Node>,
    /**
     * Resolve um fileId para a origem do bytes. Retorna o path local, `content://`, ou
     * URL http(s) (que vira redirect — não fazemos proxy de stream, seria CPU e bateria
     * para a TV buscar direto do originador).
     */
    private val resolve: (String) -> String?,
) {

    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var ssdpSocket: MulticastSocket? = null
    private val httpPool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "pulsa-dlna-http").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private var ssdpThread: Thread? = null

    /** Porta HTTP efetiva (atribuída em [start] — efêmera, anunciada no SSDP). */
    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var baseUrl: String = ""
        private set

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return runCatching {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(0)) // efêmera: evita brigar com porta ocupada
            serverSocket = ss
            port = ss.localPort
            val ip = localIp() ?: return false
            baseUrl = "http://$ip:$port"
            startHttp(ss)
            startSsdp()
            true
        }.getOrElse {
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // byebye antes de fechar o socket: a TV precisa ver a saída enquanto ainda há porta.
        runCatching { sendSsdpByebye() }
        runCatching { serverSocket?.close() }
        runCatching { ssdpSocket?.close() }
        runCatching { multicastLock?.release() }
        multicastLock = null
        httpPool.shutdownNow()
        ssdpThread?.interrupt()
        serverSocket = null
        ssdpSocket = null
        ssdpThread = null
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private fun startHttp(ss: ServerSocket) {
        Thread({
            while (running.get()) {
                val socket = runCatching { ss.accept() }.getOrNull() ?: break
                try {
                    httpPool.execute { handle(socket) }
                } catch (e: Exception) {
                    runCatching { socket.close() }
                }
            }
        }, "pulsa-dlna-accept").start()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 8000
            runCatching {
                val input = BufferedReader(InputStreamReader(s.getInputStream(), "UTF-8"))
                val requestLine = input.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isBlank()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val out = s.getOutputStream()
                when {
                    method == "GET" && path == "/desc.xml" ->
                        xml200(out, DlnaXml.deviceDescription(uuid, baseUrl))
                    method == "GET" && path == "/cds.xml" ->
                        xml200(out, DlnaXml.scpdContentDirectory())
                    method == "GET" && path == "/cms.xml" ->
                        xml200(out, DlnaXml.scpdConnectionManager())
                    // SUBSCRIBE de eventing: a TV manda, respondemos 200 com SID falso —
                    // não emitimos NOTIFY, e a Roku Media Player segue funcionando sem os
                    // eventos (muitos servidores fazem igual).
                    method == "SUBSCRIBE" -> subscribe200(out)
                    method == "POST" && path == "/ctl" ->
                        handleSoap(input, headers, out)
                    method == "GET" && path.startsWith("/f/") ->
                        serveMedia(path.removePrefix("/f/"), headers, out)
                    else -> notFound(out)
                }
            }.onFailure {
                runCatching { socket.close() }
            }
        }
    }

    private fun handleSoap(
        input: BufferedReader,
        headers: Map<String, String>,
        out: OutputStream,
    ) {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val chars = CharArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(chars, read, length - read)
                if (n < 0) break
                read += n
            }
            String(chars, 0, read)
        } else ""
        val action = headers["soapaction"]?.trim('"')
            ?: DlnaXml.soapAction(body)?.second
        val (service, name) = when {
            action != null && action.contains("ContentDirectory") ->
                DlnaXml.SERVICE_CD to action.substringAfter('#')
            action != null && action.contains("ConnectionManager") ->
                DlnaXml.SERVICE_CM to action.substringAfter('#')
            else -> DlnaXml.soapAction(body) ?: run {
                xml500(out, DlnaXml.faultInvalidAction())
                return
            }
        }
        val response = when {
            service == DlnaXml.SERVICE_CD && name == "Browse" -> {
                val req = DlnaXml.parseBrowseRequest(body)
                if (req == null) {
                    DlnaXml.faultInvalidAction()
                } else {
                    val node = nodes[req.objectId]
                    if (!req.isDirectChildren) {
                        // BrowseMetadata: devolve o próprio nó (a TV pede ao abrir
                        // detalhe de faixa ou confirmar a raiz). Nó inexistente = vazio.
                        val didl = node?.let { ContentTree.didlSingle(it, baseUrl) }.orEmpty()
                        val n = if (node != null) 1 else 0
                        DlnaXml.browseResponse(didl, n, n)
                    } else {
                        val (page, total) = ContentTree.children(
                            nodes, req.objectId, req.startingIndex, req.requestedCount,
                        )
                        val didl = ContentTree.didl(
                            nodes, req.objectId, req.startingIndex, req.requestedCount, baseUrl,
                        )
                        DlnaXml.browseResponse(didl, page.size, total)
                    }
                }
            }
            service == DlnaXml.SERVICE_CD && name == "GetSortCapabilities" ->
                DlnaXml.emptyActionResponse(service, name, "<SortCaps>dc:title</SortCaps>")
            service == DlnaXml.SERVICE_CD && name == "GetSearchCapabilities" ->
                DlnaXml.emptyActionResponse(service, name, "<SearchCaps></SearchCaps>")
            service == DlnaXml.SERVICE_CD && name == "GetSystemUpdateID" ->
                DlnaXml.emptyActionResponse(service, name, "<Id>${DlnaXml.UPDATE_ID}</Id>")
            service == DlnaXml.SERVICE_CM && name == "GetProtocolInfo" ->
                DlnaXml.getProtocolInfoResponse()
            service == DlnaXml.SERVICE_CM && name == "GetCurrentConnectionIDs" ->
                DlnaXml.emptyActionResponse(service, name, "<ConnectionIDs>0</ConnectionIDs>")
            service == DlnaXml.SERVICE_CM && name == "GetCurrentConnectionInfo" ->
                DlnaXml.emptyActionResponse(
                    service, name,
                    "<RcsID>-1</RcsID><AVTransportID>-1</AVTransportID>" +
                        "<ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager>" +
                        "<PeerConnectionID>-1</PeerConnectionID><Direction>Output</Direction>" +
                        "<Status>Unknown</Status>",
                )
            else -> DlnaXml.faultInvalidAction()
        }
        xml200(out, response)
    }

    /**
     * Mídia com `Range`/206. Três origens: arquivo local (seek direto), `content://`
     * (MediaStore — seek via FileChannel no FileDescriptor), e URL http (302 — a TV
     * busca direto do originador, sem passar proxy por aqui).
     */
    private fun serveMedia(fileId: String, headers: Map<String, String>, out: OutputStream) {
        val target = resolve(fileId)
        if (target == null) {
            notFound(out)
            return
        }
        // O mime vem da árvore (o fileId é "m42", não tem extensão para adivinhar).
        val mime = (nodes[fileId] as? ContentTree.Node.Item)?.entry?.mime
            ?: "application/octet-stream"
        if (target.startsWith("http://") || target.startsWith("https://")) {
            // Redirect: Roku Media Player segue sem problema; stream grande não trafega
            // duas vezes pelo celular (que é o que importa em bateria e Wi-Fi).
            out.write(
                (
                    "HTTP/1.1 302 Found\r\n" +
                        "Location: $target\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            out.flush()
            return
        }
        try {
            if (target.startsWith("content://")) {
                serveContentUri(Uri.parse(target), mime, headers, out)
            } else {
                val file = File(target)
                if (!file.exists() || !file.canRead()) {
                    notFound(out)
                    return
                }
                serveStream(file.length(), mime, headers, out) { start, _ ->
                    val fis = FileInputStream(file)
                    fis.skip(start)
                    fis
                }
            }
        } catch (e: FileNotFoundException) {
            notFound(out)
        } catch (e: IOException) {
            // TV desconectou no meio do transfer: normal, não é erro para o usuário.
        }
    }

    private fun serveContentUri(
        uri: Uri,
        mime: String,
        headers: Map<String, String>,
        out: OutputStream,
    ) {
        val resolver = app.contentResolver
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        pfd.use { fd ->
            val size = android.system.Os.fstat(fd.fileDescriptor).st_size
            // FileChannel direto do FileDescriptor: seekable para MediaStore, e o canal
            // É o canal de leitura (Channels.newChannel só embrulha InputStream).
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                val range = HttpRange.parse(headers["range"], size)
                writeHeaders(out, size, range, mime)
                val start = range?.start ?: 0L
                val length = range?.length ?: size
                val buf = ByteArray(64 * 1024)
                var remaining = length
                ch.position(start)
                while (remaining > 0) {
                    val n = ch.read(java.nio.ByteBuffer.wrap(buf, 0, minOf(buf.size.toLong(), remaining).toInt()))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    remaining = remaining - n.toLong()
                }
            }
        }
        out.flush()
    }

    private fun serveStream(
        size: Long,
        mime: String,
        headers: Map<String, String>,
        out: OutputStream,
        open: (start: Long, length: Long) -> java.io.InputStream,
    ) {
        val range = HttpRange.parse(headers["range"], size)
        writeHeaders(out, size, range, mime)
        val start = range?.start ?: 0L
        val length = range?.length ?: size
        open(start, length).use { input ->
            val buf = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                remaining = remaining - n.toLong()
            }
        }
        out.flush()
    }

    private fun writeHeaders(
        out: OutputStream,
        size: Long,
        range: HttpRange.Spec?,
        mime: String,
    ) {
        val sb = StringBuilder()
        if (range != null) {
            sb.append("HTTP/1.1 206 Partial Content\r\n")
            sb.append("Content-Range: bytes ").append(range.start).append('-')
                .append(range.endInclusive).append('/').append(size).append("\r\n")
            sb.append("Content-Length: ").append(range.length).append("\r\n")
        } else {
            sb.append("HTTP/1.1 200 OK\r\n")
            sb.append("Content-Length: ").append(size).append("\r\n")
        }
        sb.append("Content-Type: ").append(mime).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        // DLNA.ORG_OP=01 = aceita seek por Range; FLAGS 0170... = streaming + connection
        // stall + background — os bits que a Roku Media Player checa antes de dar play.
        sb.append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;")
            .append("DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
        sb.append("transferMode.dlna.org: Streaming\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun subscribe200(out: OutputStream) {
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "SID: uuid:pulsa-evt\r\n" +
                    "TIMEOUT: Second-1800\r\n" +
                    "Content-Length: 0\r\n\r\n"
                ).toByteArray(),
        )
        out.flush()
    }

    private fun xml200(out: OutputStream, xml: String) {
        val body = xml.toByteArray()
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    private fun xml500(out: OutputStream, xml: String) {
        val body = xml.toByteArray()
        out.write(
            (
                "HTTP/1.1 500 Internal Server Error\r\n" +
                    "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    private fun notFound(out: OutputStream) {
        out.write(
            (
                "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.flush()
    }

    // ── SSDP ────────────────────────────────────────────────────────────────

    private fun startSsdp() {
        val lock = wifi.createMulticastLock("pulsa-dlna")
        lock.setReferenceCounted(false)
        lock.acquire()
        multicastLock = lock

        val socket = MulticastSocket(InetSocketAddress(1900))
        socket.reuseAddress = true
        ssdpSocket = socket
        val t = Thread({
            runCatching {
                val group = InetAddress.getByName("239.255.255.250")
                socket.joinGroup(group)
                val buf = ByteArray(2048)
                while (running.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    if (!msg.startsWith("M-SEARCH")) continue
                    val st = msg.lineSequence()
                        .firstOrNull { it.startsWith("ST:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim() ?: continue
                    if (!DlnaXml.stAtende(st)) continue
                    // Responde unicast para o remetente (o padrão do SSDP).
                    val response = DlnaXml.ssdpResponse("$baseUrl/desc.xml", uuid, st)
                    val bytes = response.toByteArray()
                    val reply = DatagramPacket(bytes, bytes.size, packet.address, packet.port)
                    socket.send(reply)
                }
            }
        }, "pulsa-dlna-ssdp")
        t.isDaemon = true
        ssdpThread = t
        t.start()
    }

    private fun sendSsdpByebye() {
        val socket = ssdpSocket ?: return
        runCatching {
            val msg = DlnaXml.ssdpByebye(uuid).toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            socket.send(DatagramPacket(msg, msg.size, group, 1900))
        }
    }

    /** Primeiro IPv4 não-loopback (wlan0/eth0) — o que a TV alcança. */
    private fun localIp(): String? {
        val addrs = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
        }.getOrDefault(emptyList())
        return addrs.firstOrNull()?.hostAddress
    }
}
