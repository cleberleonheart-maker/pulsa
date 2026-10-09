package com.pulsa.player.data

import android.content.Context
import com.pulsa.player.data.db.FavoriteEntity
import com.pulsa.player.data.db.PlaylistEntity
import com.pulsa.player.data.db.PlaylistSongEntity
import com.pulsa.player.data.db.PulsaDatabase
import com.pulsa.player.data.db.SongMetaEntity
import com.pulsa.player.data.db.SongRow
import com.pulsa.player.dj.DjCommander
import com.pulsa.player.model.Playlist
import com.pulsa.player.model.Song
import com.pulsa.player.model.SongMeta

/**
 * Uma playlist como vai para o arquivo de backup: nome + caminho de cada faixa.
 *
 * Sem o id de propósito, e sem o `Song` inteiro. O id é do MediaStore e muda de aparelho; o
 * `Song` carrega `year`/`dateAdded` que o restore não consegue honourar de qualquer jeito. O
 * caminho é a única informação que resolve de volta numa biblioteca diferente.
 */
data class BackupPlaylist(val name: String, val paths: List<String>)

/** Uma favorita como vai para o backup: caminho + quando foi curtida. */
data class BackupFavorite(val path: String, val likedAt: Long)

/**
 * Correção manual de nome como vai para o backup: caminho + os três campos corrigidos.
 *
 * Chaveada por caminho, como as outras duas. O `song_meta` no banco é chaveado por `songId`
 * porque é o que o `Library.applyOverrides` consegue casar sem custo, mas no arquivo isso
 * seria um id de outra máquina — ver [PlaylistBackup].
 */
data class BackupMeta(val path: String, val title: String, val artist: String, val album: String)

/**
 * Playlists, favoritas e correção de metadados, agora sobre Room.
 *
 * A **API pública não mudou** — os mesmos métodos, as mesmas assinaturas — porque 19 arquivos
 * chamam isto e o ganho do Room é justamente não obrigar todos a se preocuparem com a troca. O que
 * mudou é o motor: as quatro tabelas e o esquema são idênticos aos do `SQLiteOpenHelper`
 * (version 6), então quem tinha playlist e favorita continua com elas.
 *
 * Os DAOs chamam `playlistDao`/`songDao`/etc. e não `playlists`/`songs` de propósito: existem
 * métodos públicos com esses mesmos nomes, e o sombreamento só apareceria quando alguém
 * plessar o nome errado num lugar longe daqui.
 *
 * As transações usam `runInTransaction`, e não o `withTransaction` do room-ktx: aquele é
 * `suspend`, e esta API é síncrona por decisão própria (19 arquivos, incluindo o `onCreate` de
 * activities, chamam na thread principal). Virar `suspend` seria reescrever todos eles.
 */
