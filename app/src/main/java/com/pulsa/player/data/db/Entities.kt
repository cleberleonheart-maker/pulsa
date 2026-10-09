package com.pulsa.player.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * As quatro tabelas do `pulsa.db`, com o **mesmo** esquema que o `SQLiteOpenHelper` antigo
 * criou — mesmo nome de coluna, mesmo `NOT NULL`, mesmo `DEFAULT`.
 *
 * Não é detalhismo: o Room valida o esquema existente (`onValidateSchema`) quando abre o banco
 * numa migração e **aborta** se uma coluna divergir. `_id` em vez de `id`, `DEFAULT 0` faltando
 * ou `TEXT NOT NULL` virando `TEXT` são exatamente o tipo de diferença que faria o app não abrir
 * depois de um "pequeno ajuste" aqui.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id") val id: Long = 0L,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created", defaultValue = "0") val created: Long = 0L,
    @ColumnInfo(name = "auto_add", defaultValue = "0") val autoAdd: Boolean = false,
    @ColumnInfo(name = "system", defaultValue = "0") val system: Boolean = false
)

@Entity(tableName = "playlist_songs")
data class PlaylistSongEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id") val id: Long = 0L,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "song_id") val songId: Long,
    @ColumnInfo(name = "path") val path: String? = null,
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "artist") val artist: String? = null,
    @ColumnInfo(name = "album") val album: String? = null,
    @ColumnInfo(name = "album_id") val albumId: Long? = null,
    @ColumnInfo(name = "duration") val duration: Long? = null
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey
    @ColumnInfo(name = "song_id") val songId: Long,
    @ColumnInfo(name = "path") val path: String? = null,
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "artist") val artist: String? = null,
    @ColumnInfo(name = "album") val album: String? = null,
    @ColumnInfo(name = "album_id") val albumId: Long? = null,
    @ColumnInfo(name = "duration") val duration: Long? = null,
    @ColumnInfo(name = "liked_at", defaultValue = "0") val likedAt: Long = 0L
)

@Entity(tableName = "song_meta")
data class SongMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "song_id") val songId: Long,
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "artist") val artist: String? = null,
    @ColumnInfo(name = "album") val album: String? = null
)

/** Linha de `playlists` com a contagem de músicas, que é o que a lista da biblioteca mostra. */
data class PlaylistWithCount(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "songCount") val songCount: Int?,
    @ColumnInfo(name = "autoAdd") val autoAdd: Int?,
    @ColumnInfo(name = "system") val system: Int?
)

/**
 * Os sete campos que `playlist_songs`/`favorites` guardam da faixa.
 *
 * `album_id` e `duration` são `Long?` porque as colunas são nullable: um item gravado antes de a
 * coluna existir (ou sem metadados no MediaStore) tem `NULL` ali, e o `Cursor.getLong` antigo
 * devolvia `0` nesse caso — daí o `?: 0L` no mapeamento, para a lista não mudar de tamanho.
 */
data class SongRow(
    @ColumnInfo(name = "songId") val songId: Long,
    @ColumnInfo(name = "path") val path: String?,
    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "artist") val artist: String?,
    @ColumnInfo(name = "album") val album: String?,
    @ColumnInfo(name = "albumId") val albumId: Long?,
    @ColumnInfo(name = "duration") val duration: Long?
)

/**
 * Favorita com a data em que foi curtida.
 *
 * `SongRow` não tem `likedAt` porque as consultas de exibição não precisam dele, e somar uma
 * coluna ali só faria `toSong` ter que inventar um valor. O backup precisa da data real: sem
 * ela, restaurar num celular novo transformaria toda favorita antiga em "curtida agora" e a
 * "favorita do mês" passaria a devolver as faixas Restoration escolheu no dia do restore.
 */
data class FavoriteRow(
    @ColumnInfo(name = "songId") val songId: Long,
    @ColumnInfo(name = "path") val path: String?,
    @ColumnInfo(name = "likedAt") val likedAt: Long
)
