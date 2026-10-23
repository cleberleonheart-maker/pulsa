package com.pulsa.player.podcast

import android.content.Context
import android.util.Base64
import android.util.Log
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.db.PodcastTranscriptEntity
import com.pulsa.player.data.db.PulsaDatabase
import com.pulsa.player.dj.DjSuggest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Transcrição de podcast sob demanda, usando o áudio como entrada do Gemini.
 *
 * O fluxo é "preguiçoso": só transcreve quando a pessoa pergunta algo sobre o episódio.
 * Arquivos até [INLINE_LIMIT] vão embutidos na requisição; acima disso sobem pela File API,
 * que aceita arquivos grandes e devolve um `uri` reutilizável na chamada de conteúdo.
 *
 * O resultado é gravado em `podcast_transcripts` — uma pergunta repetida não gasta cota
 * nem tempo de novo.
 */
object PodcastTranscribe {
    private const val TAG = "PodcastTranscribe"

    /** Limite do áudio embutido na requisição (base64 aumenta ~33%, então 12 MB de áudio). */
    private const val INLINE_LIMIT = 12L * 1024 * 1024

    /** Teto de download para não encher o disco em episódio gigante. */
    private const val DOWNLOAD_LIMIT = 200L * 1024 * 1024

    /** Uma transcrição "em andamento" recente evita disparar o mesmo trabalho duas vezes. */
    private const val PENDING_TTL_MS = 3 * 60 * 1000L

    private val running = ConcurrentHashMap.newKeySet<Long>()

    fun hasTranscript(context: Context, episodeId: Long): Boolean {
        val db = PulsaDatabase.get(context)
        return db.podcastDao().transcriptForEpisode(episodeId)?.status == "done"
    }

    fun getTranscript(context: Context, episodeId: Long): PodcastTranscript? {
        val db = PulsaDatabase.get(context)
        val entity = db.podcastDao().transcriptForEpisode(episodeId) ?: return null
        if (entity.status != "done") return null
        return PodcastTranscript(
            episodeId = entity.episodeId,
            fullText = entity.fullText,
            segments = PodcastTranscriptParser.fromJson(entity.segmentsJson),
            language = entity.language,
            durationMs = entity.durationMs
        )
    }

