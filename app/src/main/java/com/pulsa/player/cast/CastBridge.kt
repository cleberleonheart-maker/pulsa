package com.pulsa.player.cast

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.media.StreamKind
import com.pulsa.player.media.tv.DlnaServer
import com.pulsa.player.model.Song
import com.pulsa.player.playback.QueueKey
import com.pulsa.player.podcast.PodcastCache
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Ponte HTTP entre o aparelho e o Chromecast.
 *
 * **Por que existe.** O receptor não enxerga `content://` do MediaStore nem arquivo do
 * aparelho: mandar a URI local no `MediaItem` faz o Cast falhar (e o app cair de volta no
 * player local). O [DlnaServer] já resolve isso para a TV (Roku) com `Range`/206, então o
 * Cast reaproveita o mesmo servidor — sem SSDP, porque o Chromecast recebe a URL pronta no
 * `MediaItem` e não precisa descobrir nada.
 *
 * **Capa.** O `Default Media Receiver` mostra a arte da TV a partir de `artworkUri`, e essa
 * URL também precisa ser alcançável pelo receptor. Áudio/vídeo locais passam por `/art/<id>`
 * (decodificados na hora pelo [ArtLoader]); podcast usa a URL do feed direto.
 *
 * **Ciclo de vida.** Sobe quando a sessão Cast conecta e desce quando ela cai — servidor
 * ligado sem Cast é porta aberta e bateria à toa. [start] é idempotente para o caso de a
 * fila mudar durante a sessão.
 */
@OptIn(UnstableApi::class)
object CastBridge {

    private const val ART_DIR = "cast-art"

    private val songs = ConcurrentHashMap<String, Song>()
    private val targets = ConcurrentHashMap<String, String>()
    private val mimes = ConcurrentHashMap<String, String>()
    private val tokens = ConcurrentHashMap<String, String>()

    private var server: DlnaServer? = null

    @Volatile
    private var baseUrl: String = ""

    @Volatile
    private var appContext: Context? = null

    fun isRunning(): Boolean = server != null

    fun start(context: Context, queue: List<Song>) {
        appContext = context.applicationContext
        if (server != null) {
            queue.forEach { register(it) }
            return
        }
        val srv = DlnaServer(
            context = context,
            uuid = UUID.randomUUID().toString(),
            nodes = emptyMap(),
            resolve = { id -> targets[id] },
            mimes = mimes,
            announce = false,
            art = { id -> artFor(id) },
        )
        if (!srv.start()) return
        server = srv
        baseUrl = srv.baseUrl
        queue.forEach { register(it) }
    }

    fun stop() {
        server?.stop()
        server = null
        baseUrl = ""
        songs.clear()
        targets.clear()
        mimes.clear()
        tokens.clear()
        appContext = null
    }

    /**
     * O item que o `CastPlayer` recebe: URI HTTP quando a mídia é local, URL original quando
     * já é remota, e `artworkUri` para a TV mostrar a capa.
     */
    fun castItem(song: Song, local: MediaItem): MediaItem {
        if (server == null) return local
        register(song)
        val token = tokenFor(song) ?: return local
        val target = targets[token] ?: return local
        val uri = if (isRemote(target)) target else "$baseUrl/f/$token"
        val art = artUri(song, token)
        val metadata = local.mediaMetadata.buildUpon().apply {
            if (art != null) setArtworkUri(Uri.parse(art))
        }.build()
        return local.buildUpon()
            .setUri(Uri.parse(uri))
            .setMimeType(mimeFor(song, target))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun register(song: Song) {
        val key = keyOf(song)
        val target = targetFor(song)
        synchronized(this) {
            val existing = tokens[key]
            if (existing != null) {
                // A fila pode trazer uma instância nova do mesmo item (URL de episódio
                // atualizada, por exemplo). Mantém o token e só atualiza a origem.
                songs[existing] = song
                if (target != null) {
                    targets[existing] = target
                    mimes[existing] = mimeFor(song, target) ?: "application/octet-stream"
                }
                return
            }
            if (target == null) return
            val token = "c${tokens.size}"
            tokens[key] = token
            songs[token] = song
            targets[token] = target
            mimes[token] = mimeFor(song, target) ?: "application/octet-stream"
        }
    }

    private fun keyOf(song: Song): String = QueueKey.encode(song) ?: "i:${song.id}"

    private fun tokenFor(song: Song): String? = tokens[keyOf(song)]

    /**
     * De onde o receptor busca os bytes. `null` para rádio/stream: a URL original já é
     * pública e vai direto no `MediaItem`, sem passar pelo aparelho.
     */
    private fun targetFor(song: Song): String? {
        return when {
            song.isRadio || song.isStream -> null
            song.isPodcast -> PodcastCache.audioUrl(song.podcastId ?: return null)
            song.isVideo -> VideoLibrary.contentUri(song.videoId ?: return null).toString()
            else -> song.path.takeIf { it.isNotBlank() }
        }
    }

    private fun isRemote(target: String): Boolean =
        target.startsWith("http://") || target.startsWith("https://")

    private fun mimeFor(song: Song, target: String): String? {
        if (song.isVideo) return "video/mp4"
        if (isRemote(target)) {
            StreamKind.mimeTypeOf(target)?.let { return it }
        }
        val ext = target.substringBefore('?').substringAfterLast('.', "")
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())?.let { return it }
        }
        return "audio/mpeg"
    }

    private fun artUri(song: Song, token: String): String? {
        if (song.isPodcast) {
            val remote = PodcastCache.artUrl(song.podcastId ?: return null)
            return remote?.takeIf { isRemote(it) }
        }
        // Áudio/vídeo local: a capa é decodificada sob demanda no servidor. Se não existir,
        // o receptor recebe 404 e mostra a arte padrão dele — melhor que uma URL inválida
        // apontando para o aparelho.
        return "$baseUrl/art/$token"
    }

    /** Resolve a capa de um token; roda na thread HTTP, então pode decodificar/gravar. */
    private fun artFor(token: String): String? {
        val song = songs[token] ?: return null
        val context = appContext ?: return null
        if (song.isPodcast) {
            return PodcastCache.artUrl(song.podcastId ?: return null)?.takeIf { isRemote(it) }
        }
        val file = File(artDir(context), artName(song, token))
        if (file.exists() && file.length() > 0L) return file.absolutePath
        synchronized(this) {
            if (file.exists() && file.length() > 0L) return file.absolutePath
            val bitmap = runCatching {
                if (song.isVideo) {
                    song.videoId?.let { ArtLoader.videoFrame(context, it) }
                } else {
                    ArtLoader.decode(context, song.albumId, song.path)
                }
            }.getOrNull() ?: return null
            val ok = runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            }.isSuccess
            if (!ok) return null
        }
        return file.absolutePath
    }

    private fun artDir(context: Context): File =
        File(context.cacheDir, ART_DIR).apply { if (!exists()) mkdirs() }

    private fun artName(song: Song, token: String): String = when {
        song.isVideo -> "v${song.videoId}.jpg"
        song.albumId > 0L -> "a${song.albumId}.jpg"
        else -> "$token.jpg"
    }
}
