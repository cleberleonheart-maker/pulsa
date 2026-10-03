package com.pulsa.player.playback

import java.util.Locale
import kotlin.math.abs

/**
 * F2 — os passos de velocidade do vídeo.
 *
 * A lista é uma decisión de interface, não do motor: [VALUES] é o que o botão percorre
 * e o que o rótulo mostra. Ela é **limitada de propósito**. O `setPlaybackSpeed` do Media3
 * aceita qualquer valor até 4x (e o Media3 ainda faz *time stretching* para compensação de
 * pitch acima de 4x, que distorce a voz), mas oferecer 0,37x ou 1,73x num botão que só
 * mostra o número não ajuda ninguém — o passo tem que ser reconhecível de relance.
 *
 * Passos escolhidos: 0,5x para ver os detalhes/ouvir o áudio, 0,75x, o 1x normal, 1,25x/1,5x para
 * vídeo longo e 2x para pular trecho parado. O 1x está no meio da lista, não no fim: quem
 * aperta o botão para acelerar tem o caminho mais curto a partir do normal, e quem quer
 * voltar ao normal não precisa percorrer tudo.
 */
object PlaybackSpeeds {
    val VALUES = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    const val MIN = 0.5f
    const val MAX = 2.0f

    /** Rótulo curto pro botão: "1x", "1,5x". Sem casa decimal quando é inteiro. */
    fun label(value: Float): String {
        val v = (value * 100).toInt() / 100f
        return if (abs(v - v.toInt()) < 0.01f) "${v.toInt()}x"
        else "${String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')}x"
    }
}
