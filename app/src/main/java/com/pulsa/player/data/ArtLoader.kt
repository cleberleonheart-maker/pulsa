package com.pulsa.player.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import com.pulsa.player.R
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.model.Song
import java.io.File

object ArtLoader {
    private val cache = object : LruCache<Long, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private fun artDir(context: Context): File =
        File(context.cacheDir, "art").apply { if (!exists()) mkdirs() }

    private fun artFile(context: Context, albumId: Long): File =
        File(artDir(context), "$albumId.jpg")

    /**
     * F2b — arquivo da miniatura de vídeo.
     *
     * O prefixo `v` não é cosmético: os arquivos de capa são `$albumId.jpg`, e o id de um
     * vídeo do MediaStore pode ser igual ao de um álbum. Sem o prefixo, as duas coisas
     * colidiam no mesmo arquivo e a capa de um álbum aparecia como miniatura de vídeo.
     */
    private fun videoArtFile(context: Context, videoId: Long): File =
        File(artDir(context), "v$videoId.jpg")

    fun load(albumId: Long, path: String, imageView: ImageView) {
        // F2b: vídeo não tem álbum, então `Video.toSong` grava `albumId = 0` e o atalho
        // antigo desenhava a nota musical — era isso que o mini player mostrava ao
        // devolver o vídeo para a música depois de assistir. A miniatura de vídeo sai de
        // um frame, e não de um álbum, então é outro caminho.
        val videoId = path.videoIdOrNull()
        if (videoId != null) {
            loadVideoFrame(videoId, imageView)
            return
        }
        val cached = cache.get(albumId)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            imageView.clearColorFilter()
            return
        }
        if (albumId <= 0) {
            imageView.setImageResource(R.drawable.ic_music_note)
            return
        }
        imageView.tag = albumId.toString()
        val context = imageView.context.applicationContext
        ThreadPool.post {
            val bitmap = decode(context, albumId, path)
            if (bitmap != null) {
                cache.put(albumId, bitmap)
                ThreadPool.onUi {
                    if (imageView.tag == albumId.toString()) {
                        imageView.setImageBitmap(bitmap)
                        imageView.clearColorFilter()
                    }
                }
            }
        }
    }

    /**
     * Chave de cache da miniatura de vídeo.
     *
     * **Negativa de propósito:** a capa de álbum é chaveada pelo `albumId`, que é
     * positivo, e um id de vídeo do MediaStore pode bater com o id de um álbum — as
     * duas bibliotecas usam o mesmo espaço de inteiros. O sinal negativo separa os
     * dois namespaces sem precisar de um `LruCache` novo.
     */
    private fun videoKey(videoId: Long): Long = -videoId

    /** F2b — desenha o primeiro frame do vídeo no lugar da nota musical. */
    private fun loadVideoFrame(videoId: Long, imageView: ImageView) {
        val key = videoKey(videoId)
        val cached = cache.get(key)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            imageView.clearColorFilter()
            return
        }
        // A tag é o que impede o frame de cair numa `ImageView` que já mudou de faixa
        // enquanto o `ThreadPool.post` trabalhava.
        imageView.tag = key.toString()
        val context = imageView.context.applicationContext
        ThreadPool.post {
            val disk = videoArtFile(context, videoId)
            val bitmap = runCatching {
                if (disk.exists()) BitmapFactory.decodeFile(disk.absolutePath) else null
            }.getOrNull() ?: videoFrame(context, videoId)?.also { saveToDisk(disk, it) }
            ThreadPool.onUi {
                // A view pode ter sido reciclada entre o post e a volta: sem esta
                // comparação, o frame do vídeo velho aparece na música nova.
                if (imageView.tag != key.toString()) return@onUi
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    imageView.clearColorFilter()
                } else {
                    // Vídeo sem frame legível (apagado do MediaStore, por exemplo):
                    // melhor o ícone do que a arte de outra faixa.
                    imageView.setImageResource(R.drawable.ic_music_note)
                }
            }
            if (bitmap != null) cache.put(key, bitmap)
        }
    }

    /**
     * F2b — a miniatura de um vídeo.
     *
     * `loadThumbnail` (API 29+) é o caminho porque a plataforma gera e guarda o frame
     * sozinha, sem decodificar o arquivo. Antes disso cai no `Thumbnails.getThumbnail`,
     * que é o mesmo gerador via API antiga. E o `MediaMetadataRetriever` no fim é a
     * rede de segurança para quando o provedor não devolve nada: extrair um frame é
     * caro, mas só acontece depois das duas tentativas mais baratas falharem.
     */
    fun videoFrame(context: Context, videoId: Long): Bitmap? {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            }.getOrNull()?.let { return scaleDown(it, 512) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver, videoId,
                    MediaStore.Video.Thumbnails.MINI_KIND, null
                )
            }.getOrNull()?.let { return it }
        }
        return frameFromPath(context, videoId)
    }

    /** Rede de segurança: pergunta o caminho real do vídeo e extrai um frame dele. */
    private fun frameFromPath(context: Context, videoId: Long): Bitmap? {
        val data = runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media.DATA),
                "${MediaStore.Video.Media._ID} = ?",
                arrayOf(videoId.toString()),
                null
            )?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull() ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(data)
            retriever.frameAtTime?.let { scaleDown(it, 512) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** `Song.path` de um vídeo é `video:<id>`; devolve o id, ou null se não for vídeo. */
    private fun String.videoIdOrNull(): Long? =
        if (startsWith(Song.VIDEO_PREFIX)) {
            removePrefix(Song.VIDEO_PREFIX).toLongOrNull()
        } else null

    fun embedArt(context: Context, path: String): Bitmap? {
        if (path.isBlank()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val pic = retriever.embeddedPicture
            pic?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun decode(context: Context, albumId: Long, path: String): Bitmap? {
        if (albumId > 0) {
            val disk = artFile(context, albumId)
            if (disk.exists()) {
                try {
                    val b = BitmapFactory.decodeFile(disk.absolutePath)
                    if (b != null) return b
                } catch (_: Exception) {
                }
            }
            val fromMedia = runCatching {
                val uri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val b = BitmapFactory.decodeStream(stream)
                    if (b != null) scaleDown(b, 512) else null
                }
            }.getOrNull()
            if (fromMedia != null) {
                saveToDisk(disk, fromMedia)
                return fromMedia
            }
        }
        return embedArt(context, path)?.let {
            val b = scaleDown(it, 512)
            if (albumId > 0) saveToDisk(artFile(context, albumId), b)
            b
        }
    }

    private fun saveToDisk(file: File, bitmap: Bitmap) {
        ThreadPool.post {
            runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
            }
        }
    }

    fun decodeLarge(path: String, context: Context): Bitmap? {
        if (path.isBlank()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val pic = retriever.embeddedPicture
            pic?.let { bytes ->
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                b?.let { scaleDown(it, 1024) }
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxSize: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSize && h <= maxSize) return bitmap
        val scale = maxSize.toFloat() / maxOf(w, h)
        val nw = (w * scale).toInt()
        val nh = (h * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
