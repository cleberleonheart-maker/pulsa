package com.pulsa.player.data

import com.pulsa.player.model.Song
import java.text.Normalizer

/**
 * Os campos que uma regra de playlist automática pode casar.
 *
 * Guardados como texto (e não enum) porque vão para o banco — um enum só funcionaria depois de
 * um mapeador, e a coluna precisa ser legível por qualquer consulta que veja o `.db` de fora.
 */
object AutoRuleField {
    const val TITLE = "title"
    const val ALBUM = "album"
    const val GENRE = "genre"
    const val ARTIST = "artist"
}

/** Uma regra: "esta faixa entra se <campo> contiver <value>". */
data class AutoRule(
    val id: Long,
    val playlistId: Long,
    val field: String,
    val value: String
)

/**
 * Casa uma faixa contra as regras de uma playlist automática.
 *
 * Puro de propósito: não toca em `Context`, `MediaStore` nem banco, então os testes rodam na
 * JVM sem Android (a mesma razão dos testes de `M3u`/`PlaylistBackup`).
 */
object AutoRules {

    /** Minúsculas e sem acento, para "Álbum"/"album" e "Opening"/"opening" casarem. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    /**
     * A faixa casa com **uma** regra.
     *
     * Um valor vazio nunca casa — senão a regra entraria no `when` com o campo e casaria tudo,
     * o oposto do que a pessoa quis dizer.
     */
    fun matches(rule: AutoRule, song: Song, genre: String?): Boolean {
        val target = when (rule.field) {
            AutoRuleField.ALBUM -> song.album
            AutoRuleField.GENRE -> genre
            AutoRuleField.ARTIST -> song.artist
            else -> song.title
        } ?: return false
        val needle = normalize(rule.value)
        if (needle.isEmpty()) return false
        return normalize(target).contains(needle)
    }

    /**
     * A faixa entra se casar com **qualquer** regra (OU).
     *
     * É o comportamento esperado de "quero minhas músicas de anime num lugar só": o título com
     * "opening" **ou** o álbum com "anime" **ou** o gênero marcado — não as três juntas, que
     * quase nunca aconteceriam e deixaria a lista vazia.
     */
    fun matchesAny(rules: List<AutoRule>, song: Song, genre: String?): Boolean =
        rules.any { matches(it, song, genre) }

    /** Só as faixas que casam com alguma regra. `genreOf` resolve o gênero pelo id da faixa. */
    fun filter(rules: List<AutoRule>, songs: List<Song>, genreOf: (Long) -> String?): List<Song> =
        if (rules.isEmpty()) emptyList()
        else songs.filter { matchesAny(rules, it, genreOf(it.id)) }
}
