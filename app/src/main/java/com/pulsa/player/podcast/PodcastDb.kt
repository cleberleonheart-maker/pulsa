package com.pulsa.player.podcast

import android.content.Context
import com.pulsa.player.core.CrashLogger
import com.pulsa.player.data.db.FeedRef
import com.pulsa.player.data.db.PodcastDao
import com.pulsa.player.data.db.PodcastEpisodeEntity
import com.pulsa.player.data.db.PodcastFeedEntity
import com.pulsa.player.data.db.PodcastFeedRow
import com.pulsa.player.data.db.PulsaDatabase
import com.pulsa.player.model.Song

/**
 * F3 — o podcast sobre o Room, com o [FeedParser] como único ponto que entende XML.
 *
 * Assíncrono por dentro ([refresh]) e síncrono para o resto do app, como o `PlaylistDb`: quem
 * chama já está em `ThreadPool.post`, e os testes rodam sem aparelho.
 */
class PodcastDb(
    val dao: PodcastDao,
    /** Só para o log de erro do feed; `null` quando o `PodcastDb` foi montado à mão. */
    val context: Context? = null
) {

    /**
     * Assina um feed e devolve o id, **ou** reaproveita a assinatura que já existe.
     *
     * Assinar duas vezes a mesma URL não pode falhar nem duplicar: é o que acontece quando o
     * usuário cola o endereço de novo em vez de achar na lista, e um feed duplicado apareceria
     * duas vezes na tela com os mesmos episódios.
     */
    fun subscribe(podcast: Podcast): Long {
        dao.feedIdByUrl(podcast.feedUrl)?.let { existing ->
            dao.setSubscribed(existing, true)
            dao.applyFeed(
                existing, podcast.title, podcast.author, podcast.description,
                podcast.artworkUrl, System.currentTimeMillis()
            )
            return existing
        }
        val id = dao.insertFeed(
            PodcastFeedEntity(
                feedUrl = podcast.feedUrl,
                title = podcast.title,
                author = podcast.author,
                description = podcast.description,
                artworkUrl = podcast.artworkUrl,
                lastFetched = System.currentTimeMillis()
            )
        )
        // `insertFeed` é `IGNORE`, então devolve `-1` quando o `feed_url` bateu com uma linha
        // existente entre a consulta acima e o insert (duas abas do app, um refresh em paralelo).
        if (id > 0) {
            storeEpisodes(id, podcast.episodes)
            return id
        }
        dao.feedIdByUrl(podcast.feedUrl)?.let { existing ->
            dao.setSubscribed(existing, true)
            storeEpisodes(existing, podcast.episodes)
            return existing
        }
        return 0L
    }

    fun feeds(): List<PodcastFeedRow> = dao.subscribedFeeds()

    fun feed(id: Long): Podcast? {
        val row = dao.subscribedFeeds().firstOrNull { it.id == id } ?: return null
        val episodes = dao.episodes(id)
        PodcastCache.putAll(episodes.map { it.toEpisode() })
        return Podcast(
            id = row.id,
            feedUrl = row.feedUrl.orEmpty(),
            title = row.title.orEmpty(),
            author = row.author.orEmpty(),
            description = row.description.orEmpty(),
            artworkUrl = row.artworkUrl.orEmpty(),
            episodes = episodes.map(PodcastEpisodeEntity::toEpisode)
        )
    }

    /**
     * Grava os episódios de um refresh, sem duplicar e sem apagar progresso.
     *
     * `refreshEpisode` só escreve o que o feed traz, então `played`, `position_ms` e
     * `file_path` sobrevivem ao refresh — é o que permite atualizar o mesmo podcast todo dia
     * sem perder onde a pessoa parou.
     */
    fun storeEpisodes(podcastId: Long, episodes: List<Episode>): Int {
        if (podcastId <= 0L) return 0
        var added = 0
        for (ep in episodes) {
            val row = ep.toEntity(podcastId)
            val inserted = dao.insertEpisode(row)
            val id = if (inserted > 0) {
                added++
                inserted
            } else {
                // `IGNORE` devolve `-1` no conflito; sem este SELECT o `refreshEpisode` não
                // teria id para atualizar e o episódio ficaria com o título antigo para sempre.
                dao.episodeId(podcastId, ep.guid) ?: continue
            }
            dao.refreshEpisode(
                id, row.title, row.description, row.audioUrl, row.durationSec,
                row.publishedAt, row.artworkUrl, row.isVideo, row.videoPageUrl
            )
        }
        // O cache é populado **sempre**, e não só quando `added > 0`.
        //
        // O caso comum é `added == 0`: o usuário abre o podcast e dá play num episódio que
        // já estava no banco do refresh anterior. Com o `if`, esse episódio não entraria no
        // [PodcastCache], o [com.pulsa.player.playback.PlaybackService] não acharia a URL e o
        // player cairia no `Uri.fromFile("podcast:123")` — o episódio mais recente, o mais
        // provável de ser tocado, seria justamente o que não toca.
        PodcastCache.putAll(episodes)
        return added
    }

    /**
     * Aplica um feed já baixado a uma assinatura existente.
     *
     * Devolve quantos entraram, que a tela usa para dizer "3 novos episódios" em vez de só
     * recarregar — sem isso o usuário não teria como saber que a atualização fez alguma coisa.
     */
    fun apply(ref: FeedRef, podcast: Podcast, now: Long = System.currentTimeMillis()): RefreshResult {
        dao.applyFeed(
            ref.id, podcast.title, podcast.author, podcast.description, podcast.artworkUrl, now
        )
        return RefreshResult(ref.id, storeEpisodes(ref.id, podcast.episodes))
    }

    fun setFeedError(id: Long, error: String) = dao.setFeedError(id, error)

    fun feedsToRefresh(): List<FeedRef> = dao.feedsToRefresh()

    fun unsubscribedIds(): List<Long> = dao.unsubscribedIds()

    // ---- episódios -----------------------------------------------------------------------

    fun episodes(podcastId: Long): List<Episode> =
        dao.episodes(podcastId).map(PodcastEpisodeEntity::toEpisode).also(PodcastCache::putAll)

    fun recentUnplayed(limit: Int = 50): List<Episode> =
        dao.recentUnplayed(limit).map(PodcastEpisodeEntity::toEpisode).also(PodcastCache::putAll)

    fun unplayed(podcastId: Long): List<Episode> =
        dao.unplayed(podcastId).map(PodcastEpisodeEntity::toEpisode).also(PodcastCache::putAll)

    /** Um episódio, com o cache preenchido — é o caminho do clique em "tocar". */
    fun episode(id: Long): Episode? = dao.episode(id)?.toEpisode()?.also {
        PodcastCache.put(it.id, it.playableUrl)
    }

    fun setPlayed(id: Long, played: Boolean) = dao.setPlayed(id, played)

    fun setPosition(id: Long, positionMs: Long) = dao.setPosition(id, positionMs)

    fun markFinishedIfNeeded(id: Long) = dao.markFinishedIfNeeded(id)

    /**
     * Marca o arquivo local de um episódio e faz o cache passar a apontar para ele.
     *
     * O cache é atualizado aqui e não só na próxima leitura da lista: sem isso o episódio já
     * baixado continuaria abrindo a URL do feed e o download seria inútil até um app restart.
     * Como o arquivo tem precedência no [Episode.playableUrl], reler a linha já basta.
     */
    fun setFilePath(id: Long, path: String) {
        dao.setFilePath(id, path)
        dao.episode(id)?.toEpisode()?.let { PodcastCache.prepare(it) }
    }

    fun downloadedIds(limit: Int = 200): List<Long> = dao.downloadedIds(limit)

    /** Os episódios com arquivo em disco, para apagar antes de remover a assinatura. */
    fun downloadedEpisodes(podcastId: Long): List<Episode> =
        dao.downloadedEpisodes(podcastId).map(PodcastEpisodeEntity::toEpisode)

    /**
     * Remove a assinatura.
     *
     * Devolve os caminhos dos arquivos baixados **antes** de mexer no banco, porque o
     * chamador precisa apagá-los do disco: apagar a linha sozinha deixaria o arquivo órfão
     * ocupando espaço para sempre, sem nenhuma referência que permita limpá-lo depois.
     */
    fun unsubscribe(podcastId: Long): List<String> {
        val all = dao.episodes(podcastId)
        val files = all.map { it.filePath }.filter { it.isNotBlank() }
        // O cache é limpo a partir de **todos** os episódios do podcast, e não só dos
        // baixados: um episódio streaming também está no [PodcastCache], e esquecer só os
        // baixados deixaria a URL do resto presa na memória.
        PodcastCache.forget(all.map { it.id })
        // `deleteEpisodes` e não `deleteUndownloaded`: este último só apaga quem tem
        // `file_path = ''`, o que deixava os baixados no banco **depois** do feed sumir —
        // linhas órfãs ocupando espaço, sem podcast ao qual pertencem e sem como o usuário
        // descobrir para apagá-las. O arquivo em disco é do chamador, que já recebeu a lista.
        dao.deleteEpisodes(podcastId)
        dao.deleteFeed(podcastId)
        return files
    }

    /**
     * Um episódio vira item de fila.
     *
     * O `id` negativo é o que evita a colisão que o [com.pulsa.player.playback.QueueKey]
     * descreve: um `songId` do MediaStore é sempre positivo, e `songMeta(7)` não pode
     * devolver os metadados do episódio 7. `-(id)` também nunca colide com outro episódio,
     * porque o id do Room é único.
     *
     * O `albumId` fica `0` junto com o `artist` do podcast, pelo mesmo motivo do
     * [com.pulsa.player.model.Video.toSong]: o EQ e a capa não têm o que ler de um episódio, e
     * inventar valor faria o app tratar podcast como álbum de música.
     *
     * O `path` é `podcast:<id>` mesmo com o arquivo baixado — assim `isPodcast` continua
     * valendo e a posição é salva. O cache que decide é o [PodcastCache], que prefere o
     * arquivo local quando existe.
     *
     * Episódio em **vídeo** ganha o sinalizador `v` (`podcast:v<id>`), e é por isso que o
     * `path` não é uma concatenação só: sem ele o episódio entraria na fila como áudio e o
     * player tocaria a faixa de som de um vídeo sem imagem nenhuma — o mesmo sintoma que a
     * [com.pulsa.player.playback.PlaybackService] já evita para o `stream:`.
     *
     * Um `Episode` com `id == 0` (o padrão de um objeto montado à mão, não vindo do banco)
     * produziria `path = "podcast:0"`, e o [Song.podcastId] leria `0` — que o
     * [com.pulsa.player.playback.QueueKey.encode] trata como chave inválida. Chega em fila
     * por isso o `require`, em vez de deixar um item que toca com o arquivo de outro episódio.
     */
    fun toSong(podcastTitle: String, ep: Episode): Song {
        require(ep.id > 0L) { "episodio sem id do banco" }
        PodcastCache.prepare(ep)
        val path = Song.PODCAST_PREFIX +
            (if (ep.isVideo) Song.PODCAST_VIDEO_FLAG else "") + ep.id
        return Song(
            id = -ep.id,
            title = ep.title,
            artist = podcastTitle,
            album = podcastTitle,
            albumId = 0L,
            durationMs = ep.durationSec * 1000L,
            path = path,
            year = 0
        )
    }

    /** O que a tela precisa saber de um refresh: quantos entraram, e o erro se houve. */
    data class RefreshResult(
        val podcastId: Long,
        val added: Int,
        val error: String = ""
    ) {
        val ok: Boolean get() = error.isEmpty()
    }

    /**
     * Deixa o motivo da falha no log de erros do app.
     *
     * Sem isso a tela mostra "feed inválido" e o log não fala nada: quem for investigar depois
     * não tem como saber qual feed falhou nem por quê — e a única pista seria pedir para a
     * pessoa colar o endereço de novo. `null` quando o `PodcastDb` foi montado à mão (teste).
     */
    fun logFeedFailure(what: String, url: String, error: String) {
        val ctx = context ?: return
        CrashLogger.writeLog(ctx, "PODCAST: $what $url falhou -> $error")
    }

    companion object {
        @Volatile
        private var instance: PodcastDb? = null

        /**
         * O único lugar que monta o [PodcastDb].
         *
         * Sem isto cada chamador repetia `PodcastDb(PulsaDatabase.get(app).podcastDao())` — e a
         * forma como o [com.pulsa.player.playback.PlaybackService] fazia isso no `onPause` era
         * risco de `IllegalStateException` na main thread, que é o mesmo motivo do
         * [com.pulsa.player.podcast.PodcastCache] existir. O [PulsaDatabase.get] já é
         * singleton, então isto é só um atalho para a tela.
         */
        fun get(context: Context): PodcastDb {
            return instance ?: synchronized(this) {
                instance ?: PodcastDb(
                    PulsaDatabase.get(context.applicationContext).podcastDao(),
                    context.applicationContext
                ).also { instance = it }
            }
        }
    }
}

