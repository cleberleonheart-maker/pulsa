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
     * A chave de [song], ou `null` quando ela não é retomável — rádio e stream não têm item
     * no MediaStore para o restore reencontrar, e o rádio tem mecanismo próprio
     * ([Settings.setRadioResume]).
     */
    fun encode(song: Song): String? = when {
        song.isRadio -> null
        song.isStream -> null
        song.isVideo -> song.videoId?.let { "$VIDEO_PREFIX:$it" }
        else -> "$AUDIO_PREFIX:${song.id}"
    }

    /**
     * O inverso de [encode].
     *
     * Aceita `"a:42"` e `"v:42"`, e também o número solto `42` — o formato do `queue_ids`
     * antigo, que era só `id` de áudio. Qualquer outra coisa é `null`, e o caller trata isso
     * como "esse item não voltou mais".
     */
    fun decode(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val sep = s.indexOf(':')
        if (sep < 0) return if (s.toLongOrNull() != null) "$AUDIO_PREFIX:$s" else null
        val kind = s.substring(0, sep)
        val id = s.substring(sep + 1).trim().toLongOrNull() ?: return null
        return when (kind) {
            AUDIO_PREFIX -> "$AUDIO_PREFIX:$id"
            VIDEO_PREFIX -> "$VIDEO_PREFIX:$id"
            else -> null
        }
    }

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
     * Rádio e stream não têm chave, então passam sempre: são um item só do seu endereço, e
     * ninguém espera que a mesma rádio entre duas vezes na fila.
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