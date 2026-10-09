package com.pulsa.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PlaylistDao {

    @Query("SELECT COUNT(*) FROM playlists WHERE system = 1")
    fun countSystem(): Int

    @Query("SELECT _id FROM playlists WHERE system = 1 AND auto_add = 0")
    fun systemWithoutAutoAdd(): List<Long>

    @Query("UPDATE playlists SET auto_add = 1 WHERE _id = :id")
    fun setAutoAddFor(id: Long)

    @Insert
    fun insert(entity: PlaylistEntity): Long

    @Query("SELECT _id, name, auto_add FROM playlists WHERE auto_add = 1")
    fun autoAddRows(): List<AutoRow>

    @Query("SELECT _id, name, auto_add FROM playlists WHERE _id = :id LIMIT 1")
    fun rowById(id: Long): AutoRow?

    @Query("SELECT system FROM playlists WHERE _id = :id LIMIT 1")
    fun systemFlag(id: Long): Int?

    @Query("SELECT auto_add FROM playlists WHERE _id = :id LIMIT 1")
    fun autoAddFlag(id: Long): Int?

    @Query(
        "SELECT p._id AS id, p.name AS name, COUNT(ps._id) AS songCount, " +
            "p.auto_add AS autoAdd, p.system AS system " +
            "FROM playlists p LEFT JOIN playlist_songs ps ON ps.playlist_id = p._id " +
            "GROUP BY p._id ORDER BY p.system DESC, p.created ASC"
    )
    fun playlistsWithCount(): List<PlaylistWithCount>

    @Query("UPDATE playlists SET auto_add = :value WHERE _id = :id")
    fun setAutoAdd(id: Long, value: Boolean)

    @Query("UPDATE playlists SET name = :name WHERE _id = :id")
    fun rename(id: Long, name: String)

    /**
     * Procura pelo nome, ignorando caixa e acento, para o restore não criar "Batidinhas" do
     * lado de "batidinhas". O `NOCASE` do SQLite não ignora acento, então a comparação final
     * de verdade acontece no [PlaylistBackup] — isto só evita o caso óbvio de duplicata.
     */
    @Query("SELECT _id FROM playlists WHERE name = :name COLLATE NOCASE LIMIT 1")
    fun idByName(name: String): Long?

    /**
     * Só as do usuário. As de sistema (Gospel, Variadas, Novidades, Clássicos) **não entram no
     * backup**: elas nascem sozinhas em `ensureSpecialPlaylists`, e o nome delas é traduzido.
     * Exportá-las gravaria no backup o nome em português do aparelho de origem, que em outro
     * idioma viraria uma playlist de sistema extra e errada.
     */
    @Query("SELECT _id FROM playlists WHERE system = 0 ORDER BY created ASC")
    fun userPlaylistIds(): List<Long>

    @Query("DELETE FROM playlists WHERE _id = :id")
    fun deletePlaylist(id: Long)
}

@Dao
interface PlaylistSongDao {

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlist_id = :playlistId AND song_id = :songId")
    fun countOf(playlistId: Long, songId: Long): Int

    @Insert
    fun insert(entity: PlaylistSongEntity): Long

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId AND song_id = :songId")
    fun remove(playlistId: Long, songId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId")
    fun deletePlaylistSongs(playlistId: Long)

    @Query("DELETE FROM playlist_songs WHERE song_id = :songId")
    fun removeFromAll(songId: Long)

    @Query(
        "SELECT song_id AS songId, path, title, artist, album, album_id AS albumId, " +
            "duration AS duration FROM playlist_songs WHERE playlist_id = :playlistId ORDER BY _id ASC"
    )
    fun songsOf(playlistId: Long): List<SongRow>

    /**
     * Só os caminhos, na ordem em que foram inseridos — o que o backup precisa guardar.
     *
     * Sai do `_id ASC` e não de um `ORDER BY title`: a ordem de inserção é a ordem que o
     * usuário montou, que é o que importa numa playlist (dupla, mix, trilha de corrida).
     */
    @Query("SELECT path FROM playlist_songs WHERE playlist_id = :playlistId ORDER BY _id ASC")
    fun pathsOf(playlistId: Long): List<String>

    /**
     * Reescreve título/artista/álbum em todas as ocorrências da faixa.
     *
     * A versão antiga rodava dois `UPDATE` com o mesmo `ContentValues` e depois gravava o
     * override em `song_meta`; aqui o `song_meta` é `INSERT OR REPLACE` na transação do
     * chamador. Vale deixar os dois pontos num método só: um dia alguém lembra de atualizar a
     * tabela e esquece a outra, e a correção do nome aparece em um lugar e não no outro.
     */
    @Query(
        "UPDATE playlist_songs SET title = :title, artist = :artist, album = :album " +
            "WHERE song_id = :songId"
    )
    fun updateMetaEverywhere(songId: Long, title: String?, artist: String?, album: String?)
}

@Dao
interface FavoriteDao {

    @Query("SELECT COUNT(*) FROM favorites WHERE song_id = :songId")
    fun countOf(songId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE song_id = :songId")
    fun delete(songId: Long)

    @Query(
        "SELECT song_id AS songId, path, title, artist, album, album_id AS albumId, " +
            "duration AS duration FROM favorites WHERE liked_at >= :since ORDER BY liked_at DESC"
    )
    fun likedSince(since: Long): List<SongRow>

    @Query(
        "SELECT song_id AS songId, path, title, artist, album, album_id AS albumId, " +
            "duration AS duration FROM favorites ORDER BY song_id ASC"
    )
    fun all(): List<SongRow>

    /** Só o que o backup precisa de cada favorita: qual faixa, e quando foi curtida. */
    @Query("SELECT song_id AS songId, path, liked_at AS likedAt FROM favorites ORDER BY song_id ASC")
    fun allWithLikedAt(): List<FavoriteRow>

    @Query("UPDATE favorites SET title = :title, artist = :artist, album = :album WHERE song_id = :songId")
    fun updateMeta(songId: Long, title: String?, artist: String?, album: String?)
}

@Dao
interface SongMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entity: SongMetaEntity)

    @Query("DELETE FROM song_meta WHERE song_id = :songId")
    fun delete(songId: Long)

    /**
     * Sem `AS`: aqui volta a entidade inteira, e o Room casa pelo nome da coluna (`song_id`).
     * Os POJOs de leitura (`SongRow`, `PlaylistWithCount`) é que precisam de alias.
     */
    @Query("SELECT * FROM song_meta")
    fun all(): List<SongMetaEntity>
}

/** As duas colunas que o filtro de playlist automática precisa ler. */
data class AutoRow(
    @androidx.room.ColumnInfo(name = "_id") val id: Long,
    @androidx.room.ColumnInfo(name = "name") val name: String?,
    @androidx.room.ColumnInfo(name = "auto_add") val autoAdd: Int?
)
