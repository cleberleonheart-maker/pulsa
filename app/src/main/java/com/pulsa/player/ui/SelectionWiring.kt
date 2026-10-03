package com.pulsa.player.ui

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.media.MusicDeleter
import com.pulsa.player.media.VideoDeleter
import com.pulsa.player.model.Song
import com.pulsa.player.model.Video
import com.pulsa.player.ui.adapter.SongListAdapter
import com.pulsa.player.ui.adapter.VideoListAdapter

/**
 * Ligação entre a seleção múltipla e a ação em lote (apagar, ou tirar da playlist).
 *
 * As telas não montam isso na mão porque são **cinco** (Músicas, Biblioteca por álbum,
 * Favoritas, Playlist e Vídeos) e cada uma já tem o seu `load()` para cuidar. O que a
 * tela precisa fazer é só isto:
 *
 * ```
 * bar = setUpSelection(this, view, a, onAction = { songs, reload -> ... }) { load() }
 * bar?.setAvailable(adapter?.songs.orEmpty().map { it.id })   // depois de cada load()
 * ```
 *
 * A ação entra como parâmetro em vez de ser fixa aqui porque **apagar o arquivo** e
 * **tirar da playlist** não são a mesma coisa: na tela de playlist, o botão tem de tirar
 * da playlist. Apagar o arquivo dali por engano seria perder a música do disco sem ter
 * pedido.
 */
object SelectionWiring {

    /** Lista de músicas. [onAction] recebe as marcadas e o callback de recarregar. */
    fun setUpSelection(
        fragment: Fragment,
        root: View,
        adapter: SongListAdapter?,
        actionIcon: Int = R.drawable.ic_delete,
        actionLabel: Int = R.string.delete_selected,
        onAction: (List<Song>, () -> Unit) -> Unit,
        reload: () -> Unit,
        /** Segunda ação da barra (F2b: adicionar à fila). Sem isso, só a de apagar. */
        onExtraAction: ((List<Song>) -> Unit)? = null
    ): SelectionBar? {
        val selection = ItemSelection()
        adapter?.selection = selection
        fragment.context ?: return null
        return SelectionBar(
            root = root,
            selection = selection,
            actionIcon = actionIcon,
            actionLabel = actionLabel,
            onAction = { ids ->
                val songs = adapter?.songs.orEmpty().filter { it.id in ids }
                if (songs.isEmpty()) {
                    // Marcação sem item correspondente: só ids que já não estão na lista.
                    selection.clear()
                } else {
                    onAction(songs, reload)
                    // A seleção é zerada na hora, não depois da confirmação. Manter as
                    // linhas marcadas enquanto o diálogo está aberto faz a pessoa achar que
                    // o botão não respondeu — e cancelar o diálogo não desfaz nada, porque
                    // a lista não mudou.
                    selection.clear()
                }
            },
            extraAction = onExtraAction?.let { callback ->
                {
                    val songs = adapter?.songs.orEmpty().filter { it.id in selection.snapshot() }
                    if (songs.isEmpty()) {
                        selection.clear()
                    } else {
                        callback(songs)
                        selection.clear()
                    }
                }
            }
        )
    }

    /** Lista de vídeos, que tem o [VideoDeleter] próprio. */
    fun setUpSelection(
        fragment: Fragment,
        root: View,
        adapter: VideoListAdapter?,
        reload: () -> Unit,
        /** Segunda ação da barra (F2b: adicionar à fila). Sem isso, a barra fica só com o apagar. */
        onExtraAction: ((List<Video>) -> Unit)? = null
    ): SelectionBar? {
        val selection = ItemSelection()
        adapter?.selection = selection
        val ctx = fragment.context ?: return null
        return SelectionBar(root, selection, onAction = { ids ->
            val videos = adapter?.videos.orEmpty().filter { it.id in ids }
            if (videos.isEmpty()) {
                selection.clear()
            } else {
                confirmDeleteVideos(ctx, videos, reload)
                selection.clear()
            }
        }, extraAction = onExtraAction?.let { callback ->
            {
                val videos = adapter?.videos.orEmpty().filter { it.id in selection.snapshot() }
                if (videos.isEmpty()) {
                    selection.clear()
                } else {
                    callback(videos)
                    // Limpa na hora, como na ação de apagar: a lista não mudou, então deixar
                    // marcado só faz a barra continuar no lugar sem motivo.
                    selection.clear()
                }
            }
        })
    }

    private fun confirmDeleteVideos(
        ctx: Context,
        videos: List<Video>,
        reload: () -> Unit
    ) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.delete_videos)
            .setMessage(ctx.getString(R.string.delete_videos_confirm, videos.size))
            .setPositiveButton(R.string.delete) { d, _ ->
                d.dismiss()
                VideoDeleter.delete(ctx, videos) { outcome ->
                    // Separado em vez de um `when`: o caminho de sucesso precisa do número
                    // na mensagem, e um `when` aqui misturaria `String` com id de resource —
                    // e o `Toast.makeText` não tem sobrecarga que aceite os dois.
                    if (outcome is MusicDeleter.Outcome.Deleted) {
                        Toast.makeText(
                            ctx, ctx.getString(R.string.videos_deleted, videos.size), Toast.LENGTH_SHORT
                        ).show()
                        reload()
                    } else {
                        val res = if (outcome is MusicDeleter.Outcome.Cancelled) {
                            R.string.delete_cancelled
                        } else {
                            R.string.delete_failed
                        }
                        Toast.makeText(ctx, res, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
