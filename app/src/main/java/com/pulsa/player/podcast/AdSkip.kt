package com.pulsa.player.podcast

import android.content.Context
import android.util.Log
import com.pulsa.player.core.NetworkStatus
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.db.PulsaDatabase
import com.pulsa.player.dj.DjSuggest
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pula propagandas faladas em podcasts, usando a transcrição já existente.
 *
 * Quando o episódio já foi transcrito (ou logo depois de perguntar sobre ele), o app pede ao
 * Gemini, em lote e com contexto mínimo, para marcar **apenas** os trechos com patrocínio
 * ("trazido por", cupom, link, "inscreva-se com o cupom", leitura de publi) como propaganda.
 * Os trechos são gravados no próprio `segments_json` com o flag `ad`, e o `PlaybackService`
 * pula por eles no `emitProgress` enquanto a opção estiver ligada.
 *
 * Como é uma opção que gasta cota, ela nasce **desligada**: ligada, o app transcreve o
 * episódio no Wi‑Fi (com chave do Gemini) na primeira vez e marca os blocos; depois o banco
 * já tem o flag e o salto é instantâneo. Sem transcrição e sem rede/chave, nada acontece e o
 * antigo `SilenceSkipper` continua cortando as **pausas longas** (o que já existia no item 278);
 * este módulo corta a fala da publicidade, que silêncio sozinho não corta.
 *
 * Tudo o que decide o "pular ou não" é puro ([isInsideAd], [nextAdStart], [skipTarget]) para
 * poder ser testado sem Android.
 */
object AdSkip {
    private const val TAG = "AdSkip"

    /** Respiro máximo entre dois trechos para ainda serem o mesmo bloco de propaganda. */
    private const val BLOCK_GAP_MS = 2_000L

    /** Evita marcar o mesmo episódio mais de uma vez por sessão. */
    private val marked = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Transcrição pronta por episódio — lida no thread principal pelo player a cada tick. */
    private val cache = java.util.concurrent.ConcurrentHashMap<Long, List<TranscriptSegment>>()

    fun enabled(context: Context): Boolean = Settings.adSkip(context)

    /** Segmentos já carregados para [episodeId], ou `null` se ainda não deu tempo de ler o banco. */
    fun cached(episodeId: Long): List<TranscriptSegment>? = cache[episodeId]

    /** Esquece o cache de um episódio (transcrição refeita, por exemplo). */
    fun invalidate(episodeId: Long) {
        cache.remove(episodeId)
        marked.remove(episodeId)
    }

    /**
     * Prepara o cache do episódio que começou a tocar. Barato: só mexe no banco em background
     * e só quando ainda não há transcrição em memória.
     */
    fun watch(context: Context, episodeId: Long) {
        if (episodeId <= 0L) return
        if (cache.containsKey(episodeId)) return
        val app = context.applicationContext
        ThreadPool.post {
            val entity = PulsaDatabase.get(app).podcastDao().transcriptForEpisode(episodeId) ?: return@post
            if (entity.status != "done") return@post
            val segments = PodcastTranscriptParser.fromJson(entity.segmentsJson)
            if (segments.isEmpty()) return@post
            cache[episodeId] = segments
            if (segments.none { it.isAd }) markAdsIfNeeded(app, episodeId)
        }
    }

    /** Chamado quando a transcrição é (re)gerada; mantém cache e marcação em sincronia. */
    fun onTranscriptSaved(context: Context, episodeId: Long, segments: List<TranscriptSegment>) {
        if (segments.isEmpty()) return
        cache[episodeId] = segments
        marked.remove(episodeId)
        if (segments.none { it.isAd }) markAdsIfNeeded(context, episodeId)
    }

    /**
     * Analisa a transcrição existente e marca os trechos com patrocínio. Idempotente: se já
     * há algum segmento com `ad=true` ou o episódio já foi marcado nesta sessão, não refaz.
     */
    fun markAdsIfNeeded(context: Context, episodeId: Long) {
        if (episodeId <= 0) return
        val app = context.applicationContext
        if (!enabled(app)) return
        if (!marked.add(episodeId)) return
        ThreadPool.post {
            val db = PulsaDatabase.get(app)
            val entity = db.podcastDao().transcriptForEpisode(episodeId)
            if (entity?.status != "done") {
                // Sem transcrição ainda: só gera no Wi‑Fi e com chave, porque é caro e demorado.
                if (Settings.geminiKey(app).isBlank() || NetworkStatus.isMetered(app)) {
                    marked.remove(episodeId)
                    return@post
                }
                val ep = PodcastDb.get(app).episode(episodeId)
                if (ep == null) {
                    marked.remove(episodeId)
                    return@post
                }
                PodcastTranscribe.transcribeIfNeeded(app, ep) { ok, _ ->
                    if (!ok) marked.remove(episodeId)
                }
                return@post
            }
            val segments = PodcastTranscriptParser.fromJson(entity.segmentsJson)
            if (segments.isEmpty()) return@post
            if (segments.any { it.isAd }) return@post
            val key = Settings.geminiKey(app)
            if (key.isBlank()) return@post
            val markedSegs = classify(app, segments, key)
            if (markedSegs == null || markedSegs == segments) return@post
            db.podcastDao().upsertTranscript(
                entity.copy(
                    segmentsJson = PodcastTranscriptParser.toJson(markedSegs),
                    fullText = markedSegs.joinToString(" ") { it.text }
                )
            )
            cache[episodeId] = markedSegs
        }
    }

