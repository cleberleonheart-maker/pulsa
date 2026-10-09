package com.pulsa.player.data

import com.pulsa.player.data.PlaylistBackup.parse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O `parse` é a única parte do backup de biblioteca testável sem aparelho: não usa Context, nem
 * MediaStore, nem banco. E é justamente onde mora a compatibilidade com arquivo velho, que é a
 * coisa que quebra em silêncio — o `optString` do `JSONObject` devolve `""` para chave
 * ausente, e um item com nome vazio que passasse adiante criaria uma playlist sem nome na tela
 * da pessoa.
 */
class PlaylistBackupTest {

    private fun block(vararg pairs: Pair<String, Any>): JSONObject {
        val o = JSONObject()
        pairs.forEach { (k, v) -> o.put(k, v) }
        return o
    }

    @Test
    fun blocoAusenteViraListaVazia() {
        // É o caminho do backup v1 (o arquivo antigo, sem este bloco). Restaurar tem de seguir
        // normal e restaurar o que tinha — não estourar e perder o aprendizado da Virgin junto.
        val parsed = parse(null)
        assertTrue(parsed.playlists.isEmpty())
        assertTrue(parsed.favorites.isEmpty())
        assertTrue(parsed.meta.isEmpty())
    }

    @Test
    fun lePlaylistsComCaminhosNaOrdem() {
        val json = JSONObject().put(
            "playlists",
            org.json.JSONArray().put(
                JSONObject()
                    .put("name", "Batidinhas")
                    .put(
                        "paths",
                        org.json.JSONArray()
                            .put("/sdcard/Music/a.mp3")
                            .put("/sdcard/Music/b.mp3")
                            .put("/sdcard/Music/c.mp3")
                    )
            )
        )
        val pl = parse(json).playlists.single()
        assertEquals("Batidinhas", pl.name)
        // A ordem é a ordem em que a pessoa montou a playlist. Um parse que devolvesse por
        // título ou por caminho devolveria a dupla do jeito errado.
        assertEquals(
            listOf("/sdcard/Music/a.mp3", "/sdcard/Music/b.mp3", "/sdcard/Music/c.mp3"),
            pl.paths
        )
    }

    @Test
    fun playlistSemNomeEDescartada() {
        val json = JSONObject().put(
            "playlists",
            org.json.JSONArray().put(
                JSONObject().put("name", "   ").put("paths", org.json.JSONArray())
            )
        )
        // Uma playlist sem nome apareceria na lista como uma linha em branco que o usuário não
        // consegue renomear nem apagar por engano — e o restore contaria ela como restaurada.
        assertTrue(parse(json).playlists.isEmpty())
    }

    @Test
    fun caminhoEmBrancoEDescartado() {
        val json = JSONObject().put(
            "playlists",
            org.json.JSONArray().put(
                JSONObject().put("name", "X").put(
                    "paths",
                    org.json.JSONArray().put("/a.mp3").put("").put("   ")
                )
            )
        )
        assertEquals(listOf("/a.mp3"), parse(json).playlists.single().paths)
    }

    @Test
    fun favoritaSemDataCaiEmAgora() {
        val json = JSONObject().put(
            "favorites",
            org.json.JSONArray().put(JSONObject().put("path", "/a.mp3"))
        )
        val fav = parse(json).favorites.single()
        assertEquals("/a.mp3", fav.path)
        // Perder a data só desloca a "favorita do mês"; perder a favorita inteira é bem pior.
        assertTrue(fav.likedAt > 0L)
    }

    @Test
    fun favoritaComDataZeroCaiEmAgora() {
        val json = JSONObject().put(
            "favorites",
            org.json.JSONArray().put(
                JSONObject().put("path", "/a.mp3").put("likedAt", 0L)
            )
        )
        assertTrue(parse(json).favorites.single().likedAt > 0L)
    }

