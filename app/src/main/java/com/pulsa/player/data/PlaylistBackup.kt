package com.pulsa.player.data

import android.content.Context
import com.pulsa.player.core.Permissions
import com.pulsa.player.data.db.PulsaDatabase
import com.pulsa.player.dj.DjCommander
import com.pulsa.player.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * Playlists, favoritas e correções de nome dentro do arquivo de backup.
 *
 * O resto do backup (`DjLearn`, `DjMemory`, EQ, 3D) mora em `SettingsActivity` há mais tempo e
 * funciona; não mexi naquilo. O que estava faltando era exatamente isto — o arquivo que a
 * pessoa levava para o celular novo carregava o aprendizado da Virgin e nada mais. Perder as
 * playlists e as favoritas era perder o trabalho manual dela.
 *
 * **Tudo aqui tem duas identidades, e o caminho é só a primeira.** O `MediaStore._ID` é
 * autoincrement do banco do Android: dois aparelhos com a mesma música na mesma pasta dão ids
 * diferentes, e depois de um "Restaurar arquivos" até o mesmo aparelho troca o id. Gravar o id
 * da máquina de origem produziria uma playlist que "restaurou" com sucesso, sem erro, e aponta
 * para faixas erradas ou para nada. O caminho resolve de volta — **no mesmo aparelho**. Para
 * o outro, quem resolve é a identidade portátil de (artista, título), em [SongKey].
 *
 * E o que não volta é dito. [report] separa o que casou do que não casou, e a tela mostra os
 * dois: um restore que engole faixa ausente e mostra "tudo certo" faz a pessoa acreditar que
 * está tudo lá, e só descobrir weeks depois, abrindo a playlist e vendo que ela está pela
 * metade.
 */
object PlaylistBackup {

    /** Versão do bloco novo. O `backupVersion` geral sobe junto, mas o bloco se versiona à parte. */
    private const val BLOCK_VERSION = 1

    /** Quantos caminhos faltando levar no relatório. Mais que isso vira parede de texto no diálogo. */
    private const val MISSING_EXAMPLES = 6

    /**
     * Como o bloco é aplicado.
     *
     * A diferença importa porque a **nuvem** e o **arquivo** têm regras diferentes: o arquivo
     * é uma restauração (a pessoa escolheu aquele arquivo), e a nuvem é uma cópia de outra
     * máquina — que pode ter playlist que a pessoa aqui já renomeou, favorita que ela aqui
     * organizou e correção de nome que ela aqui ajustou. Puxar a nuvem por cima apagaria
     * trabalho manual.
     */
    enum class Mode {
        /** O arquivo manda: o que está no aparelho é completado pelo que veio. */
        RESTORE,

        /** Os dois lados somam: nada do aparelho é apagado, reordenado ou sobrescrito. */
        MERGE
    }

    /**
     * Uma faixa como ela viaja: caminho **e** identidade portátil.
     *
     * O caminho resolve no mesmo aparelho; a chave resolve no outro. Os dois vão juntos porque
     * o caminho é o acerto exato e o mais barato, e a chave é o que salva a faixa quando o
     * aparelho é outro.
     */
    data class SongRef(
        val path: String,
        val key: String = "",
        val durationMs: Long = 0L
    )

    /** Uma playlist como ela viaja: nome + as faixas, na ordem em que a pessoa montou. */
    data class ParsedPlaylist(val name: String, val refs: List<SongRef>) {
        /** Só os caminhos: é o formato antigo, e é o que o web player lê. */
        val paths: List<String> get() = refs.map { it.path }
    }

