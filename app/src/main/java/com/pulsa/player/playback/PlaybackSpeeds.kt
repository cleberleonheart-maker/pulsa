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

    /** A velocidade "normal" — o primeiro lugar que o anúncio da Virgin procura. */
    const val NORMAL = 1f

    /** Rótulo curto pro botão: "1x", "1,5x". Sem casa decimal quando é inteiro. */
    fun label(value: Float): String {
        val v = (value * 100).toInt() / 100f
        return if (abs(v - v.toInt()) < 0.01f) "${v.toInt()}x"
        else "${String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')}x"
    }

    /**
     * O próximo passo da lista a partir de [current], subindo ou descendo [up].
     *
     * A lista é uma roda: `2x` acima volta a `0,5x`, e `0,5x` abaixo volta a `2x` — o botão
     * nunca trava num canto. Se [current] não estiver na lista (veio de fora), parte do normal.
     * Pura, para o teste cobrir o giro inteiro nos dois sentidos.
     */
    fun step(current: Float, up: Boolean): Float {
        var i = VALUES.indexOfFirst { abs(it - current) < 0.01f }
        if (i < 0) i = VALUES.indexOfFirst { abs(it - NORMAL) < 0.01f }
        if (i < 0) i = 0
        val delta = if (up) 1 else -1
        return VALUES[(i + delta).mod(VALUES.size)]
    }

    /**
     * A fala da Virgin: "1,5x" vira "um e meio".
     *
     * O TTS leria o rótulo cru como "um cinco x" — por isso os passos conhecidos têm palavra,
     * e qualquer valor fora da lista cai no rótulo mesmo. Pura, para o teste conferir.
     */
    fun speech(value: Float): String = when {
        abs(value - 0.5f) < 0.01f -> "meio"
        abs(value - 0.75f) < 0.01f -> "tres quartos"
        abs(value - NORMAL) < 0.01f -> "normal"
        abs(value - 1.25f) < 0.01f -> "um e um quarto"
        abs(value - 1.5f) < 0.01f -> "um e meio"
        abs(value - 2.0f) < 0.01f -> "o dobro"
        else -> label(value)
    }
}
