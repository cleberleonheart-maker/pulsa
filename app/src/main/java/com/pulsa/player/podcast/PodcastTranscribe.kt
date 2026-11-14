package com.pulsa.player.podcast

import android.content.Context
import android.util.Base64
import android.util.Log
import com.pulsa.player.R
import com.pulsa.player.core.CrashLogger
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

    /** Transcrições em voo **neste processo**. */
    private val running = ConcurrentHashMap.newKeySet<Long>()

    /** Quem está esperando uma transcrição em voo acabar, para ser respondido de verdade. */
    private val waiters = ConcurrentHashMap<Long, MutableList<(Boolean, String?) -> Unit>>()

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
     *
     * Se já há uma transcrição **em voo** para o mesmo episódio, quem chama entra na fila de
     * [waiters] e é respondido com o resultado — não com um "vou transcrever quando der". Era o
     * que acontecia com o `AdSkip`: ele começava a transcrição, e a pergunta da pessoa pelo
     * podcast chegava dois minutos depois e levava um "transcrevendo" que nunca virava resposta.
     */
    fun transcribeIfNeeded(context: Context, episode: Episode, onDone: (Boolean, String?) -> Unit) {
        val db = PulsaDatabase.get(context)
        val existing = runCatching {
            db.podcastDao().transcriptForEpisode(episode.id)
        }.getOrElse { t ->
            CrashLogger.writeLog(
                context.applicationContext,
                "Podcast: falha ao ler a transcricao id=${episode.id} -> $t"
            )
            null
        }
        if (existing?.status == "done") {
            onDone(true, null)
            return
        }

        val key = Settings.geminiKey(context)
        if (key.isBlank()) {
            onDone(false, "transcribe_need_key")
            return
        }

        // `running` é a verdade sobre o que está transcorrendo **agora**. Uma linha "pending"
        // no banco sem nada em voo é lixo de um app morto no meio — e prometer "vou
        // transcrever quando tiver condições" para ela é uma mentira que ninguém cumpre.
        if (!running.add(episode.id)) {
            esperar(episode.id, onDone)
            return
        }

        try {
            db.podcastDao().upsertTranscript(
                PodcastTranscriptEntity(
                    episodeId = episode.id,
                    podcastId = episode.podcastId,
                    status = "pending",
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (t: Throwable) {
            // Sem esta linha o `running` ficaria marcado para sempre e o episódio nunca mais
            // transcreveria — todo comando dele responderia "transcrevendo".
            running.remove(episode.id)
            CrashLogger.writeLog(
                context.applicationContext,
                "Podcast: nao consegui marcar a transcricao pendente id=${episode.id} -> $t"
            )
            onDone(false, "transcribe_failed")
            return
        }

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
                ThreadPool.onUi { concluir(episode.id, ok, if (ok) null else "transcribe_failed", onDone) }
            }
        }
    }

    /** Entra na fila de quem espera a transcrição em voo terminar. */
    private fun esperar(episodeId: Long, onDone: (Boolean, String?) -> Unit) {
        val fila = waiters.computeIfAbsent(episodeId) { ArrayList() }
        synchronized(fila) { fila.add(onDone) }
    }

    /** Entrega o resultado para quem iniciou e para quem entrou na fila de espera. */
    private fun concluir(
        episodeId: Long,
        ok: Boolean,
        msg: String?,
        onDone: (Boolean, String?) -> Unit
    ) {
        val fila = waiters.remove(episodeId)
        val lista = fila?.let { synchronized(it) { ArrayList(it) } } ?: emptyList()
        onDone(ok, msg)
        lista.forEach { it(ok, msg) }
    }

    /**
     * Pergunta sobre o episódio.
     *
     * Aqui é tudo bloqueio — Room para ler a transcrição, rede para transcrever e responder — e
     * o comando de voz chega na main thread (o `SpeechRecognizer` entrega assim). Ler o banco
     * ali derrubava o app. O trabalho sai para o pool e a resposta volta **sempre** na main
     * thread, para o caller poder falar direto.
     */
    fun askAboutEpisode(
        context: Context,
        episode: Episode,
        question: String,
        onAnswer: (String?) -> Unit
    ) {
        ThreadPool.post {
            val transcript = runCatching {
                getTranscript(context, episode.id)
            }.getOrElse { t ->
                // Sem isto a exceção morre dentro do pool e a Virgin fica muda: o
                // `ExecutorService` engole a exceção, e ninguém nunca responde.
                CrashLogger.writeLog(
                    context.applicationContext,
                    "Podcast: falha ao ler a transcricao id=${episode.id} -> $t"
                )
                null
            }
            if (transcript != null && transcript.segments.isNotEmpty()) {
                answerWithTranscript(context, episode, transcript, question, onAnswer)
                return@post
            }
            transcribeIfNeeded(context, episode) { ok, msg ->
                if (!ok) {
                    ThreadPool.onUi { onAnswer(msg ?: "transcribe_none") }
                    return@transcribeIfNeeded
                }
                // A transcrição ficou pronta: reler o banco também sai da main thread.
                ThreadPool.post {
                    val fresh = runCatching { getTranscript(context, episode.id) }.getOrNull()
                    if (fresh != null && fresh.segments.isNotEmpty()) {
                        answerWithTranscript(context, episode, fresh, question, onAnswer)
                    } else {
                        ThreadPool.onUi { onAnswer("transcribe_none") }
                    }
                }
            }
        }
    }

    /**
     * A fala da Virgin para a resposta de [askAboutEpisode].
     *
     * O mapeamento estava **copiado** nos dois dispatchers (`MainVirgin` e `DjSession`), e uma
     * cópia pode divergir da outra sem ninguém perceber: o comando novo entra num e não no
     * outro. Aqui é uma função só.
     *
     * O [resolve] é quem pega a string no idioma da pergunta, então esta função é pura — e o
     * teste cobre os tokens, inclusive a citação offline que pode conter ":".
     */
    fun answerSpeech(token: String?, resolve: (Int, List<String>) -> String): String {
        if (token == null) return resolve(R.string.dj_voice_transcribe_none, emptyList())
        return when {
            token == "transcribe_none" -> resolve(R.string.dj_voice_transcribe_none, emptyList())
            token.startsWith("transcribe_need_key") -> resolve(R.string.dj_voice_transcribe_need_key, emptyList())
            token.startsWith("transcribe_pending") -> resolve(R.string.dj_voice_transcribe_pending, emptyList())
            token.startsWith("transcribe_failed") -> resolve(R.string.dj_voice_transcribe_failed, emptyList())
            token.startsWith("transcribe_no_match") -> resolve(R.string.dj_voice_transcribe_no_match, emptyList())
            token.startsWith("transcribe_offline:") -> {
                // O tempo viaja em **milissegundos**, não em "min:seg": ele tem ":" e a
                // citação também pode ter. Separar por ":" misturava os dois — a Virgin
                // dizia "12" e a citação começava com "34:".
                val parts = token.split(":", limit = 3)
                val ms = parts.getOrNull(1)?.toLongOrNull()
                val time = if (ms == null) "" else PodcastTranscriptParser.formatTime(ms)
                // A citação é o resto do token: a transcrição costuma ter ":" no meio do trecho.
                val quote = parts.getOrNull(2).orEmpty()
                resolve(R.string.dj_voice_transcribe_offline, listOf(time, quote))
            }
            else -> token
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

    /**
     * Responde com a transcrição. O callback sai **sempre** na main thread, venha do caminho
     * curto (sem trecho relevante / sem chave) ou da chamada de rede.
     */
    private fun answerWithTranscript(
        context: Context,
        episode: Episode,
        transcript: PodcastTranscript,
        question: String,
        onAnswer: (String?) -> Unit
    ) {
        val relevant = PodcastTranscriptParser.findRelevant(transcript.segments, question)
        if (relevant.isEmpty()) {
            ThreadPool.onUi { onAnswer("transcribe_no_match") }
            return
        }
        val quote = relevant.first()
        // O tempo vai em milissegundos: é o que deixa o token sem ambiguidade (ver
        // [answerSpeech]) — a citação é texto livre e pode ter ":" no meio.
        val offline = "transcribe_offline:${quote.startMs}:${quote.text.take(120)}"

        val key = Settings.geminiKey(context)
        if (key.isBlank()) {
            ThreadPool.onUi { onAnswer(offline) }
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
