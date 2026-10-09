package com.pulsa.player.playback

import com.pulsa.player.model.Song

/**
 * F2b — a chave de um item da fila, com o **tipo junto do id**.
 *
 * **Por que `song.id` não serve.** A fila é uma `List<Song>` e cada item tem um `id: Long`,
 * mas esse número não identifica a mídia sozinho: o MediaStore numera **cada coleção por
 * conta própria**, então o áudio de id 42 e o vídeo de id 42 são coisas diferentes. Pior,
 * rádio e stream usam id sintético (`url.hashCode()`), que pode bater com um id de verdade.
 * Um id_collide de vídeo e música no mesmo ponto do Android Auto trazia um `songMeta(id)`
 * do áudio para o vídeo, um `genreOf(id)` do áudio no EQ do vídeo, e um
 * `Library.songsByIds` que devolvia a **música** quando a fila pedia o **vídeo**.
 *
 * **Por que não guardar o `path`.** O `path` é o que a fila já carrega (`video:<id>`,
 * `stream:<url>`, `file://…`) e parece o caminho óbvio, mas ele não sobrevive à mudança de
 * usuário do MediaStore nem a app instalado em outro cartão — e o `id` sobrevive. A chave é
 * o par (tipo, id) porque é o que o MediaStore garante.
 *
 * **Por que uma string e não um objeto.** A fila vai para `SharedPreferences` em JSON, e o
 * formato já gravado (`queue_ids`, um `JSONArray` de números) precisa continuar legível por
 * uma versão anterior do app — downgrade, ou um `onCreate` antes de o usuário atualizar. Por
 * isso [encode] sai como `"a:42"` e [decode] aceita tanto esse formato quanto o número
 * solto, que ele entende como áudio.
 *
 * Não depende de Android, então a regra cabe num teste de JVM — como [ItemSelection].
 */
object QueueKey {

    /** Prefixo da chave de áudio. `a` (e não "musica") porque é o que ocupa menos no JSON salvo. */
    const val AUDIO_PREFIX = "a"

    /** Prefixo da chave de vídeo. O `Song.VIDEO_PREFIX` (`video:`) é do `path`, este é da fila. */
    const val VIDEO_PREFIX = "v"

    /**
     * Prefixo da chave de podcast (F3).
     *
     * É `e` (episode) e não `p` (podcast) porque a chave é do **episódio**: um podcast assinado
     * tem dezenas de episódios na fila ao mesmo tempo, e a chave precisa apontar para um deles.
     * Um `p` levaria à conclusão errada de que podcast é uma faixa só.
     */
    const val EPISODE_PREFIX = "e"

    /**
     * Fila universal — prefixo da chave de rádio. O padrão esteve em `encode` como `null` porque
     * a chave era só o id do MediaStore, e rádio não tem id nenhum. Aqui a identidade é o
     * endereço da estação em si: a mesma URL é a mesma estação, e é o que o servidor de rádio
     * entrega de estável. O `Song.RADIO_PREFIX` (`radio:`) é do `path`, este é da fila.
     */
    const val RADIO_PREFIX = "r"

    /**
     * Fila universal — prefixo da chave de stream (PeerTube/URL colada). Igual ao rádio: o que
     * identifica um stream é a URL, não um id — o hash que serve de `song.id` não é estável nem
     * único (pode colidir com um id de verdade, o mesmo problema que a chave tipada resolve).
     */
    const val STREAM_PREFIX = "s"

    /**
     * A chave de [song], ou `null` quando ela não é retomável.
     *
     * Áudio/vídeo/episódio têm id numérico no MediaStore/Room e a chave é `tipo:id`. Rádio e
     * stream não têm linha no MediaStore — a identidade deles é a URL, então a chave é
     * `r:<url>`/`s:<url>`. O texto de exibição vai à parte no `queue_extras` (ver [Settings]),
     * porque a URL não sabe o nome da estação.
     */
    fun encode(song: Song): String? = when {
        song.isRadio -> song.radioUrl?.takeIf { it.isNotBlank() }?.let { "$RADIO_PREFIX:$it" }
        song.isStream -> song.streamUrl?.takeIf { it.isNotBlank() }?.let { "$STREAM_PREFIX:$it" }
        // O podcast vem **antes** do vídeo de propósito: um episódio em vídeo tem `path` de
        // podcast e nunca `video:`, mas se a ordem invertesse e um dia o `videoId` aparecesse
        // nesse caminho, a chave seria `v:` e o restore.seekaria no MediaStore pelo id do
        // Room — abrindo um vídeo aleatório em vez do episódio.
        song.isPodcast -> song.podcastId?.let { "$EPISODE_PREFIX:$it" }
        song.isVideo -> song.videoId?.let { "$VIDEO_PREFIX:$it" }
        // `id` negativo não é id de MediaStore: isso só acontece se um episódio entrou sem id
        // legível, e nesse caso `a:-7` faria o restore procurar no banco a música de id -7.
        else -> if (song.id > 0L) "$AUDIO_PREFIX:${song.id}" else null
    }