    fun export(context: Context, db: PlaylistDb): JSONObject {
        val playlists = db.backupPlaylists()
        val favorites = db.backupFavorites()

        // A biblioteca inteira, uma vez. Ela é quem sabe o artista e o título de cada caminho,
        // e é daí que sai a identidade portátil. Sem esta varredura o backup continuaria sendo
        // só um conjunto de caminhos — que não resolve em outro aparelho.
        val all = Library.allSongs(context)
        val byPath = all.associateBy { it.path }
        val byId = all.associateBy { it.id }

        val plArray = JSONArray()
        playlists.forEach { bp ->
            val items = JSONArray()
            bp.paths.forEach { path ->
                val song = byPath[path]
                items.put(
                    JSONObject().apply {
                        put("path", path)
                        put("key", song?.let { SongKey.of(it) } ?: "")
                        put("dur", song?.durationMs ?: 0L)
                    }
                )
            }
            plArray.put(
                JSONObject().apply {
                    put("name", bp.name)
                    // `paths` continua lá: quem lê o blob (o web player) usa esta lista, e o
                    // formato antigo do backup precisa continuar legível.
                    put("paths", JSONArray().apply { bp.paths.forEach { put(it) } })
                    put("items", items)
                }
            )
        }

        val favArray = JSONArray()
        favorites.forEach { bf ->
            val song = byPath[bf.path]
            favArray.put(
                JSONObject().apply {
                    put("path", bf.path)
                    put("likedAt", bf.likedAt)
                    put("key", song?.let { SongKey.of(it) } ?: "")
                    put("dur", song?.durationMs ?: 0L)
                }
            )
        }

        // Correção manual de nome: o `song_meta` guarda "essa faixa de tag quebrada na minha
        // coleção se chama X". Sem isto no arquivo, o usuário que renomeou 30 faixas com tag
        // errada perde o trabalho no celular novo e a biblioteca volta ao nome sujo.
        //
        // O `song_meta` é chaveado por `songId`, e o backup precisa do caminho — que é a
        // única forma de resolver de volta. Uma varredura da biblioteca monta o índice todo;
        // o caminho lento seria uma consulta ao MediaStore *por override*.
        val metaArray = JSONArray()
        db.metaOverrides().values.forEach { m ->
            val song = byId[m.songId]
            metaArray.put(
                JSONObject().apply {
                    put("title", m.title)
                    put("artist", m.artist)
                    put("album", m.album)
                    // `song_meta` guarda o id da máquina de origem; quem lê de volta é quem
                    // sabe resolvê-lo em caminho.
                    put("path", song?.path ?: "")
                    put("key", song?.let { SongKey.of(it) } ?: "")
                    put("dur", song?.durationMs ?: 0L)
                }
            )
        }

        return JSONObject().apply {
            put("blockVersion", BLOCK_VERSION)
            put("playlists", plArray)
            put("favorites", favArray)
            put("songMeta", metaArray)
            // Os marcadores de podcast moram no mesmo bloco: são trabalho manual da pessoa,
            // do mesmo jeito que a playlist é, e perder um ao trocar de aparelho é a mesma
            // coisa que perder a playlist.
            put("podcastBookmarks", PodcastBookmarkBackup.export(podcastDao(context)))
            // E o progresso dos episódios: o "onde parei" de cada podcast, para o episódio não
            // recomeçar do zero no aparelho novo.
            put("podcastProgress", PodcastProgressBackup.export(podcastDao(context)))
        }
    }

    /** O DAO de podcast: o bloco de biblioteca é o único que sabe dos dois lados do app. */
    private fun podcastDao(context: Context) =
        PulsaDatabase.get(context.applicationContext).podcastDao()

