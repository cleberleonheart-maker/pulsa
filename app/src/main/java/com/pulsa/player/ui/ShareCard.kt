package com.pulsa.player.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.pulsa.player.R
import com.pulsa.player.core.ThreadPool
import java.io.File

/**
 * F5 · Cartão compartilhável unificado.
 *
 * Antes, compartilhar música mandava o arquivo de áudio e compartilhar vídeo mandava o vídeo:
 * dois caminhos que só sabiam do próprio tipo, e um episódio de podcast não tinha como sair.
 * Aqui os três viram **a mesma coisa** — um cartão quadrado com a capa, o título, o subtítulo
 * e a assinatura — que é o que dá para mandar e ler no zap/status sem depender de o outro
 * aparelho abrir o arquivo.
 *
 * **O que a capa é, por tipo** (quem chama decide, via [art]): música = arte do álbum; vídeo =
 * um frame; episódio = a arte do feed. Sem capa o cartão sai com um quadro vazio e um símbolo
 * musical — nunca sem título, que é a informação que importa.
 *
 * **Por que a montagem roda no [ThreadPool].** A capa de episódio vem por rede e a de música
 * pode vir do MediaStore; as duas seriam `NetworkOnMainThreadException`/engasgo na main.
 * [breakLines] é pura e testada à parte; o desenho em si só o aparelho confere.
 */
object ShareCard {

    private const val SIZE = 1080

    /**
     * Quebra o texto em linhas que caibam em [maxWidth], no máximo [maxLines].
     *
     * Greedy por palavra, com corte duro quando uma palavra sozinha não cabe (título de
     * podcast costuma ter URL ou palavra gigante). Se sobrar texto além de [maxLines], a
     * última linha ganha `…` — e a reticência é desenhada **depois** de tirar o que não cabe,
     * senão ela mesma estoura a largura.
     *
     * É pura de propósito: o desenho não dá para testar aqui, mas a regra de quebra dá, e é
     * onde mora o erro que apareceria como texto saindo do cartão.
     */
    fun breakLines(
        text: String,
        maxWidth: Float,
        maxLines: Int,
        widthOf: (String) -> Float,
        spaceWidth: Float
    ): List<String> {
        if (maxLines <= 0) return emptyList()
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val words = clean.split(" ")

        // Uma palavra maior que a linha é cortada por caractere: sem isto um título com URL
        // sairia inteiro transbordando do cartão.
        fun hardBreak(word: String): List<String> {
            val out = ArrayList<String>()
            var piece = ""
            for (ch in word) {
                val next = piece + ch
                if (piece.isNotEmpty() && widthOf(next) > maxWidth) {
                    out += piece
                    piece = ch.toString()
                } else {
                    piece = next
                }
            }
            if (piece.isNotEmpty()) out += piece
            return out
        }

        fun piecesOf(word: String): List<String> =
            if (widthOf(word) <= maxWidth) listOf(word) else hardBreak(word)

        val lines = ArrayList<String>()
        var current = ""
        for (word in words) {
            if (current.isNotEmpty() &&
                widthOf(current) + spaceWidth + widthOf(word) <= maxWidth
            ) {
                current = "$current $word"
                continue
            }
            if (current.isNotEmpty()) {
                lines += current
                current = ""
            }
            val pieces = piecesOf(word)
            lines += pieces.dropLast(1)
            current = pieces.last()
        }
        if (current.isNotEmpty()) lines += current

        if (lines.size <= maxLines) return lines
        val kept = lines.take(maxLines).toMutableList()
        val ellipsis = "…"
        var last = kept.last()
        while (last.isNotEmpty() && widthOf(last + ellipsis) > maxWidth) {
            last = last.dropLast(1)
        }
        kept[kept.lastIndex] = last.trimEnd() + ellipsis
        return kept
    }

