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

object PlaylistDialog {

    fun showAdd(context: Context, song: Song) {
        val appCtx = context.applicationContext
        ThreadPool.post {
            val playlists = PlaylistDb.get(appCtx).playlists()
            ThreadPool.onUi {
                if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) return@onUi
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

    fun promptNew(context: Context, onCreated: (Long) -> Unit) {
        val input = LayoutInflater.from(context).inflate(R.layout.dialog_playlist_name, null, false)
        val editText = input.findViewById<EditText>(R.id.playlist_name_input)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.new_playlist_title)
            .setView(input)
            .setPositiveButton(R.string.create) { dialog, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    onCreated(PlaylistDb.get(context).createPlaylist(name))
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun showActions(context: Context, playlist: Playlist, onChanged: () -> Unit) {
        val db = PlaylistDb.get(context)
        val items = arrayOf(
            context.getString(R.string.rename_playlist),
            if (playlist.autoAdd) context.getString(R.string.remove_auto) else context.getString(R.string.add_auto),
            context.getString(R.string.delete_playlist)
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(playlist.name)
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> {
                        promptRename(context, playlist) {
                            db.renamePlaylist(playlist.id, it)
                            onChanged()
                        }
                    }
                    1 -> {
                        db.setAutoAdd(playlist.id, !playlist.autoAdd)
                        onChanged()
                    }
                    2 -> {
                        MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.delete_playlist)
                            .setMessage(R.string.delete_confirm)
                            .setPositiveButton(R.string.delete) { d, _ ->
                                db.deletePlaylist(playlist.id)
                                onChanged()
                                d.dismiss()
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
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.rename_playlist)
            .setView(input)
            .setPositiveButton(R.string.save) { dialog, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) onSaved(name)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addAndToast(context: Context, playlistId: Long, song: Song) {
        val added = PlaylistDb.get(context).addSong(playlistId, song)
        val playlists = PlaylistDb.get(context).playlists()
        val name = playlists.firstOrNull { it.id == playlistId }?.name ?: ""
        val msg = if (added) {
            context.getString(R.string.song_added, name)
        } else {
            context.getString(R.string.song_already_in_playlist, name)
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
