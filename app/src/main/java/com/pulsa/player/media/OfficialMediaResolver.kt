package com.pulsa.player.media

import android.os.Looper
import com.pulsa.player.core.ThreadPool
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * #277 – Tenta resolver links de páginas para links diretos via APIs oficiais/públicas.
 *
 * As fontes e o trabalho puro de cada uma estão em [OfficialSources]; aqui está só a rede, o
 * cache e a fila em background. A separação é o que permite testar a parte que decide sem
 * aparelho e sem rede.
 */
object OfficialMediaResolver {

    data class Resolved(val directUrl: String, val name: String?)

    /**
     * Cache de resoluções bem-sucedidas. Os links resolvidos são estáveis, e mais
     * importante: a validação de UI (`UrlDownloader.validate`) é sincrona e não pode
     * tocar na rede. Ela só consulta este cache.
     */
    private val cache = ConcurrentHashMap<String, Resolved>()

    private fun key(url: String): String = url.trim().lowercase()

    /** Resultado já resolvido para esta URL, se houver. Nunca faz rede — seguro no main. */
    fun cached(input: String): Resolved? = cache[key(input)]

    /**
     * Resolve fora da thread principal e devolve o resultado na UI. É o caminho usado
     * pelos botões: `resolve()` faz HTTP de propósito e no main thread lançaria
     * `NetworkOnMainThreadException` (que antes era engolido e virava "link de página").
     */
    fun resolveAsync(input: String, onResult: (Resolved?) -> Unit) {
        val url = input.trim()
        val hit = cached(url)
        if (hit != null) {
            onResult(hit)
            return
        }
        ThreadPool.postNetwork {
            val result = resolve(url)
            ThreadPool.onUi { onResult(result) }
        }
    }

    fun resolve(input: String): Resolved? {
        val url = input.trim()
        if (url.isEmpty()) return null
        cached(url)?.let { return it }
        if (isDirectMedia(url)) return null
        // Rede no main thread falharia silenciosamente (NetworkOnMainThreadException).
        if (Looper.myLooper() == Looper.getMainLooper()) return null

        val resolved = when (OfficialSources.sourceOf(url)) {
            OfficialSources.Source.APPLE -> resolveApple(url)
            OfficialSources.Source.DEEZER -> resolveDeezer(url)
            OfficialSources.Source.ARCHIVE -> resolveArchive(url)
            null -> null
        }
        if (resolved != null) cache[key(url)] = resolved
        return resolved
    }

    private fun isDirectMedia(url: String): Boolean {
        val u = url.lowercase()
        val path = u.substringBefore('?')
        return path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac") ||
            path.endsWith(".ogg") || path.endsWith(".opus") || path.endsWith(".flac") ||
            path.endsWith(".wav") || path.endsWith(".mp4") || path.endsWith(".m4v") ||
            path.endsWith(".webm") || path.endsWith(".mov")
    }

    /**
     * Apple Music / iTunes: a faixa (`?i=`), o álbum ou a faixa pelo id do caminho.
     *
     * O `lookup` da Apple devolve o preview da faixa — 30 segundos, que é o que a loja
     * distribui. É o limite honesto dessa API: o arquivo completo não existe para o
     * developer.
     */
    private fun resolveApple(url: String): Resolved? {
        val id = OfficialSources.appleLookupId(url) ?: return null
        val collection = OfficialSources.appleIsCollection(url)
        val body = getText(OfficialSources.appleLookupUrl(id, collection)) ?: return null
        val (preview, name) = OfficialSources.applePreview(body) ?: return null
        return Resolved(preview, name)
    }

    /** Deezer: a faixa ou a primeira faixa do álbum. Preview de 30 s, como o da Apple. */
    private fun resolveDeezer(url: String): Resolved? {
        val (id, album) = OfficialSources.deezerId(url) ?: return null
        val body = getText(OfficialSources.deezerLookupUrl(id, album)) ?: return null
        val (preview, name) = OfficialSources.deezerPreview(body) ?: return null
        return Resolved(preview, name)
    }

    /**
     * Internet Archive: o arquivo de áudio original do item.
     *
     * Este é o único dos três que entrega a faixa **completa**: o Archive hospeda o arquivo
     * que o próprio autor publicou, e o download é direto, sem DRM e sem limite de tamanho.
     */
    private fun resolveArchive(url: String): Resolved? {
        val id = OfficialSources.archiveIdentifier(url) ?: return null
        val body = getText(OfficialSources.archiveMetadataUrl(id)) ?: return null
        val (audio, title) = OfficialSources.archiveAudio(id, body) ?: return null
        return Resolved(audio, title)
    }

    /**
     * O corpo da resposta, ou `null`.
     *
     * `null` é o que o chamador entende: a fonte respondeu, mas não com o que a gente
     * precisa. A diferença importa para a mensagem na tela — "não tem preview para esta
     * faixa" é muito melhor do que "link inválido".
     */
    private fun getText(url: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Pulsa")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
            conn.disconnect()
            text.ifBlank { null }
        } catch (t: Throwable) {
            null
        }
    }
}
