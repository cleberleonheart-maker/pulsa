package com.pulsa.player.media.tv

import java.util.Locale

/**
 * A2 — a árvore de conteúdo que o Roku Media Player enxerga, pura (sem Android, sem rede).
 *
 * **Por que pura.** A árvore é só transformação de dados: lista de faixas vira pastas
 * "Músicas"/"Vídeos", itens viram XML DIDL-Lite com paginação. Todo o risco aqui é de
 * *texto malformado* (título com `&` partindo o XML do servidor, índice de página errado
 * fazendo a TV pular música) — defeitos que só aparecem na TV, longe do debugger. Cobrir
 * com teste JVM é a única forma honesta de travar isso antes do usuário ver.
 *
 * **Forma da árvore.** Dois níveis e nada mais: raiz (`0`) → `music`/`videos` → itens.
 * Pastas por álbum/artista viriam depois; a Roku Media Player navega bem listas longas com
 * paginação, então lista única por tipo já resolve o "tocar na TV" sem inventar UI de árvore.
 *
 * **IDs.** `music`/`videos` para pastas; para item, o mesmo id lógico da faixa (`m<id>`
 * música, `v<id>` vídeo) — estável entre reconstruções da árvore, e é o que o `/f/<id>`
 * do [DlnaServer] devolve. Sem id estável o seek da TV (que refaz Browse por página) perde
 * o lugar no meio da reprodução.
 */
object ContentTree {

    /** Id da raiz, convenção UPnP: todo servidor começa em "0". */
    const val ROOT = "0"
    const val MUSIC = "music"
    const val VIDEOS = "videos"
    const val ARTISTS = "artists"
    const val ALBUMS = "albums"
    const val PLAYLISTS = "playlists"
    const val PODCASTS = "podcasts"

    /** Música acessível tanto por lista plana quanto por artista/álbum — mesma faixa, dois caminhos. */
    const val MUSIC_ALL = "music_all"

    /**
     * Faixa de entrada: o que a árvore precisa saber, e nada mais. O `fileId` é o que o
     * servidor HTTP devolve em `/f/<id>`; `url` não existe aqui de propósito — a URL final
     * depende de IP/porta do servidor, que só existem quando a sessão DLNA sobe.
     */
    data class Entry(
        val fileId: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val mime: String,
        val size: Long,
        val durationMs: Long,
        val isVideo: Boolean,
    )

    /** Nó pronto para serialização: pasta (com childCount) ou item (com metadados). */
    sealed class Node {
        abstract val id: String
        abstract val parentId: String
        abstract val title: String

        data class Container(
            override val id: String,
            override val parentId: String,
            override val title: String,
            val childCount: Int,
        ) : Node()

        data class Item(
            override val id: String,
            override val parentId: String,
            override val title: String,
            val entry: Entry,
        ) : Node()
    }

    /**
     * Monta a árvore a partir das faixas. Música e vídeo separados por [Entry.isVideo];
     * ordenação por título (case-insensitive, acento vira código — ok: a TV só precisa de
     * ordem estável, não de colação perfeita).
     */
    fun build(entries: List<Entry>): Map<String, Node> {
        val music = entries.filter { !it.isVideo }.sortedWith(titleOrder)
        val videos = entries.filter { it.isVideo }.sortedWith(titleOrder)
        val nodes = linkedMapOf<String, Node>()
        nodes[ROOT] = Node.Container(ROOT, "-1", "Pulsa", 2)
        nodes[MUSIC] = Node.Container(MUSIC, ROOT, "Músicas", music.size)
        nodes[VIDEOS] = Node.Container(VIDEOS, ROOT, "Vídeos", videos.size)
        music.forEach { nodes[it.fileId] = Node.Item(it.fileId, MUSIC, it.title, it) }
        videos.forEach { nodes[it.fileId] = Node.Item(it.fileId, VIDEOS, it.title, it) }
        return nodes
    }

    /** Playlist para a TV: um nome e os itens que ela contém (mesmos `fileId` das [Entry]). */
    data class Group(val id: String, val title: String, val fileIds: List<String>)

