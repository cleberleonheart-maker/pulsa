package com.pulsa.player.podcast

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * F3 — apagar o arquivo de um episódio baixado.
 *
 * **Por que isto existe:** o episódio baixado guarda em `file_path` o `Uri` que o
 * [com.pulsa.player.media.MusicDownloader] ganhou do MediaStore (`content://media/…`). O
 * `File(path).delete()` — a resposta óbvia — não apaga nada nesse caso, porque não existe um
 * arquivo com esse nome: o `File` é construído a partir da string inteira e o `delete()`
 * devolve `false` em silêncio. O efeito é o pior dos dois: a assinatura sai da tela, o banco
 * não tem mais a linha, e o áudio continua ocupando espaço sem nenhuma referência que
 * permita limpá-lo depois.
 *
 * O descarte (`unsubscribe` devolver os caminhos antes de mexer no banco) continua sendo do
 * [PodcastDb]; isto é só o "como se apaga", num lugar só.
 */
object PodcastFiles {

    /**
     * Apaga o arquivo de um episódio. `false` quando não deu — o chamador pode avisar, mas
     * nunca deve travar o cancelamento da assinatura por causa de um arquivo teimoso.
     *
     * Aceita as três formas que chegam aqui: `content://` (o caso normal hoje, MediaStore),
     * `file://` e caminho solto (gravação antiga, quando o download era um arquivo).
     */
    fun delete(context: Context, path: String): Boolean {
        if (path.isBlank()) return false
        val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            null -> File(path).delete()
            "content" -> runCatching {
                context.contentResolver.delete(uri, null, null) > 0
            }.getOrDefault(false)
            "file" -> uri.path?.let { File(it).delete() } ?: false
            else -> false
        }
    }

    /**
     * Tamanho real de um episódio baixado.
     *
     * O `content://` do MediaStore não tem `File` correspondente, então `File(path).length()`
     * devolve `0` — e um teto que soma zero para podcast nunca acusa nada. O tamanho vem da
     * coluna `OpenableColumns.SIZE`, que é a mesma que o sistema usa para mostrar o arquivo.
     */
    fun size(context: Context, path: String): Long {
        if (path.isBlank()) return 0L
        val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return 0L
        return when (uri.scheme?.lowercase()) {
            null -> File(path).length()
            "content" -> runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use 0L
                        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (column < 0 || cursor.isNull(column)) 0L else cursor.getLong(column)
                    } ?: 0L
            }.getOrDefault(0L)
            "file" -> uri.path?.let { File(it).length() } ?: 0L
            else -> 0L
        }
    }
}