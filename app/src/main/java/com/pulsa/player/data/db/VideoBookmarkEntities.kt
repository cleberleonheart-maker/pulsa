package com.pulsa.player.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Marcador do usuário num **vídeo local** — o "voltar neste minuto" que o vídeo não publica.
 *
 * O podcast já tinha marcador ([PodcastBookmarkEntity]); o vídeo não, e o motivo era
 * simples: o podcast tem identidade de fácil (feed + guid), e o vídeo local tem o caminho
 * do arquivo. É esse caminho que vira a chave aqui.
 *
 * **Por que o caminho e não o id do MediaStore:** o id do MediaStore é local e muda quando
 * o arquivo é reindexado (ou quando o app é reinstalado); o caminho é o que o app já usa
 * para abrir o arquivo. Se o arquivo for movido, o marcador fica para trás — que é o mesmo
 * comportamento do resto do app, que também guarda o caminho.
 *
 * O índice único em `(video_key, position_ms)` é o que impede dois marcadores no mesmo
 * instante: tocar duas vezes no botão não pode criar uma lista com itens idênticos.
 */
@Entity(
    tableName = "video_bookmarks",
    indices = [
        Index(value = ["video_key", "position_ms"], unique = true),
        Index(value = ["video_key"])
    ]
)
data class VideoBookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id") val id: Long = 0L,
    @ColumnInfo(name = "video_key") val videoKey: String,
    @ColumnInfo(name = "position_ms", defaultValue = "0") val positionMs: Long = 0L,
    @ColumnInfo(name = "title", defaultValue = "") val title: String = "",
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = 0L
)

/**
 * Os marcadores de um vídeo.
 *
 * A chave (`video_key`) é o caminho do arquivo; a lista vem em ordem de tempo, que é a
 * ordem em que a folha de capítulos mostra.
 */
@Dao
interface VideoBookmarkDao {

    @Query("SELECT * FROM video_bookmarks WHERE video_key = :key ORDER BY position_ms ASC")
    fun bookmarks(key: String): List<VideoBookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: VideoBookmarkEntity): Long

    @Query("DELETE FROM video_bookmarks WHERE _id = :id")
    fun delete(id: Long)
}
