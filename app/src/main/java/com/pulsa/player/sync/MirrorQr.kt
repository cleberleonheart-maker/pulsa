package com.pulsa.player.sync

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR do código de "ouvir juntos": o host desenha, o convidado escaneia.
 *
 * O que entra no QR é uma URI `pulsa://sessao/<CODIGO>` em vez do código cru, para a câmera
 * de qualquer aparelho reconhecer como link do Pulsa, e para o nosso leitor descartar QRs que
 * não sejam da sessão. [codeFrom] aceita as duas formas (URI e código cru) — assim quem colar
 * o texto à mão também funciona.
 *
 * [encode]/[codeFrom] são puras de propósito: é a parte que dá para testar sem câmera nem
 * tela, e onde mora o erro que apareceria como "código inválido" lendo um QR legítimo.
 */
object MirrorQr {

    private const val PREFIX = "pulsa://sessao/"

    /** Código de sessão: letras/dígitos, curto. Casa com o formato que o servidor devolve. */
    private val CODE = Regex("^[A-Z0-9]{3,12}$")

    fun encode(code: String): String = PREFIX + code.trim().uppercase()

    /** Extrai o código de um QR lido (ou de um texto colado). `null` se não parecer da sessão. */
    fun codeFrom(scanned: String?): String? {
        val text = scanned?.trim().orEmpty()
        if (text.isEmpty()) return null
        val raw = if (text.startsWith(PREFIX, ignoreCase = true)) {
            text.substring(PREFIX.length)
        } else {
            text
        }
        val code = raw.trim().trim('/').uppercase()
        return if (CODE.matches(code)) code else null
    }

    /** Desenha o QR de [text] num quadrado de [sizePx]. Roda fora da main (é pesado em pixels). */
    fun bitmap(text: String, sizePx: Int): Bitmap? = runCatching {
        val size = sizePx.coerceAtLeast(64)
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }.getOrNull()
}