    /**
     * Monta o cartão e abre o compartilhamento.
     *
     * [art] é lazy porque carregar a capa é a parte cara (disco/rede) e ela já roda fora da
     * main aqui dentro — passar um `Bitmap` pronto obrigaria quem chama a bloquear a tela.
     * [context] precisa ser um contexto de tela (Activity): é ele que abre o chooser.
     */
    fun share(context: Context, title: String, subtitle: String, art: () -> Bitmap?) {
        val app = context.applicationContext
        ThreadPool.post {
            val bitmap = runCatching { render(title, subtitle, art()) }.getOrNull()
            val file = bitmap?.let { runCatching { writePng(app, it) }.getOrNull() }
            ThreadPool.onUi {
                if (bitmap == null || file == null) {
                    toast(app, R.string.share_failed)
                    return@onUi
                }
                val text = listOf(title, subtitle).filter { it.isNotBlank() }.joinToString(" - ")
                val ok = runCatching { launch(context, file, text) }.isSuccess
                if (!ok) toast(app, R.string.share_failed)
            }
        }
    }

    private fun launch(context: Context, file: File, text: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text.isNotBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, text)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_music)))
    }

    private fun toast(context: Context, res: Int) {
        android.widget.Toast.makeText(context, res, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun writePng(context: Context, bitmap: Bitmap): File {
        val dir = File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
        // Nome por conteúdo: dois compartilhamentos do mesmo item reaproveitam o arquivo, e o
        // `FileProvider` serve `content://` com o mesmo caminho sem acumular lixo no cache.
        val file = File(dir, "card-${bitmap.width}x${bitmap.height}-${bitmap.hashCode() and 0xffffff}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun render(title: String, subtitle: String, art: Bitmap?): Bitmap {
        val size = SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, size.toFloat(),
                intArrayOf(Color.parseColor("#241046"), Color.parseColor("#0A0A0F")),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)

        // Capa quadrada centrada no terço de cima, com cantos arredondados.
        val coverSize = size * 0.58f
        val coverLeft = (size - coverSize) / 2f
        val coverTop = size * 0.10f
        val cover = RectF(coverLeft, coverTop, coverLeft + coverSize, coverTop + coverSize)
        drawCover(canvas, art, cover)

        // Título e subtítulo abaixo da capa.
        val textLeft = coverLeft
        val textWidth = coverSize
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.062f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C9C3D8")
            textSize = size * 0.042f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val titleLines = breakLines(title, textWidth, 2, { bold.measureText(it) }, bold.measureText(" "))
        val subtitleText = breakLines(subtitle, textWidth, 1, { regular.measureText(it) }, regular.measureText(" "))

        var y = cover.bottom + size * 0.055f + (-bold.fontMetrics.ascent)
        val titleStep = (bold.fontMetrics.descent - bold.fontMetrics.ascent) * 1.12f
        titleLines.forEach { line ->
            canvas.drawText(line, textLeft, y, bold)
            y += titleStep
        }
        val subStep = (regular.fontMetrics.descent - regular.fontMetrics.ascent) * 1.2f
        y += size * 0.015f
        subtitleText.forEach { line ->
            canvas.drawText(line, textLeft, y, regular)
            y += subStep
        }

        // Assinatura no pé do cartão, alinhada à direita.
        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8A7FA8")
            textSize = size * 0.03f
            letterSpacing = 0.35f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("PULSA", cover.right, size - size * 0.03f, footer)

        return bmp
    }

    private fun drawCover(canvas: Canvas, art: Bitmap?, dst: RectF) {
        val radius = dst.width() * 0.06f
        val path = Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        if (art != null) {
            val src = centerCrop(art, dst.width().toInt())
            canvas.drawBitmap(art, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            canvas.drawRect(dst, Paint().apply { color = Color.parseColor("#3A2B5C") })
            val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#8A7FA8")
                textSize = dst.width() * 0.3f
                textAlign = Paint.Align.CENTER
            }
            val cx = dst.centerX()
            val cy = dst.centerY() - (glyph.fontMetrics.ascent + glyph.fontMetrics.descent) / 2f
            canvas.drawText("♪", cx, cy, glyph)
        }
        canvas.restore()
    }

    /** Recorte central quadrado do bitmap de origem, para não esticar a capa. */
    private fun centerCrop(bitmap: Bitmap, target: Int): Rect {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return Rect(0, 0, w, h)
        val side = minOf(w, h)
        val left = (w - side) / 2
        val top = (h - side) / 2
        return Rect(left, top, left + side, top + side)
    }
}
