package com.pulsa.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * F3 — podcast.
 *
 * **Por que os métodos não suspendem.** O resto das tabelas é síncrono e vive dentro de
 * `ThreadPool.post`; o podcast segue a mesma regra em vez de introduzir corrotina só aqui,
 * para não haver dois jeitos de esperar o mesmo banco no app.
 */
@Dao
interface PodcastDao {

    // ---- feeds ---------------------------------------------------------------------------

    @Query("SELECT _id FROM podcast_feeds WHERE feed_url = :url LIMIT 1")
    fun feedIdByUrl(url: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertFeed(entity: PodcastFeedEntity): Long

    @Query(
        "UPDATE podcast_feeds SET title = :title, author = :author, description = :description, " +
            "artwork_url = :artwork, last_fetched = :now, last_error = '' WHERE _id = :id"
    )
    fun applyFeed(id: Long, title: String, author: String, description: String, artwork: String, now: Long)

    @Query("UPDATE podcast_feeds SET last_error = :error WHERE _id = :id")
    fun setFeedError(id: Long, error: String)

    @Query("UPDATE podcast_feeds SET subscribed = :value WHERE _id = :id")
    fun setSubscribed(id: Long, value: Boolean)

    @Query("DELETE FROM podcast_feeds WHERE _id = :id")
    fun deleteFeed(id: Long)

    /**
     * Feeds com a contagem de não ouvido, do mais recente para o mais antigo.
     *
     * O `last_error` vem junto porque um feed que dá 404 tem que mostrar o erro **na lista** —
     * sem isso o usuário vê a assinatura e conclui que o app simplesmente parou de atualizar.
     */
    @Query(
        "SELECT f._id AS id, f.feed_url AS feed_url, f.title AS title, f.author AS author, " +
            "f.description AS description, f.artwork_url AS artwork_url, " +
            "f.last_fetched AS last_fetched, f.last_error AS last_error, " +
            "(SELECT COUNT(*) FROM podcast_episodes e " +
            "  WHERE e.podcast_id = f._id AND e.played = 0) AS unplayed " +
            "FROM podcast_feeds f WHERE f.subscribed = 1 " +
            "ORDER BY f.last_fetched DESC, f.title ASC"
    )
    fun subscribedFeeds(): List<PodcastFeedRow>

    /** Os que o refresh automático deve atualizar. */
    @Query("SELECT _id, feed_url FROM podcast_feeds WHERE subscribed = 1")
    fun feedsToRefresh(): List<FeedRef>

    /** Os que o app mostra como "não assinados" na busca — os salvos e removidos da lista. */
    @Query("SELECT _id FROM podcast_feeds WHERE subscribed = 0")
    fun unsubscribedIds(): List<Long>

    // ---- episódios -----------------------------------------------------------------------

    /**
     * `INSERT OR IGNORE` com índice único em `(podcast_id, guid)` é o que impede a lista de
     * crescer sozinha: rodar o refresh dez vezes seguidas dá o mesmo resultado que rodar uma.
     * O `RETURNING _id` pega o id do episódio que **realmente** entrou — no conflito não há
     * linha nova, e quem chama precisa do id existente para depois atualizar `played`/`position`
     * sem apagar o progresso do usuário.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEpisode(entity: PodcastEpisodeEntity): Long

    @Query("SELECT _id FROM podcast_episodes WHERE podcast_id = :podcastId AND guid = :guid LIMIT 1")
    fun episodeId(podcastId: Long, guid: String): Long?

    /**
     * Atualiza só o que o feed traz.
     *
     * `file_path`, `played` e `position_ms` ficam **fora** de propósito: são estado local. Se
     * o refresh sobrescrevesse `played`, ouvir um episódio voltaria como não ouvido a cada
     * atualização — e como o refresh é automático, o usuário perderia o progresso sozinho.
     */
    @Query(
        "UPDATE podcast_episodes SET title = :title, description = :description, " +
            "audio_url = :audioUrl, duration_sec = :durationSec, published_at = :publishedAt, " +
            "artwork_url = :artwork, is_video = :isVideo, video_page_url = :videoPage " +
            "WHERE _id = :id"
    )
    fun refreshEpisode(
        id: Long,
        title: String,
        description: String,
        audioUrl: String,
        durationSec: Long,
        publishedAt: Long,
        artwork: String,
        isVideo: Boolean,
        videoPage: String
    )

    @Query(
        "SELECT * FROM podcast_episodes WHERE podcast_id = :podcastId " +
            "ORDER BY published_at DESC, _id DESC"
    )
    fun episodes(podcastId: Long): List<PodcastEpisodeEntity>

    /** Não ouvidos primeiro, e dentro deles os mais recentes — que é como a tela abre. */
    @Query(
        "SELECT * FROM podcast_episodes WHERE played = 0 " +
            "ORDER BY published_at DESC, _id DESC LIMIT :limit"
    )
    fun recentUnplayed(limit: Int): List<PodcastEpisodeEntity>

    @Query(
        "SELECT * FROM podcast_episodes WHERE podcast_id = :podcastId AND played = 0 " +
            "ORDER BY published_at DESC, _id DESC"
    )
    fun unplayed(podcastId: Long): List<PodcastEpisodeEntity>

    /** Um episódio só, para a tela de detalhe e para retomar do `position_ms`. */
    @Query("SELECT * FROM podcast_episodes WHERE _id = :id LIMIT 1")
    fun episode(id: Long): PodcastEpisodeEntity?

    @Query("SELECT _id FROM podcast_episodes WHERE file_path != '' ORDER BY _id DESC LIMIT :limit")
    fun downloadedIds(limit: Int): List<Long>

    @Query("UPDATE podcast_episodes SET file_path = :path WHERE _id = :id")
    fun setFilePath(id: Long, path: String)

    @Query("UPDATE podcast_episodes SET played = :played WHERE _id = :id")
    fun setPlayed(id: Long, played: Boolean)

    @Query("UPDATE podcast_episodes SET position_ms = :position WHERE _id = :id")
    fun setPosition(id: Long, position: Long)

    /**
     * Marca ouvido quando chegou a 90% ([com.pulsa.player.podcast.Episode.finished]).
     *
     * O UPDATE vem com a condição no WHERE, e não com um `if` antes: o tick de posição e o
     * "marcar ouvido" da tela podem chegar juntos, e ler-depois-escrever deixaria os dois
     * sobrescrevendo o outro.
     */
    @Query(
        "UPDATE podcast_episodes SET played = 1 WHERE _id = :id AND played = 0 " +
            "AND duration_sec > 0 AND position_ms > 0 AND position_ms >= duration_sec * 900"
    )
    fun markFinishedIfNeeded(id: Long)

    @Query("SELECT * FROM podcast_episodes WHERE podcast_id = :podcastId AND file_path != ''")
    fun downloadedEpisodes(podcastId: Long): List<PodcastEpisodeEntity>

    @Query("DELETE FROM podcast_episodes WHERE podcast_id = :podcastId")
    fun deleteEpisodes(podcastId: Long)
}

/** Os dois campos que o refresh automático precisa, para não carregar o feed inteiro. */
data class FeedRef(
    @androidx.room.ColumnInfo(name = "_id") val id: Long,
    @androidx.room.ColumnInfo(name = "feed_url") val feedUrl: String?
)
