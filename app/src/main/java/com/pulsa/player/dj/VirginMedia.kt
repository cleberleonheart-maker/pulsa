package com.pulsa.player.dj

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.model.Song
import com.pulsa.player.model.Video

/**
 * Operações de MediaStore/biblioteca compartilhadas entre o assistente da tela
 * principal (MainVirgin) e o da cabine do DJ (DjSession).
 */
object VirginMedia {

    private val exts = setOf(
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma", "3gp", "mid", "midi", "amr"
    )

    data class PendriveFile(
        val uri: Uri,
        val name: String,
        val title: String?,
        val artist: String?
    )

    class SongKeys(
        val names: HashSet<String>,
        val tags: HashSet<String>
    )

    data class DuplicateSet(
        val songsCopies: List<Song>,
        val videoCopiesList: List<Video>,
        val pairs: Int,
        val copies: Int
    )

    data class PendriveScan(
        val files: List<PendriveFile>,
        val newFiles: List<PendriveFile>,
        val skipped: Int
    )

    fun findArtist(context: Context, query: String?): String? {
        val q = DjCommander.norm(query.orEmpty()).trim()
        if (q.isEmpty()) return null
        val names = runCatching {
            Library.allSongs(context).map { it.artist }.toSet()
        }.getOrDefault(emptySet())
        var best: String? = null
        var bestScore = -1
        for (name in names) {
            val n = DjCommander.norm(name)
            if (n.isEmpty()) continue
            val score = when {
                n == q -> 1000
                n.contains(q) -> 100 + q.length
                q.contains(n) && q.length >= n.length -> 50 + n.length
                else -> -1
            }
            if (score > bestScore) {
                bestScore = score
                best = name
            }
        }
        return best
    }

    fun findDuplicates(context: Context): DuplicateSet {
        val all = Library.allSongs(context)
        val groups = LinkedHashMap<String, MutableList<Song>>()
        for (s in all) {
            if (s.title.isBlank()) continue
            val key = "${s.artist.lowercase()}|${s.title.lowercase()}"
                .trim().replace("\\s+".toRegex(), " ")
            groups.getOrPut(key) { mutableListOf() }.add(s)
        }
        val songsCopies = mutableListOf<Song>()
        var songPairs = 0
        for (list in groups.values) {
            if (list.size > 1) {
                songsCopies += list.drop(1)
                songPairs++
            }
        }

        val videos = VideoLibrary.all(context)
        val videoGroups = LinkedHashMap<String, MutableList<Video>>()
        for (v in videos) {
            if (v.title.isBlank()) continue
            val key = v.title.lowercase().trim().replace("\\s+".toRegex(), " ")
            videoGroups.getOrPut(key) { mutableListOf() }.add(v)
        }
        val videoCopiesList = mutableListOf<Video>()
        var videoPairs = 0
        for (list in videoGroups.values) {
            if (list.size > 1) {
                videoCopiesList += list.drop(1)
                videoPairs++
            }
        }
        return DuplicateSet(
            songsCopies, videoCopiesList,
            songPairs + videoPairs,
            songsCopies.size + videoCopiesList.size
        )
    }

