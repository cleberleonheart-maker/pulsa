package com.pulsa.player.podcast

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Leitor de feed RSS/Atom.
 *
 * **Por que `javax.xml` e não `XmlPullParser`.** O `XmlPullParser` do Android é stub no teste
 * unitário JVM (mesmo problema do `org.json`), então um parser escrito nele não tem teste
 * nenhum sem aparelho. O `javax.xml` é a mesma implementação nas duas pontas: dá para cobrir
 * aqui os casos que quebram de verdade, que são todos de feed malformado.
 *
 * **Por que não usar biblioteca de podcast pronta.** As que resolvem isso são pesadas
 * (Room + Entity + coroutines + serialização) e trazem um modelo de dados próprio, que teria
 * de ser achatado para entrar no `Playback` e no `PlaylistDb` que já existem. O feed é XML
 * plano; ler XML não é o trabalho difícil.
 */
object FeedParser {

    /**
     * Múltiplos feeds por requisição é o normal (YouTube e o Spotify mandam `<feed>` num
     * namespace de Atom), então um `Document` por raiz em vez de try/catch por item.
     */
    fun parse(bytes: ByteArray): Podcast? = parseOrError(bytes).getOrNull()

    /**
     * O mesmo parse, mas **com o motivo da falha** no `Result`.
     *
     * Existia só o [parse], que devolvia `null`, e a tela traduzia isso em "feed inválido" —
     * uma mensagem que não ajuda ninguém a consertar nada. As causas que aparecem no mundo
     * real são completamente diferentes entre si:
     *
     * - **não é feed**: a URL responde 200 com a página HTML do programa (o usuário colou o
     *   link do site, não o do feed) — o certo é dizer isso, senão ele tenta de novo com o
     *   mesmo endereço;
     * - **XML malformado**: `&` solto ou entidade não declarada na descrição. É comum, e o
     *   [relaxedBytes] resolve na maioria dos casos;
     * - **feed sem link nem título**: não é podcast, é RSS de outro assunto;
     * - **erro de verdade** (memória, IO), que precisa aparecer em vez de virar "inválido".
     */
    fun parseOrError(bytes: ByteArray): Result<Podcast> {
        val clean = stripBom(gunzip(bytes))
        if (looksLikeHtml(clean)) {
            return Result.failure(IllegalStateException("isso é uma página web, não um feed"))
        }
        // Colaram o link da busca, não o do feed: o iTunes responde JSON e o parser chega a
        // tentar ler como XML. Dizer "JSON" evita que a pessoa tente o mesmo endereço outra vez.
        if (looksLikeJson(clean)) {
            return Result.failure(IllegalStateException("isso é uma resposta JSON, não um feed"))
        }
        val first = runCatching { read(clean) }
        val second = first.exceptionOrNull()?.let { runCatching { read(relaxedBytes(clean)) } }
        val doc = first.getOrNull() ?: second?.getOrNull()
            ?: return Result.failure(
                IllegalStateException(
                    "XML malformado: " +
                        describe(second?.exceptionOrNull() ?: first.exceptionOrNull())
                )
            )
        return runCatching {
            // A raiz se procura por **localName**, e não por `getElementsByTagName`.
            //
            // `getElementsByTagName` casa pelo nome qualificado: num documento com namespace o
            // `<rdf:RDF>` tem `nodeName == "rdf:RDF"`, então buscar por `"RDF"` não acha nada —
            // e o `parse` caía no `<channel>` abaixo dele. O resultado era um podcast com
            // título e link corretos e **zero episódios**, sem erro nenhum: um feed RDF que o
            // app assinava e mostrava vazio para sempre.
            val tags = listOf("rss", "feed", "RDF", "channel")
            val candidates = elementsDeep(doc.documentElement)
            tags.asSequence()
                .flatMap { tag -> candidates.filter { it.localNameOrName().equals(tag, true) } }
                // `parseFeed` é quem decide o resto; se a primeira raiz não for de feed, tenta
                // a próxima em vez de devolver `null` num documento válido.
                .mapNotNull { parseFeed(it) }
                .firstOrNull()
        }.mapCatching { it ?: throw IllegalStateException("feed sem título nem link") }
    }