    /**
     * Aplica o bloco. Devolve sempre um [Report] — nunca lança — para que um bloco corrompido
     * num arquivo antigo não derrube o restore inteiro (que já tem o aprendizado da Virgin
     * para salvar antes de dar erro).
     */
    fun import(
        context: Context,
        db: PlaylistDb,
        block: JSONObject?,
        mode: Mode = Mode.RESTORE
    ): Report {
        if (block == null) return Report()

        // Ler o arquivo e decidir o que fazer com ele são coisas distintas: o parse é puro e é
        // o único pedaço testável sem aparelho (o resto é MediaStore e banco). Também é onde
        // mora a tolerância a arquivo velho — um `pulsa-backup.json` de antes do bloco passa
        // por aqui e sai como lista vazia em vez de estourar.
        val parsed = parse(block)

        // Os marcadores de podcast são resolvidos pelo feed e pelo `guid` do episódio, e não
        // pelo caminho da música — por isso vão **antes** da trava de permissão de mídia: um
        // celular sem acesso às músicas ainda assim tem podcast, e o marcador volta igual.
        val bookmarks = PodcastBookmarkBackup.import(podcastDao(context), parsed.podcastBookmarks)

        // O progresso dos episódios entra na mesma volta: o arquivo manda, mas a nuvem só
        // preenche o que está vazio — o progresso local não pode regredir para o de outra
        // máquina só porque o push é mais velho.
        val progress = PodcastProgressBackup.import(
            podcastDao(context),
            parsed.podcastProgress,
            overwrite = mode == Mode.RESTORE
        )

        // Sem permissão de mídia a resolução volta vazia — e "40 músicas não encontradas no
        // aparelho" seria mentira: o arquivo está inteiro, o app é que não pode ler a
        // biblioteca. O relatório separa os dois casos, senão quem restaura num celular sem
        // permissão conclui que o backup estragou.
        if (!Permissions.hasAccess(context)) {
            return Report(
                mediaPermissionMissing = true,
                bookmarks = bookmarks.restored,
                bookmarksMissing = bookmarks.missing,
                progress = progress.restored,
                progressMissing = progress.missing
            )
        }
        var songsOk = 0
        var favOk = 0
        var metaSaved = 0

        // Uma resolução para o arquivo inteiro: `songsByPaths` vai ao MediaStore em lotes, e
        // resolver uma vez por playlist transformaria um backup com 30 playlists em 30
        // varreduras da biblioteca completa.
        val allPaths = (parsed.playlists.flatMap { it.refs.map { ref -> ref.path } } +
            parsed.favorites.map { it.path } + parsed.meta.map { it.path })
            .filter { it.isNotBlank() }
            .distinct()
        val resolver = Resolver(context, allPaths)

        // As listas de faltantes são separadas de propósito: o relatório distingue "música que
        // estava na playlist e não voltou" de "favorita que não voltou", porque a segunda
        // significa que o like foi perdido, e a primeira só que a playlist veio pela metade.
        val missingInPlaylists = mutableListOf<String>()
        val missingFavorites = mutableListOf<String>()

        parsed.playlists.forEach { bp ->
            val resolved = bp.refs.map { it to resolver.find(it) }
            val songs = resolved.mapNotNull { it.second }
            // Na fusão, a playlist pode casar pelo **conteúdo** e não pelo nome: quem renomeou
            // a playlist aqui não pode ver a versão antiga do nome ressurgir da nuvem.
            val id = if (mode == Mode.MERGE) {
                mergePlaylistId(db, bp, songs, localPlaylistKeys(db))
            } else {
                db.playlistIdForRestore(bp.name)
            }
            resolved.forEach { (ref, song) ->
                if (song == null) {
                    missingInPlaylists += ref.path.ifBlank { ref.key }
                } else {
                    db.addSong(id, song)
                    // Conta como presente mesmo que `addSong` devolva false: ele devolve false
                    // quando a faixa já está lá, e restaurar o mesmo arquivo duas vezes é o
                    // caso normal, não um erro.
                    songsOk++
                }
            }
        }

        parsed.favorites.forEach { bf ->
            val song = resolver.find(SongRef(bf.path, bf.key, bf.durationMs))
            if (song == null) {
                missingFavorites += bf.path.ifBlank { bf.key }
            } else {
                db.restoreFavorite(song, bf.likedAt)
                favOk++
            }
        }

        val localMeta = db.metaOverrides()
        parsed.meta.forEach { m ->
            // O `song_meta` é chaveado por `songId`, e o `songId` gravado aqui é o da máquina de
            // origem. O caminho tem que virar id *desta* máquina, e só o MediaStore sabe qual é —
            // gravar o id de origem faria a correção de nome cair em outra faixa, ou em nenhuma.
            val song = resolver.find(SongRef(m.path, m.key, m.durationMs)) ?: return@forEach
            // Na fusão, a correção de nome que **esta** máquina tem é a verdade: a nuvem pode
            // estar com a versão de antes da pessoa renomear. Sobrescrever aqui seria perder o
            // trabalho manual do aparelho que a pessoa está usando.
            if (mode == Mode.MERGE && localMeta.containsKey(song.id)) return@forEach
            db.saveMetaOverride(song.id, m.title, m.artist, m.album)
            metaSaved++
        }

        val playlistsOk = parsed.playlists.size

        return Report(
            playlists = playlistsOk,
            songs = songsOk,
            songsMissing = missingInPlaylists.size,
            favorites = favOk,
            favoritesMissing = missingFavorites.size,
            metaOverrides = metaSaved,
            bookmarks = bookmarks.restored,
            bookmarksMissing = bookmarks.missing,
            progress = progress.restored,
            progressMissing = progress.missing,
            missingExamples = (missingInPlaylists + missingFavorites)
                .take(MISSING_EXAMPLES)
                .map(::fileName)
        )
    }

