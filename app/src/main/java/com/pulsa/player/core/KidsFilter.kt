package com.pulsa.player.core

import com.pulsa.player.model.Song
import java.text.Normalizer
import java.util.Locale

/**
 * Modo família/kids (item 293) — a parte **pura**.
 *
 * O app não tem metadado de "conteúdo explícito" na biblioteca (o `MediaStore` não carrega
 * rating), então a triagem é por **palavra**: títulos/artistas/álbuns com termos da lista
 * [BLOCKED_WORDS] ficam de fora, e rádio/stream externo some junto ("sem rádio"). A
 * normalização tira acento e caixa para o casamento não depender de "café" vs "CAFE".
 *
 * A decisão de ligar/desligar mora no `Settings` (com senha); aqui só entra o que é testável
 * em JVM, sem `Context`.
 */
object KidsFilter {

    /**
     * Termos que barram uma faixa/podcast no modo kids. A lista é curta e conservadora de
     * propósito: pega marcadores de conteúdo adulto e palavrões comuns em pt/en/es. Fácil de
     * estender sem mexer na lógica.
     */
    val BLOCKED_WORDS: Set<String> = setOf(
        // conteúdo adulto / marcações
        "explicit", "explicito", "explicitly", "uncensored", "adult", "adults", "porn",
        "porno", "pornografia", "sexo", "sexual", "sexy", "nude", "nudes", "erotic",
        "erotico", "sensual", "xxx", "18+", "nsfw",
        // palavrões (pt/en/es)
        "porra", "caralho", "merda", "bosta", "foda", "foder", "fodase", "puta", "putaria",
        "viado", "cacete", "pinto", "pica", "cuzao", "arrombado", "desgraca",
        "fuck", "fucking", "shit", "bitch", "asshole", "bastard", "cunt", "dick",
        "mierda", "joder", "puta", "puto", "cojones", "verga", "polla", "cabron"
    )

    /** Normaliza para tokens: minúsculas, sem acento, pontuação vira espaço. */
    fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val stripped = Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return stripped.replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    /** O texto (ou qualquer um dos campos) contém uma palavra bloqueada? */
    fun isBlocked(vararg fields: String?): Boolean {
        for (field in fields) {
            if (field.isNullOrBlank()) continue
            val tokens = normalize(field).split(' ')
            if (tokens.any { it in BLOCKED_WORDS }) return true
        }
        return false
    }

    /** Uma faixa pode passar na triagem? No modo kids, rádio/stream não passa. */
    fun allow(song: Song, kids: Boolean): Boolean {
        if (!kids) return true
        if (song.isRadio || song.isStream) return false
        return !isBlocked(song.title, song.artist, song.album)
    }

    /** Filtra a fila preservando a ordem. Fora do modo kids devolve a mesma lista. */
    fun filter(songs: List<Song>, kids: Boolean): List<Song> {
        if (!kids) return songs
        return songs.filter { allow(it, true) }
    }
}
