package com.pulsa.player.model

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val path: String,
    val year: Int,
    val dateAdded: Long = 0L
) {
    /** Rádio = item que o serviço toca como fluxo, `path = "radio:<url>"` (E4). */
    val isRadio: Boolean get() = path.startsWith(RADIO_PREFIX)
    val radioUrl: String? get() = if (isRadio) path.removePrefix(RADIO_PREFIX) else null

    /** Vídeo = item que o serviço toca e a tela de vídeo renderiza, `path = "video:<id>"` (E5).
     *
     * Um episódio de podcast em vídeo **não** usa este prefixo: o id dele é do Room, e ir no
     * `VideoLibrary.contentUri(id)` devolveria o vídeo errado do MediaStore, em silêncio.
     */
    val isVideo: Boolean get() = path.startsWith(VIDEO_PREFIX)
    val videoId: Long? get() = if (isVideo) path.removePrefix(VIDEO_PREFIX).toLongOrNull() else null

    /**
     * Stream remoto (HLS/DASH/progressivo), `path = "stream:<url>"` (F2).
     *
     * Diferente de [isVideo], que aponta para um id do MediaStore, aqui o caminho é a URL
     * e não existe item local — então a `VideoPlayerActivity` toca uma fila de um item só.
     */
    val isStream: Boolean get() = path.startsWith(STREAM_PREFIX)
    val streamUrl: String? get() = if (isStream) path.removePrefix(STREAM_PREFIX) else null

    /**
     * Episódio de podcast (F3), `path = "podcast:<id do episódio>"`.
     *
     * O `id` é o do Room, não o `guid` nem a URL: o motor precisa de um id estável **e
     * numérico** para casar com o `position_ms` que o serviço grava a cada tick. O `guid`
     * serve para deduplicar no banco (ver [com.pulsa.player.podcast.Episode]).
     *
     * O `path` continua sendo `podcast:<id>` **mesmo com o episódio baixado**. Guardar o
     * caminho do arquivo aqui desligaria [isPodcast], e com ela a identidade `e:<id>` da fila
     * e a gravação de posição — ou seja, baixar um episódio faria o app esquecer onde a pessoa
     * parou. Quem decide entre arquivo e URL é o
     * [com.pulsa.player.podcast.PodcastCache], no momento de montar o `MediaItem`.
     */
    val isPodcast: Boolean get() = path.startsWith(PODCAST_PREFIX)

    /**
     * O id do episódio, tanto para `podcast:<id>` quanto para `podcast:v<id>`.
     *
     * O sinalizador `v` do vídeo é removido **antes** de ler o número. Sem isso o
     * `toLongOrNull()` receberia `"v123"` e devolveria `null`, e o episódio em vídeo ficaria
     * sem chave na fila ([com.pulsa.player.playback.QueueKey.encode] cairia no áudio com o
     * `song.id` negativo) e sem `position_ms` gravado — o sintoma é o podcast em vídeo que
     * nunca retoma de onde parou.
     */
    val podcastId: Long?
        get() {
            if (!isPodcast) return null
            val rest = path.removePrefix(PODCAST_PREFIX)
            val numeric = if (rest.startsWith(PODCAST_VIDEO_FLAG)) rest.substring(1) else rest
            return numeric.toLongOrNull()
        }

    /**
     * Episódio de podcast **em vídeo**: `podcast:v<id>`, com o `v` de sinalizador.
     *
     * Compartilhar o prefixo faz o [com.pulsa.player.playback.QueueKey] e a tela reconhecerem
     * os dois sem uma segunda comparação, e o `v` marca o tipo sem competir com o número do
     * id em [podcastId].
     *
     * O `v` sozinho **não** basta: o número tem que existir. Um `podcast:v` truncado daria
     * `true` aqui com [podcastId] nulo, e o efeito cascateia sem erro visível — a fila
     * perderia a chave `e:<id>` e a [needsVideoScreen] abriria a tela de vídeo para um
     * episódio que não existe. Exigir o id torna "é vídeo" e "tem id" a mesma pergunta.
     */
    val isPodcastVideo: Boolean
        get() = isPodcast && path.removePrefix(PODCAST_PREFIX).let { rest ->
            rest.startsWith(PODCAST_VIDEO_FLAG) && rest.drop(1).toLongOrNull() != null
        }

    /**
     * `true` quando o item precisa da [com.pulsa.player.VideoPlayerActivity] para ter imagem.
     *
     * São três tipos, e a regra estava escrita em três lugares — `openNowPlaying`, o
     * `onSongChanged` da home e o da NowPlaying. O terceiro a escrever esqueceria o episódio de
     * podcast em vídeo, que é o sintoma exato do `MediaItem` tocando: sai o áudio do vídeo e
     * nenhuma imagem, sem erro nenhum na tela.
     */
    val needsVideoScreen: Boolean get() = isVideo || isStream || isPodcastVideo

    companion object {
        const val RADIO_PREFIX = "radio:"
        const val VIDEO_PREFIX = "video:"
        const val STREAM_PREFIX = "stream:"
        const val PODCAST_PREFIX = "podcast:"

        /** Sinalizador de podcast em vídeo, dentro do [PODCAST_PREFIX]. */
        const val PODCAST_VIDEO_FLAG = "v"
    }
}

data class Album(
    val id: Long,
    val name: String,
    val artist: String,
    val numSongs: Int
)

data class Artist(
    val name: String,
    val numSongs: Int,
    val numAlbums: Int
)

data class Playlist(
    val id: Long,
    val name: String,
    val count: Int,
    val autoAdd: Boolean = false,
    val system: Boolean = false
)

data class Video(
    val id: Long,
    val title: String,
    val durationMs: Long,
    val path: String,
    val sizeBytes: Long
)

/**
 * F2b — o [Video] como item de fila.
 *
 * Este `Song` é o que o motor toca e o que a fila persiste, então a construção precisa ser
 * **uma** só: ela aparecia escrita em três lugares ([PlaybackService.videoSong],
 * [PulsaLibraryTree] e [VideoPlayerActivity]) e um deles mudar sozinho quebrava a
 * correspondência — o mesmo vídeo restoring com `path` diferente do original não voltava
 * (a chave do [com.pulsa.player.playback.QueueKey] é o par tipo+id, mas o `path` é o que o
 * motor abre).
 *
 * `albumLabel` vem de fora porque o texto é recurso (`R.string.tab_videos`) e o model não
 * tem `Context`. `artist` fica vazio de propósito: vídeo do MediaStore não tem artista, e
 * inventar um faria a notificação e o EQ tratarem vídeo como música.
 */
fun Video.toSong(albumLabel: String): Song = Song(
    id = id,
    title = title,
    artist = "",
    album = albumLabel,
    albumId = 0L,
    durationMs = durationMs,
    path = Song.VIDEO_PREFIX + id,
    year = 0
)

data class SongMeta(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String
)