    /**
     * Corta o BOM e afasta o XML de um `&` solto.
     *
     * O `DocumentBuilder` é estrito e estoura em qualquer `&` que não seja uma das cinco
     * entidades — e descrição de episódio com "AT&T" ou "Rock&Roll" é a regra, não a exceção.
     * Trocar por `&amp;` deixa o XML válido sem perder o texto; o que já é entidade
     * (`&amp;`, `&#39;`) fica como está.
     */
    private fun relaxedBytes(bytes: ByteArray): ByteArray {
        val text = String(bytes, Charsets.UTF_8)
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '&') {
                val semi = text.indexOf(';', i + 1)
                val name = if (semi in (i + 1)..(i + 12)) text.substring(i + 1, semi) else null
                if (name != null && (name.startsWith("#") || name in KEEP_ENTITIES)) {
                    sb.append(text, i, semi + 1)
                    i = semi + 1
                    continue
                }
                sb.append("&amp;")
                i++
                continue
            }
            // Controle que o XML proíbe (0x00-0x08 e companhia) também derruba o parser.
            if (c.code < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private val KEEP_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

    /** O BOM na cabeça do arquivo faz o parser reclamar de conteúdo antes do `<?xml`. */
    private fun stripBom(bytes: ByteArray): ByteArray = if (
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()
    ) {
        bytes.copyOfRange(3, bytes.size)
    } else {
        bytes
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 512)).trimStart().lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    private fun looksLikeJson(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 512)).trimStart()
        if (!head.startsWith("{") && !head.startsWith("[")) return false
        val text = String(bytes, Charsets.UTF_8)
        // `{"` ou `[{` já basta para corpo de busca; `[{}` vazio não tem rota aqui de qualquer
        // jeito, e a resposta de verdade do iTunes sempre traz chaves.
        return text.contains("\":") || text.contains("\": ")
    }

    /**
     * Descompacta gzip se o corpo vier comprimido.
     *
     * O `HttpURLConnection` só descompacta quando **a gente** pediu `Accept-Encoding`, e não
     * pedimos — mas há servidores que mandam gzip do mesmo jeito (redirecionamento, CDN com
     * header forçado). Aí o parser lê bytes de página binária, falha nos dois caminhos e a
     * pessoa vê "XML malformado" sem entender por quê. Os dois bytes mágicos `1F 8B` decidem:
     * não sendo gzip, o corpo passa intacto.
     */
    private fun gunzip(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 0x1F.toByte() || bytes[1] != 0x8B.toByte()) return bytes
        return try {
            java.io.ByteArrayInputStream(bytes).use { input ->
                java.util.zip.GZIPInputStream(input).use { it.readBytes() }
            }
        } catch (e: Exception) {
            // Não é gzip depois de tudo — deixa o parser reclamar do corpo original, com a
            // mensagem real, em vez de "Not in GZIP format".
            bytes
        }
    }

    /** Uma linha só, porque a mensagem vai para a tela e para o log. */
    private fun describe(t: Throwable?): String {
        val msg = t?.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return when {
            msg.isEmpty() -> t?.javaClass?.simpleName ?: "erro desconhecido"
            msg.length > 120 -> msg.take(120) + "…"
            else -> msg
        }
    }

    /** Todos os elementos da árvore, em profundidade — para achar a raiz por nome local. */
    private fun elementsDeep(root: Element?): List<Element> {
        if (root == null) return emptyList()
        val out = mutableListOf<Element>()
        val stack = ArrayDeque<Element>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val el = stack.removeLast()
            out += el
            for (i in 0 until el.childNodes.length) {
                (el.childNodes.item(i) as? Element)?.let { stack.addLast(it) }
            }
        }
        return out
    }

    fun parse(input: InputStream): Podcast? = runCatching { parse(input.readBytes()) }.getOrNull()

    fun parseFeed(root: Element): Podcast? {
        // Atom traz título e itens no próprio <feed>; RSS põe em <channel> dentro de <rss>.
        // RDF (RSS 1.0) usa <channel> como irmão de <item>, sem <rss> — daí a quarta opção
        // no `parse`.
        val channel = child(root, "channel") ?: root
        val feedUrl = linkOf(channel) ?: return null
        val title = text(channel, "title")?.trim().orEmpty()
        if (title.isEmpty()) return null

        // No RSS e no Atom os itens estão **dentro** do `channel`; no RDF eles são irmãos dele,
        // pendurados no `rdf:RDF`. Buscar só dentro do `channel` fazia o feed RDF passar com
        // título e link certos e **zero episódios** — a tela mostrava um podcast vazio sem
        // nenhum aviso, porque o feed não estava errado.
        val items = childNodes(channel, "item") + childNodes(channel, "entry") +
            childNodes(root, "item") + childNodes(root, "entry")

        return Podcast(
            feedUrl = feedUrl,
            title = title,
            author = authorOf(channel),
            description = text(channel, "description")?.trim()
                ?: text(channel, "summary")?.trim().orEmpty(),
            artworkUrl = artworkOf(channel),
            episodes = episodesOf(items, feedUrl)
        )
    }

    // ---- itens ---------------------------------------------------------------------------

    private fun episodesOf(nodes: List<Element>, feedUrl: String): List<Episode> {
        val out = mutableListOf<Episode>()
        val seen = HashSet<String>()
        nodes.forEach { node ->
            val ep = parseItem(node, feedUrl) ?: return@forEach
            // Dedup dentro do próprio arquivo: alguns feeds repetem o item mais recente no
            // feed e no `channel`, e o Episode viraria duplicado na lista.
            if (seen.add(ep.guid)) out += ep
        }
        // Mais novo primeiro. O feed vem do mais antigo para o mais novo na maioria dos casos,
        // mas podcast é RSS e a ordem não é contrato.
        return out.sortedByDescending { it.publishedAt }
    }

    private fun parseItem(node: Element, feedUrl: String): Episode? {
        val enclosure = audioUrlOf(node) ?: return null
        // O guid primeiro; a URL como último recurso (item sem guid é feed ruim, mas existe).
        val guid = (text(node, "guid")?.trim()
            ?: text(node, "id")?.trim()
            ?: enclosure.url).takeIf { it.isNotBlank() } ?: enclosure.url
        val art = artworkOf(node)

        return Episode(
            guid = guid,
            title = (text(node, "title")?.trim().orEmpty()).ifEmpty { "Sem título" },
            description = descriptionOf(node),
            audioUrl = enclosure.url,
            durationSec = durationOf(node),
            publishedAt = publishedAtOf(node),
            artworkUrl = art,
            isVideo = enclosure.isVideo,
            videoPageUrl = if (enclosure.isVideo) enclosure.url else ""
        )
    }

    /** O `url` do enclosure e se ele é vídeo — o `type` é o que decide. */
    private data class Enclosure(val url: String, val isVideo: Boolean)

    /**
     * O enclosure é o caminho normal. O Atom usa `<link rel="enclosure">`, e alguns feeds
     * expõem a mídia só por `media:content` — sem os três, o episódio não tem áudio e o item
     * é descartado em vez de aparecer na lista como algo que não toca.
     */
    private fun audioUrlOf(node: Element): Enclosure? {
        elements(node, "enclosure").forEach { e ->
            val url = e.getAttribute("url").trim()
            if (url.isNotEmpty()) return Enclosure(url, isVideoType(e.getAttribute("type")))
        }
        elements(node, "link").forEach { e ->
            if (e.getAttribute("rel") == "enclosure") {
                e.getAttribute("href").trim().takeIf { it.isNotEmpty() }?.let {
                    return Enclosure(it, isVideoType(e.getAttribute("type")))
                }
            }
        }
        elements(node, "content").forEach { e ->
            val url = e.getAttribute("url").trim()
            if (url.isEmpty()) return@forEach
            val medium = e.getAttribute("medium")
            if (medium.equals("audio", true) || medium.equals("video", true)) {
                return Enclosure(url, medium.equals("video", true))
            }
        }
        // Item sem enclosure: alguns podcasts em vídeo publicam só o link da página. Guardar a
        // URL como se fosse áudio faria o ExoPlayer tentar tocar HTML — melhor manter o item e
        // marcar como vídeo para a busca resolver depois.
        val pageUrl = videoPageOf(node)
        if (pageUrl != null) return Enclosure(pageUrl, isVideo = true)
        return null
    }

    /**
     * `video/...` no `type` do enclosure.
     *
     * `startsWith("video")` e não `contains`: feed de vídeo costuma vir como
     * `video/mp4; codecs=...`, e o `type` de áudio como `audio/mpeg`. Um `contains("video")`
     * pegaria também `application/x-video-...` de contêiner que o ExoPlayer não abre.
     */
    private fun isVideoType(type: String): Boolean = type.trim().lowercase().startsWith("video")

    /** Link de vídeo declarado no item (`media:content`, `yt:videoId`, ou o `<link>` solto). */
    private fun videoPageOf(node: Element): String? {
        prefixed(node, "media", "content")?.getAttribute("url")?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        prefixed(node, "yt", "videoId")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return "https://www.youtube.com/watch?v=$it" }
        return null
    }

    /**
     * Duração em segundos.
     *
     * `<itunes:duration>` é `HH:MM:SS` ou `MM:SS` e pode ter meia hora (`1:02:03`); alguns
     * feeds colocam só o número de segundos. Os três são tratados, e o campo
     * `itunes:duration` ganha do `duration` do Atom, que é o que o Atom realmente padroniza.
     */
    private fun durationOf(node: Element): Long {
        val raw = prefixedText(node, "itunes", "duration")
            ?: prefixedText(node, "itunes", "durationShort")
            ?: text(node, "duration")?.trim()
        if (raw.isNullOrEmpty()) return 0L
        if (!raw.contains(':')) return raw.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        return raw.split(':').mapNotNull { it.trim().toLongOrNull() }
            .fold(0L) { acc, part -> acc * 60 + part }
    }

    /**
     * Data de publicação em epoch **ms**, normalizada para UTC.
     *
     * RSS usa RFC 822 (`Tue, 05 Oct 2026 09:00:00 GMT`), Atom usa ISO 8601. Os dois aparecem
     * na prática e o formato errado devolve `null` — daí a lista de tentativas em vez de um
     * formato só. OsMillisecondos explícitos do Atom (`2026-10-05T09:00:00.000Z`) quebram o
     * `SimpleDateFormat` sem milissegundos, então esse formato vem antes dos outros.
     */
    private fun publishedAtOf(node: Element): Long {
        val raw = (text(node, "pubDate")?.trim()
            ?: text(node, "published")?.trim()
            ?: text(node, "updated")?.trim())
            .orEmpty()
        if (raw.isEmpty()) return 0L
        return parseDate(raw) ?: 0L
    }

    private fun parseDate(raw: String): Long? {
        val iso = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (pattern in iso) {
            val ok = tryParse(raw, pattern, TimeZone.getTimeZone("UTC")) ?: continue
            return ok
        }
        // RFC 822 com nome de dia e de zona, que é o formato do RSS clássico.
        val rfc = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss",
            "dd MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm:ss zzz"
        )
        for (pattern in rfc) {
            tryParse(raw, pattern, TimeZone.getTimeZone("GMT"))?.let { return it }
        }
        return null
    }

    private fun tryParse(raw: String, pattern: String, zone: TimeZone): Long? = try {
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.parse(raw)?.time
    } catch (e: Exception) {
        null
    }

    // ---- cabeçalho -----------------------------------------------------------------------

    /**
     * O `<link>` do feed.
     *
     * RSS é texto (`<link>https://…</link>`), Atom é atributo (`<link href="…"/>`) e às vezes
     * vem como `rel="self"` ou `rel="alternate"`. Só `alternate` e o sem-`rel` valem: o
     * `self` é o endereço do próprio XML, que como endereço do feed faz o app guardar um
     * `.xml` e depois tentar tocar isso como áudio.
     */
    /**
     * O link do canal, na ordem em que ele serve para alguma coisa.
     *
     * O fallback para `rel="self"` não é teoria: o Megaphone — uma das maiores plataformas de
     * podcast do mundo — publica `<atom:link rel="self">` e **nenhum** `<link>` de texto. Com
     * a regra antiga (só `alternate` e link vazio) o feed era descartado como "feed inválido",
     * com título e episódios perfeitamente bons na resposta. E o `self` é justamente a melhor
     * identidade possível: é o mesmo endereço que a pessoa colou, que é o que a assinatura
     * deduplica.
     */
    private fun linkOf(channel: Element): String? {
        val links = elements(channel, "link")
        // `rel` vazio primeiro (RSS com link de texto), depois `alternate` (Atom), `self` e por
        // fim qualquer um — um feed com só `href` não pode ser descartado por isso.
        for (aceito in listOf("", "alternate", "self", null)) {
            for (e in links) {
                val rel = e.getAttribute("rel").trim()
                if (aceito != null && !rel.equals(aceito, true)) continue
                if (aceito == null && rel.isNotEmpty()) continue
                val href = e.getAttribute("href").trim()
                    .ifEmpty { e.textContent?.trim().orEmpty() }
                if (href.isNotEmpty()) return href
            }
        }
        return null
    }

    private fun authorOf(channel: Element): String =
        prefixedText(channel, "itunes", "author")
            ?: text(channel, "managingEditor")?.trim()
            ?: text(channel, "author")?.trim()
            ?: prefixedText(channel, "dc", "creator")
            ?: ""

    /**
     * Arte do cabeçalho, com fallback para a arte do primeiro episódio — muito podcast publica
     * a capa só no item, e sem isso a lista inteira ficaria com ícone genérico.
     */
    private fun artworkOf(channel: Element): String {
        elements(channel, "image").forEach { e ->
            val href = e.getAttribute("href").trim()
            if (href.isNotEmpty()) return href
            val nested = child(e, "url")?.textContent?.trim().orEmpty()
            if (nested.isNotEmpty()) return nested
        }
        prefixed(channel, "itunes", "image")?.getAttribute("href")?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    /** Descrição do episódio, sem HTML: a descrição de podcast é quase sempre um parágrafo de HTML. */
    private fun descriptionOf(node: Element): String {
        val raw = (text(node, "description")
            ?: text(node, "summary")
            ?: text(node, "encoded")
            ?: text(node, "content")).orEmpty()
        return stripHtml(raw).trim()
    }

    /**
     * Tira tag e desescapa entidade.
     *
     * O `Text` do Android é um singleton com um buffer estático compartilhado, então usá-lo
     * aqui devolveria o texto de outro componente — e a descrição do episódio apareceria na
     * tela errada. `Html.fromHtml` também exige `Context`/`Spanned`, o que quebraria o teste
     * de JVM. Uma expressão regular resolve o caso comum; o resto vira espaço em vez de sobrar
     * `<p>` na tela.
     */
    internal fun stripHtml(raw: String): String {
        if (raw.isEmpty()) return ""
        var s = TAG.replace(raw, " ")
        // A regex tem **um** grupo só: o nome da entidade. Ler `groupValues[2]` estourava
        // `IndexOutOfBounds` em toda descrição de podcast com HTML — ou seja, em quase
        // todos os episódios de qualquer podcast real.
        s = ENTITIES.replace(s) { m -> unescape(m.groupValues[1], m.value) }
        return s.replace("<", " ").replace(">", " ")
    }

    private val TAG = Regex("<[^>]*>")
    private val ENTITIES = Regex("&(#x?[0-9A-Fa-f]+|[a-zA-Z]+);")

    private fun unescape(what: String, fallback: String): String = when {
        what.startsWith("#x") || what.startsWith("#X") ->
            what.drop(2).toIntOrNull(16)?.let { codePointToString(it) } ?: fallback
        what.startsWith("#") -> what.drop(1).toIntOrNull()?.let { codePointToString(it) } ?: fallback
        else -> NAMED_ENTITIES[what] ?: fallback
    }

    private fun codePointToString(cp: Int): String = try {
        String(Character.toChars(cp))
    } catch (e: IllegalArgumentException) {
        ""
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "rsquo" to "'", "lsquo" to "'", "rdquo" to "\"", "ldquo" to "\"",
        "laquo" to "«", "raquo" to "»", "eacute" to "é", "egrave" to "è",
        "aacute" to "á", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
        "atilde" to "ã", "otilde" to "õ", "ccedil" to "ç", "ntilde" to "ñ"
    )

    // ---- DOM -----------------------------------------------------------------------------

    /**
     * XXE desligado.
     *
     * Feed é conteúdo **de terceiros**: é exatamente o formato que um feed malicioso usaria
     * para tentar ler `/data/data/com.pulsa.player/...` e subir o resultado para o servidor
     * dele. Sem estas quatro linhas, `parse` de um feed hostil é uma leitura de arquivo
     * arbitrário. O Android não traz `FEATURE_SECURE_PROCESSING` ligado por padrão, então
     * isto precisa ser explícito.
     */
    /**
     * Lê o XML. **Lança** — o `try/catch` que devolvia `null` engolia o motivo e o
     * `parseOrError` acabava informando "erro desconhecido", que é a frase que não diz nada.
     * Quem quer um `null` usa `runCatching` e escolhe o que reporta.
     */
    private fun read(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        f.setFeature("http://xml.org/sax/features/external-general-entities", false)
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        f.isExpandEntityReferences = false
        f.isXIncludeAware = false
        f.isNamespaceAware = true
        return f.newDocumentBuilder().parse(bytes.inputStream())
    }

    private fun child(parent: Node, name: String): Element? {
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.localNameOrName() == name) return n as Element
        }
        return null
    }

    private fun childNodes(parent: Node, name: String): List<Element> {
        val out = mutableListOf<Element>()
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.localNameOrName() == name) out += n as Element
        }
        return out
    }

    private fun elements(parent: Node, name: String): List<Element> = childNodes(parent, name)

    private fun text(parent: Node, name: String): String? {
        val e = child(parent, name) ?: return null
        return e.textContent
    }

    /**
     * Elemento com prefixo de namespace, casando por `localName`.
     *
     * Busca por `itunes:duration` com `getElementsByTagName` funciona num documento sem
     * namespace, mas **quebra** assim que o feed declara `xmlns:itunes` — que é quase todo
     * feed de podcast que importa. O `localName` vem certo nos dois casos.
     */
    /**
     * O texto de um elemento com prefixo de namespace, já aparado.
     *
     * Existe porque [prefixed] devolve o [Element] e o `?.trim()` no `Element` não compila:
     * `trim` é de `CharSequence`, e o elemento do DOM não é uma string. Sem este helper o
     * autor e a duração do episódio virariam vazios — que é o sintoma de "o podcast toca sem
     * nome e sem tempo".
     */
    private fun prefixedText(parent: Node, prefix: String, name: String): String? =
        prefixed(parent, prefix, name)?.textContent?.trim()

    private fun prefixed(parent: Node, prefix: String, name: String): Element? {
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType != Node.ELEMENT_NODE) continue
            val e = n as Element
            if (!e.localNameOrName().equals(name, true)) continue
            if (e.prefix == prefix || e.nodeName.startsWith("$prefix:")) return e
        }
        return null
    }

    /**
     * O nome local do nó, com o prefixo de namespace já removido.
     *
     * É extensão de [Node] e não de [Element] porque quem chama são os `kids.item(i)`, que o
     * DOM tipa como `Node`: com a extensão em `Element` o `n.localNameOrName()` não resolvia
     * e o `child`/`childNodes` inteiro deixava de compilar.
     */
    private fun Node.localNameOrName(): String {
        val el = this as? Element ?: return nodeName.substringAfter(':')
        return el.localName ?: el.nodeName.substringAfter(':')
    }
}
