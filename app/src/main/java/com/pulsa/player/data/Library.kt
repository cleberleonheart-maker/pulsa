package com.pulsa.player.data

import android.content.Context
import android.provider.BaseColumns
import android.provider.MediaStore
import com.pulsa.player.model.Album
import com.pulsa.player.model.Artist
import com.pulsa.player.model.Song

object Library {
    private const val UNKNOWN = "<unknown>"

    private val songProjection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.YEAR,
        MediaStore.Audio.Media.DATE_ADDED
    )

    fun allSongs(context: Context): List<Song> {
        return querySongs(context, null, null)
    }

    /** Músicas adicionadas mais recentemente ao dispositivo, em ordem (recente → antiga). */
    fun recentSongs(context: Context, limit: Int = 12): List<Song> {
        val out = allSongs(context)
            .filter { it.dateAdded > 0L }
            .sortedByDescending { it.dateAdded }
        return if (out.size > limit) out.take(limit).toList() else out
    }

    fun songsById(context: Context, id: Long): List<Song> {
        return querySongs(
            context,
            "${MediaStore.Audio.Media._ID}=? AND ${MediaStore.Audio.Media.IS_MUSIC}!=0",
            arrayOf(id.toString())
        )
    }

    /**
     * Carrega vários `id`s de uma vez, devolvendo na MESMA ordem em que foram pedidos.
     *
     * [querySongs] ordena por TRACK/TITLE, o que é certo para listar a biblioteca e errado
     * para restaurar uma fila: a ordem salva é a ordem que o usuário montou. Por isso aqui
     * o resultado é reordenado pelos ids recebidos, não pela query.
     *
     * Faixa que não existe mais (apagada do dispositivo) é omitida. Por isso o tamanho do
     * resultado pode ser menor que o da lista: quem chama tem de reajustar o índice.
     *
     * A busca vai em lotes de [ID_BATCH]: um `IN` com a fila inteira estoura o limite de
     * variáveis do SQLite (999 nas versões antigas) numa playlist grande, e a consulta
     * voltaria vazia — a fila restaurada viria pela metade, sem erro nenhum na tela.
     */
    fun songsByIds(context: Context, ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val found = HashMap<Long, Song>(ids.size)
        for (batch in ids.chunked(ID_BATCH)) {
            val placeholders = batch.joinToString(",") { "?" }
            querySongs(
                context,
                "${MediaStore.Audio.Media._ID} IN ($placeholders) AND ${MediaStore.Audio.Media.IS_MUSIC}!=0",
                batch.map { it.toString() }.toTypedArray()
            ).forEach { found[it.id] = it }
        }
        return ids.mapNotNull { found[it] }
    }

    /**
     * Resolve por `DATA` (caminho do arquivo), que é a **única identidade de uma música que
     * sobrevive a trocar de aparelho** — o `MediaStore._ID` é um autoincrement do banco do
     * Android, e depois de um "Restaurar arquivos" o mesmo `/storage/emulated/0/Music/x.mp3`
     * recebe um id completamente diferente.
     *
     * Por isso o backup de playlists/favoritas guarda o caminho, e não o id: um backup
     * restaurado num outro celular não pode gravar o id da máquina de origem, sob pena de a
     * playlist apontar para faixas de outra pessoa ou simplesmente ficar vazia sem erro
     * nenhum na tela.
     *
     * Mesma ordem pedida, mesma lógica de lote de [songsByIds], mesma semântica de "quem não
     * existe mais é omitido" — caminho que não bate com nada (apagado do cartão) simplesmente
     * não volta, e quem restaura precisa报告显示 isso em vez de fingir que restaurou tudo.
     */
    fun songsByPaths(context: Context, paths: List<String>): List<Song> {
        val wanted = paths.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyList()
        val found = HashMap<String, Song>(wanted.size)
        for (batch in wanted.chunked(ID_BATCH)) {
            val placeholders = batch.joinToString(",") { "?" }
            querySongs(
                context,
                "${MediaStore.Audio.Media.DATA} IN ($placeholders) AND ${MediaStore.Audio.Media.IS_MUSIC}!=0",
                batch.toTypedArray()
            ).forEach { found[it.path] = it }
        }
        return paths.mapNotNull { found[it] }
    }

    private const val ID_BATCH = 400

    private val gospelKeywords = listOf(
        "gospel", "louvor", "adora", "adorac", "adorã", "hinario", "hino", "harpa",
        "igreja", "jesus", "cristo", "deus", "fiel", "salm", "cantico", "cântico",
        "ministerio", "ministério", "evangelh", "santo", "espiritual", "congregac",
        "congregaç", "ceia", "culto", "bencao", "benção", "milagr", "perdao", "perdão",
        "redenc", "redenç", "salvador", "ceifeiro", "espirito", "espírito", "aleluia",
        "gloria", "glória", "amem", "amém", "noiva", "altar", "consagrad", "celestial"
    )

    fun isGospel(context: Context, songs: List<Song>): BooleanArray {
        val genres = genreMap(context)
        return BooleanArray(songs.size) { i ->
            val song = songs[i]
            val haystack = "${song.title} ${song.artist} ${song.album}".lowercase()
            val genre = genres[song.id] ?: ""
            if (genre.isNotEmpty()) {
                val g = genre.lowercase()
                if (g.contains("gospel") || g.contains("religio") || g.contains("louvor") ||
                    g.contains("hino") || g.contains("adorac") || g.contains("adorã")
                ) return@BooleanArray true
            }
            gospelKeywords.any { haystack.contains(it) }
        }
    }

    private fun genreMap(context: Context): Map<Long, String> {
        val map = HashMap<Long, String>()
        runCatching {
            val genresUri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                genresUri,
                arrayOf(BaseColumns._ID, MediaStore.Audio.GenresColumns.NAME),
                null, null, null
            )?.use { genresCursor ->
                val iId = genresCursor.getColumnIndexOrThrow(BaseColumns._ID)
                val iName = genresCursor.getColumnIndexOrThrow(MediaStore.Audio.GenresColumns.NAME)
                while (genresCursor.moveToNext()) {
                    val genreId = genresCursor.getLong(iId)
                    val name = genresCursor.getString(iName) ?: continue
                    context.contentResolver.query(
                        MediaStore.Audio.Genres.Members.getContentUri("external", genreId),
                        arrayOf(MediaStore.Audio.Media._ID),
                        null, null, null
                    )?.use { members ->
                        val iAudio = members.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        while (members.moveToNext()) {
                            map.putIfAbsent(members.getLong(iAudio), name)
                        }
                    }
                }
            }
        }
        return map
    }

    @Volatile
    private var genreCache: Map<Long, String>? = null

    @Volatile
    private var genreCacheAt = 0L

    private const val GENRE_CACHE_TTL_MS = 60_000L

    /** Gênero de uma faixa (com cache curto, pois consulta várias vezes o MediaStore). */
    fun genreOf(context: Context, songId: Long): String? {
        val now = System.currentTimeMillis()
        var map = genreCache
        if (map == null || now - genreCacheAt > GENRE_CACHE_TTL_MS) {
            map = genreMap(context)
            genreCache = map
            genreCacheAt = now
        }
        return map[songId]
    }

    fun songsByAlbum(context: Context, albumId: Long): List<Song> {
        return querySongs(
            context,
            "${MediaStore.Audio.Media.ALBUM_ID}=? AND ${MediaStore.Audio.Media.IS_MUSIC}!=0",
            arrayOf(albumId.toString())
        )
    }

    fun songsByArtist(context: Context, artist: String): List<Song> {
        return querySongs(
            context,
            "${MediaStore.Audio.Media.ARTIST}=? AND ${MediaStore.Audio.Media.IS_MUSIC}!=0",
            arrayOf(artist)
        )
    }

    fun albums(context: Context): List<Album> {
        val out = mutableListOf<Album>()
        val projection = arrayOf(
            BaseColumns._ID,
            MediaStore.Audio.AlbumColumns.ALBUM,
            MediaStore.Audio.AlbumColumns.ARTIST,
            MediaStore.Audio.AlbumColumns.NUMBER_OF_SONGS
        )
        val cursor = try {
            context.contentResolver.query(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                projection, null, null, "${MediaStore.Audio.AlbumColumns.ALBUM} COLLATE NOCASE ASC"
            )
        } catch (e: Exception) {
            null
        }
        cursor?.use { c ->
            val iId = c.getColumnIndexOrThrow(BaseColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.AlbumColumns.ALBUM)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.AlbumColumns.ARTIST)
            val iNum = c.getColumnIndexOrThrow(MediaStore.Audio.AlbumColumns.NUMBER_OF_SONGS)
            while (c.moveToNext()) {
                val name = c.getString(iName) ?: continue
                if (name.isBlank()) continue
                out += Album(
                    id = c.getLong(iId),
                    name = name,
                    artist = cleanArtist(c.getString(iArtist)),
                    numSongs = c.getInt(iNum)
                )
            }
        }
        return out
    }

    fun artists(context: Context): List<Artist> {
        val out = mutableListOf<Artist>()
        val projection = arrayOf(
            BaseColumns._ID,
            MediaStore.Audio.ArtistColumns.ARTIST,
            MediaStore.Audio.ArtistColumns.NUMBER_OF_TRACKS,
            MediaStore.Audio.ArtistColumns.NUMBER_OF_ALBUMS
        )
        val cursor = try {
            context.contentResolver.query(
                MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
                projection, null, null, "${MediaStore.Audio.ArtistColumns.ARTIST} COLLATE NOCASE ASC"
            )
        } catch (e: Exception) {
            null
        }
        cursor?.use { c ->
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.ArtistColumns.ARTIST)
            val iTracks = c.getColumnIndexOrThrow(MediaStore.Audio.ArtistColumns.NUMBER_OF_TRACKS)
            val iAlbums = c.getColumnIndexOrThrow(MediaStore.Audio.ArtistColumns.NUMBER_OF_ALBUMS)
            while (c.moveToNext()) {
                val name = cleanArtist(c.getString(iName))
                out += Artist(name, c.getInt(iTracks), c.getInt(iAlbums))
            }
        }
        return out
    }

    private fun querySongs(context: Context, selection: String?, args: Array<String>?): List<Song> {
        val out = mutableListOf<Song>()
        val cursor = try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                songProjection, selection, args,
                "${MediaStore.Audio.Media.TRACK}, ${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )
        } catch (e: Exception) {
            null
        }
        cursor?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDuration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iDateAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val title = c.getString(iTitle) ?: continue
                if (title.isBlank()) continue
                out += Song(
                    id = c.getLong(iId),
                    title = title,
                    artist = cleanArtist(c.getString(iArtist)),
                    album = c.getString(iAlbum) ?: "Desconhecido",
                    albumId = c.getLong(iAlbumId),
                    durationMs = c.getLong(iDuration),
                    path = c.getString(iData) ?: "",
                    year = c.getInt(iYear),
                    dateAdded = c.getLong(iDateAdded) * 1000L
                )
            }
        }
        return applyOverrides(context, out)
    }

    private fun applyOverrides(context: Context, songs: List<Song>): List<Song> {
        val overrides = runCatching {
            PlaylistDb.get(context).metaOverrides()
        }.getOrDefault(emptyMap())
        if (overrides.isEmpty()) return songs
        return songs.map { song ->
            val meta = overrides[song.id] ?: return@map song
            song.copy(
                title = if (meta.title.isBlank()) song.title else meta.title,
                artist = if (meta.artist.isBlank()) song.artist else cleanArtist(meta.artist),
                album = if (meta.album.isBlank()) song.album else meta.album
            )
        }
    }

    private fun cleanArtist(artist: String?): String {
        val a = artist ?: return "Artista desconhecido"
        return if (a.isBlank() || a.equals(UNKNOWN, true)) "Artista desconhecido" else a
    }
}
