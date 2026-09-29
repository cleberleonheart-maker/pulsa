package com.pulsa.player.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pulsa.player.R
import com.pulsa.player.core.Permissions
import com.pulsa.player.core.RadioStations
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.model.Song
import java.io.File

/**
 * E6 — a árvore que o Auto/Wear/Assistant enxergam.
 *
 * Fica dentro do [PlaybackService] (virou `MediaLibraryService`) em vez de virar um serviço
 * separado, que era o plano original. A razão: um segundo `MediaLibraryService` teria o seu
 * próprio `ExoPlayer` e a sua própria fila, e foi exatamente juntar áudio, vídeo e rádio em
 * um player só (E3b–E5) que a F1 resolveu. Um serviço de biblioteca separado desfaria isso.
 * Com um só, tocar pela busca do sistema cai na MESMA sessão e a MESMA fila do app.
 *
 * Os `mediaId` carregam o tipo e o id local (`musica:<id>`, `video:<id>`, `radio:<url>`), o que
 * deixa a resolução em [PlaybackService] um `startsWith` — sem serializar a `Song` inteira
 * dentro do id, que passaria por Binder e estouraria o limite de transação.
 */
internal class PulsaLibraryTree(private val context: Context) {

    private val db by lazy { PlaylistDb.get(context) }

    /** Item que o sistema entende como pasta: os filhos vêm em [onGetChildren]. */
    private fun folder(mediaId: String, title: String, type: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .build()
            )
            .build()

