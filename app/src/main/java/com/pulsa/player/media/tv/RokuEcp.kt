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

    private val NOME_RMP = listOf(
        "roku media player",
        "media player",
        "player de mídia",
        "reprodutor de mídia",
    )

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

    /**
     * `/query/apps` — lista de canais instalados, para achar o Roku Media Player pelo nome.
     */
    fun apps(host: String): List<App> = appsDetalhe(host).lista

    private data class AppsDetalhe(val lista: List<App>, val detalhe: String)

    /**
     * `/query/apps` com o porquê do resultado para o Diagnóstico. Nesta TV o endpoint não
     * responde nem em 8s — e "sem resposta" podia ser 403, timeout ou XML ruim, três
     * diagnósticos que pedem remédios diferentes. HTTP 4xx não repete (não vira 200);
     * timeout/XML ilegível repete uma vez (ryma de rede é comum).
     */
    private fun appsDetalhe(host: String): AppsDetalhe {
        var ultimo = "sem tentativa"
        repeat(2) {
            val h = http("GET", "http://$host:$PORT/query/apps", 8000, 8000)
            when {
                h.erro != null -> ultimo = "sem resposta (${h.erro})"
                h.code != 200 -> return AppsDetalhe(emptyList(), "HTTP ${h.code}")
                else -> {
                    val lista = h.corpo?.let { parseApps(it) }.orEmpty()
                    if (lista.isNotEmpty()) {
                        return AppsDetalhe(lista, "HTTP 200, ${lista.size} canais")
                    }
                    ultimo = "HTTP 200 mas XML ilegível"
                }
            }
        }
        return AppsDetalhe(emptyList(), ultimo)
    }

    /** O app é o Roku Media Player? (nome vindo do ECP — id sozinho já enganou a gente). */
    fun ehMediaPlayer(app: App): Boolean = NOME_RMP.any { app.name.lowercase().contains(it) }

    /**
     * Lança um canal pelo id ([appId]) via POST /launch, com fallback GET (firmware
     * antigo responde só ao GET do mesmo endpoint).
     *
     * **Por que só o Media Player e não item específico.** O Roku não tem deep-link para
     * item DLNA — o único `launch` que aceita argumento é de app com query string própria
     * (Netflix/YouTube). Para mídia de servidor, o caminho real é: lança o Media Player, ele
     * mesmo descobre os servidores DLNA da rede (inclusive o nosso) e o usuário escolhe
     * "Pulsa" no controle. O app só empurra o canal; a navegação é da TV.
     *
     * Devolve código HTTP + motivo do erro (quando -1): "404" (canal errado), "timeout"
     * (rede) e "200" são diagnósticos diferentes — Boolean empastelaria tudo.
     */
    fun launch(host: String, appId: String): Int = tentarLaunch(host, appId).first

    private fun tentarLaunch(host: String, appId: String): Pair<Int, String?> {
        val url = "http://$host:$PORT/launch/$appId"
        val post = http("POST", url, 3000, 3000)
        if (post.code != -1) return post.code to post.erro
        // Só tenta o GET quando o POST nem respondeu; resposta HTTP (mesmo 404) do POST
        // já é o veredito — dois códigos concorrentes no log só confundem o diagnóstico.
        val get = http("GET", url, 3000, 3000)
        return get.code to get.erro
    }

    /**
     * Resultado de [launchRmp]. [idRmp] é o id confirmado do Roku Media Player, guardado
     * pela UI para o próximo launch — tem TV em que `/query/apps` não responde e este id
     * "aprendido" do active-app é o único caminho automático.
     */
    data class LaunchInfo(
        val aberto: Boolean,
        val log: List<String>,
        val idRmp: String? = null,
        /** TV em reprodução e o launch automático NÃO interrompeu (o que a família estava vendo continua na tela). */
        val emUso: Boolean = false,
    )

    /**
     * Lança o Roku Media Player e **confere** que abriu. Caminhos, nesta ordem:
     * 1. já está aberto? (é também assim que o id é "aprendido" do active-app);
     * 2. id salvo de uma vez em que o RMP apareceu no active-app (dispensa /query/apps);
     * 3. id vindo de `/query/apps` por nome.
     *
     * **Por que sem chute de id.** A doc oficial da Roku usa o id 12 como exemplo … do
     * Netflix: o chute "12" abriu Netflix na TV do usuário e o `active-app` com id 12
     * "confirmava" o erro. Sem lista e sem id salvo, o caminho honesto é o manual ("abra
     * o player na mão"), que o diálogo do painel já ensina.
     *
     * **Por que conferir.** O POST /launch devolvendo 200 só diz que o Roku aceitou o
     * pedido — em alguns firmwares o canal não existe/não abre e o 200 vem mesmo assim.
     * Sem a checagem por `/query/active-app` o app marcava "conectado" com a TV no menu
     * e o usuário via o botão da TV "não fazer nada".
     */
    /**
     * O que a TV mostra é reprodução em curso (não a tela inicial)? [state] vem de
     * `/query/media-player` — `play`/`pause`/`buffer` significam conteúdo na tela.
     * Endpoint ausente/403 NÃO conta como "em uso": TV que esconde esse endpoint não
     * poderia conectar nunca; o log registra o estado para o diagnóstico decidir.
     */
    internal fun estadoOcupaTela(state: String): Boolean =
        state.trim().lowercase() in setOf("play", "pause", "buffer")

    /** Único lugar em que a conexão pode abrir o player sozinha: menu do Roku / "home". */
    private val TELA_INICIAL = setOf("roku dynamic menu", "home", "roku")

    /**
     * Há algo na TV que a conexão automática não pode interromper: reprodução em curso
     * (play/pause/buffer) **ou** qualquer app aberto que não seja o próprio Media Player
     * nem a tela inicial (ex.: YouTube da família). O estado do media-player sozinho não
     * basta — nesta TV ele pode 403 igual ao /query/apps, e aí só o nome do app ativo
     * evita derrubar o que está na tela.
     */
    internal fun tvEmUso(ativo: App?, estado: String?): Boolean {
        // O próprio Media Player nunca conta como "em uso" — ele é o alvo do launch.
        if (ativo != null && ehMediaPlayer(ativo)) return false
        if (estado != null && estadoOcupaTela(estado)) return true
        if (ativo == null) return false
        return ativo.name.trim().lowercase() !in TELA_INICIAL
    }

    fun launchRmp(
        host: String,
        idSalvo: String? = null,
        podeInterromper: Boolean = false,
    ): LaunchInfo {
        val log = ArrayList<String>()
        val (ativo, status) = activeAppDetalhe(host)
        log += if (ativo != null) "active-app: ${ativo.name} (id ${ativo.id})"
        else "active-app: $status"
        if (ativo != null && ehMediaPlayer(ativo)) {
            log += "já estava aberto"
            return LaunchInfo(true, log, ativo.id)
        }
        // ECP mudo (sem resposta/4xx) não adianta tentar lista: só repetiria o -1 depois
        // de 16s de /query/apps — e o log já diz o porquê.
        if (ativo == null && status != null && !status.startsWith("200")) {
            return LaunchInfo(false, log)
        }
        // Nunca trocar de canal sozinho: família assistindo (YouTube aberto) ou algo
        // tocando → conexão silenciosa; quem decide a troca é o botão explícito do painel.
        val estado = runCatching { mediaPlayer(host) }.getOrNull()
        log += "estado: ${estado?.state?.takeIf { it.isNotBlank() } ?: "sem resposta"}"
        if (tvEmUso(ativo, estado?.state)) {
            val motivo = estado?.state?.takeIf { estadoOcupaTela(it) } ?: ativo?.name
            if (!podeInterromper) {
                log += "TV em uso ($motivo) — não interrompi"
                return LaunchInfo(false, log, null, emUso = true)
            }
            log += "TV em uso ($motivo) — trocando a pedido"
        }
        idSalvo?.let { salvo ->
            log += "id salvo: $salvo"
            val (code, erro) = tentarLaunch(host, salvo)
            log += "launch/$salvo → $code" + (erro?.let { " ($it)" } ?: "")
            if (code in 200..299 && confirmarAtivo(host, App(salvo, ""), log)) {
                return LaunchInfo(true, log, salvo)
            }
        }
        val res = appsDetalhe(host)
        log += "apps: ${res.detalhe}"
        val alvos = res.lista.filter { ehMediaPlayer(it) }
        if (alvos.isEmpty()) {
            if (res.lista.isNotEmpty()) log += "Roku Media Player fora da lista — abra na TV na mão"
            return LaunchInfo(false, log)
        }
        for (alvo in alvos) {
            log += "por nome: ${alvo.name} (id ${alvo.id})"
            val (code, erro) = tentarLaunch(host, alvo.id)
            log += "launch/${alvo.id} → $code" + (erro?.let { " ($it)" } ?: "")
            if (code !in 200..299) continue
            val ok = confirmarAtivo(host, alvo, log)
            return LaunchInfo(ok, log, if (ok) alvo.id else null)
        }
        return LaunchInfo(false, log)
    }

    /**
     * Espera até ~1,2s o app trocar de foreground (o launch é assíncrono no Roku).
     * Confirma por id do canal alvo **ou** por nome de Media Player no active-app.
     */
    private fun confirmarAtivo(host: String, esperado: App, log: MutableList<String>): Boolean {
        repeat(3) { i ->
            val atual = activeApp(host)
            val ehRmp = atual != null && (atual.id == esperado.id || ehMediaPlayer(atual))
            when {
                atual == null && i == 2 -> log += "active-app: sem resposta"
                ehRmp -> {
                    log += "active-app: ${atual!!.name} (id ${atual.id})"
                    return true
                }
                atual != null && i == 2 -> log += "active-app: ${atual.name} (id ${atual.id})"
            }
            Thread.sleep(400)
        }
        return false
    }

    /** `/query/active-app` — app em primeiro plano (o que prova que o launch pegou). */
    fun activeApp(host: String): App? = activeAppDetalhe(host).first

    /** Como [activeApp] + o porquê quando vier null: `"HTTP 403"`, `"sem resposta (…)"`. */
    private fun activeAppDetalhe(host: String): Pair<App?, String?> {
        val h = http("GET", "http://$host:$PORT/query/active-app", 3000, 3000)
        return when {
            h.erro != null -> null to "sem resposta (${h.erro})"
            h.code != 200 -> null to "HTTP ${h.code}"
            else -> {
                val app = h.corpo?.let { parseActiveApp(it) }
                app to (if (app == null) "200, sem id (tela inicial)" else null)
            }
        }
    }

    /**
     * Interfaces IPv4 ativas (`wlan0=192.168.1.5`, `rmnet0=…`) — o Diagnóstico mostra
     * isto para separar "Wi-Fi desligado/celular" de "rede isolada" num piscar de olhos.
     */
    fun redes(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>()
                    .map { "${iface.name}=${it.hostAddress}" }
            }
    }.getOrDefault(emptyList())

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
        // Confirma cada host com o device-info (tira da lista quem não é Roku de verdade
        // e traz o nome amigável para o diálogo de pareamento). Se o SSDP deu hosts que o
        // device-info não quis, a varredura da sub-rede é o plano B — antes o teste
        // "hosts.isEmpty()" era feito ANTES da confirmação e o pareamento ficava vazio.
        var achados = hosts.mapNotNull { host -> deviceInfo(host) }
        if (achados.isEmpty()) achados = discoverScan().mapNotNull { host -> deviceInfo(host) }
        return achados
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

    /** Resposta HTTP crua: código, corpo (só em 2xx) e motivo quando não houve resposta. */
    private data class Http(val code: Int, val corpo: String?, val erro: String?)

    /**
     * GET/POST com código + corpo + motivo de falha em uma chamada — o Diagnóstico precisa
     * separar "HTTP 403" de "timeout" de "corpo veio" (três remédios diferentes), e o
     * [appsDetalhe]/[activeAppDetalhe] comem os três campos.
     */
    private fun http(method: String, url: String, connectTimeout: Int, readTimeout: Int): Http = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeout
        conn.readTimeout = readTimeout
        conn.requestMethod = method
        conn.doInput = true
        val code = conn.responseCode
        if (code in 200..299) {
            // Corpo lido aqui mesmo: Content-Length é bytes e a leitura pode estourar o
            // readTimeout — a exceção cai no runCatching de fora e vira "sem resposta".
            Http(code, readAll(conn.inputStream), null)
        } else {
            runCatching { conn.errorStream?.close() }
            Http(code, null, null)
        }
    }.getOrElse { e ->
        Http(-1, null, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }

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
     * `LOCATION` do cabeçalho SSDP → host **sem porta** do ECP. O header vem como
     * `LOCATION: http://192.168.0.15:8060/query/device-info`; a porta sai daqui porque
     * todo URL do ECP é montado como `http://host:8060/…` — manter ":8060" na autoridade
     * virava `http://ip:8060:8060/…` e o `device-info` (e tudo mais) morria em
     * MalformedURLException, derrubando o pareamento por SSDP inteiro.
     */
    internal fun parseSsdpLocation(header: String): String? {
        val line = header.lineSequence().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?: return null
        val url = line.substringAfter(':').trim()
        return url.removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore('?')
            .removeSuffix(":$PORT")
            .ifBlank { null }
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

    /**
     * `/query/active-app` → app em primeiro plano. O formato do ECP trocou entre
     * firmwares: o oficial da doc é `<app id="12">Netflix</app>` (nome como **texto
     * direto**), mas há quem envie `<name>` como filho — lê os dois; sem nome (nunca
     * confirmar só pelo id: id 12 + nome ausente "confirmou" o Netflix).
     * Algumas TVs ainda embrulham em `<active-app><app…>` — por isso a procura por
     * elemento "app" quando a raiz não é ele.
     */
    internal fun parseActiveApp(xml: String): App? = runCatching {
        val doc = parseXml(xml)
        val root = doc.documentElement ?: return null
        val el = if (local(root) == "app") {
            root
        } else {
            val nodes = doc.getElementsByTagName("app")
            if (nodes.length == 0) null else nodes.item(0) as? Element
        } ?: return null
        val id = el.getAttribute("id").ifBlank { null } ?: text(el, "id") ?: return null
        App(id, text(el, "name") ?: textoDireto(el) ?: id)
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

    /** Texto imediato do elemento (`<app id="12">Netflix</app>` → "Netflix"). */
    private fun textoDireto(el: Element): String? {
        val sb = StringBuilder()
        val kids = el.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) {
                sb.append(n.nodeValue)
            }
        }
        return sb.toString().trim().ifBlank { null }
    }

    private fun local(node: Node): String = node.localName ?: node.nodeName.substringAfter(':')
}
