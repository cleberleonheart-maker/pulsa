package com.pulsa.player.data

import com.pulsa.player.model.Song
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * A identidade de uma faixa que sobrevive à mudança de aparelho.
 *
 * **Por que o caminho não serve.** O backup e a nuvem guardavam o caminho do arquivo
 * (`/storage/emulated/0/Music/...`), e o caminho é do aparelho: o celular novo tem a pasta
 * `Música`, o outro tem `Music`, e o mesmo arquivo pode estar em `/sdcard/Music/Foo/bar.mp3`
 * num e em `/storage/emulated/0/Music/foo/bar.mp3` no outro. O id do MediaStore não salva
 * mais — é autoincrement do banco do Android e muda de máquina para máquina.
 *
 * **Por que artista + título.** O hash do conteúdo seria o ideal, mas custa ler o arquivo
 * inteiro de cada faixa da biblioteca só para montar o backup — e a biblioteca de quem usa o
 * Pulsa tem milhares de faixas. `(artista, título)` é o que a pessoa reconhece como "a mesma
 * música", e é o que o `M3u` já usa para casar faixas entre aparelhos
 * (`data/M3u.kt` → `resolve`).
 *
 * **Por que a duração fica fora da chave.** A mesma música pode ter duração levemente
 * diferente em duas máquinas: um ripping com tag errada, um AAC re-encodado pelo celular. Se
 * a duração estivesse dentro da chave, as duas não casariam nunca. Ela fica ao lado, como
 * **desempate**: quando há duas faixas com o mesmo artista e título, a duração diz qual é a
 * certa ([SongIndex.find]).
 */
object SongKey {

    /**
     * Separador interno entre artista e título.
     *
     * `\u001F` (unit separator) não aparece em nome de artista nem de título, então uma faixa
     * chamada "AC/DC" não vira duas metades como aconteceria com `|`.
     */
    private const val SEP = '\u001F'

    /** Chave portátil: artista e título normalizados, separados por [SEP]. */
    fun of(artist: String, title: String): String = norm(artist) + SEP + norm(title)

    fun of(song: Song): String = of(song.artist, song.title)

    /**
     * Normalização de nome: minúsculas, sem acento, espaços dobrados viram um.
     *
     * O acento é removido porque o mesmo arquivo pode estar com a tag em NFC num aparelho e
     * em NFD no outro — e "Banda" e "Banda" são a mesma banda, não duas. O espaço interno
     * também: "Jay  Chou" e "Jay Chou" são a mesma pessoa com a tag digitada de um jeito e de
     * outro.
     */
    fun norm(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        val decomposed = Normalizer.normalize(trimmed.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            val type = Character.getType(ch)
            if (type != Character.NON_SPACING_MARK.toInt() &&
                type != Character.COMBINING_SPACING_MARK.toInt() &&
                type != Character.ENCLOSING_MARK.toInt()
            ) {
                sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Artista de uma chave, já normalizado. */
    fun artistOf(key: String): String = key.substringBefore(SEP)

    /** Título de uma chave, já normalizado. */
    fun titleOf(key: String): String = key.substringAfter(SEP)

    /**
     * Chave só de título — o último degrau da resolução.
     *
     * Existe para a faixa que não tem artista preenchido: uma tag vazia de artista é comum em
     * coleção montada por download, e "Desconhecido" não pode ser o nome de ninguém.
     */
    fun titleKey(title: String): String = norm(title)
}

/**
 * Índice de resolução: dado um backup (ou a nuvem), acha a faixa local que corresponde.
 *
 * A resolução é uma escada, do mais forte ao mais fraco:
 *
 * 1. **caminho igual** — mesmo aparelho, mesma pasta: acerto imediato;
 * 2. **artista + título + duração perto** — a mesma faixa, com a duração como desempate;
 * 3. **artista + título** — a mesma faixa com a tag de duração diferente;
 * 4. **só o título, e só se for única** — faixa sem artista na tag.
 *
 * O último degrau é restrito de propósito: duas faixas com o mesmo título e artistas
 * diferentes ("Yesterday" dos Beatles e dos Guns) não podem ser trocadas uma pela outra — o
 * caso de uso do degrau 4 é a faixa sem artista, e ele só vale quando **não há dúvida**.
 */
class SongIndex(songs: List<Song>) {

    private val byKey = HashMap<String, MutableList<Song>>()
    private val byTitle = HashMap<String, MutableList<Song>>()

    init {
        for (song in songs) {
            byKey.getOrPut(SongKey.of(song)) { ArrayList() }.add(song)
            byTitle.getOrPut(SongKey.titleKey(song.title)) { ArrayList() }.add(song)
        }
    }

    /**
     * A faixa local que corresponde a esta identidade.
     *
     * `durationMs` desempata quando há mais de uma com o mesmo artista e título: a mais
     * próxima do tempo gravado é a que a pessoa queria.
     */
    fun find(key: String, durationMs: Long): Song? {
        if (key.isBlank()) return null
        val exact = byKey[key] ?: return null
        return pick(exact, durationMs, strict = false)
    }

    /**
     * A faixa local pelo título, quando ela é única.
     *
     * Só é usado para faixa **sem artista**: com artista preenchido, o degrau 3 ([find]) já
     * resolve, e um título sozinho seria adivinhação.
     */
    fun findByTitle(title: String, durationMs: Long): Song? {
        if (title.isBlank()) return null
        val candidates = byTitle[SongKey.titleKey(title)] ?: return null
        return pick(candidates, durationMs, strict = true)
    }

    /**
     * Escolhe entre as candidatas.
     *
     * `strict` é o que separa os dois usos: com artista e título, a identidade já é forte e a
     * duração só desempata; só com o título, tudo tem que concordar — inclusive a duração, e
     * inclusive a unicidade. Duas faixas com o mesmo título e a mesma duração continuam sendo
     * dúvida, e dúvida não vira faixa restaurada.
     */
    private fun pick(candidates: List<Song>, durationMs: Long, strict: Boolean): Song? {
        if (candidates.size == 1) {
            val only = candidates.first()
            if (!strict) return only
            if (durationMs <= 0L || only.durationMs <= 0L) return only
            return if (abs(only.durationMs - durationMs) <= DURATION_TOLERANCE_MS) only else null
        }
        if (durationMs <= 0L) return null
        val within = candidates.filter {
            it.durationMs > 0L && abs(it.durationMs - durationMs) <= DURATION_TOLERANCE_MS
        }
        if (within.size == 1) return within.first()
        return if (strict) null else within.firstOrNull()
    }

    companion object {
        /**
         * Tolerância da duração, em milissegundos.
         *
         * Dois segundos comem a diferença entre um arquivo com tag de duração correta e o
         * mesmo arquivo com a tag arredondada — e não comem a diferença entre duas faixas
         * diferentes, que costuma ser de dezenas de segundos.
         */
        const val DURATION_TOLERANCE_MS = 2000L
    }
}
