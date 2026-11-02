package com.pulsa.player.model

/**
 * Os `Song` de rádio e de stream — os dois itens da fila que **não** são arquivo local.
 *
 * O `path` é a identidade (`radio:<url>` / `stream:<url>`) e ela precisa ser **a mesma** em
 * todo lugar: o [com.pulsa.player.playback.QueueKey] deriva a chave do `path`, e um `path`
 * diferente num canto e outro produz uma fila que não reconhece o próprio item — o restore
 * monta o `Song` pela URL e não acha o que a tela criou.
 *
 * O `id` também vem da URL, e não de um id do banco: o item entra na fila em qualquer
 * aparelho e na próxima sessão, e tem de ser o mesmo item. É por isso que as factories
 * existem — a forma do `Song` de rádio e de stream estava escrita em três lugares, e cada
 * canto novo corria o risco de inventar a sua.
 */
object RemoteSongs {

    /** Rádio: `path = radio:<url>`. */
    fun radio(url: String, name: String, genre: String, radioLabel: String): Song = Song(
        id = radioId(url),
        title = name,
        artist = genre,
        album = radioLabel,
        albumId = 0L,
        durationMs = 0L,
        path = Song.RADIO_PREFIX + url,
        year = 0
    )

    /**
     * Stream (HLS/DASH/progressivo): `path = stream:<url>`.
     *
     * O `artist` fica vazio por padrão (o stream não tem artista); o restore preenche com o
     * que estava na sombra da fila. A duração é 0 de propósito: o manifesto só é lido quando
     * o stream começa, e 0 deixa a barra em modo indeterminado em vez de mentir que o vídeo
     * tem 1 segundo.
     */
    fun stream(url: String, title: String, videosLabel: String, artist: String = ""): Song = Song(
        id = streamId(url),
        title = title,
        artist = artist,
        album = videosLabel,
        albumId = 0L,
        durationMs = 0L,
        path = Song.STREAM_PREFIX + url,
        year = 0
    )

    /**
     * Id sintético do rádio, derivado da URL.
     *
     * A rádio não está em álbum nenhum: o id vem do endereço, para a mesma estação ser o
     * mesmo item na fila, no histórico e depois de um restart.
     */
    fun radioId(url: String): Long = (url.hashCode() and 0x7fffffff).toLong()

    /**
     * Id sintético do stream, derivado da URL.
     *
     * Antes era uma constante `-1L` para todo stream, e isso quebrava a legenda: a
     * `SubtitleConfig` guarda a escolha por id, então abrir dois streams seguidos fazia o
     * segundo herdar a legenda do primeiro. O hash da URL dá id estável por endereço e
     * diferente para cada um.
     */
    fun streamId(url: String): Long = url.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL

    /**
     * A fila é **só** rádio?
     *
     * Decide se o botão de avançar **gira as estações** ou **anda na fila**. Girar as estações
     * só faz sentido na fila do rádio, onde a fila **é** a lista de estações. Numa fila
     * misturada (música com uma rádio no fim), o botão tem que andar no que a pessoa montou:
     * trocar a fila inteira pela lista de estações perderia as músicas que estavam depois.
     */
    fun isRadioOnly(songs: List<Song>): Boolean = songs.isNotEmpty() && songs.all { it.isRadio }
}
