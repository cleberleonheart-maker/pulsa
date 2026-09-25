package com.pulsa.player.dj
import com.pulsa.player.core.Settings

import android.content.Context
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sugestão de música com IA (Gemini) falada pela DJ Virgin.
 * Usa a chave configurada em Settings (gemini_key) e o modelo gratuito gemini-3.6-flash.
 */
object DjSuggest {

    const val MODEL = "gemini-3.6-flash"

    data class Suggestion(val title: String, val artist: String, val reason: String)

    fun isReady(context: Context): Boolean =
        Settings.geminiOn(context) && Settings.geminiKey(context).isNotBlank()

    /**
     * Pede à Virgin (via Gemini) uma recomendação a partir da biblioteca do usuário.
     * Retorna null se a IA estiver desligada, sem chave, sem músicas ou em erro.
     */
    fun suggest(context: Context, songs: List<Song>): Suggestion? {
        if (!isReady(context)) return null
        if (songs.isEmpty()) return null

        val sampled = if (songs.size <= 60) songs else {
            val step = songs.size / 60
            (0 until 60).map { songs[it * step] }
        }

        val listText = StringBuilder()
        sampled.forEachIndexed { i, s ->
            listText.append("${i + 1}. ").append(s.title).append(" - ").append(s.artist).append("\n")
        }

        val prompt = buildString {
            append(DjIdentity.personalityPrompt())
            appendLine()
            appendLine("Agora, abaixo está uma lista de músicas da biblioteca do usuário (título - artista).")
            appendLine("Escolha a MELHOR música para tocar agora, considerando variedade e o momento.")
            appendLine("Responda APENAS em português do Brasil, neste formato exato em UMA linha:")
            appendLine("TITULO | ARTISTA | motivo curto em até 10 palavras")
            appendLine()
            append("Lista:\n").append(listText)
        }

        val reply = generate(context, prompt, maxTokens = 700) ?: return null
        return parse(reply)
    }

    /**
     * Sugere com a IA e cai no offline quando a IA falha.
     *
     * A IA depende de rede, chave válida e cota. Sem este fallback, uma falha de
     * rede fazia a Virgin dizer "não consegui pensar em nada" mesmo tendo uma
     * sugestão offline pronta - a resposta offline é determinística e sempre
     * existe, então nunca vale a pena mostrar falha no lugar dela.
     */
    fun suggestOrOffline(context: Context, songs: List<Song>): Suggestion =
        if (isReady(context)) suggest(context, songs) ?: offline(context, songs)
        else offline(context, songs)

    /** Fala melhor o resultado: título, artista e o motivo separados. */
    fun toSpeech(suggestion: Suggestion): String = buildString {
        append(suggestion.title)
        if (suggestion.artist.isNotBlank()) append(", de ").append(suggestion.artist)
        append(". ")
        append(suggestion.reason.ifBlank { "Essa combina com o momento." })
    }

    private val REASONS_FAV = listOf(
        "É uma das tuas favoritas e faz tempo que ela não aparece.",
        "Tocou pouco ultimamente e eu tô com saudade dela."
    )
    private val REASONS_PLAY = listOf(
        "É a que você mais ouviu por aqui e combina com o momento.",
        "Faz tempo que não rola no teu som."
    )

    /**
     * Sugestão OFFLINE (sem Gemini): usa favoritas e o histórico da DjLearn.
     * Não toca na faixa atual nem em músicas reprovadas (disliked / muitas skips).
     */
    fun offline(context: Context, songs: List<Song>): Suggestion {
        val favIds = runCatching { PlaylistDb.get(context).favorites().map { it.id }.toSet() }
            .getOrDefault(emptySet())
        val learn = DjLearn.learn(context)
        val currentId = Playback.currentSong?.id
        val pool = songs.filter {
            it.id != currentId &&
                it.id !in learn.disliked &&
                (learn.skipCount[it.id] ?: 0) < 3
        }
        val candidates = if (pool.isEmpty()) songs.filter { it.id != currentId } else pool
        if (candidates.isEmpty()) {
            val any = songs.firstOrNull()
            if (any != null) return Suggestion(any.title, any.artist, REASONS_PLAY[0])
            return Suggestion("", "", "")
        }
        val byFav = candidates.filter { it.id in favIds }
        val pick = if (byFav.isNotEmpty()) {
            byFav.sortedByDescending { learn.plays[it.id] ?: 0 }.first()
        } else {
            candidates.sortedByDescending { learn.plays[it.id] ?: 0 }.first()
        }
        val reason = if (pick.id in favIds) REASONS_FAV.random() else REASONS_PLAY.random()
        return Suggestion(pick.title, pick.artist, reason)
    }

