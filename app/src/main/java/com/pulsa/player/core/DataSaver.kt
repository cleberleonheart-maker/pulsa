package com.pulsa.player.core

/**
 * Item 291 — economia de dados no vídeo.
 *
 * Em rede medida (4G/5G), um vídeo em 1080p/4K pode passar de 1 GB por hora. Com a economia
 * ligada, o seletor de trilhas do `ExoPlayer` fica limitado a ~480p e ~1,2 Mbps, o bastante
 * para assistir no celular sem estourar o plano. No Wi-Fi/cabo nada muda — a melhor trilha
 * disponível continua valendo.
 *
 * A decisão é pura ([shouldLimit]/[maxSize]/[maxBitrate]) para poder ser testada na JVM sem
 * `ConnectivityManager`; quem pergunta pela rede é o [NetworkStatus], no serviço.
 */
object DataSaver {

    /** Altura máxima da trilha de vídeo em rede medida (~480p). */
    const val METERED_MAX_HEIGHT = 480

    /** Largura equivalente a 16:9 em [METERED_MAX_HEIGHT]. */
    const val METERED_MAX_WIDTH = 854

    /** Teto de bitrate da trilha de vídeo em rede medida (1,2 Mbps). */
    const val METERED_MAX_BITRATE = 1_200_000

    /**
     * A economia vale agora? Precisa do opt-in do usuário **e** de rede medida: quem está no
     * Wi-Fi não deve pagar queda de qualidade, e quem desligou a economia pediu a melhor
     * imagem mesmo no 4G.
     */
    fun shouldLimit(enabled: Boolean, metered: Boolean): Boolean = enabled && metered

    /**
     * Limite de largura×altura para a trilha de vídeo no estado atual. Sem limite devolve
     * `Int.MAX_VALUE`, que é o neutro do `TrackSelectionParameters` — assim o serviço aplica
     * o mesmo caminho (com e sem teto) em vez de ter dois ramos.
     */
    fun maxSize(enabled: Boolean, metered: Boolean): Pair<Int, Int> =
        if (shouldLimit(enabled, metered)) METERED_MAX_WIDTH to METERED_MAX_HEIGHT
        else Int.MAX_VALUE to Int.MAX_VALUE

    /** Teto de bitrate da trilha de vídeo no estado atual (ver [maxSize]). */
    fun maxBitrate(enabled: Boolean, metered: Boolean): Int =
        if (shouldLimit(enabled, metered)) METERED_MAX_BITRATE else Int.MAX_VALUE
}