    /**
     * A2b — árvore hierárquica para navegar na TV com o controle remoto.
     *
     * **Por que hierarquia.** A lista plana de músicas funciona no papel, mas navegar por
     * 3 mil faixas com o D-pad do controle da Roku é inviável: o usuário rola minutos até
     * achar o que quer. As pastas dão o mesmo atalho que ele já usa no celular —
     * **Artistas → Álbuns → faixas**, mais Playlists e Podcasts por feed.
     *
     * **Forma.** Raiz → (Músicas | Artistas | Álbuns | Playlists | Podcasts | Vídeos).
     * "Músicas" continua existindo como lista plana (quem prefere rolar tudo), mas as
     * pastas são o caminho curto.
     *
     * **Chaves de pasta não colidem com ids de faixa.** Artistas/álbuns recebem prefixo
     * (`a:`/`b:`/`p:`/`c:`) e o nome entra codificado, então `a:The Beatles` nunca casa com
     * um `fileId` `m123`. O id da pasta precisa ser **estável** entre reconstruções — a TV
     * refaz Browse por página durante a reprodução e um id que muda perde o lugar.
     */
    fun buildTree(
        entries: List<Entry>,
        playlists: List<Group> = emptyList(),
        podcasts: List<Group> = emptyList(),
    ): Map<String, Node> {
        val music = entries.filter { !it.isVideo }.sortedWith(titleOrder)
        val videos = entries.filter { it.isVideo }.sortedWith(titleOrder)
        val nodes = linkedMapOf<String, Node>()

        // A mesma faixa aparece em vários caminhos (Músicas, Artistas, Álbuns, Playlists).
        // Um nó tem **um** pai só, então cada aparição recebe um id distinto (o primeiro
        // fica sendo o `fileId` puro; os demais ganham sufixo `#n`). O `res` sempre aponta
        // para `/f/<fileId>` — o id do *arquivo*, que não muda — para o servidor achar os
        // bytes e o mime, sem depender do id do nó.
        val used = HashMap<String, Int>()

        fun addItem(entry: Entry, parentId: String) {
            val n = used.getOrDefault(entry.fileId, 0)
            used[entry.fileId] = n + 1
            val nodeId = if (n == 0) entry.fileId else "${entry.fileId}#$n"
            nodes[nodeId] = Node.Item(nodeId, parentId, entry.title, entry)
        }

        // Pastas de topo só aparecem quando têm conteúdo — pasta vazia na TV confunde.
        val top = ArrayList<String>()
        if (music.isNotEmpty()) {
            top += MUSIC
            top += ARTISTS
            top += ALBUMS
        }
        if (playlists.isNotEmpty()) top += PLAYLISTS
        if (podcasts.isNotEmpty()) top += PODCASTS
        if (videos.isNotEmpty()) top += VIDEOS

        nodes[ROOT] = Node.Container(ROOT, "-1", "Pulsa", top.size)

        // Lista plana de músicas.
        if (music.isNotEmpty()) {
            nodes[MUSIC] = Node.Container(MUSIC, ROOT, "Músicas", music.size)
            music.forEach { addItem(it, MUSIC) }
        }

        // Artistas → Álbuns → faixas.
        if (music.isNotEmpty()) {
            val byArtist = music.groupBy { it.artist?.ifBlank { null } ?: DESCONHECIDO }
                .toSortedMap(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            nodes[ARTISTS] = Node.Container(ARTISTS, ROOT, "Artistas", byArtist.size)
            byArtist.forEach { (artist, songs) ->
                val artistId = folderId("a", artist)
                val albums = songs.groupBy { it.album?.ifBlank { null } ?: DESCONHECIDO }
                    .toSortedMap(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                nodes[artistId] = Node.Container(artistId, ARTISTS, artist, albums.size)
                albums.forEach { (album, tracks) ->
                    val albumId = folderId("b", "$artist\u0000$album")
                    val ordered = tracks.sortedWith(titleOrder)
                    nodes[albumId] = Node.Container(albumId, artistId, album, ordered.size)
                    ordered.forEach { addItem(it, albumId) }
                }
            }
        }

        // Álbuns (lista plana, sem passar pelo artista).
        if (music.isNotEmpty()) {
            val byAlbum = music.groupBy { it.album?.ifBlank { null } ?: DESCONHECIDO }
                .toSortedMap(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            nodes[ALBUMS] = Node.Container(ALBUMS, ROOT, "Álbuns", byAlbum.size)
            byAlbum.forEach { (album, tracks) ->
                // Pasta própria da lista plana (id com sufixo "flat"): o mesmo álbum já tem
                // pasta na árvore de Artistas, e um nó pertence a um pai só.
                val flatId = folderId("f", album)
                val ordered = tracks.sortedWith(titleOrder)
                nodes[flatId] = Node.Container(flatId, ALBUMS, album, ordered.size)
                ordered.forEach { addItem(it, flatId) }
            }
        }

        // Playlists: pasta "Playlists" → pasta por playlist → faixas.
        if (playlists.isNotEmpty()) {
            nodes[PLAYLISTS] = Node.Container(PLAYLISTS, ROOT, "Playlists", playlists.size)
            val byId = music.associateBy { it.fileId }
            playlists.forEachIndexed { i, pl ->
                val items = pl.fileIds.mapNotNull { byId[it] }
                if (items.isEmpty()) return@forEachIndexed
                val plId = folderId("p", "${pl.title}\u0000$i")
                nodes[plId] = Node.Container(plId, PLAYLISTS, pl.title, items.size)
                items.forEach { addItem(it, plId) }
            }
        }

        // Podcasts: pasta "Podcasts" → pasta por feed → episódios.
        if (podcasts.isNotEmpty()) {
            nodes[PODCASTS] = Node.Container(PODCASTS, ROOT, "Podcasts", podcasts.size)
            val byId = entries.associateBy { it.fileId }
            podcasts.forEachIndexed { i, feed ->
                val items = feed.fileIds.mapNotNull { byId[it] }
                if (items.isEmpty()) return@forEachIndexed
                val feedId = folderId("c", "${feed.title}\u0000$i")
                nodes[feedId] = Node.Container(feedId, PODCASTS, feed.title, items.size)
                items.forEach { addItem(it, feedId) }
            }
        }

        // Vídeos (lista plana — a Roku Media Player agrupa bem vídeo por si).
        if (videos.isNotEmpty()) {
            nodes[VIDEOS] = Node.Container(VIDEOS, ROOT, "Vídeos", videos.size)
            videos.forEach { addItem(it, VIDEOS) }
        }

        return nodes
    }

    /** Nome para faixa/álbum sem artista/álbum preenchido — evita pasta com nome vazio. */
    private const val DESCONHECIDO = "Desconhecido"

    /**
     * Id de pasta estável e sem colisão. Prefixo por tipo + nome codificado em hex para
     * não carregar `&`/aspas/espaços para dentro de um id de nó (o id vira atributo XML e
     * chave de mapa, não pode ter caractere que confunda nem o parser nem o mapa).
     */
    private fun folderId(prefix: String, name: String): String {
        val bytes = name.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8)
        val hex = buildString(bytes.size * 2) {
            for (b in bytes) {
                append(HEX[(b.toInt() shr 4) and 0xF])
                append(HEX[b.toInt() and 0xF])
            }
        }
        return "$prefix:$hex"
    }

    private const val HEX = "0123456789abcdef"

    private val titleOrder = compareBy<Entry> { it.title.lowercase(Locale.ROOT) }

    /**
     * Filhos de uma pasta, paginados. `start`/`count` vêm do Browse SOAP: a TV pede
     * página a página (tipicamente 50 em 50) e espera `totalMatches` honesto senão a barra
     * de rolagem da TV some no meio da lista.
     */
    fun children(
        nodes: Map<String, Node>,
        parentId: String,
        start: Int,
        count: Int,
    ): Pair<List<Node>, Int> {
        val all = nodes.values.filter { it.parentId == parentId }
        val total = all.size
        val from = start.coerceIn(0, total)
        val to = if (count <= 0) total else (from + count).coerceAtMost(total)
        return all.subList(from, to) to total
    }

    /**
     * DIDL de um nó único (`BrowseMetadata`): a TV pede isto ao abrir o detalhe de uma
     * faixa ou ao confirmar a pasta raiz antes de listar. Sem este caminho o metadata
     * virava Browse de filhos do próprio item (vazio), e a TV mostrava "arquivo não
     * encontrado" no play.
     */
    fun didlSingle(node: Node, baseUrl: String): String = didlList(listOf(node), baseUrl)

    private fun didlList(page: List<Node>, baseUrl: String): String {
        val sb = StringBuilder()
        sb.append(
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">",
        )
        page.forEach { node -> appendNode(sb, node, baseUrl) }
        sb.append("</DIDL-Lite>")
        return sb.toString()
    }

    /**
     * DIDL-Lite de uma página. `baseUrl` monta o `res` (`http://ip:porta/f/<id>`);
     * `DLNA.ORG_OP=01` avisa a TV que aceitamos `Range` (seek), que é o que separa
     * "funciona" de "trava no minuto 3".
     */
    fun didl(
        nodes: Map<String, Node>,
        parentId: String,
        start: Int,
        count: Int,
        baseUrl: String,
    ): String {
        val (page, _) = children(nodes, parentId, start, count)
        return didlList(page, baseUrl)
    }

    private fun appendNode(sb: StringBuilder, node: Node, baseUrl: String) {
        when (node) {
                is Node.Container -> {
                    sb.append("<container id=\"").append(esc(node.id))
                        .append("\" parentID=\"").append(esc(node.parentId))
                        .append("\" restricted=\"1\" childCount=\"").append(node.childCount)
                        .append("\"><dc:title>").append(esc(node.title))
                        .append("</dc:title><upnp:class>object.container.storageFolder")
                        .append("</upnp:class></container>")
                }
                is Node.Item -> {
                    val e = node.entry
                    sb.append("<item id=\"").append(esc(node.id))
                        .append("\" parentID=\"").append(esc(node.parentId))
                        .append("\" restricted=\"1\">")
                        .append("<dc:title>").append(esc(node.title)).append("</dc:title>")
                    if (!e.artist.isNullOrBlank()) {
                        sb.append("<dc:creator>").append(esc(e.artist)).append("</dc:creator>")
                    }
                    if (!e.album.isNullOrBlank()) {
                        sb.append("<upnp:album>").append(esc(e.album)).append("</upnp:album>")
                    }
                    sb.append("<upnp:class>")
                        .append(if (e.isVideo) "object.item.videoItem" else "object.item.audioItem.musicTrack")
                        .append("</upnp:class>")
                    sb.append("<res protocolInfo=\"http-get:*:").append(e.mime)
                        .append(":DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\"")
                    if (e.size > 0) sb.append(" size=\"").append(e.size).append("\"")
                    if (e.durationMs > 0) {
                        sb.append(" duration=\"").append(hms(e.durationMs)).append("\"")
                    }
                    sb.append(">").append(esc("$baseUrl/f/${e.fileId}")).append("</res></item>")
                }
            }
    }

    /**
     * Escapa os cinco caracteres que partem XML. Título com `&` ("Rock & Roll") sem isto
     * vira Browse com resposta malformada e a TV mostra a pasta vazia — o defeito mais
     * comum de servidor DLNA caseiro.
     */
    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    /** `123456` → `0:00:12`; formato que a TV entende (H:MM:SS). */
    fun hms(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    }

    /**
     * MIME por extensão. Só extensões que a Roku Media Player toca de verdade; DTS/AVI
     * ficam de fora de propósito — listar o que a TV não toca é pior que não listar, o
     * usuário escolhe a faixa, dá play e vê erro sem explicação.
     */
    fun mimeFor(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/ogg"
            "aac" -> "audio/aac"
            "wav", "wave" -> "audio/wav"
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            else -> null
        }
    }
}
