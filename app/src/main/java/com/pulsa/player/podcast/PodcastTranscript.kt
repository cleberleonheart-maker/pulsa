package com.pulsa.player.podcast

import org.json.JSONArray
import org.json.JSONObject

/**
 * Modelo de transcrição para busca falada no podcast.
 */
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** `true` quando o trecho é leitura de patrocínio/propaganda — ver [AdSkip]. */
    val isAd: Boolean = false
)

data class PodcastTranscript(
    val episodeId: Long,
    val fullText: String,
    val segments: List<TranscriptSegment>,
    val language: String = "pt",
    val durationMs: Long = 0L
)

object PodcastTranscriptParser {
    fun fromJson(json: String): List<TranscriptSegment> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                TranscriptSegment(
                    startMs = o.optLong("start", 0L),
                    endMs = o.optLong("end", 0L),
                    text = o.optString("text", ""),
                    isAd = o.optBoolean("ad", false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun toJson(segments: List<TranscriptSegment>): String {
        val arr = JSONArray()
        segments.forEach { s ->
            arr.put(JSONObject().apply {
                put("start", s.startMs)
                put("end", s.endMs)
                put("text", s.text)
                if (s.isAd) put("ad", true)
            })
        }
        return arr.toString()
    }

    /**
     * Palavras que aparecem na própria frase do comando e não no tema perguntado. Sem esta
     * lista, "o que falaram sobre bitcoin nesse episódio" buscaria também por "que", "sobre"
     * e "episódio", e qualquer trecho casaria por acaso.
     */
    private val STOPWORDS = setOf(
        "que", "de", "do", "da", "dos", "das", "o", "a", "os", "as", "e", "em", "no", "na",
        "nos", "nas", "um", "uma", "por", "com", "para", "pra", "sobre", "nesse", "neste",
        "esse", "este", "essa", "esta", "episodio", "podcast", "falaram", "disseram",
        "mencionaram", "fala", "falar", "falou", "disse", "dizer", "qual", "quais", "como",
        "onde", "quando", "virgi", "virgin"
    )

    /**
     * Busca os segmentos mais relevantes para uma pergunta.
     * Busca simples por palavras-chave (sem embeddings por enquanto).
     *
     * O mínimo é **2 letras** (não 3) para temas curtos como "IA" e "5G" funcionarem; os
     * termos vazios da frase são cortados por [STOPWORDS], não pelo tamanho.
     */
    fun findRelevant(
        segments: List<TranscriptSegment>,
        query: String,
        maxSegments: Int = 5
    ): List<TranscriptSegment> {
        if (segments.isEmpty() || query.isBlank()) return emptyList()
        // Propaganda citada pelo patrocinador não é conteúdo do episódio: nunca entregar como
        // resposta ao ouvinte (e o salto de anúncio usa o mesmo flag).
        val content = segments.filterNot { it.isAd }
        if (content.isEmpty()) return emptyList()
        val qNorm = normalize(query)
        val words = qNorm.split(" ").filter { it.length >= 2 && it !in STOPWORDS }.distinct()
        if (words.isEmpty()) return emptyList()

        val scored: List<TranscriptSegment> = content.map { seg ->
            val tNorm = normalize(seg.text)
            val score = words.count { w -> tNorm.contains(w) }
            Pair(seg, score)
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(maxSegments)
            .map { it.first }
            .sortedBy { it.startMs }

        return scored
    }

    private fun normalize(s: String): String {
        return s.lowercase()
            .replace("[àáâãäå]".toRegex(), "a")
            .replace("[èéêë]".toRegex(), "e")
            .replace("[ìíîï]".toRegex(), "i")
            .replace("[òóôõö]".toRegex(), "o")
            .replace("[ùúûü]".toRegex(), "u")
            .replace("[ç]".toRegex(), "c")
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000L).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%d:%02d", min, sec)
    }
}
