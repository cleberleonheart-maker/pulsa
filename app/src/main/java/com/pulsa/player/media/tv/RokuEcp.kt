package com.pulsa.player.media.tv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * A1 — falar com um Roku pelo **ECP** (External Control Protocol), o HTTP que o Roku
 * expõe na porta **8060** desde o firmware 1.
 *
 * **Por que não biblioteca.** O ECP é um punhado de endpoints REST sem autenticação e sem
 * streaming — o protocolo inteiro cabe nesta classe, e uma lib de terceiros seria mais
 * código para auditar do que o que estamos escrevendo. Nada aqui criptografa ou envia
 * credencial: o ECP é de propósito aberto na LAN, e só aceita comandos de quem está na
 * mesma rede (o próprio Roku expõe o toggle "Control by mobile apps").
 *
 * **Descoberta.** O caminho certo é SSDP (`M-SEARCH` para `urn:roku-com:service:ecp:1` em
 * 239.255.255.250:1900): o Roku responde unicast com o `LOCATION` apontando para o próprio
 * IP. Rede doméstica com Wi-Fi multicast filtrado (roteador barato) engole o M-SEARCH — e é
 * comum —, então o [discover] tem o plano B de varrer a sub-rede /24 na porta 8060 com
 * pool de threads e timeout curto. O plano B é mais lento, mas acha o Roku no mesmo roteador
 * que filtra multicast, porque o unicast continua livre.
 *
 * **Nomes de pacote.** O projeto inteiro está em `com.pulsa.player.*`; não existe
 * `br.com.pulsa` aqui (resquício de nota antiga). Parsers são funções puras para cobertura
 * em teste JVM, mesmo motivo documentado no `FeedParser`.
 */
object RokuEcp {

    /** Porta fixa do ECP, documentada pela Roku em todo firmware. */
    const val PORT = 8060

    private val NOME_RMP = listOf("roku media player", "media player", "player de mídia")

    data class Device(val host: String, val friendlyName: String, val model: String)

    data class App(val id: String, val name: String)

    data class PlayerState(
        val state: String,
        val positionMs: Long,
        val durationMs: Long,
    ) {
        val isPlaying: Boolean get() = state.equals("play", ignoreCase = true)
    }

    // ── comandos ────────────────────────────────────────────────────────────

    /** `/query/device-info`. Confirma que um host respondeu e traz o nome amigável real. */
    fun deviceInfo(host: String): Device? {
        val xml = get("http://$host:$PORT/query/device-info") ?: return null
        return parseDeviceInfo(host, xml)
    }

    /** `/query/apps` — lista de canais instalados, para achar o Roku Media Player pelo nome. */
    fun apps(host: String): List<App> {
        val xml = get("http://$host:$PORT/query/apps", 3000, 3000) ?: return emptyList()
        return parseApps(xml)
    }

    /**
     * Lança o Roku Media Player ([appId] = "11" no firmware antigo, "12" no novo).
     *
     * **Por que só o Media Player e não item específico.** O Roku não tem deep-link para
     * item DLNA — o único `launch` que aceita argumento é o de app com query string própria
     * (Netflix/YouTube). Para mídia de servidor, o caminho real é: lança o Media Player, ele
     * mesmo descobre os servidores DLNA da rede (inclusive o nosso) e o usuário escolhe
     * "Pulsa" no controle. O app só empurra o canal; a navegação é da TV.
     */
    fun launch(host: String, appId: String): Boolean {
        return request("http://$host:$PORT/launch/$appId", "POST", 3000, 3000) != null
    }

    /**
     * Tenta lançar o Roku Media Player (por nome → "12" → "11"), validando cada tentativa.
     * Retorna true se algum POST retornar 200.
     *
     * **Por que os dois IDs.** O ID do canal muda entre firmware ("12" é o atual, "11" o
     * antigo) e `apps()` pode dar timeout em rede lenta — por isso o nome vem primeiro e
     * os IDs históricos são o plano B, cada um conferido pelo código HTTP antes de rendição.
     */
    fun launchRmp(host: String): Boolean {
        val found = apps(host).firstOrNull { app ->
            val n = app.name.lowercase()
            NOME_RMP.any { n.contains(it) }
        }
        if (found?.id != null) {
            if (launch(host, found.id)) return true
        }
        if (launch(host, "12")) return true
        if (launch(host, "11")) return true
        return false
    }

    /** `/keypress/<tecla>` — Play, Pause, Forward, Rewind, Home, VolumeUp/Down... */
    fun keypress(host: String, key: String) {
        request("http://$host:$PORT/keypress/$key", "POST")
    }

    /** `/query/media-player` — estado do player nativo, para o painel de controle. */
    fun mediaPlayer(host: String): PlayerState? {
        val xml = get("http://$host:$PORT/query/media-player") ?: return null
        return parseMediaPlayer(xml)
    }

    // ── descoberta ──────────────────────────────────────────────────────────

    /**
     * Acha Rokus na rede: SSDP primeiro, varredura da sub-rede em segundo.
     * Retorna hosts únicos (o SSDP e a varredura podem achar o mesmo aparelho).
     */
    fun discover(): List<Device> {
        val hosts = LinkedHashSet<String>()
        hosts += discoverSsdp()
        if (hosts.isEmpty()) hosts += discoverScan()
        // Confirma cada host com o device-info (tira da lista quem não é Roku de verdade
        // e traz o nome amigável para o diálogo de pareamento).
        return hosts.mapNotNull { host -> deviceInfo(host) }
    }