    @Test
    fun favoritaPreservaADataOriginal() {
        val quando = 1_700_000_000_000L
        val json = JSONObject().put(
            "favorites",
            org.json.JSONArray().put(
                JSONObject().put("path", "/a.mp3").put("likedAt", quando)
            )
        )
        // É o que impede um backup de três meses atrás de virar "curtida hoje" e falsificar a
        // favorita do mês logo depois de restaurar.
        assertEquals(quando, parse(json).favorites.single().likedAt)
    }

    @Test
    fun favoritaSemCaminhoEDescartada() {
        val json = JSONObject().put(
            "favorites",
            org.json.JSONArray().put(JSONObject().put("path", ""))
        )
        assertTrue(parse(json).favorites.isEmpty())
    }

    @Test
    fun correcaoDeNomePreservaOsTresCampos() {
        val json = JSONObject().put(
            "songMeta",
            org.json.JSONArray().put(
                JSONObject()
                    .put("path", "/a.mp3")
                    .put("title", "Nome Certo")
                    .put("artist", "Artista Certo")
                    .put("album", "Álbum Certo")
            )
        )
        val m = parse(json).meta.single()
        assertEquals("/a.mp3", m.path)
        assertEquals("Nome Certo", m.title)
        assertEquals("Artista Certo", m.artist)
        assertEquals("Álbum Certo", m.album)
    }

    @Test
    fun correcaoSemCaminhoEDescartada() {
        // É o caso de uma música que o usuário deletou do aparelho depois de renomear: a
        // correção não tem onde ser aplicada e não pode virar entrada órfã.
        val json = JSONObject().put(
            "songMeta",
            org.json.JSONArray().put(JSONObject().put("title", "Sem caminho"))
        )
        assertTrue(parse(json).meta.isEmpty())
    }

    @Test
    fun arquivoTruncadoNaoEstoura() {
        // Um restore interrompido pela metade, ou um arquivo editado à mão: tem que devolver
        // o que der para ler, não Launchar exceção no meio do restore das outras seções.
        val json = JSONObject()
            .put("playlists", org.json.JSONArray().put(JSONObject().put("name", "X")))
            .put("favorites", "isto não é um array")
            .put("songMeta", JSONObject())
        val parsed = parse(json)
        assertEquals(1, parsed.playlists.size)
        assertTrue(parsed.favorites.isEmpty())
        assertTrue(parsed.meta.isEmpty())
    }

    @Test
    fun chavesDesconhecidasNaoQuebram() {
        // Um arquivo de uma versão futura do app pode ter campos a mais. Descartar o que não se
        // conhece é o que permite este restore continuar funcionando daqui a um ano.
        val json = block(
            "playlists" to org.json.JSONArray().put(
                block(
                    "name" to "X",
                    "paths" to org.json.JSONArray().put("/a.mp3"),
                    "capaBase64" to "AAAA",
                    "criadaEm" to 123L
                )
            ),
            "favoritos" to org.json.JSONArray()
        )
        val parsed = parse(json)
        assertEquals(1, parsed.playlists.size)
        assertEquals(listOf("/a.mp3"), parsed.playlists.single().paths)
        assertTrue(parsed.favorites.isEmpty())
    }

    @Test
    fun relatorioVazioNaoMudaNada() {
        val r = PlaylistBackup.Report()
        // É o que decide se o diálogo diz "não tinha nada para restaurar" em vez de mentir que
        // restaurou — a diferença entre um arquivo inútil e um restore bem-sucedido.
        assertTrue(!r.changed)
        assertEquals(0, r.missing)
    }

    @Test
    fun relatorioContaFaltanteDePlaylistEFavorita() {
        val r = PlaylistBackup.Report(
            playlists = 2,
            songs = 10,
            songsMissing = 3,
            favorites = 4,
            favoritesMissing = 1
        )
        assertEquals(4, r.missing)
        assertTrue(r.changed)
    }
}