/**
 * O caminho completo do feed em background, com o erro em vez de silêncio.
 *
 * Existe para o worker e para a tela terem **um** lugar que faz a sequência inteira — subscribe,
 * gravar episódios, cache, erro. Sem ele, cada chamador repetiria a ordem e um deles
 * inevitavelmente esqueceria de popular o [PodcastCache], que é o que faz o episódio tocar.
 */
object PodcastSync {

    /**
     * Assina (ou reativa) e devolve o id, `0` quando o feed não respondeu.
     *
     * Bloqueante de verdade: a rede está aqui, não num callback. Quem chama está em
     * `ThreadPool.postNetwork` ou num `CoroutineWorker`.
     */
    fun subscribeNow(db: PodcastDb, podcast: Podcast): Long = db.subscribe(podcast)

    /**
     * Busca o feed e assina. O erro vai para `last_error` da assinatura.
     *
     * Usa o [PodcastNet.fetchFeedResult], e não o `lastHttpError`: o erro vem **junto** com o
     * resultado, então uma assinatura aberta no mesmo instante não troca a mensagem de erro
     * desta por "HTTP 404" e vice-versa.
     */
    fun subscribeByUrl(db: PodcastDb, url: String): SubscribeOutcome {
        val podcast = PodcastNet.fetchFeedResult(url).getOrElse {
            // A tela leva só a primeira linha; o log leva tudo, inclusive o começo do corpo
            // que o servidor devolveu — que é a parte que diz **de verdade** o que houve.
            val full = it.message.orEmpty()
            val message = full.lineSequence().first().ifBlank { "feed inválido" }
            // Vai para o log de erros com a URL: sem isto a única forma de achar o problema
            // seria pedir para a pessoa colar o endereço aqui de novo.
            db.logFeedFailure("assinar", url, full.ifBlank { "feed inválido" })
            return SubscribeOutcome(0L, message)
        }
        return SubscribeOutcome(db.subscribe(podcast), "")
    }