    fun deleteDuplicates(
        context: Context,
        songsCopies: List<Song>,
        videoCopiesList: List<Video>
    ): Boolean {
        val resolver = context.contentResolver
        return runCatching {
            songsCopies.all { s ->
                resolver.delete(
                    ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id
                    ),
                    null, null
                ) > 0
            } && videoCopiesList.all { v ->
                resolver.delete(VideoLibrary.contentUri(v.id), null, null) > 0
            }
        }.getOrDefault(false)
    }

    fun deleteSong(context: Context, song: Song): Boolean =
        runCatching {
            context.contentResolver.delete(
                ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id
                ),
                null, null
            ) > 0
        }.getOrDefault(false)

    fun removeFromPlaylists(context: Context, song: Song) {
        runCatching {
            val db = PlaylistDb.get(context)
            db.removeSongFromAll(song.id)
            db.removeFavorite(song.id)
        }
    }

    fun librarySongKeys(context: Context): SongKeys {
        val names = hashSetOf<String>()
        val tags = hashSetOf<String>()
        runCatching {
            Library.allSongs(context)
        }.getOrDefault(emptyList()).forEach { s ->
            val pathName = s.path.substringAfterLast('/').substringBeforeLast('.').lowercase().trim()
            if (pathName.isNotEmpty()) names.add(pathName)
            if (s.title.isNotBlank()) tags.add("${s.artist.lowercase()}|${s.title.lowercase()}")
        }
        return SongKeys(names, tags)
    }

    fun readAudioTags(context: Context, uri: Uri): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT < 23) return null to null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            title to artist
        } catch (t: Throwable) {
            null to null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun scanPendrive(context: Context, rootUri: Uri): PendriveScan {
        val extras = librarySongKeys(context)
        val files = mutableListOf<PendriveFile>()
        val seen = hashSetOf<String>()
        fun walk(doc: DocumentFile?, depth: Int) {
            if (doc == null || depth > 4 || files.size >= 4000) return
            val children = runCatching { doc.listFiles() }.getOrNull() ?: return
            for (f in children) {
                if (files.size >= 4000) return
                if (f.isDirectory) {
                    walk(f, depth + 1)
                } else {
                    val name = f.name ?: continue
                    if (exts.contains(name.substringAfterLast('.', "").lowercase())) {
                        if (seen.add(f.uri.toString())) {
                            val (title, artist) = readAudioTags(context, f.uri)
                            files.add(PendriveFile(f.uri, name, title, artist))
                        }
                    }
                }
            }
        }
        walk(DocumentFile.fromTreeUri(context, rootUri), 0)

        val newFiles = mutableListOf<PendriveFile>()
        var skipped = 0
        val pendriveSeen = hashSetOf<String>()
        val pendriveKeys = hashSetOf<String>()
        for (f in files) {
            val fname = f.name.substringBeforeLast('.').lowercase().trim()
            if (fname.isEmpty()) {
                skipped++
                continue
            }
            val title = f.title?.trim().orEmpty()
            val artist = f.artist?.trim().orEmpty()
            val tagKey = if (title.isNotEmpty()) "${artist.lowercase()}|${title.lowercase()}" else ""
            val isDup =
                extras.names.contains(fname) ||
                    (tagKey.isNotEmpty() && (extras.tags.contains(tagKey) || !pendriveKeys.add(tagKey))) ||
                    !pendriveSeen.add(fname)
            if (isDup) {
                skipped++
            } else {
                newFiles.add(f)
            }
        }
        return PendriveScan(files, newFiles, skipped)
    }

    fun copyToMusic(context: Context, f: PendriveFile): Boolean {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(f.name.substringAfterLast('.', "").lowercase())
            ?: "audio/mpeg"
        val input = runCatching {
            context.contentResolver.openInputStream(f.uri)
        }.getOrNull() ?: return false
        return try {
            input.use { stream ->
                if (Build.VERSION.SDK_INT >= 29) {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/")
                        put(MediaStore.Audio.Media.IS_MUSIC, 1)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                        if (!f.title.isNullOrBlank()) put(MediaStore.Audio.Media.TITLE, f.title)
                        if (!f.artist.isNullOrBlank()) put(MediaStore.Audio.Media.ARTIST, f.artist)
                    }
                    val uri = resolver.insert(
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        values
                    ) ?: return false
                    try {
                        resolver.openOutputStream(uri)?.use { out ->
                            stream.copyTo(out)
                        } ?: return false
                        resolver.update(uri, ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }, null, null)
                        true
                    } catch (t: Throwable) {
                        runCatching { resolver.delete(uri, null, null) }
                        false
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    if (!dir.exists() && !dir.mkdirs()) return false
                    val target = java.io.File(dir, uniqueName(context, f.name))
                    target.outputStream().use { out -> stream.copyTo(out) }
                    val ok = target.exists() && target.length() > 0
                    if (ok) {
                        MediaScannerConnection.scanFile(
                            context, arrayOf(target.absolutePath), null
                        ) { _, _ -> }
                    }
                    ok
                }
            }
        } catch (t: Throwable) {
            false
        }
    }

    fun uniqueName(context: Context, name: String): String {
        if (!java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), name
            ).exists()
        ) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "${base}_$i$ext"
            if (!java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), candidate
                ).exists()
            ) return candidate
            i++
        }
    }
}