    /**
     * Garante que o episódio tem transcrição. Chama [onDone] com `(true, null)` quando já
     * existe (ou acabou de ser gerada), ou `(false, motivo)` quando não dá para transcrever.
     */
    fun transcribeIfNeeded(context: Context, episode: Episode, onDone: (Boolean, String?) -> Unit) {
        val db = PulsaDatabase.get(context)
        val existing = db.podcastDao().transcriptForEpisode(episode.id)
        if (existing?.status == "done") {
            onDone(true, null)
            return
        }

        val key = Settings.geminiKey(context)
        if (key.isBlank()) {
            onDone(false, "transcribe_need_key")
            return
        }

        if (existing?.status == "pending" &&
            System.currentTimeMillis() - existing.createdAt < PENDING_TTL_MS
        ) {
            onDone(false, "transcribe_pending")
            return
        }

        if (!running.add(episode.id)) {
            onDone(false, "transcribe_pending")
            return
        }

        db.podcastDao().upsertTranscript(
            PodcastTranscriptEntity(
                episodeId = episode.id,
                podcastId = episode.podcastId,
                status = "pending",
                createdAt = System.currentTimeMillis()
            )
        )

        ThreadPool.postNetwork {
            var ok = false
            try {
                val segments = transcribe(context, episode, key)
                if (segments.isNotEmpty()) {
                    db.podcastDao().upsertTranscript(
                        PodcastTranscriptEntity(
                            episodeId = episode.id,
                            podcastId = episode.podcastId,
                            fullText = segments.joinToString(" ") { it.text },
                            segmentsJson = PodcastTranscriptParser.toJson(segments),
                            durationMs = episode.durationSec * 1000L,
                            status = "done",
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    AdSkip.onTranscriptSaved(context, episode.id, segments)
                    ok = true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "transcrição falhou para ${episode.id}", t)
            } finally {
                running.remove(episode.id)
                if (!ok) {
                    runCatching {
                        db.podcastDao().upsertTranscript(
                            PodcastTranscriptEntity(
                                episodeId = episode.id,
                                podcastId = episode.podcastId,
                                status = "failed",
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
                ThreadPool.onUi { onDone(ok, if (ok) null else "transcribe_failed") }
            }
        }
    }

    fun askAboutEpisode(
        context: Context,
        episode: Episode,
        question: String,
        onAnswer: (String?) -> Unit
    ) {
        val transcript = getTranscript(context, episode.id)
        if (transcript != null && transcript.segments.isNotEmpty()) {
            answerWithTranscript(context, episode, transcript, question, onAnswer)
            return
        }
        transcribeIfNeeded(context, episode) { ok, msg ->
            val fresh = if (ok) getTranscript(context, episode.id) else null
            if (fresh != null && fresh.segments.isNotEmpty()) {
                answerWithTranscript(context, episode, fresh, question, onAnswer)
            } else {
                onAnswer(msg ?: "transcribe_none")
            }
        }
    }

    // ---- transcrição -------------------------------------------------------------------

    private fun transcribe(context: Context, episode: Episode, key: String): List<TranscriptSegment> {
        val audio = obtainAudio(context, episode)
        val mime = mimeForEpisode(episode)
        try {
            val part = if (audio.length() <= INLINE_LIMIT) {
                JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", mime)
                    .put("data", Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)))
            } else {
                val uri = uploadFile(audio, mime, key)
                JSONObject().put("file_data", JSONObject()
                    .put("mime_type", mime)
                    .put("file_uri", uri))
            }

            val prompt = """
Transcreva o áudio deste episódio de podcast em português (ou no idioma falado).

Responda APENAS com um array JSON válido, sem texto fora do JSON, no formato:
[{"start": 12345, "end": 23456, "text": "trecho falado", "ad": false}]

Regras:
- `start` e `end` em milissegundos desde o início do áudio.
- Quebre em trechos de 10 a 30 segundos, na ordem.
- Transcreva literalmente o que é falado, sem resumir.
- Não invente conteúdo; se um trecho for inaudível, escreva "[inaudível]".
- Marque `"ad": true` em TODOS os trechos que fazem parte de leitura de patrocínio/propaganda (patrocinador, cupom, link de compra, "este episódio é trazido por"), não só o primeiro do bloco. Notícia, indicação de outro episódio e despedida NÃO são propaganda.
""".trim()

            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject()
                    .put("parts", JSONArray()
                        .put(JSONObject().put("text", prompt))
                        .put(part))))
                .put("generationConfig", JSONObject()
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", 65536)
                    .put("responseMimeType", "application/json"))

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${DjSuggest.MODEL}:generateContent")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30000
                readTimeout = 180000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                Log.w(TAG, "Gemini transcrição HTTP $code: ${err.take(300)}")
                return emptyList()
            }
            val resp = conn.inputStream.bufferedReader().readText()
            return parseSegments(extractText(resp))
        } finally {
            runCatching { audio.delete() }
        }
    }

    /** Devolve um arquivo local com o áudio: o baixado, ou um temporário do streaming. */
    private fun obtainAudio(context: Context, episode: Episode): File {
        if (episode.downloaded) {
            val local = File(episode.filePath)
            if (local.exists() && local.length() > 0) return local
        }
        val ext = episode.playableUrl.substringBefore('?')
            .substringAfterLast('/').substringAfterLast('.', "mp3")
            .take(5)
        val dest = File.createTempFile("pulsa-ep-${episode.id}-", ".$ext", context.cacheDir)
        val url = URL(episode.audioUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Pulsa/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > DOWNLOAD_LIMIT) throw IllegalStateException("áudio grande demais")
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (t: Throwable) {
            runCatching { dest.delete() }
            throw t
        } finally {
            conn.disconnect()
        }
        return dest
    }

    /** Sobe pela File API (resumable) e espera o arquivo ficar ACTIVE, devolvendo o `uri`. */
    private fun uploadFile(file: File, mime: String, key: String): String {
        val size = file.length()

        val start = (URL("https://generativelanguage.googleapis.com/upload/v1beta/files?key=$key")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30000
            readTimeout = 60000
            setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            setRequestProperty("X-Goog-Upload-Command", "start")
            setRequestProperty("X-Goog-Upload-Header-Content-Length", size.toString())
            setRequestProperty("X-Goog-Upload-Header-Content-Type", mime)
            setRequestProperty("Content-Type", "application/json")
        }
        start.outputStream.use {
            it.write(JSONObject().put("file", JSONObject()
                .put("display_name", file.name)).toString().toByteArray(Charsets.UTF_8))
        }
        val uploadUrl = start.getHeaderField("X-Goog-Upload-URL")
            ?: throw IllegalStateException("sem upload URL (HTTP ${start.responseCode})")
        start.disconnect()

        val upload = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30000
            readTimeout = 300000
            setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            setRequestProperty("X-Goog-Upload-Offset", "0")
            setRequestProperty("Content-Length", size.toString())
        }
        upload.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        val uploadBody = upload.inputStream.bufferedReader().readText()
        upload.disconnect()
        val fileName = JSONObject(uploadBody).optJSONObject("file")?.optString("name").orEmpty()
        if (fileName.isBlank()) throw IllegalStateException("upload sem nome de arquivo")

        repeat(60) {
            val check = (URL("https://generativelanguage.googleapis.com/v1beta/$fileName?key=$key")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 30000
            }
            val obj = JSONObject(check.inputStream.bufferedReader().readText())
            check.disconnect()
            val state = obj.optString("state")
            if (state == "ACTIVE") return obj.optString("uri")
            if (state == "FAILED") throw IllegalStateException("processamento do áudio falhou")
            Thread.sleep(2000)
        }
        throw IllegalStateException("tempo esgotado esperando o áudio")
    }

