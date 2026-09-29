package com.pulsa.player.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Log de erros do app.
 *
 * Por que dois destinos: o arquivo principal fica no diretório externo do próprio app
 * (`Android/data/com.pulsa.player`), que é o lugar que nunca pede permissão e nunca é
 * limpado pelo sistema — mas que **ninguém consegue ler** a partir do Android 11. Nem outro
 * app, nem `adb`, nem o Termux em proot. Isso já custou rodadas inteiras de depuração: o
 * crash acontecia, o log existia, e o único jeito de ver o stack era o app mostrar na tela.
 *
 * Então, além do principal, cada escrita é espelhada em `Download/pulsa-erros.log`, via
 * MediaStore. Esse é o mesmo diretório que o Termux em proot já lê normalmente, então dá
 * para depurar de verdade:
 *
 *     unzip -p <apk> ... ; tail /storage/emulated/0/Download/pulsa-erros.log
 *
 * O espelho é o MESMO nome sempre, e reescrito por inteiro a cada vez. Sem isso seriam 60
 * arquivos acumulados por dia de teste, e o Termux não tem como ler diretório do app.
 */
object CrashLogger {

    private const val MAX_BYTES = 250_000
    private const val LOG_NAME = "pulsa.log"

    /** Nome do espelho legível. Fixo de propósito — é o que se digita para ler. */
    private const val MIRROR_NAME = "pulsa-erros.log"
    private const val MIRROR_DIR = "Download/Pulsa"

    private val lock = Any()

    fun writeLog(context: Context, text: String) {
        synchronized(lock) {
            val line = "\n== ${stamp()} ==\n$text\n"
            val merged = append(readPrimary(context), line)
            writePrimary(context, merged)
            // Espelho fora do `try`: uma falha aqui não pode derrubar quem estava
            // reportando o erro original.
            runCatching { writeMirror(context, merged) }
        }
    }

    /** Lê o log atual (usado pela tela de diagnóstico e pelo espelho). */
    fun readLog(context: Context): String = synchronized(lock) { readPrimary(context) }

    /**
     * Devolve um arquivo legível para compartilhar/inspecionar, se existir.
     *
     * Prefere o espelho no `Download` (que sobrevive à limpeza de cache e é legível por
     * qualquer ferramenta) e cai para o arquivo interno se ele não existir ainda.
     */
    fun shareableFile(context: Context): File? = synchronized(lock) {
        val internal = context.getExternalFilesDir(null)?.let { File(it, LOG_NAME) }
        internal?.takeIf { it.exists() && it.length() > 0 }
    }

    private fun stamp() =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())

    private fun append(prior: String, line: String): String {
        val full = prior + line
        return if (full.length > MAX_BYTES) full.substring(full.length - MAX_BYTES) else full
    }

    private fun readPrimary(context: Context): String = try {
        val dir = context.getExternalFilesDir(null)
        val file = dir?.let { File(it, LOG_NAME) }
        if (file != null && file.exists()) file.readText() else ""
    } catch (t: Throwable) {
        ""
    }

    private fun writePrimary(context: Context, text: String) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            File(dir, LOG_NAME).writeText(text)
        } catch (t: Throwable) {
        }
    }

    /**
     * Grava o espelho em `Download/Pulsa/pulsa-erros.log` pelo MediaStore.
     *
     * Usa MediaStore (e não `File` direto) porque do Android 10 o `Download` é armazenamento
     * com escopo: escrita direta por caminho throws `FileNotFoundException`, e o Termux em
     * proot não tem permissão de escrita ali. O MediaStore não pede permissão nenhuma para
     * o próprio app nos arquivos que ele cria.
     *
     * Reaproveita a linha existente pelo mesmo DISPLAY_NAME para não criar um arquivo por
     * erro — e reescreve do zero, porque o conteúdo é sempre o acumulado.
     */
    private fun writeMirror(context: Context, text: String) {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Files.getContentUri("external")
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, MIRROR_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, MIRROR_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
        }

        // Tenta achar o que já existe; o `insert` de um DISPLAY_NAME repetido criaria duplicata.
        val existing = queryMirror(resolver, collection)
        if (existing != null) {
            resolver.openOutputStream(existing)?.use { it.write(text.toByteArray()) }
            return
        }
        val uri = resolver.insert(collection, values) ?: return
        runCatching {
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }.onFailure {
            // Falhou em cima: não deixa o registro órfão takingando espaço no Download.
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    private fun queryMirror(
        resolver: android.content.ContentResolver,
        collection: android.net.Uri
    ): android.net.Uri? = try {
        // Filtra também pelo RELATIVE_PATH: sem isso, um arquivo com o mesmo nome em outro
        // diretório do Download casava e a escrita iria para o arquivo errado.
        val selection = buildString {
            append("${MediaStore.MediaColumns.DISPLAY_NAME}=?")
            append(" AND ${MediaStore.MediaColumns.MIME_TYPE}=?")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            }
        }
        val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(MIRROR_NAME, "text/plain", "$MIRROR_DIR/")
        } else {
            arrayOf(MIRROR_NAME, "text/plain")
        }
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                android.content.ContentUris.withAppendedId(collection, c.getLong(0))
            } else {
                null
            }
        }
    } catch (t: Throwable) {
        null
    }

}