    /**
     * Limpa o texto que a IA devolveu e vira Suggestion.
     * Aceita o formato pedido (TITULO | ARTISTA | motivo) e tambem as variacoes
     * que os modelos entregam na pratica: markdown em volta (**Titulo**), aspas,
     * travessao no lugar do pipe, preambulo antes da resposta e resposta sem motivo.
     */
    fun parse(raw: String): Suggestion? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        // Pipe primeiro: se a linha tem "|" ele e o separador de campos, mesmo que
        // a frase tambem contenha um travessao.
        val sep = SEPARATORS.firstOrNull { s -> lines.any { l -> l.contains(s) } } ?: return null
        val line = lines.first { it.contains(sep) }
        val parts = line.split(sep).map { clean(it) }
        if (parts.isEmpty() || parts[0].isBlank()) return null
        val title = parts[0]
        val artist = parts.getOrElse(1) { "" }
        val reason = parts.drop(2).joinToString(" ").trim()
        return Suggestion(title, artist, reason)
    }

    /** Separadores aceitos entre titulo, artista e motivo. O pipe vem primeiro. */
    private val SEPARATORS = listOf("|", " - ", " – ", " — ")

    /**
     * Tira markdown, aspas e espacos. NAO tira pontuacao final: "Charlie Brown Jr."
     * perde o ponto e deixa de casar com a biblioteca.
     */
    private fun clean(s: String): String =
        s.replace("*", "").replace("`", "").replace("\"", "").replace("'", "")
            .replace("#", "").replace("_", " ").trim()

    /**
     * Partes de texto que a IA devolveu. Os modelos novos razonam antes de responder,
     * e o raciocínio chega como parte separada (thought=true) - ler só a primeira
     * parte pegava o raciocínio e não a resposta.
     */
    data class Part(val text: String, val thought: Boolean)

    /** Junta as partes de resposta ignorando o raciocínio. */
    fun answerFrom(parts: List<Part>): String? {
        val real = parts.filter { !it.thought && it.text.isNotBlank() }
        val text = if (real.isEmpty()) return null else real.joinToString("\n") { it.text }
        return text.trim().ifBlank { null }
    }

    /** Encontra a música sugerida na biblioteca (por igualdade normalizada). */
    fun findSong(songs: List<Song>, suggestion: Suggestion): Song? {
        val targetTitle = DjCommander.norm(suggestion.title)
        val targetArtist = DjCommander.norm(suggestion.artist)
        return songs.firstOrNull {
            DjCommander.norm(it.title).contains(targetTitle) || targetTitle.contains(DjCommander.norm(it.title))
        } ?: songs.firstOrNull {
            if (targetArtist.isBlank()) false
            else DjCommander.norm(it.artist).contains(targetArtist)
        }
    }

    fun generate(context: Context, prompt: String, maxTokens: Int = 250): String? {
        val key = Settings.geminiKey(context)
        if (key.isBlank()) return null

        val body = JSONObject()
            .put("contents", JSONArray()
                .put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))))
            )
            .put("generationConfig", JSONObject()
                .put("temperature", 0.8)
                .put("maxOutputTokens", maxTokens))

        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                android.util.Log.w("DjSuggest", "Gemini HTTP $code: ${err.take(300)}")
                null
            } else {
                answerOf(conn.inputStream.bufferedReader().readText())
            }
        } catch (t: Throwable) {
            android.util.Log.w("DjSuggest", "Gemini error", t)
            null
        }
    }

    /**
     * Tira o texto de resposta do JSON do Gemini. Lê todas as partes e ignora as
     * de raciocínio; quando não sobra resposta, registra o finishReason, que é o
     * que diz se foi corte de token, bloqueio ou resposta vazia.
     */
    private fun answerOf(body: String): String? {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            android.util.Log.w("DjSuggest", "Gemini sem candidates: ${root.optString("promptFeedback").take(200)}")
            return null
        }
        val candidate = candidates.getJSONObject(0)
        val partsJson = candidate.optJSONObject("content")?.optJSONArray("parts")
        val parts = ArrayList<Part>()
        if (partsJson != null) {
            for (i in 0 until partsJson.length()) {
                val p = partsJson.optJSONObject(i) ?: continue
                parts.add(Part(p.optString("text"), p.optBoolean("thought", false)))
            }
        }
        val answer = answerFrom(parts)
        if (answer == null) {
            android.util.Log.w(
                "DjSuggest",
                "Gemini devolveu sem resposta utilizavel: finishReason=" +
                    candidate.optString("finishReason") + " partes=" + parts.size
            )
        }
        return answer
    }

    /** Resumo semanal em texto para a Virgin contar as novidades do usuário. */
    fun weeklySummary(context: Context, songs: List<Song>): String? {
        if (!isReady(context) || songs.isEmpty()) return null
        val prompt = buildString {
            append(DjIdentity.personalityPrompt())
            appendLine("Essa é a biblioteca do usuário (título - artista).")
            appendLine("Faça um resumo semanal pessoal de 2 a 3 frases comentando a coleção e dê uma dica musical.")
            appendLine(songs.take(40).joinToString("\n") { "${it.title} - ${it.artist}" })
        }
        return generate(context, prompt, maxTokens = 220)
    }
}
