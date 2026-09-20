package com.pulsa.player.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import com.pulsa.player.R
import com.pulsa.player.core.ThreadPool
import java.io.File

object ArtLoader {
    private val cache = object : LruCache<Long, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private fun artDir(context: Context): File =
        File(context.cacheDir, "art").apply { if (!exists()) mkdirs() }

    private fun artFile(context: Context, albumId: Long): File =
        File(artDir(context), "$albumId.jpg")

    fun load(albumId: Long, path: String, imageView: ImageView) {
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
