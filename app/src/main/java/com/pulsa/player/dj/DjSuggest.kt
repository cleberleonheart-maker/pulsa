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

        val reply = generate(context, prompt, maxTokens = 250) ?: return null
        return parse(reply)
    }

    /** Fala melhor o resultado: título, artista e o motivo separados. */
    fun toSpeech(suggestion: Suggestion): String =
        suggestion.title + ", de " + suggestion.artist + ". " + suggestion.reason

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

    fun parse(raw: String): Suggestion? {
        val line = raw.lines().map { it.trim() }.firstOrNull { it.contains("|") } ?: return null
        val parts = line.split("|").map { it.trim() }
        if (parts.size < 2) return null
        val title = parts[0].replace("\"", "").trim()
        val artist = parts[1].replace("\"", "").trim()
        val reason = if (parts.size > 2) parts[2].replace("\"", "").trim() else ""
        if (title.isBlank()) return null
        return Suggestion(title, artist, reason)
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
                val json = conn.inputStream.bufferedReader().readText()
                val result = JSONObject(json)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                result.trim()
            }
        } catch (t: Throwable) {
            android.util.Log.w("DjSuggest", "Gemini error", t)
            null
        }
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