    /**
     * Lê o bloco do arquivo. Puro de propósito: sem Context, sem banco, sem MediaStore — é o
     * pedaço que dá para testar de verdade num backup malformado.
     *
     * Tolerante a propósito também. Quem restaura está podendo ter um arquivo de uma versão
     * anterior do app, de um restore interrompido pela metade, ou editado à mão. Cada campo
     * ausente vira lista vazia em vez de exceção, e o chamador decide o que fazer — inclusive
     * o caso "o arquivo é do Pulsa mas não tinha nada para restaurar", que é diferente de
     * "o arquivo não é do Pulsa".
     */
    fun parse(block: JSONObject?): Parsed {
        if (block == null) return Parsed()

        val playlists = mutableListOf<ParsedPlaylist>()
        block.optJSONArray("playlists")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) continue
                playlists += ParsedPlaylist(name, refsOf(obj))
            }
        }

        val favorites = mutableListOf<BackupFavorite>()
        block.optJSONArray("favorites")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val path = obj.optString("path").trim()
                val key = obj.optString("key").trim()
                if (path.isEmpty() && key.isEmpty()) continue
                // `likedAt` ausente ou zero: cai em "agora". Melhor do que pular a favorita,
                // porque perder a data só desloca a "favorita do mês", não apaga o like.
                val likedAt = obj.optLong("likedAt", 0L).takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                favorites += BackupFavorite(
                    path = path,
                    likedAt = likedAt,
                    key = key,
                    durationMs = obj.optLong("dur", 0L)
                )
            }
        }

        val meta = mutableListOf<BackupMeta>()
        block.optJSONArray("songMeta")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val path = obj.optString("path").trim()
                val key = obj.optString("key").trim()
                if (path.isEmpty() && key.isEmpty()) continue
                meta += BackupMeta(
                    path = path,
                    title = obj.optString("title"),
                    artist = obj.optString("artist"),
                    album = obj.optString("album"),
                    key = key,
                    durationMs = obj.optLong("dur", 0L)
                )
            }
        }

        val bookmarks = PodcastBookmarkBackup.parse(block.optJSONArray("podcastBookmarks"))
        val progress = PodcastProgressBackup.parse(block.optJSONArray("podcastProgress"))

        return Parsed(playlists, favorites, meta, bookmarks, progress)
    }

    /**
     * As faixas de uma playlist, na ordem.
     *
     * O formato novo (`items`, com caminho + identidade portátil) e o antigo (`paths`, só
     * caminho) são lidos aqui, e o antigo vira uma lista de faixas sem chave — que ainda
     * restaura, porque o caminho resolve no mesmo aparelho.
     */
    private fun refsOf(obj: JSONObject): List<SongRef> {
        val items = obj.optJSONArray("items")
        if (items != null) {
            val refs = mutableListOf<SongRef>()
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                val path = item.optString("path").trim()
                val key = item.optString("key").trim()
                if (path.isEmpty() && key.isEmpty()) continue
                refs += SongRef(path, key, item.optLong("dur", 0L))
            }
            return refs
        }
        val refs = mutableListOf<SongRef>()
        obj.optJSONArray("paths")?.let { parr ->
            for (j in 0 until parr.length()) {
                parr.optString(j).takeIf { it.isNotBlank() }?.let { refs += SongRef(it) }
            }
        }
        return refs
    }

    data class Parsed(
        val playlists: List<ParsedPlaylist> = emptyList(),
        val favorites: List<BackupFavorite> = emptyList(),
        val meta: List<BackupMeta> = emptyList(),
        val podcastBookmarks: List<PodcastBookmarkBackup.BackupBookmark> = emptyList(),
        val podcastProgress: List<PodcastProgressBackup.BackupProgress> = emptyList()
    )

    /**
     * A playlist local onde esta da nuvem deve entrar.
     *
     * Primeiro pelo nome (o caso normal). Depois pelo **conteúdo**: quem renomeou a playlist
     * aqui não pode ver a versão antiga do nome ressurgir da nuvem — e a playlist renomeada
     * tem as mesmas músicas. O limite de metade evita o caso de duas playlists diferentes
     * (uma de rock, outra de pagode) que compartilham uma faixa qualquer.
     */
    private fun mergePlaylistId(
        db: PlaylistDb,
        bp: ParsedPlaylist,
        cloudSongs: List<Song>,
        localKeys: Map<Long, Set<String>>
    ): Long {
        val norm = DjCommander.norm(bp.name)
        db.playlists().firstOrNull { !it.system && DjCommander.norm(it.name) == norm }
            ?.let { return it.id }
        if (cloudSongs.isEmpty()) return db.playlistIdForRestore(bp.name)
        val cloudKeys = cloudSongs.map { SongKey.of(it) }.toHashSet()
        for (local in db.playlists()) {
            if (local.system || local.count == 0) continue
            val keys = localKeys[local.id] ?: continue
            if (keys.isEmpty()) continue
            val overlap = cloudKeys.count { it in keys }
            if (overlap == 0) continue
            val smaller = minOf(cloudKeys.size, keys.size)
            if (overlap * 2 >= smaller) return local.id
        }
        return db.playlistIdForRestore(bp.name)
    }

    /**
     * As chaves das faixas de cada playlist local, calculadas **uma vez** por fusão.
     *
     * A comparação de conteúdo é o que evita a playlist duplicada depois de uma renomeação, e
     * ela custa uma consulta por playlist local. Calculando uma vez só, o custo não cresce
     * com o número de playlists que vêm da nuvem.
     */
    private fun localPlaylistKeys(db: PlaylistDb): Map<Long, Set<String>> {
        val out = HashMap<Long, Set<String>>()
        for (playlist in db.playlists()) {
            if (playlist.system || playlist.count == 0) continue
            out[playlist.id] = db.songs(playlist.id).map { SongKey.of(it) }.toHashSet()
        }
        return out
    }

    /**
     * Acha a faixa local que corresponde a uma referência do backup.
     *
     * A escada é: caminho (mesmo aparelho) → identidade portátil com duração de desempate →
     * identidade portátil sozinha → só o título, e só quando não há dúvida. O índice sobre a
     * biblioteca inteira só é montado **se** algum item precisar dele: quem restaura no mesmo
     * aparelho resolve tudo pelo caminho e não paga a varredura.
     */
    private class Resolver(private val context: Context, paths: List<String>) {

        private val byPath = HashMap<String, Song>()
        private var index: SongIndex? = null

        init {
            if (paths.isNotEmpty()) {
                for (song in Library.songsByPaths(context, paths)) byPath[song.path] = song
            }
        }

        fun find(ref: SongRef): Song? {
            if (ref.path.isNotBlank()) byPath[ref.path]?.let { return it }
            if (ref.key.isBlank()) return null
            val idx = index ?: Library.allSongs(context)
                .let { SongIndex(it) }
                .also { index = it }
            idx.find(ref.key, ref.durationMs)?.let { return it }
            // Último degrau: faixa **sem** artista na tag. Com artista preenchido, o degrau
            // acima já resolve — e título sozinho seria adivinhação entre duas faixas de
            // artistas diferentes com o mesmo nome.
            if (SongKey.artistOf(ref.key).isBlank()) {
                idx.findByTitle(SongKey.titleOf(ref.key), ref.durationMs)?.let { return it }
            }
            return null
        }
    }

    /** `/storage/emulated/0/Music/Foo/bar.mp3` vira `bar.mp3`: o caminho inteiro não ajuda ninguém. */
    private fun fileName(path: String): String = path.substringAfterLast('/')

    data class Report(
        val playlists: Int = 0,
        val songs: Int = 0,
        val songsMissing: Int = 0,
        val favorites: Int = 0,
        val favoritesMissing: Int = 0,
        val metaOverrides: Int = 0,
        /** Marcadores de podcast que voltaram. */
        val bookmarks: Int = 0,
        /** Marcadores de podcast que não voltaram: o episódio não está neste aparelho. */
        val bookmarksMissing: Int = 0,
        /** Episódios de podcast que voltaram com o progresso de onde a pessoa parou. */
        val progress: Int = 0,
        /** Episódios cujo progresso não voltou: o episódio não está neste aparelho. */
        val progressMissing: Int = 0,
        val missingExamples: List<String> = emptyList(),
        val mediaPermissionMissing: Boolean = false
    ) {
        val changed: Boolean
            get() = playlists > 0 || songs > 0 || favorites > 0 || metaOverrides > 0 ||
                bookmarks > 0 || progress > 0
        val missing: Int get() = songsMissing + favoritesMissing
    }
}