    /**
     * Atualiza uma assinatura já existente.
     *
     * Um feed que dá 404 **não** é removido da lista: a assinatura do usuário continua válida
     * para quando o podcast voltar, e apagar silenciosamente seria o jeito rápido de perder
     * meses de histórico. O que muda é o `last_error`, que a tela mostra.
     */
    fun refreshFeed(db: PodcastDb, ref: FeedRef): PodcastDb.RefreshResult {
        val url = ref.feedUrl?.trim().orEmpty()
        if (url.isEmpty()) {
            db.setFeedError(ref.id, "feed sem endereço")
            return PodcastDb.RefreshResult(ref.id, 0, "feed sem endereço")
        }
        // Mesmo motivo do [subscribeByUrl]: o `lastHttpError` é um campo compartilhado e o
        // [refreshAll] percorre vários feeds em sequência. Lê-lo aqui faria o podcast que
        // falhou com 404 ser gravado com o erro do podcast seguinte, e o `last_error` da lista
        // viraria uma mistura de mensagens que não corresponde a nenhum podcast.
        val podcast = PodcastNet.fetchFeedResult(url).getOrElse {
            val full = it.message.orEmpty()
            val err = full.lineSequence().first().ifBlank { "feed inválido" }
            db.logFeedFailure("atualizar", url, full.ifBlank { "feed inválido" })
            db.setFeedError(ref.id, err)
            return PodcastDb.RefreshResult(ref.id, 0, err)
        }
        return db.apply(ref, podcast)
    }