class PlaylistDb private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val db: PulsaDatabase by lazy { PulsaDatabase.get(appContext) }

    private val playlistDao get() = db.playlistDao()
    private val songDao get() = db.playlistSongDao()
    private val favoriteDao get() = db.favoriteDao()
    private val metaDao get() = db.songMetaDao()

    fun isSystem(playlistId: Long): Boolean = playlistDao.systemFlag(playlistId) == 1

    fun ensureSpecialPlaylists(context: Context) {
        db.runInTransaction {
            if (playlistDao.countSystem() == 0) {
                val names = arrayOf(
                    context.getString(com.pulsa.player.R.string.gospel_playlist),
                    context.getString(com.pulsa.player.R.string.variadas_playlist),
                    context.getString(com.pulsa.player.R.string.smart_new_playlist),
                    context.getString(com.pulsa.player.R.string.smart_classics_playlist)
                )
                val created = System.currentTimeMillis()
                names.forEach { name ->
                    playlistDao.insert(
                        PlaylistEntity(
                            name = name,
                            created = created,
                            autoAdd = true,
                            system = true
                        )
                    )
                }
            } else {
                // As playlists de sistema nasceram com `auto_add = 0` na v4 e só passaram a
                // preencher sozinhas depois disso. O conserto é por linha e não um UPDATE geral
                // porque uma playlist que o usuário criou não é do sistema.
                playlistDao.systemWithoutAutoAdd().forEach { playlistDao.setAutoAddFor(it) }
            }
        }
    }

    fun syncAutoSongs(context: Context, songs: List<Song>) {
        val targets = playlistDao.autoAddRows()
        if (targets.isEmpty()) return
        val gospelName = context.getString(com.pulsa.player.R.string.gospel_playlist)
        val variadasName = context.getString(com.pulsa.player.R.string.variadas_playlist)
        val smartNewName = context.getString(com.pulsa.player.R.string.smart_new_playlist)
        val smartClassicsName = context.getString(com.pulsa.player.R.string.smart_classics_playlist)
        val isGospel = Library.isGospel(context, songs)
        targets.forEach { row ->
            val filtered = when (row.name) {
                gospelName -> songs.filterIndexed { i, _ -> isGospel[i] }
                variadasName -> songs.filterIndexed { i, _ -> !isGospel[i] }
                smartNewName -> songs.filter { it.year >= 2020 }
                smartClassicsName -> songs.filter { it.year > 0 && it.year < 2000 }
                else -> songs
            }
            addAllMissingSongs(row.id, filtered)
        }
    }

    fun syncAutoPlaylist(context: Context, playlistId: Long, songs: List<Song>) {
        val row = playlistDao.rowById(playlistId) ?: return
        if (row.autoAdd != 1) return
        val gospelName = context.getString(com.pulsa.player.R.string.gospel_playlist)
        val variadasName = context.getString(com.pulsa.player.R.string.variadas_playlist)
        val isGospel = Library.isGospel(context, songs)
        val filtered = when (row.name) {
            gospelName -> songs.filterIndexed { i, _ -> isGospel[i] }
            variadasName -> songs.filterIndexed { i, _ -> !isGospel[i] }
            else -> songs
        }
        addAllMissingSongs(playlistId, filtered)
    }

    fun playlists(): List<Playlist> = playlistDao.playlistsWithCount().map {
        Playlist(
            id = it.id,
            name = it.name ?: "",
            count = it.songCount ?: 0,
            autoAdd = it.autoAdd == 1,
            system = it.system == 1
        )
    }

    fun isAutoAdd(playlistId: Long): Boolean = playlistDao.autoAddFlag(playlistId) == 1

    fun setAutoAdd(playlistId: Long, value: Boolean) {
        playlistDao.setAutoAdd(playlistId, value)
    }

    fun addAllMissingSongs(playlistId: Long, songs: List<Song>) {
        db.runInTransaction {
            songs.forEach { addSong(playlistId, it) }
        }
    }

    fun isFavorite(songId: Long): Boolean = favoriteDao.countOf(songId) > 0

    fun setFavorite(song: Song, value: Boolean) {
        if (value) {
            favoriteDao.put(
                FavoriteEntity(
                    songId = song.id,
                    path = song.path,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    albumId = song.albumId,
                    duration = song.durationMs,
                    likedAt = System.currentTimeMillis()
                )
            )
        } else {
            favoriteDao.delete(song.id)
        }
    }

    fun favoritesLikedSince(sinceMs: Long): List<Song> = favoriteDao.likedSince(sinceMs).map(::toSong)

    fun favorites(): List<Song> = favoriteDao.all().map(::toSong)

    fun removeFavorite(songId: Long) {
        favoriteDao.delete(songId)
    }

    fun createPlaylist(name: String): Long = playlistDao.insert(
        PlaylistEntity(name = name.trim(), created = System.currentTimeMillis())
    )

    fun renamePlaylist(id: Long, name: String) {
        playlistDao.rename(id, name.trim())
    }

    fun deletePlaylist(id: Long) {
        db.runInTransaction {
            songDao.deletePlaylistSongs(id)
            playlistDao.deletePlaylist(id)
        }
    }

    fun addSong(playlistId: Long, song: Song): Boolean {
        if (songDao.countOf(playlistId, song.id) > 0) return false
        songDao.insert(
            PlaylistSongEntity(
                playlistId = playlistId,
                songId = song.id,
                path = song.path,
                title = song.title,
                artist = song.artist,
                album = song.album,
                albumId = song.albumId,
                duration = song.durationMs
            )
        )
        return true
    }

    fun removeSong(playlistId: Long, songId: Long) {
        songDao.remove(playlistId, songId)
    }

    fun removeSongFromAll(songId: Long) {
        songDao.removeFromAll(songId)
    }

    fun updateSongMeta(songId: Long, title: String, artist: String, album: String) {
        // Os três lugares andam juntos de propósito: o nome corrigido precisa aparecer na
        // playlist, na favorita e no override, senão a correção vale em metade da biblioteca.
        db.runInTransaction {
            favoriteDao.updateMeta(songId, title, artist, album)
            songDao.updateMetaEverywhere(songId, title, artist, album)
            metaDao.put(SongMetaEntity(songId = songId, title = title, artist = artist, album = album))
        }
    }

    fun saveMetaOverride(songId: Long, title: String, artist: String, album: String) {
        metaDao.put(SongMetaEntity(songId = songId, title = title, artist = artist, album = album))
    }

    fun clearMetaOverride(songId: Long) {
        metaDao.delete(songId)
    }

    fun metaOverrides(): Map<Long, SongMeta> {
        val out = HashMap<Long, SongMeta>()
        metaDao.all().forEach {
            out[it.songId] = SongMeta(
                songId = it.songId,
                title = it.title ?: "",
                artist = it.artist ?: "",
                album = it.album ?: ""
            )
        }
        return out
    }

    fun songMeta(songId: Long): SongMeta? = metaOverrides()[songId]

    // ---- backup -----------------------------------------------------------------------
    //
    // Estas quatro não são a API de usuário: existem para o `PlaylistBackup` e ficam aqui
    // porque morar no DAO significaria alcançar o `favoriteDao`/`playlistDao` private de fora.
    // O backup é a única coisa que precisa ler caminho + data de curtida, e precisa das duas
    // coisas de uma vez só.

    /** Playlists do usuário, com os caminhos das faixas na ordem em que ele as adicionou. */
    fun backupPlaylists(): List<BackupPlaylist> {
        val out = mutableListOf<BackupPlaylist>()
        db.runInTransaction {
            playlistDao.userPlaylistIds().forEach { id ->
                val row = playlistDao.rowById(id) ?: return@forEach
                out += BackupPlaylist(
                    name = row.name ?: "",
                    paths = songDao.pathsOf(id)
                )
            }
        }
        return out
    }

    /** Favoritas com a data real da curtida, para a "favorita do mês" não virar "de hoje". */
    fun backupFavorites(): List<BackupFavorite> =
        favoriteDao.allWithLikedAt().map { row ->
            BackupFavorite(path = row.path ?: "", likedAt = row.likedAt)
        }

    /**
     * Reaproveita a linha da favorita quando o usuário já tinha curtido, para preservar a data
     * original em vez de carimbar "agora" — senão restaurar um backup de meses atrás faria
     * `favoritesLikedSince` devolver as faixas antigas como se fossem novas e a "favorita do
     * mês" ficaria errada até o fim do mês.
     */
    fun restoreFavorite(song: Song, likedAt: Long) {
        val existing = favoriteDao.allWithLikedAt().firstOrNull { it.songId == song.id }
        favoriteDao.put(
            FavoriteEntity(
                songId = song.id,
                path = song.path,
                title = song.title,
                artist = song.artist,
                album = song.album,
                albumId = song.albumId,
                duration = song.durationMs,
                likedAt = existing?.likedAt ?: likedAt
            )
        )
    }

    /**
     * Acha a playlist pelo nome, ignorando caixa e acento, ou cria uma nova.
     *
     * Casa por nome em vez de criar sempre: restaurar o mesmo arquivo duas vezes não pode
     * duplicar "Batidinhas" umas vinte vezes — backup é a coisa que a pessoa reinstala e restaura
     * mais de uma vez, e duplicar tudo silenciosamente é pior do que não fazer nada.
     */
    fun playlistIdForRestore(name: String): Long {
        val clean = name.trim()
        playlistDao.idByName(clean)?.let { return it }
        val norm = DjCommander.norm(clean)
        playlists().firstOrNull { !it.system && DjCommander.norm(it.name) == norm }
            ?.let { return it.id }
        return createPlaylist(clean)
    }

    fun songs(playlistId: Long): List<Song> = songDao.songsOf(playlistId).map(::toSong)

    /**
     * As colunas nullable viram `Long?`, e o `Cursor.getLong` de antes devolvia `0` no lugar de
     * `NULL` — sem o `?: 0L` aqui as faixas sem álbum sumiriam da lista (id 0 não casa com
     * nada), em vez de aparecerem como desconhecidas.
     */
    private fun toSong(row: SongRow) = Song(
        id = row.songId,
        title = row.title ?: "",
        artist = row.artist ?: "Artista desconhecido",
        album = row.album ?: "Desconhecido",
        albumId = row.albumId ?: 0L,
        durationMs = row.duration ?: 0L,
        path = row.path ?: "",
        year = 0
    )

    companion object {
        @Volatile
        private var instance: PlaylistDb? = null

        fun get(context: Context): PlaylistDb {
            return instance ?: synchronized(this) {
                instance ?: PlaylistDb(context.applicationContext).also { instance = it }
            }
        }
    }
}
