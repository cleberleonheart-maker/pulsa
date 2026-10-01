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

    /** Vídeo = item que o serviço toca e a tela de vídeo renderiza, `path = "video:<id>"` (E5). */
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

    companion object {
        const val RADIO_PREFIX = "radio:"
        const val VIDEO_PREFIX = "video:"
        const val STREAM_PREFIX = "stream:"
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

data class SongMeta(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String
)