    /**
     * SSDP: `M-SEARCH` para o alvo do ECP, três vezes (rede Wi-Fi engole o primeiro
     * pacote com frequência; o terceiro é o que costuma chegar).
     */
    private fun discoverSsdp(): List<String> {
        val hosts = LinkedHashSet<String>()
        val msg = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:roku-com:service:ecp:1\r\n" +
                "\r\n"
            ).toByteArray()
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1200
                val group = InetAddress.getByName("239.255.255.250")
                val target = InetSocketAddress(group, 1900)
                repeat(3) { socket.send(DatagramPacket(msg, msg.size, target)) }
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + 2500
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    runCatching { socket.receive(packet) }.getOrNull() ?: break
                    parseSsdpLocation(String(packet.data, 0, packet.length))?.let { hosts += it }
                }
            }
        }
        return hosts.toList()
    }

    /**
     * Plano B: varre a sub-rede /24 do próprio IP na porta 8060. Acha o Roku no roteador
     * que filtra multicast, porque unicast passa. 32 threads, 400ms de timeout por host:
     * /24 inteiro em ~3s pior caso, e quem não respondeu cai rápido.
     */
    private fun discoverScan(): List<String> {
        val prefix = subnetPrefix() ?: return emptyList()
        val hosts = LinkedHashSet<String>()
        val pool = Executors.newFixedThreadPool(32)
        try {
            val futures = (1..254).map { i ->
                pool.submit(Callable {
                    val host = "$prefix.$i"
                    if (deviceInfo(host) != null) host else null
                })
            }
            futures.forEach { f ->
                runCatching { f.get(3, TimeUnit.SECONDS) }.getOrNull()?.let { hosts += it }
            }
        } finally {
            pool.shutdownNow()
        }
        return hosts.toList()
    }

    /** Prefixo /24 do primeiro IPv4 não-loopback (wlan0 ou eth0). */
    internal fun subnetPrefix(): String? {
        val addrs = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
        }.getOrDefault(emptyList())
        val ip = addrs.firstOrNull()?.hostAddress ?: return null
        val cut = ip.lastIndexOf('.')
        return if (cut > 0) ip.substring(0, cut) else null
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private fun get(url: String): String? = request(url, "GET")
    private fun get(url: String, connectTimeout: Int, readTimeout: Int): String? = request(url, "GET", connectTimeout, readTimeout)

    private fun request(url: String, method: String, connectTimeout: Int = 1500, readTimeout: Int = 1500): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeout
        conn.readTimeout = readTimeout
        conn.requestMethod = method
        conn.doInput = true
        if (conn.responseCode != 200) return null
        conn.inputStream.use { readAll(it) }
    }.getOrNull()

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    // ── parse (funções puras — cobertas por teste JVM, como o FeedParser) ────

    /**
     * `LOCATION` do cabeçalho SSDP → host (IP:porta) do ECP. O header vem como
     * `LOCATION: http://192.168.0.15:8060/query/device-info`; interessa só a autoridade.
     */
    internal fun parseSsdpLocation(header: String): String? {
        val line = header.lineSequence().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?: return null
        val url = line.substringAfter(':').trim()
        return url.removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore('?').ifBlank { null }
    }

    /**
     * `/query/device-info` → [Device]. O XML varia por firmware (alguns têm
     * `user-device-name`, outros só `friendly-name`), por isso os dois nomes.
     */
    internal fun parseDeviceInfo(host: String, xml: String): Device? = runCatching {
        val doc = parseXml(xml)
        val root = doc.documentElement ?: return null
        val name = text(root, "user-device-name")?.ifBlank { null }
            ?: text(root, "friendly-name")
            ?: return null
        Device(host, name, text(root, "model-name") ?: "Roku")
    }.getOrNull()

    /**
     * `/query/apps` → canais. Cada `<app id="...">` tem o nome como atributo **e** como
     * filho `<name>` (o formato trocou ao longo dos anos); lê os dois jeitos.
     */
    internal fun parseApps(xml: String): List<App> = runCatching {
        val doc = parseXml(xml)
        val nodes = doc.getElementsByTagName("app")
        (0 until nodes.length).mapNotNull { i ->
            val el = nodes.item(i) as? Element ?: return@mapNotNull null
            val id = el.getAttribute("id").ifBlank { null } ?: text(el, "id") ?: return@mapNotNull null
            val name = el.getAttribute("name").ifBlank { null } ?: text(el, "name") ?: return@mapNotNull null
            App(id, name)
        }
    }.getOrDefault(emptyList())

    /**
     * `/query/media-player` → estado. O Roku devolve `state` (`play`/`pause`/`buffer`/
     * `idle`) e tempos em **segundos** — aqui vira ms, que é a unidade do app inteiro.
     * Campos ausentes viram 0/null, não derrubam o painel.
     */
    internal fun parseMediaPlayer(xml: String): PlayerState? = runCatching {
        val doc = parseXml(xml)
        val root = doc.documentElement ?: return null
        val state = text(root, "state") ?: return null
        PlayerState(
            state = state,
            positionMs = (text(root, "position")?.toLongOrNull() ?: 0L) * 1000,
            durationMs = (text(root, "duration")?.toLongOrNull() ?: 0L) * 1000,
        )
    }.getOrNull()

    // ── XML util (mesmo motivo do FeedParser: javax.xml funciona em teste e aparelho) ──

    private fun parseXml(xml: String): Document {
        val f = DocumentBuilderFactory.newInstance()
        runCatching { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { f.isNamespaceAware = false }
        return f.newDocumentBuilder().parse(xml.byteInputStream())
    }

    /** Primeiro filho com o nome pedido, ignorando namespace. */
    private fun text(root: Element, name: String): String? {
        val kids = root.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n is Element && local(n) == name) return n.textContent?.trim()?.ifBlank { null }
        }
        return null
    }

    private fun local(node: Node): String = node.localName ?: node.nodeName.substringAfter(':')
}
