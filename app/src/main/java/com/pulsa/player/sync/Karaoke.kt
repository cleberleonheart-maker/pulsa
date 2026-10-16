package com.pulsa.player.sync

import kotlin.math.ceil

/**
 * Karaokê palavra-a-palavra (item 126).
 *
 * O LRC só traz o instante de cada linha, não de cada palavra. Então a fração cantada
 * da linha (0..1) é distribuída entre as palavras e as que já "entraram" vão sendo
 * acesas em sequência. Tudo aqui é puro (sem Android) para dar para testar.
 */
object Karaoke {

    data class Word(val start: Int, val end: Int)

    /** Palavras da linha, com os offsets de caractere no texto original. */
    fun words(text: String): List<Word> {
        val out = ArrayList<Word>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break
            val start = i
            while (i < n && !text[i].isWhitespace()) i++
            out.add(Word(start, i))
        }
        return out
    }

    /** Fração cantada da linha segundo a posição (0 antes do início, 1 no fim). */
    fun fractionFor(positionMs: Long, lineStartMs: Long, lineEndMs: Long): Float {
        val span = lineEndMs - lineStartMs
        if (span <= 0L) return if (positionMs >= lineStartMs) 1f else 0f
        return ((positionMs - lineStartMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /** Quantas palavras já foram cantadas segundo [fraction] (ao menos 1 assim que avança). */
    fun sungWords(text: String, fraction: Float): Int {
        val count = words(text).size
        if (count == 0) return 0
        if (fraction <= 0f) return 0
        return ceil(fraction * count).toInt().coerceIn(1, count)
    }

    /** Offset (exclusivo) no texto cobrindo as palavras já cantadas; 0 = nenhuma. */
    fun highlightEnd(text: String, fraction: Float): Int {
        val sung = sungWords(text, fraction)
        if (sung == 0) return 0
        return words(text)[sung - 1].end
    }
}
