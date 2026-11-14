package com.pulsa.player.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

data class ConverterSourceInfo(
    val name: String,
    val mime: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    /**
     * `false` quando não foi possível saber o que o arquivo tem dentro.
     *
     * A diferença importa: "não tem vídeo" desliga o MP4 na tela, mas "não sei" não pode
     * desabilitar nada — senão um link que o sistema não conseguiu ler virava "só áudio".
     */
    val tracksKnown: Boolean = true
)

object ConverterSourceInspector {

    fun inspect(context: Context, uri: Uri): ConverterSourceInfo {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
        }
        var duration = 0L
        var width = 0
        var height = 0
        var mime = resolver.getType(uri).orEmpty()
        runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            if (mime.isBlank()) mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            retriever.release()
        }
        var hasVideo = false
        var hasAudio = false
        var tracksKnown = false
        runCatching {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val trackMime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (trackMime.startsWith("video/")) hasVideo = true
                if (trackMime.startsWith("audio/")) hasAudio = true
            }
            extractor.release()
            tracksKnown = true
        }
        if (!tracksKnown) {
            if (mime.startsWith("video/")) {
                hasVideo = true
                tracksKnown = true
            }
            if (mime.startsWith("audio/")) {
                hasAudio = true
                tracksKnown = true
            }
        }
        return ConverterSourceInfo(
            name = name.ifBlank { "arquivo" },
            mime = mime,
            durationMs = duration,
            width = width,
            height = height,
            sizeBytes = size,
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            tracksKnown = tracksKnown
        )
    }

    /**
     * Os metadados de um **link direto**, sem baixar o arquivo.
     *
     * O `MediaMetadataRetriever` e o `MediaExtractor` leem só o cabeçalho do container
     * (pedido HTTP de intervalo). Sem isso, a conversão de um link não sabia a duração — e,
     * com ela, nem o tamanho final (que é o que o teto de espaço olha) — nem se o arquivo
     * tinha vídeo, e o corte por tempo ficava sem chão.
     *
     * Devolve `null` quando nada pôde ser lido: aí é melhor não saber do que mentir.
     */
    fun inspectUrl(url: String, name: String = ""): ConverterSourceInfo? {
        if (url.isBlank()) return null
        var duration = 0L
        var width = 0
        var height = 0
        var mime = ""
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, emptyMap())
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
        } catch (t: Throwable) {
            duration = 0L
        } finally {
            runCatching { retriever.release() }
        }
        var hasVideo = false
        var hasAudio = false
        var tracksKnown = false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(url, emptyMap())
            for (index in 0 until extractor.trackCount) {
                val trackMime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (trackMime.startsWith("video/")) hasVideo = true
                if (trackMime.startsWith("audio/")) hasAudio = true
            }
            tracksKnown = true
        } catch (t: Throwable) {
            tracksKnown = false
        } finally {
            runCatching { extractor.release() }
        }
        if (!tracksKnown && width > 0) {
            hasVideo = true
            tracksKnown = true
        }
        if (duration <= 0L && !tracksKnown) return null
        return ConverterSourceInfo(
            name = name.ifBlank { "link" },
            mime = mime,
            durationMs = duration,
            width = width,
            height = height,
            sizeBytes = 0L,
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            tracksKnown = tracksKnown
        )
    }
}