    /** Atualiza todas as assinaturas. Usado pelo worker. */
    fun refreshAll(db: PodcastDb): Int {
        var added = 0
        for (ref in db.feedsToRefresh()) {
            if (ref.feedUrl.isNullOrBlank()) continue
            added += refreshFeed(db, ref).added
        }
        return added
    }

    data class SubscribeOutcome(val podcastId: Long, val error: String) {
        val ok: Boolean get() = error.isEmpty() && podcastId > 0L
    }
}

private fun Episode.toEntity(podcastId: Long) = PodcastEpisodeEntity(
    podcastId = podcastId,
    guid = guid,
    title = title,
    description = description,
    audioUrl = audioUrl,
    durationSec = durationSec,
    publishedAt = publishedAt,
    artworkUrl = artworkUrl,
    isVideo = isVideo,
    videoPageUrl = videoPageUrl
)

private fun PodcastEpisodeEntity.toEpisode() = Episode(
    id = id,
    podcastId = podcastId,
    guid = guid,
    title = title,
    description = description,
    audioUrl = audioUrl,
    durationSec = durationSec,
    publishedAt = publishedAt,
    artworkUrl = artworkUrl,
    isVideo = isVideo,
    videoPageUrl = videoPageUrl,
    filePath = filePath,
    played = played,
    positionMs = positionMs
)
