package com.pulsa.player.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.net.URL

/**
 * Legendas de vídeo.
 *
 * **Duas formas de chegar numa legenda**, e as duas convivem:
 *
 * 1. **Por convenção de nome** (o comportamento antigo): `meu-video.mp4` pega o
 *    `meu-video.srt` que estiver no aparelho. É o caso comum — baixou o vídeo e a legenda veio
 *    junto — e não pede configuração nenhuma.
 * 2. **Por escolha do usuário**: um `.srt`/`.vtt` colado por link, ou apontado pelo seletor
 *    de arquivos, para um vídeo que não tem legenda do lado. É o único jeito de legenda em
 *    **stream**, que por definição não tem arquivo local.
 *
 * A busca da (1) é pelo MediaStore e não por `File` no caminho do vídeo: no Android 10+ o
 * app não tem leitura do arquivo por caminho quando ele não é próprio, e o vídeo só está no
 * MediaStore mesmo. Por isso casamos pelo `DISPLAY_NAME`, não por pasta.
 */
@UnstableApi
object Subtitles {

    private val EXTS = listOf("srt", "vtt", "ass", "ssa")
    private val MIME = mapOf(
        "srt" to MimeTypes.APPLICATION_SUBRIP,
        "vtt" to MimeTypes.TEXT_VTT,
        "ass" to MimeTypes.TEXT_SSA,
        "ssa" to MimeTypes.TEXT_SSA
    )
    private val MIME_ALT = mapOf(
        "srt" to listOf("application/x-subrip"),
        "vtt" to listOf("text/vtt"),
        "ass" to listOf("text/x-ssa", "application/x-ssa"),
        "ssa" to listOf("text/x-ssa", "application/x-ssa")
    )

    /** Uma legenda carregada. [owned] é um cache temporário que o app pode apagar. */
    data class Loaded(val uri: Uri, val mimeType: String, val language: String, val owned: Boolean = false)

    /**
     * Monta o `MediaItem` com a legenda da convenção de nome anexada, ou null se não houver.
     * `base` precisa ser o item sem legenda — devolve cópia, porque o original está em uso.
     */
    fun attach(context: Context, base: MediaItem): MediaItem? {
        val uri = autoUri(context, base) ?: return null
        return withSubtitle(base, listOf(Loaded(uri, MimeTypes.APPLICATION_SUBRIP, "pt")))
    }

    /**
     * O `Uri` da legenda que a convenção de nome acharia, ou null se o vídeo não tem par.
     *
     * Separado do [attach] porque o diálogo precisa **oferecer** a opção "Automático" pelo
     * nome do arquivo que seria usado — se só existisse o `MediaItem` já montado, a UI não
     * teria como mostrar "Automático: meu-video.srt".
     */
    fun autoUri(context: Context, base: MediaItem): Uri? {
        val videoId = base.localConfiguration?.uri?.let { idOf(it) } ?: return null
        val name = displayNameOf(context, videoId) ?: return null
        val stem = name.substringBeforeLast('.', name)
        if (stem.isEmpty()) return null
        return findSubtitle(context, stem)?.uri
    }

    /**
     * Carrega uma legenda por URL http(s) e devolve o `MediaItem` pronto.
     *
     * O arquivo vai para o cache do app: uma legenda remota é usada uma vez e não vale
     * guardar para sempre, e apontar o `MediaItem` direto para a URL obrigaria o Media3 a
     * usar o `DataSource` de streaming — que é o mesmo do vídeo, então funciona, mas
     * qualquer retry reabre a rede. Com o arquivo local, o player lê do disco.
     *
     * Chamada em `ThreadPool` (toca a rede).
     */
    fun attachFromUrl(context: Context, base: MediaItem, url: String, language: String = "pt"): MediaItem? {
        val ext = extOf(url) ?: return null
        val mime = mimeOf(ext) ?: return null
        val file = File(context.cacheDir, "sub_${System.currentTimeMillis()}.$ext")
        val conn = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("User-Agent", "Pulsa/1.0 (Android)")
            setRequestProperty("Accept", "text/vtt, application/x-subrip, */*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            conn.inputStream.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            file.delete()
            return null
        } finally {
            conn.disconnect()
        }
        // Vazio não é legenda: sem `-->`, nenhum parser extrai nada e o vídeo parece
        // "sem legenda" mesmo com um arquivo ligado.
        if (file.length() < 16L) {
            file.delete()
            return null
        }
        val uri = Uri.fromFile(file)
        return withSubtitle(base, listOf(Loaded(uri, mime, language, owned = true)))
    }

    /**
     * Monta o `MediaItem` com as trilhas dadas, marcando a primeira como padrão.
     *
     * Sempre **substitui** as trilhas que havia, nunca soma. O parâmetro `replace` foi
     * removido de propósito: o Media3 1.3.1 não tem o `SubtitleParser.Merging`, que junta
     * várias trilhas numa saída só. Manter as duas faria a `SubtitleView` escolher uma e
     * esconder a outra sem nenhum controle, e a legenda que a pessoa escolheu tem
     * prioridade sobre a de convenção de nome — então substituir é o comportamento certo.
     */
    @JvmStatic
    fun withSubtitle(base: MediaItem, tracks: List<Loaded>): MediaItem {
        if (tracks.isEmpty()) return base
        val all = tracks
        return base.buildUpon()
            .setSubtitleConfigurations(
                all.mapIndexed { i, t ->
                    MediaItem.SubtitleConfiguration.Builder(t.uri)
                        .setMimeType(t.mimeType)
                        .setLanguage(t.language)
                        // Não existe "SELECTION_FLAG_NONE": as flags são bit a bit e 0 já
                        // significa nenhuma. Só a primeira trilha entra como selecionada.
                        .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                }
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
     * Procura `<stem>.<ext>` em qualquer um dos formatos, comparando o nome sem a extensão.
     * O MediaStore não deixa filtrar "ends with" de forma confiável e o volume de sub é
     * pequeno, então filtramos em memória.
     */
    private fun findSubtitle(context: Context, stem: String): Sub? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        // Só filtra por MIME: `MediaColumns` não tem `MEDIA_TYPE`, e ele fica em
        // `Files.FileColumns` (que não existe na projection do `MediaColumns`). Incluímos as
        // variantes que o provedor grava de verdade — o `MimeTypes` do Media3 é o ideal e o
        // Android nem sempre grava nenhum dos dois.
        val accepted = (MIME.values + MIME_ALT.values.flatten()).distinct()
        return runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                "${MediaStore.MediaColumns.MIME_TYPE} IN (${accepted.joinToString(",") { "?" }})",
                accepted.toTypedArray(),
                null
            )?.use { c ->
                val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                var best: Sub? = null
                while (c.moveToNext()) {
                    if (nameCol < 0) break
                    val name = c.getString(nameCol) ?: continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in EXTS) continue
                    val fileStem = name.substringBeforeLast('.', name)
                    if (!fileStem.equals(stem, ignoreCase = true)) continue
                    // srt primeiro: é o formato mais comum e o que o Android converte melhor.
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

    /** Extensão do arquivo de legenda, olhando a query string antes do caminho. */
    fun extOf(urlOrName: String): String? {
        val withoutQuery = urlOrName.substringBefore('?').substringBefore('#')
        val last = withoutQuery.substringAfterLast('/')
        val ext = last.substringAfterLast('.', "").lowercase()
        return ext.takeIf { it in EXTS }
    }

    private fun mimeOf(ext: String): String? = MIME[ext]

    private data class Sub(val uri: Uri, val ext: String, val rank: Int)
}
