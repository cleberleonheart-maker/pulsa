package com.pulsa.player.dj

import com.pulsa.player.model.Song

/**
 * Os nomes de tipo que o DJ usa — na fila e no histórico.
 *
 * Um vocabulário só: o `kind` gravado no `play_log` ([DjLearn.kindOf]) e o tipo citado na
 * voz ("joga o vídeo pro fim") são as mesmas palavras. Duas listas de nomes para manter é
 * como um dos dois lados fica para trás — e foi exatamente assim que rádio e stream
 * ficaram de fora da reordenação por voz: o parser só sabia dizer "vídeo" e "episódio".
 */
object Kinds {

    const val MUSIC = "music"
    const val VIDEO = "video"
    const val PODCAST = "podcast"
    const val RADIO = "radio"
    const val STREAM = "stream"

    /** Todo tipo, para a pergunta que não cita nenhum ("o que já passou hoje?"). */
    const val ALL = "all"

    /**
     * O tipo de um item da fila.
     *
     * A ordem importa: o episódio em vídeo (`podcast:v<id>`) é podcast **e** vídeo, e o
     * stream é vídeo para quem pergunta ("o que já tocou de vídeo") mas tem nome próprio
     * quando a pergunta é precisa ("o que já tocou de stream").
     */
    fun of(song: Song): String = when {
        song.isPodcast -> PODCAST
        song.isRadio -> RADIO
        song.isStream -> STREAM
        song.isVideo -> VIDEO
        else -> MUSIC
    }
}
