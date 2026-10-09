package com.pulsa.player.core

import android.content.ContentUris
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

        // Reaproveita o arquivo já criado (o URI guardado), senão cria um por erro.
        val existing = queryMirror(context, resolver, collection)
        if (existing != null) {
            runCatching { resolver.openOutputStream(existing)?.use { it.write(text.toByteArray()) } }
            return
        }
        // Ainda não existe (primeira escrita, ou o usuário apagou o arquivo): cria e guarda
        // o URI. Guardar é o que impede o próximo erro de virar um arquivo novo.
        purgeDuplicates(resolver, collection)
        val uri = resolver.insert(collection, values) ?: return
        Settings.setMirrorLogUri(context, uri.toString())
        runCatching {
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }.onFailure {
            // Falhou em cima: não deixa o registro órfão takingando espaço no Download.
            runCatching { resolver.delete(uri, null, null) }
            Settings.setMirrorLogUri(context, null)
        }
    }

    private fun queryMirror(
        context: Context,
        resolver: android.content.ContentResolver,
        collection: android.net.Uri
    ): android.net.Uri? {
        // O caminho ANTERIOR era consultar por DISPLAY_NAME + RELATIVE_PATH, e era o que
        // criava um arquivo por erro. Duas falhas somadas: o MediaStore acrescenta a
        // extensão do MIME, então o nome real é "pulsa-erros.log.txt" e nunca casava com
        // "pulsa-erros.log"; e em MediaStore.Downloads (Android 11+) não dá para filtrar
        // por RELATIVE_PATH, então a consulta voltava vazia de qualquer jeito. Resultado:
        // 32 cópias de 250 KB no Download, com o insert batendo de frente na anterior e o
        // SO renomeando para "(1)", "(2)"...
        //
        // Agora o URI do espelho é guardado no SharedPreferences e reaproveitado direto.
        // Não depende de nome, de extensão nem de coluna — que era justamente o problema.
        Settings.mirrorLogUri(context)?.let { saved ->
            val uri = android.net.Uri.parse(saved)
            if (exists(resolver, uri)) return uri
        }
        return null
    }

    private fun exists(resolver: android.content.ContentResolver, uri: android.net.Uri): Boolean = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.moveToFirst() } == true
    } catch (t: Throwable) {
        false
    }

    /**
     * Apaga os arquivos que sobraram das colunas de nome antigas.
     *
     * Só roda quando ainda não há URI guardado, e apaga TUDO que casar com o nome-base —
     * inclusive o arquivo canônico, porque o conteúdo antigo é o mesmo que está sendo
     * gravado agora: apagar e recriar não perde nada e garante que a pasta termine com um
     * arquivo só, em vez de um "(1)" antigo convivendo com o novo.
     *
     * A versão anterior filtrava por `RELATIVE_PATH` e nunca encontrava nada, porque em
     * `MediaStore.Downloads` essa coluna não é consultável. Por isso o filtro é só o
     * nome, sem caminho.
     */
    private fun purgeDuplicates(resolver: android.content.ContentResolver, collection: android.net.Uri) {
        try {
            val base = MIRROR_NAME.substringBeforeLast('.')
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("%$base%"),
                null
            )?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val doomed = mutableListOf<Long>()
                while (c.moveToNext()) {
                    if (idCol < 0) break
                    doomed += c.getLong(idCol)
                }
                for (id in doomed) {
                    runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
                }
            }
        } catch (t: Throwable) {
        }
    }

}