    /** Item tocável. O [uri] é o mesmo que o `PlaybackService` monta para a fila. */
    private fun playable(song: Song): MediaItem? {
        val uri = song.videoId?.let { VideoLibrary.contentUri(it) }
            ?: song.radioUrl?.let(Uri::parse)
            ?: Uri.fromFile(File(song.path))
        val type = when {
            song.isVideo -> MediaMetadata.MEDIA_TYPE_VIDEO
            song.isRadio -> MediaMetadata.MEDIA_TYPE_RADIO_STATION
            else -> MediaMetadata.MEDIA_TYPE_MUSIC
        }
        val mediaId = mediaIdFor(song) ?: return null
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(type)
                    .build()
            )
            .build()
    }

    private fun playable(station: com.pulsa.player.core.UserStation): MediaItem =
        MediaItem.Builder()
            .setMediaId("radio:${station.url}")
            .setUri(Uri.parse(station.url))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setSubtitle(station.genre)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build()
            )
            .build()

    private fun playable(video: com.pulsa.player.model.Video): MediaItem =
        MediaItem.Builder()
            .setMediaId("video:${video.id}")
            .setUri(VideoLibrary.contentUri(video.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(video.title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                    .build()
            )
            .build()

    /**
     * `null` quando o `path` é de vídeo ou rádio mas não traz um id utilizável (ex.:
     * `video:abc`). Sem esta guarda o id viraria a string `"video:null"`, que não resolve de
     * volta para nenhuma faixa — o item entraria na fila e o `songFor` devolveria `null`,
     * jogando a chamada fora.
     */
    fun mediaIdFor(song: Song): String? = when {
        song.isVideo -> song.videoId?.let { "video:$it" }
        song.isRadio -> song.radioUrl?.let { "radio:$it" }
        else -> "musica:${song.id}"
    }

    /**
     * Raiz só com a pasta "Pulsa": o Auto pede a raiz antes de pedir os filhos, e uma pasta
     * única mantém a assinatura de browser estável (o controller se inscreve nela).
     */
    fun root(): List<MediaItem> = ImmutableList.of(
        folder("pulsa", str(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
    )

    /**
     * Os rótulos das pastas vão por `getString`: com texto fixo em português, a árvore mostrada
     * pelo Auto e pelo Wear sairia em português mesmo com o app em inglês.
     */
    private fun str(id: Int) = context.getString(id)

    fun childrenOf(parentId: String): ImmutableList<MediaItem> = when (parentId) {
        "pulsa" -> ImmutableList.of(
            folder("musicas", str(R.string.tab_songs), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder("favoritas", str(R.string.tab_favorites), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder("radio", str(R.string.radio), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder("videos", str(R.string.tab_videos), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder("playlists", str(R.string.tab_playlists), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder("recentes", str(R.string.home_recent_title), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        )
        "musicas", "favoritas", "recentes", "videos" -> ImmutableList.copyOf(itemsOf(parentId))
        "radio" -> ImmutableList.copyOf(
            RadioStations.list(context).map { playable(it) }
        )
        "playlists" -> ImmutableList.copyOf(
            db.playlists().map {
                folder("playlist:${it.id}", it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
        )
        else -> if (parentId.startsWith("playlist:")) {
            val id = parentId.removePrefix("playlist:").toLongOrNull()
            if (id == null) ImmutableList.of()
            else ImmutableList.copyOf(db.songs(id).mapNotNull { playable(it) })
        } else {
            ImmutableList.of()
        }
    }

    private fun itemsOf(id: String): List<MediaItem> = when (id) {
        "musicas" -> if (Permissions.hasAccess(context)) {
            Library.allSongs(context).mapNotNull { playable(it) }
        } else {
            emptyList()
        }
        "favoritas" -> if (Permissions.hasAccess(context)) {
            db.favorites().mapNotNull { playable(it) }
        } else {
            emptyList()
        }
        "recentes" -> if (Permissions.hasAccess(context)) {
            Library.recentSongs(context).mapNotNull { playable(it) }
        } else {
            emptyList()
        }
        "videos" -> if (Permissions.hasVideo(context)) {
            VideoLibrary.all(context).map { playable(it) }
        } else {
            emptyList()
        }
        else -> emptyList()
    }

    /**
     * Busca do sistema ("tocar <algo>" do Assistant). Varre as mesmas fontes da árvore e
     * devolve faixas — devolve também a pasta de rádio quando o texto casa com uma estação,
     * porque o usuário costuma pedir rádio por nome de estación.
     */
    fun search(query: String, limit: Int = 25): ImmutableList<MediaItem> {
        val q = query.trim()
        if (q.isEmpty()) return ImmutableList.of()
        val needle = q.lowercase()
        val out = LinkedHashMap<String, MediaItem>()
        fun add(items: List<MediaItem>) {
            for (item in items) {
                if (out.size >= limit) return
                out.putIfAbsent(item.mediaId, item)
            }
        }
        val songs = if (Permissions.hasAccess(context)) {
            // `mapNotNull` já descarta o que não vira item, então o `take` pode ser aplicado
            // sobre os `Song` ANTES de montar o `MediaItem`: antes ele vinha depois, o que
            // convertia a biblioteca inteira em MediaItem para depois descartar quase tudo.
            Library.allSongs(context).filter { it.matches(needle) }
                .take(limit).mapNotNull { playable(it) }
        } else {
            emptyList()
        }
        add(songs)
        if (out.size < limit && Permissions.hasAccess(context)) {
            add(db.favorites().filter { it.matches(needle) }.mapNotNull { playable(it) })
        }
        if (out.size < limit) {
            add(RadioStations.list(context).filter { it.matches(needle) }.map { playable(it) })
        }
        if (out.size < limit && Permissions.hasVideo(context)) {
            add(VideoLibrary.all(context).filter { it.title.lowercase().contains(needle) }
                .mapNotNull { playable(it) })
        }
        return ImmutableList.copyOf(out.values)
    }

    /**
     * `null` = o browser não pode ver o item (arquivo sumiu do MediaStore). O Media3 usa isso
     * para remover do cache do browser em vez de devolver um item quebrado.
     */
    fun itemById(mediaId: String): MediaItem? {
        if (mediaId.startsWith("musica:")) {
            if (!Permissions.hasAccess(context)) return null
            val id = mediaId.removePrefix("musica:").toLongOrNull() ?: return null
            return Library.songsById(context, id).firstOrNull()?.let { playable(it) }
        }
        if (mediaId.startsWith("video:")) {
            if (!Permissions.hasVideo(context)) return null
            val id = mediaId.removePrefix("video:").toLongOrNull() ?: return null
            return VideoLibrary.all(context).firstOrNull { it.id == id }?.let { playable(it) }
        }
        if (mediaId.startsWith("radio:")) {
            val url = mediaId.removePrefix("radio:")
            return RadioStations.list(context).firstOrNull { it.url == url }?.let { playable(it) }
        }
        return null
    }

    /**
     * Volta do `mediaId` para o modelo do app. É o inverso de [mediaIdFor] e existe porque a
     * fila do serviço é `List<Song>`, não `List<MediaItem>`: um `setMediaItems` de browser
     * externo precisa virar `Song` antes de entrar, senão notificação, widget e next/prev
     * descreveriam a faixa errada.
     *
     * Devolve `null` quando o id é de uma pasta (não dá para tocar) ou não existe mais.
     */
    fun songFor(mediaId: String?): Song? {
        if (mediaId == null) return null
        return when {
            mediaId.startsWith("musica:") -> {
                // mesma guarda de `itemById`: um browser não pode usar o `mediaId` para ler a
                // biblioteca com a permissão negada
                if (!Permissions.hasAccess(context)) return null
                val id = mediaId.removePrefix("musica:").toLongOrNull() ?: return null
                Library.songsById(context, id).firstOrNull()
            }
            mediaId.startsWith("video:") -> {
                if (!Permissions.hasVideo(context)) return null
                val id = mediaId.removePrefix("video:").toLongOrNull() ?: return null
                val video = VideoLibrary.all(context).firstOrNull { it.id == id } ?: return null
                Song(
                    id = video.id,
                    title = video.title,
                    artist = "",
                    // mesmo `album` que o `VideoPlayerActivity` usa ao enfileirar um vídeo
                    album = str(R.string.tab_videos),
                    albumId = 0L,
                    durationMs = video.durationMs,
                    path = Song.VIDEO_PREFIX + video.id,
                    year = 0
                )
            }
            mediaId.startsWith("radio:") -> {
                val url = mediaId.removePrefix("radio:")
                val station = RadioStations.list(context).firstOrNull { it.url == url } ?: return null
                Song(
                    // mesmo id sintético do `PlaybackService.restoreRadioResume`, para o item
                    // entrar na fila com a mesma identidade de quando o rádio é retomado
                    id = (url.hashCode() and 0x7fffffff).toLong(),
                    title = station.name,
                    artist = station.genre,
                    album = station.genre,
                    albumId = 0L,
                    durationMs = 0L,
                    path = Song.RADIO_PREFIX + url,
                    year = 0
                )
            }
            else -> null
        }
    }

    // --- Future helpers: a API do MediaLibraryService é toda ListenableFuture e os callbacks
    // rodam na main thread, então resolver de forma síncrona é intencional. A leitura do
    // MediaStore é o gargalo; o Media3 dá para devolver o future já concluído e o browser
    // recebe na mesma volta do looper. Paginação grande ficaria melhor com um executor
    // dedicado -- não faço agora porque `onGetChildren` pagina em 50 e o MediaStore local
    // responde em ms.

    fun rootFuture(params: LibraryParams?):
        ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(root()[0], params))

    fun itemFuture(mediaId: String, params: LibraryParams?):
        ListenableFuture<LibraryResult<MediaItem>> {
        val item = itemById(mediaId)
            ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE, params))
        return Futures.immediateFuture(LibraryResult.ofItem(item, params))
    }

    fun childrenFuture(
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val all = childrenOf(parentId)
        val from = (page * pageSize).coerceIn(0, all.size)
        val to = (from + pageSize).coerceIn(from, all.size)
        val slice = all.subList(from, to)
        return Futures.immediateFuture(LibraryResult.ofItemList(slice, params))
    }

    /**
     * A busca recomeça do zero a cada página, então o limite é o fim da página pedida e não o
     * tamanho dela: com `pageSize * (page + 1)` o browser receberia resultados que já tinha e
     * a varredura da biblioteca inteira rodaria uma vez por página.
     */
    fun searchFuture(
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val end = pageSize * (page + 1)
        val all = search(query, end)
        val from = (page * pageSize).coerceIn(0, all.size)
        val to = (from + pageSize).coerceIn(from, all.size)
        return Futures.immediateFuture(LibraryResult.ofItemList(all.subList(from, to), params))
    }

    fun voidFuture(params: LibraryParams?):
        ListenableFuture<LibraryResult<Void>> =
        Futures.immediateFuture(LibraryResult.ofVoid(params))

    private fun Song.matches(needle: String) =
        title.lowercase().contains(needle) ||
            artist.lowercase().contains(needle) ||
            album.lowercase().contains(needle)

    private fun com.pulsa.player.core.UserStation.matches(needle: String) =
        name.lowercase().contains(needle) || genre.lowercase().contains(needle)
}