    private fun classify(
        context: Context,
        segments: List<TranscriptSegment>,
        key: String
    ): List<TranscriptSegment>? {
        return try {
            val list = JSONArray()
            segments.forEachIndexed { i, s ->
                list.put(JSONObject().put("i", i).put("t", s.text))
            }
            val prompt = """
Você é um detector de propaganda/patrocínio em podcast em português.

Marque APENAS trechos que sejam explicitamente propaganda: leitura de patrocinador ("patrocínio", "apoiado por", "trazido por"), cupom/desconto com código, indicação de link/aplicativo para comprar, "use o cupom", ofertas com prazo, gravações comerciais integradas.

NUNCA marque: apresentação do programa, agradecimentos genéricos, menção a rede/produtor, indicação de episódio, despedida.

Responda APENAS com um array JSON: [{"i": 0, "ad": true}, ...]
Inclua SOMENTE os índices que forem propaganda. Se não houver nenhum, responda [].
""".trim() + "\n\n" + list.toString()

            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject()
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject()
                    .put("temperature", 0.1)
                    .put("maxOutputTokens", 1024))

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${DjSuggest.MODEL}:generateContent")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 20000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val resp = conn.inputStream.bufferedReader().readText()
            val text = extractText(resp)
            val start = text.indexOf('[')
            val end = text.lastIndexOf(']')
            if (start < 0 || end <= start) return null
            val arr = JSONArray(text.substring(start, end + 1))
            val adIdx = mutableSetOf<Int>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("ad", false)) adIdx.add(o.optInt("i"))
            }
            if (adIdx.isEmpty()) return segments
            segments.mapIndexed { i, s -> if (adIdx.contains(i)) s.copy(isAd = true) else s }
        } catch (t: Throwable) {
            Log.w(TAG, "falha ao marcar propaganda", t)
            null
        }
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

    // ---- decisão pura para o player ------------------------------------------------------

    /** Retorna `true` se a posição cai dentro de algum trecho marcado como propaganda. */
    fun isInsideAd(segments: List<TranscriptSegment>, posMs: Long): Boolean {
        val seg = currentAdSegment(segments, posMs) ?: return false
        return posMs >= seg.startMs && posMs < seg.endMs
    }

    /** Segmento de propaganda no qual estamos agora, ou `null`. */
    fun currentAdSegment(segments: List<TranscriptSegment>, posMs: Long): TranscriptSegment? {
        if (segments.isEmpty()) return null
        return segments.firstOrNull { it.isAd && posMs >= it.startMs && posMs < it.endMs }
    }

    /** Início em ms do próximo bloco de propaganda a partir de [posMs] (inclusive deste bloco), ou `null`. */
    fun nextAdStart(segments: List<TranscriptSegment>, posMs: Long): Long? {
        if (segments.isEmpty()) return null
        return segments.filter { it.isAd && it.startMs >= posMs - 200L }
            .minOfOrNull { it.startMs }
    }

    /**
     * Se estivermos dentro de propaganda, devolve o ponto exato para onde pular.
     *
     * Um bloco de patrocínio costuma vir em vários trechos de 10–30 s; pular para o fim do
     * trecho atual faria 3–4 saltos seguidos (e um soluço a cada um). Aqui os trechos
     * vizinhos marcados são encadeados e o salto vai direto ao fim do bloco. `null` = não pular.
     */
    fun skipTarget(segments: List<TranscriptSegment>, posMs: Long): Long? {
        val idx = segments.indexOfFirst { it.isAd && posMs >= it.startMs && posMs < it.endMs }
        if (idx < 0) return null
        var end = segments[idx].endMs
        var i = idx + 1
        while (i < segments.size) {
            val next = segments[i]
            if (!next.isAd) break
            // Propaganda contígua; um respiro curto entre trechos ainda é o mesmo bloco.
            if (next.startMs - end > BLOCK_GAP_MS) break
            if (next.endMs > end) end = next.endMs
            i++
        }
        return if (end > posMs) end else null
    }
}
