package com.pulsa.player.podcast

/**
 * Junta capítulos e marcadores numa lista só, na ordem do tempo.
 *
 * Fica em arquivo próprio (e não dentro de [Chapters]) porque são duas coisas diferentes:
 * o capítulo é o índice que o feed publica, o marcador é o "voltar neste minuto" que só o
 * ouvinte sabe. A folha de capítulos ([com.pulsa.player.ui.ChapterSheetFragment]) mostra os
 * dois na mesma lista, e é esta função que decide a ordem.
 *
 * Puro para o teste cobrir a parte que decide a ordem e o título de reserva — o banco e a
 * folha ficam de fora. Marcador sem título (o usuário apagou o texto no diálogo) usa o
 * carimbo do tempo, que continua sendo uma posição reconhecível.
 */
object PodcastBookmarks {

    fun rows(
        chapters: List<Chapter>,
        bookmarks: List<Bookmark>,
        fallbackTitle: (Long) -> String = { Chapters.formatTime(it) }
    ): List<ChapterRow> =
        (chapters.map { ChapterRow(it.startMs, it.title) } +
            bookmarks.map {
                ChapterRow(
                    startMs = it.positionMs,
                    title = it.title.ifBlank { fallbackTitle(it.positionMs) },
                    bookmarkId = it.id
                )
            })
            .sortedWith(compareBy<ChapterRow> { it.startMs }.thenBy { if (it.isBookmark) 0 else 1 })
}
