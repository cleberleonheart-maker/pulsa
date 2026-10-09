package com.pulsa.player.data

import android.content.Context
import com.pulsa.player.core.Permissions
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
 * **Tudo aqui é por caminho, nunca por id.** O `MediaStore._ID` é autoincrement do banco do
 * Android: dois aparelhos com a mesma música na mesma pasta dão ids diferentes, e depois de um
 * "Restaurar arquivos" até o mesmo aparelho troca o id. Gravar o id da máquina de origem
 * produziria uma playlist que "restaurou" com sucesso, sem erro, e aponta para faixas
 * erradas ou para nada. O caminho resolve de volta.
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

    fun export(context: Context, db: PlaylistDb): JSONObject {
        val playlists = db.backupPlaylists()
        val favorites = db.backupFavorites()

        val plArray = JSONArray()
        playlists.forEach { bp ->
            plArray.put(
                JSONObject().apply {
                    put("name", bp.name)
                    put("paths", JSONArray().apply { bp.paths.forEach { put(it) } })
                }
            )
        }

        val favArray = JSONArray()
        favorites.forEach { bf ->
            favArray.put(
                JSONObject().apply {
                    put("path", bf.path)
                    put("likedAt", bf.likedAt)
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
        val byId = Library.allSongs(context).associateBy { it.id }
        val metaArray = JSONArray()
        db.metaOverrides().values.forEach { m ->
            metaArray.put(
                JSONObject().apply {
                    put("title", m.title)
                    put("artist", m.artist)
                    put("album", m.album)
                    // `song_meta` guarda o id da máquina de origem; quem lê de volta é quem
                    // sabe resolvê-lo em caminho.
                    put("path", byId[m.songId]?.path ?: "")
                }
            )
        }

        return JSONObject().apply {
            put("blockVersion", BLOCK_VERSION)
            put("playlists", plArray)
            put("favorites", favArray)
            put("songMeta", metaArray)
        }
    }

    /**
     * Aplica o bloco. Devolve sempre um [Report] — nunca lança — para que um bloco corrompido
     * num arquivo antigo não derrube o restore inteiro (que já tem o aprendizado da Virgin
     * para salvar antes de dar erro).
     */
    fun import(context: Context, db: PlaylistDb, block: JSONObject?): Report {
        if (block == null) return Report()

        // Sem permissão de mídia a resolução volta vazia — e "40 músicas não encontradas no
        // aparelho" seria mentira: o arquivo está inteiro, o app é que não pode ler a
        // biblioteca. O relatório separa os dois casos, senão quem restaura num celular sem
        // permissão conclui que o backup estragou.
        if (!Permissions.hasAccess(context)) return Report(mediaPermissionMissing = true)

        // Ler o arquivo e decidir o que fazer com ele são coisas distintas: o parse é puro e é
        // o único pedaço testável sem aparelho (o resto é MediaStore e banco). Também é onde
        // mora a tolerância a arquivo velho — um `pulsa-backup.json` de antes do bloco passa
        // por aqui e sai como lista vazia em vez de estourar.
        val parsed = parse(block)

        var songsOk = 0
        var favOk = 0
        var metaSaved = 0

        // Uma resolução para o arquivo inteiro: `songsByPaths` vai ao MediaStore em lotes, e
        // resolver uma vez por playlist transformaria um backup com 30 playlists em 30
        // varreduras da biblioteca completa.
        val allPaths = (parsed.playlists.flatMap { it.paths } + parsed.favorites.map { it.path })
            .filter { it.isNotBlank() }
            .distinct()
        val resolved = Library.songsByPaths(context, allPaths).associateBy { it.path }

        // As listas de faltantes são separadas de propósito: o relatório distingue "música que
        // estava na playlist e não voltou" de "favorita que não voltou", porque a segunda
        // significa que o like foi perdido, e a primeira só que a playlist veio pela metade.
        val missingInPlaylists = mutableListOf<String>()
        val missingFavorites = mutableListOf<String>()

        parsed.playlists.forEach { bp ->
            val id = db.playlistIdForRestore(bp.name)
            bp.paths.forEach { path ->
                val song = resolved[path]
                if (song == null) {
                    missingInPlaylists += path
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
            val song = resolved[bf.path]
            if (song == null) {
                missingFavorites += bf.path
            } else {
                db.restoreFavorite(song, bf.likedAt)
                favOk++
            }
        }

        parsed.meta.forEach { m ->
            // O `song_meta` é chaveado por `songId`, e o `songId` gravado aqui é o da máquina de
            // origem. O caminho tem que virar id *desta* máquina, e só o MediaStore sabe qual é —
            // gravar o id de origem faria a correção de nome cair em outra faixa, ou em nenhuma.
            val song = resolved[m.path] ?: return@forEach
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

        val playlists = mutableListOf<BackupPlaylist>()
        block.optJSONArray("playlists")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) continue
                val paths = mutableListOf<String>()
                obj.optJSONArray("paths")?.let { parr ->
                    for (j in 0 until parr.length()) {
                        parr.optString(j).takeIf { it.isNotBlank() }?.let { paths += it }
                    }
                }
                playlists += BackupPlaylist(name, paths)
            }
        }

        val favorites = mutableListOf<BackupFavorite>()
        block.optJSONArray("favorites")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val path = obj.optString("path").trim()
                if (path.isEmpty()) continue
                // `likedAt` ausente ou zero: cai em "agora". Melhor do que pular a favorita,
                // porque perder a data só desloca a "favorita do mês", não apaga o like.
                val likedAt = obj.optLong("likedAt", 0L).takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                favorites += BackupFavorite(path, likedAt)
            }
        }

        val meta = mutableListOf<BackupMeta>()
        block.optJSONArray("songMeta")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val path = obj.optString("path").trim()
                if (path.isEmpty()) continue
                meta += BackupMeta(
                    path = path,
                    title = obj.optString("title"),
                    artist = obj.optString("artist"),
                    album = obj.optString("album")
                )
            }
        }

        return Parsed(playlists, favorites, meta)
    }

    data class Parsed(
        val playlists: List<BackupPlaylist> = emptyList(),
        val favorites: List<BackupFavorite> = emptyList(),
        val meta: List<BackupMeta> = emptyList()
    )


    /** `/storage/emulated/0/Music/Foo/bar.mp3` vira `bar.mp3`: o caminho inteiro não ajuda ninguém. */
    private fun fileName(path: String): String = path.substringAfterLast('/')

    data class Report(
        val playlists: Int = 0,
        val songs: Int = 0,
        val songsMissing: Int = 0,
        val favorites: Int = 0,
        val favoritesMissing: Int = 0,
        val metaOverrides: Int = 0,
        val missingExamples: List<String> = emptyList(),
        val mediaPermissionMissing: Boolean = false
    ) {
        val changed: Boolean get() = playlists > 0 || songs > 0 || favorites > 0 || metaOverrides > 0
        val missing: Int get() = songsMissing + favoritesMissing
    }
}
