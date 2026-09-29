package com.pulsa.player.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes

/**
 * Legendas de video: acha o arquivo `.srt`/`.vtt` que acompanha o video e entrega pronto
 * para o Media3 desenhar.
 *
 * E feito por CONVENCIAO de nome, nao por configuracao: `meu-video.mp4` pega
 * `meu-video.srt` (e tambem `.vtt`, `.ass`, com maiusculas ou minusculas). Nao ha como
 * escolher a legenda na UI, o que e de proposito — o alvo e o caso comum (baixou o video
 * e a legenda veio junto), nao um reprodutor de legenda profissional.
 *
 * A busca e pelo MediaStore, e nao por `File` no caminho do video: no Android 10+ o app
 * nao tem acesso de leitura ao arquivo por caminho quando ele nao e proprio, e o video
 * so esta no MediaStore mesmo. Por isso procuramos pelo `DISPLAY_NAME` do video, com o
 * prefixo de nome, e nao por "pastaZT".
 */
object Subtitles {

    private val EXTS = listOf("srt", "vtt", "ass", "ssa")
    private val MIME = mapOf(
        "srt" to MimeTypes.APPLICATION_SUBRIP,
        "vtt" to MimeTypes.TEXT_VTT,
        "ass" to MimeTypes.TEXT_SSA,
        "ssa" to MimeTypes.TEXT_SSA
    )

    /**
     * Monta o MediaItem ja com a legenda anexada, ou null se o video nao tem.
     *
     * `base` precisa ser o MediaItem sem legenda — este metodo devolve uma copia, porque
     * o item original pode estar em uso na fila.
     */
    fun attach(context: Context, base: MediaItem): MediaItem? {
        val videoId = base.localConfiguration?.uri?.let { idOf(it) } ?: return null
        val name = displayNameOf(context, videoId) ?: return null
        val stem = name.substringBeforeLast('.', name)
        if (stem.isEmpty()) return null
        val sub = findSubtitle(context, stem) ?: return null
        return base.buildUpon()
            .setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(sub.uri)
                        .setMimeType(MIME[sub.ext] ?: MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("pt")
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
            .build()
    }

    private fun idOf(uri: Uri): Long? = runCatching {
        uri.lastPathSegment?.toLongOrNull()
    }.getOrNull()

    private fun displayNameOf(context: Context, videoId: Long): String? = runCatching {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()

    /**
     * Procura `<stem>.<ext>` em qualquer um dos formatos, comparando o nome sem a
     * extensao. O MediaStore nao deixa filtrar "ends with" de forma confiavel e o volume
     * de sub e pequeno, entao a gente filtra em memoria.
     */
    private fun findSubtitle(context: Context, stem: String): Sub? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE
        )
        return runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                // Só filtra por MIME: MediaColumns não tem MEDIA_TYPE, e o media_type
                // fica em Files.FileColumns (que só existe na coluna do provider de
                // arquivos, não na projection do MediaColumns).
                "${MediaStore.MediaColumns.MIME_TYPE} IN (?,?,?,?)",
                arrayOf(
                    MimeTypes.APPLICATION_SUBRIP,
                    MimeTypes.TEXT_VTT,
                    MimeTypes.TEXT_SSA,
                    "application/x-ssa"
                ),
                null
            )?.use { c ->
                val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                var best: Sub? = null
                while (c.moveToNext()) {
                    if (nameCol < 0 || mimeCol < 0) break
                    val name = c.getString(nameCol) ?: continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in EXTS) continue
                    val fileStem = name.substringBeforeLast('.', name)
                    if (!fileStem.equals(stem, ignoreCase = true)) continue
                    // srt primeiro: e o formato mais comum e o que o Android converte melhor.
                    val rank = if (ext == "srt") 0 else 1
                    if (best == null || rank < best.rank) {
                        best = Sub(
                            uri = ContentUris.withAppendedId(
                                MediaStore.Files.getContentUri("external"),
                                c.getLong(0)
                            ),
                            ext = ext,
                            rank = rank
                        )
                    }
                }
                best
            }
        }.getOrNull()
    }

    private data class Sub(val uri: Uri, val ext: String, val rank: Int)
}