    private fun mimeForEpisode(episode: Episode): String {
        val src = if (episode.downloaded) episode.filePath else episode.playableUrl
        val name = src.substringBefore('?')
        return when {
            name.endsWith(".m4a", true) || name.endsWith(".mp4", true) -> "audio/mp4"
            name.endsWith(".aac", true) -> "audio/aac"
            name.endsWith(".wav", true) -> "audio/wav"
            name.endsWith(".ogg", true) || name.endsWith(".opus", true) -> "audio/ogg"
            name.endsWith(".flac", true) -> "audio/flac"
            else -> "audio/mpeg"
        }
    }

    private fun parseSegments(text: String): List<TranscriptSegment> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return PodcastTranscriptParser.fromJson(text.substring(start, end + 1))
            .filter { it.text.isNotBlank() }
    }

    private fun extractText(json: String): String {
        return try {
            val parts = JSONObject(json)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            buildString {
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (p.has("thought")) continue
                    append(p.optString("text", ""))
                }
            }.trim()
        } catch (e: Exception) {
            ""
        }
    }

    // ---- pergunta sobre a transcrição ---------------------------------------------------

    private fun answerWithTranscript(
        context: Context,
        episode: Episode,
        transcript: PodcastTranscript,
        question: String,
        onAnswer: (String?) -> Unit
    ) {
        val relevant = PodcastTranscriptParser.findRelevant(transcript.segments, question)
        if (relevant.isEmpty()) {
            onAnswer("transcribe_no_match")
            return
        }
        val quote = relevant.first()
        val offline = "transcribe_offline:${PodcastTranscriptParser.formatTime(quote.startMs)}:${quote.text.take(120)}"

        val key = Settings.geminiKey(context)
        if (key.isBlank()) {
            onAnswer(offline)
            return
        }

        ThreadPool.postNetwork {
            val answer = try {
                val contextText = relevant.joinToString("\n\n") {
                    "[${PodcastTranscriptParser.formatTime(it.startMs)}] ${it.text}"
                }
                val prompt = """
Você é a Virgin, DJ do app Pulsa. Responda em português brasileiro, curto e direto.

Pergunta sobre o episódio "${episode.title}":
"$question"

Trechos relevantes da transcrição:
$contextText

Regras:
- No máximo 2 frases.
- Cite o tempo do trecho mais relevante no formato (minuto:segundo).
- Se não houver informação suficiente, diga isso claramente.
""".trim()

                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject()
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject()
                        .put("temperature", 0.7)
                        .put("maxOutputTokens", 300))

                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${DjSuggest.MODEL}:generateContent")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-goog-api-key", key)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    extractText(conn.inputStream.bufferedReader().readText()).ifBlank { offline }
                } else {
                    offline
                }
            } catch (t: Throwable) {
                Log.w(TAG, "erro ao perguntar sobre episódio", t)
                offline
            }
            ThreadPool.onUi { onAnswer(answer) }
        }
    }
}