    /**
     * O inverso de [encode].
     *
     * Aceita `"a:42"`, `"v:42"`, `"e:42"`, `"r:<url>"` e `"s:<url>"`, e também o número solto
     * `42` — o formato do `queue_ids` antigo, que era só `id` de áudio. Um endereço de
     * rádio/stream pode conter `:` e até `,` sem problema: o split é no **primeiro** `:` e o
     * resto é o payload inteiro. Qualquer outra coisa é `null`, e o caller trata isso como
     * "esse item não voltou mais".
     */
    fun decode(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val sep = s.indexOf(':')
        if (sep < 0) return if (s.toLongOrNull() != null) "$AUDIO_PREFIX:$s" else null
        val kind = s.substring(0, sep)
        val rest = s.substring(sep + 1).trim()
        return when (kind) {
            AUDIO_PREFIX -> rest.toLongOrNull()?.let { "$AUDIO_PREFIX:$it" }
            VIDEO_PREFIX -> rest.toLongOrNull()?.let { "$VIDEO_PREFIX:$it" }
            EPISODE_PREFIX -> rest.toLongOrNull()?.let { "$EPISODE_PREFIX:$it" }
            // Payload de rádio/stream não é número: o endereço em si. Só não pode ser vazio.
            RADIO_PREFIX -> if (rest.isNotEmpty()) "$RADIO_PREFIX:$rest" else null
            STREAM_PREFIX -> if (rest.isNotEmpty()) "$STREAM_PREFIX:$rest" else null
            else -> null
        }
    }

    /** `true` quando a chave é de episódio, para o restore da fila saber o que reencontrar. */
    fun isEpisode(key: String): Boolean = key.startsWith("$EPISODE_PREFIX:")

    /** `true` quando a chave é de rádio (`r:<url>`). */
    fun isRadio(key: String): Boolean = key.startsWith("$RADIO_PREFIX:")

    /** `true` quando a chave é de stream (`s:<url>`). */
    fun isStream(key: String): Boolean = key.startsWith("$STREAM_PREFIX:")

    /** As chaves da fila na ordem, pulando o que não é retomável. */
    fun encodeAll(songs: List<Song>): List<String> = songs.mapNotNull { encode(it) }

    /** Duas chaves iguais são o mesmo item da fila, mesmo com `id` repetido em coleções diferentes. */
    fun sameType(a: String?, b: String?): Boolean = a != null && a == b

    /**
     * F2b — o que realmente entrou na fila ao "adicionar à fila".
     *
     * Um item só entra se ainda não estiver lá, e a comparação é por chave: o vídeo de id
     * 42 e a música de id 42 são itens diferentes, então os dois podem estar na fila. Por
     * `id` o vídeo seria descartado por causa de uma música — e o inverso também.
     *
     * Fila universal: rádio e stream também têm chave (a URL), então a mesma rádio **não**
     * entra duas vezes — igual ao resto. Isso era até impossível antes, quando eles não
     * tinham chave e o `filterNew` deixava passar sempre.
     */
    fun filterNew(existing: List<Song>, candidates: List<Song>): List<Song> {
        val seen = existing.mapNotNull { encode(it) }.toMutableSet()
        val out = ArrayList<Song>(candidates.size)
        for (song in candidates) {
            val key = encode(song)
            if (key != null) {
                if (!seen.add(key)) continue
            }
            out.add(song)
        }
        return out
    }

    /**
     * Reancora o índice salvo depois do restore.
     *
     * A fila salva pode ter perdido itens: foto apagada do MediaStore, sdcard removido. O
     * índice original apontaria para a faixa errada — o bug que o `songsByIds` em lotes já
     * corrigiu, e que aqui precisa da chave porque `indexOf` por `id` sozinho encontraria o
     * vídeo no lugar da música com o mesmo número.
     *
     * Devolve `-1` quando nada da fila voltou, para o caller não tentar tocar fila vazia.
     */
    fun reanchor(savedKeys: List<String>, savedIndex: Int, loadedKeys: List<String>): Int {
        if (loadedKeys.isEmpty()) return -1
        val wanted = savedKeys.getOrNull(savedIndex.coerceIn(0, savedKeys.lastIndex)) ?: return 0
        val at = loadedKeys.indexOfFirst { sameType(it, wanted) }
        // A faixa que estava tocando não voltou (apagada, sdcard): cai na primeira da fila,
        // que é o melhor palpite — e o próximo tick regrava o índice certo.
        return if (at >= 0) at else 0
    }
}