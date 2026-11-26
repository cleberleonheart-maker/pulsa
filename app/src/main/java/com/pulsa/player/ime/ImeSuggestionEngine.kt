package com.pulsa.player.ime

import java.text.Normalizer
import java.util.Locale

object ImeSuggestionEngine {
    private val words = mapOf(
        "pt" to listOf(
            "acerca", "agora", "agosto", "ainda", "alguém", "amanhã", "amigo", "amor", "ano",
            "antes", "aqui", "assim", "até", "aula", "bom", "casa", "com", "como", "dia", "de",
            "depois", "disso", "do", "dos", "e", "ela", "ele", "em", "entre", "era", "essa",
            "esse", "esta", "este", "eu", "fazer", "fechou", "foi", "grande", "hoje", "hora",
            "isso", "isto", "já", "lá", "lhe", "logo", "mais", "mas", "me", "mesmo", "meu",
            "minha", "muito", "na", "não", "nas", "nem", "no", "nos", "nossa", "nosso", "num",
            "nunca", "o", "os", "ou", "para", "pela", "pelo", "por", "porque", "pra", "quando",
            "que", "quem", "se", "sem", "ser", "seu", "seus", "sim", "sobre", "só", "sua",
            "suas", "também", "tá", "te", "tem", "tempo", "ter", "teu", "tu", "tudo", "um",
            "uma", "vai", "vez", "você", "voce", "vos",
        ),
        "en" to listOf(
            "amazing", "because", "beautiful", "better", "city", "could", "day", "first", "good",
            "great", "happy", "hello", "home", "love", "make", "music", "night", "people", "play",
            "please", "really", "right", "thank", "today", "tomorrow", "very", "want", "welcome",
            "world", "would", "yes",
        ),
        "es" to listOf(
            "ahora", "amor", "bien", "bueno", "cada", "casa", "como", "con", "cuando", "de",
            "del", "desde", "dia", "donde", "dos", "el", "ella", "ellos", "en", "entre", "eres",
            "esta", "este", "estoy", "fue", "gracias", "hola", "hoy", "hombre", "la", "las",
            "le", "lo", "los", "más", "me", "mi", "mucho", "muy", "nada", "ni", "no", "nos",
            "para", "pero", "por", "porque", "que", "qué", "quien", "se", "sin", "sobre", "son",
            "su", "sus", "también", "te", "tiene", "todo", "tu", "un", "una", "y", "ya", "yo",
        ),
    )

    private val frequent = mapOf(
        "pt" to setOf(
            "a", "o", "de", "que", "do", "da", "em", "um", "para", "é", "com", "não", "uma",
            "os", "no", "se", "na", "por", "mais", "as", "dos", "como", "mas", "ao", "ele",
        ),
        "en" to setOf(
            "the", "to", "of", "and", "a", "in", "is", "it", "you", "that", "for", "on", "with",
            "as", "at", "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
        ),
        "es" to setOf(
            "el", "la", "que", "de", "y", "a", "en", "los", "del", "se", "las", "por", "un",
            "para", "con", "una", "su", "lo", "como", "más", "pero", "sus", "le", "ya", "yo",
        ),
    )

    fun suggestions(prefix: String, language: String, limit: Int = 3): List<String> {
        val clean = prefix.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return emptyList()
        val languageKey = languageKey(language)
        val normalizedPrefix = normalize(clean)
        return words[languageKey].orEmpty().asSequence()
            .filter { normalize(it).startsWith(normalizedPrefix) }
            .distinctBy { normalize(it) }
            .sortedWith(
                compareBy<String> { if (normalize(it) == normalizedPrefix) 0 else 1 }
                    .thenBy { if (isFrequent(it, languageKey)) 0 else 1 }
                    .thenBy { it.length }
            )
            .take(limit)
            .toList()
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)

    private fun languageKey(language: String): String = when {
        language.startsWith("pt", ignoreCase = true) -> "pt"
        language.startsWith("es", ignoreCase = true) -> "es"
        else -> "en"
    }

    private fun isFrequent(word: String, language: String): Boolean =
        frequent[language].orEmpty().any { normalize(it) == normalize(word) }
}
