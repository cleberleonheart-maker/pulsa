package com.pulsa.player.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.model.Playlist
import com.pulsa.player.model.Song
import com.pulsa.player.core.ThreadPool

/**
 * Diálogos de playlist.
 *
 * **Por que todo acesso ao banco sai daqui em [ThreadPool.post].** O `PlaylistDb` é Room, e
 * o Room lança `IllegalStateException` em qualquer query na main thread — não é lentidão, é
 * exceção. O lugar mais fácil de quebrar isso é exatamente este arquivo: os botões de um
 * `AlertDialog` são callbacks de toque, então rodam na main. `createPlaylist`, `renamePlaylist`,
 * `setAutoAdd`, `deletePlaylist` e `addSong` estavam todos ali dentro, e qualquer um deles
 * derrubava o app ao ser tocado.
 *
 * O padrão é sempre o mesmo: a main só **pede** e **mostra**; a escrita vai para [post] e o
 * resultado volta para [onUi]. Os callbacks [onCreated]/[onChanged] continuam chegando na main,
 * porque quem os implementa toca a interface.
 */
object PlaylistDialog {

    /** `Activity` destruída deixa o `Toast` e o dialog pendurado; o app fecha sozinho. */
    private fun Context.dead(): Boolean =
        this is android.app.Activity && (isFinishing || isDestroyed)

    fun showAdd(context: Context, song: Song) {
        val appCtx = context.applicationContext
        ThreadPool.post {
            val playlists = runCatching { PlaylistDb.get(appCtx).playlists() }.getOrDefault(emptyList())
            ThreadPool.onUi {
                if (context.dead()) return@onUi
                showAddDialog(context, song, playlists)
            }
        }
    }

    private fun showAddDialog(context: Context, song: Song, playlists: List<Playlist>) {
        val names = listOf(context.getString(R.string.new_playlist)) + playlists.map { it.name }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.add_to_playlist_title)
            .setItems(names.toTypedArray()) { dialog, which ->
                if (which == 0) {
                    promptNew(context) { id -> addAndToast(context, id, song) }
                } else {
                    val id = playlists[which - 1].id
                    addAndToast(context, id, song)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Pede o nome e cria a playlist em background.
     *
     * `onCreated` só é chamado **depois** da escrita, e nunca com `0`: um id inválido faria a
     * tela recarregar a lista para nada e o `Toast` de "criada" aparecer sem a playlist existir.
     */
    fun promptNew(context: Context, onCreated: (Long) -> Unit) {
        val input = LayoutInflater.from(context).inflate(R.layout.dialog_playlist_name, null, false)
        val editText = input.findViewById<EditText>(R.id.playlist_name_input)
        val appCtx = context.applicationContext
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.new_playlist_title)
            .setView(input)
            .setPositiveButton(R.string.create) { dialog, _ ->
                val name = editText.text.toString().trim()
                dialog.dismiss()
                if (name.isEmpty()) return@setPositiveButton
                ThreadPool.post {
                    val id = runCatching { PlaylistDb.get(appCtx).createPlaylist(name) }.getOrDefault(0L)
                    if (id <= 0L) return@post
                    ThreadPool.onUi { onCreated(id) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun showActions(context: Context, playlist: Playlist, onChanged: () -> Unit) {
        val items = arrayOf(
            context.getString(R.string.rename_playlist),
            if (playlist.autoAdd) context.getString(R.string.remove_auto) else context.getString(R.string.add_auto),
            context.getString(R.string.delete_playlist)
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(playlist.name)
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> promptRename(context, playlist) { onChanged() }
                    1 -> {
                        val next = !playlist.autoAdd
                        write(context) { PlaylistDb.get(context).setAutoAdd(playlist.id, next) }
                        onChanged()
                    }
                    2 -> {
                        MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.delete_playlist)
                            .setMessage(R.string.delete_confirm)
                            .setPositiveButton(R.string.delete) { d, _ ->
                                d.dismiss()
                                write(context) { PlaylistDb.get(context).deletePlaylist(playlist.id) }
                                onChanged()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun promptRename(context: Context, playlist: Playlist, onSaved: (String) -> Unit) {
        val input = LayoutInflater.from(context).inflate(R.layout.dialog_playlist_name, null, false)
        val editText = input.findViewById<EditText>(R.id.playlist_name_input)
        editText.setText(playlist.name)
        val appCtx = context.applicationContext
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.rename_playlist)
            .setView(input)
            .setPositiveButton(R.string.save) { dialog, _ ->
                val name = editText.text.toString().trim()
                dialog.dismiss()
                if (name.isEmpty()) return@setPositiveButton
                ThreadPool.post {
                    runCatching { PlaylistDb.get(appCtx).renamePlaylist(playlist.id, name) }
                    ThreadPool.onUi { onSaved(name) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addAndToast(context: Context, playlistId: Long, song: Song) {
        val appCtx = context.applicationContext
        ThreadPool.post {
            val outcome = runCatching {
                val db = PlaylistDb.get(appCtx)
                val added = db.addSong(playlistId, song)
                // O nome vem da mesma query de escrita: um segundo `playlists()` seria outra
                // ida ao banco para um dado que já está na mão.
                val title = db.playlists().firstOrNull { it.id == playlistId }?.name.orEmpty()
                added to title
            }.getOrDefault(false to "")
            ThreadPool.onUi {
                if (context.dead()) return@onUi
                val (added, title) = outcome
                val msg = if (added) {
                    context.getString(R.string.song_added, title)
                } else {
                    context.getString(R.string.song_already_in_playlist, title)
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Escrita única que não precisa de volta para a UI.
     *
     * [onChanged] é chamado pelo caller na hora, não aqui: recarregar a lista enquanto a escrita
     * ainda está na fila mostra o estado velho.
     */
    private fun write(context: Context, block: () -> Unit) {
        ThreadPool.post { runCatching { block() } }
    }
}
