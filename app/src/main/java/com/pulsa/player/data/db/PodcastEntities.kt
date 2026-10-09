package com.pulsa.player.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * F3 — as tabelas de podcast (Room v8).
 *
 * Ficam em arquivo separado de [Entities] porque as quatro de lá têm um motivo de existir
 * documentado no topo — o esquema que o `SQLiteOpenHelper` antigo criou — e elas não podem
 * ser tocadas sem quebrar a validação de esquema numa migração. Estas são novas: não têm
 * tabela antiga, e a migração 7→8 só cria.
 *
 * **Por que `podcast_episodes.guid` é `UNIQUE` e não a chave primária.** A identidade do
 * episódio é o `guid` do feed (ver [com.pulsa.player.podcast.Episode]), e ele é único por
 * feed, não no banco: dois podcasts distintos publicam episódios com o mesmo `guid` com
 * frequência suficiente para acontecer. A chave primária é um id local (`_id`), e o
 * `guid` carrega o índice único composto com o podcast — é o que permite `INSERT OR IGNORE`
 * num refresh sem duplicar nada e sem o app precisar ler antes de gravar.
 */
@Entity(
    tableName = "podcast_feeds",
    // `UNIQUE` de verdade, e não só a consulta `feedIdByUrl` antes do insert: sem o índice,
    // `INSERT OR IGNORE` do [com.pulsa.player.podcast.PodcastDb.subscribe] nunca conflita e a
    // mesma URL vira duas assinaturas sempre que duas telas assinam ao mesmo tempo.
    indices = [Index(value = ["feed_url"], unique = true)]
)
data class PodcastFeedEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id") val id: Long = 0L,
    /** Endereço do XML. `UNIQUE` (ver o índice da tabela) para a mesma URL não duplicar. */
    @ColumnInfo(name = "feed_url", defaultValue = "") val feedUrl: String,
    @ColumnInfo(name = "title", defaultValue = "") val title: String = "",
    @ColumnInfo(name = "author", defaultValue = "") val author: String = "",
    @ColumnInfo(name = "description", defaultValue = "") val description: String = "",
    @ColumnInfo(name = "artwork_url", defaultValue = "") val artworkUrl: String = "",
    /** Último refresh bem-sucedido, epoch ms. `0` = nunca lido. */
    @ColumnInfo(name = "last_fetched", defaultValue = "0") val lastFetched: Long = 0L,
    /** `E` de HTTP quando o último refresh falhou. Vazio = sem erro. */
    @ColumnInfo(name = "last_error", defaultValue = "") val lastError: String = "",
    @ColumnInfo(name = "subscribed", defaultValue = "1") val subscribed: Boolean = true
)

@Entity(
    tableName = "podcast_episodes",
    indices = [
        Index(value = ["podcast_id", "guid"], unique = true),
        Index(value = ["podcast_id", "played"]),
        Index(value = ["published_at"])
    ]
)
data class PodcastEpisodeEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id") val id: Long = 0L,
    @ColumnInfo(name = "podcast_id") val podcastId: Long,
    @ColumnInfo(name = "guid") val guid: String,
    @ColumnInfo(name = "title", defaultValue = "") val title: String = "",
    @ColumnInfo(name = "description", defaultValue = "") val description: String = "",
    @ColumnInfo(name = "audio_url") val audioUrl: String,
    @ColumnInfo(name = "duration_sec", defaultValue = "0") val durationSec: Long = 0L,
    @ColumnInfo(name = "published_at", defaultValue = "0") val publishedAt: Long = 0L,
    @ColumnInfo(name = "artwork_url", defaultValue = "") val artworkUrl: String = "",
    /**
     * `1` quando o enclosure tem `type` de vídeo — podcast em vídeo, que o `FeedParser` não separa
     * do áudio hoje. A rota é a mesma do vídeo do MediaStore.
     */
    @ColumnInfo(name = "is_video", defaultValue = "0") val isVideo: Boolean = false,
    /** Endereço do YouTube, quando o episódio é um link de canal/playlist em vez de enclosure. */
    @ColumnInfo(name = "video_page_url", defaultValue = "") val videoPageUrl: String = "",
    /** Onde o episódio baixado está. Vazio = só streaming. */
    @ColumnInfo(name = "file_path", defaultValue = "") val filePath: String = "",
    @ColumnInfo(name = "played", defaultValue = "0") val played: Boolean = false,
    /** Atraso em ms para retomar no meio. */
    @ColumnInfo(name = "position_ms", defaultValue = "0") val positionMs: Long = 0L
)

/** Feed com a contagem de episódios não ouvidos, que é o badge da lista de assinaturas. */
data class PodcastFeedRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "feed_url") val feedUrl: String?,
    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "author") val author: String?,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String?,
    @ColumnInfo(name = "last_fetched") val lastFetched: Long?,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "unplayed") val unplayed: Int?
)
