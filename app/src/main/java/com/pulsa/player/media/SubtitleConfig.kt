package com.pulsa.player.media

import android.content.Context
import androidx.media3.common.MediaItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * F2 — guarda a legenda que o usuário escolheu, para não perder a cada abertura da tela.
 *
 * **Por que preciso disso.** O `MediaItem.SubtitleConfiguration` que o usuário escolhe morre
 * junto com o `MediaItem`, e a fila é montada de novo a cada `Playback.start()`. Sem guardar,
 * a legenda escolhida "desaparecia" no próximo vídeo e o usuário tinha que repetir a operação
 * toda vez — o botão viraria enfeite.
 *
 * Guardamos só os **identificadores** (o que é), não o `MediaItem` (o que o player usa). A
 * trilha é reconstruída a partir da lista salva na hora de abrir a tela, e revalidada contra
 * o que a convenção de nome já tinha achado: o que o usuário escolheu tem prioridade, o resto
 * some. Se o arquivo escolhido foi apagado do cartão, a entrada não some da config — só deixa
 * de resolver, e o vídeo volta à legenda automática.
 */
object SubtitleConfig {

    private const val FILE = "subtitle_config"
    private const val KEY_PREFIX = "sub_"
    private const val LEGEND = "legenda"

    /**
     * Mime types para o seletor de arquivos do sistema.
     *
     * Inclui os "x-" que o Android às vezes usa de verdade (é mais comum o Files do
     * provedor marcar `.srt` como `text/plain`), porque se a lista não casar o seletor
     * esconde os arquivos e o usuário conclui que "o app não tem legenda".
     */
    val MIME_FILTER = arrayOf(
        "application/x-subrip",
        "text/vtt",
        "text/plain",
        "text/ssa",
        "text/x-ssa"
    )

    data class Track(val uri: String, val mimeType: String, val label: String) {
        fun toLoaded(): Subtitles.Loaded =
            Subtitles.Loaded(android.net.Uri.parse(uri), mimeType, LEGEND)
    }

    /** Trilhas salvas para um `mediaId` (id do vídeo do MediaStore ou hash da URL do stream). */
    fun tracks(context: Context, mediaId: Long): List<Track> {
        val raw = prefs(context).getString(KEY_PREFIX + mediaId, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString("uri").ifBlank { return@mapNotNull null }
                Track(
                    uri = uri,
                    mimeType = o.optString("mime").ifBlank { "application/x-subrip" },
                    label = o.optString("label").ifBlank { LEGEND }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, mediaId: Long, tracks: List<Track>) {
        val editor = prefs(context).edit()
        if (tracks.isEmpty()) {
            editor.remove(KEY_PREFIX + mediaId)
        } else {
            val arr = JSONArray()
            tracks.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("uri", t.uri)
                        .put("mime", t.mimeType)
                        .put("label", t.label)
                )
            }
            editor.putString(KEY_PREFIX + mediaId, arr.toString())
        }
        editor.apply()
    }

    fun clear(context: Context, mediaId: Long) = save(context, mediaId, emptyList())

    /**
     * Aplica a config salva em cima do item, removendo o que estava lá antes.
     *
     * A convenção de nome é montada pelo [Subtitles.attach] e entra no `base`; as escolhidas
     * vêm da config e **substituem** tudo. Devolve o `base` intacto quando não há config,
     * para o chamador não precisar checar.
     */
    fun apply(context: Context, base: MediaItem, mediaId: Long): MediaItem {
        val saved = tracks(context, mediaId)
        if (saved.isEmpty()) return base
        return Subtitles.withSubtitle(base, saved.map { it.toLoaded() })
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
