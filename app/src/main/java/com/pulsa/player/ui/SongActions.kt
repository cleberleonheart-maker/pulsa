package com.pulsa.player.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.provider.MediaStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.media.MusicDeleter
import com.pulsa.player.media.MusicEditor
import com.pulsa.player.audio.RingtoneSetter

object SongActions {

    fun show(
        context: Context,
        song: Song,
        extra: Pair<Int, () -> Unit>? = null,
        onDeleted: (() -> Unit)? = null
    ) {
        val favorite = PlaylistDb.get(context).isFavorite(song.id)
        val favoriteLabel = if (favorite) {
            context.getString(R.string.favorite_remove)
        } else {
            context.getString(R.string.favorite_add)
        }
        val items = mutableListOf(
            context.getString(R.string.song_action_play_now),
            context.getString(R.string.add_to_playlist),
            favoriteLabel,
            context.getString(R.string.share_music),
            context.getString(R.string.ringtone_phone),
            context.getString(R.string.ringtone_notification),
            context.getString(R.string.ringtone_alarm),
            context.getString(R.string.edit_song),
            context.getString(R.string.delete_song)
        )
        if (extra != null) items += context.getString(extra.first)
        MaterialAlertDialogBuilder(context)
            .setTitle(song.title)
            .setItems(items.toTypedArray()) { dialog, which ->
                when (which) {
                    0 -> Playback.start(listOf(song), 0)
                    1 -> PlaylistDialog.showAdd(context, song)
                    2 -> {
                        val db = PlaylistDb.get(context)
                        db.setFavorite(song, !favorite)
                        android.widget.Toast.makeText(
                            context,
                            if (!favorite) R.string.favorite_added else R.string.favorite_removed,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        onDeleted?.invoke()
                    }
                    3 -> share(context, song)
                    4 -> RingtoneSetter.setAs(context, song, RingtoneManager.TYPE_RINGTONE)
                    5 -> RingtoneSetter.setAs(context, song, RingtoneManager.TYPE_NOTIFICATION)
                    6 -> RingtoneSetter.setAs(context, song, RingtoneManager.TYPE_ALARM)
                    7 -> MusicEditor.edit(context, song, onDeleted)
                    8 -> confirmDelete(context, song, onDeleted)
                    else -> extra?.second?.invoke()
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun share(context: Context, song: Song) {
        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            song.id
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, song.title)
            putExtra(Intent.EXTRA_TEXT, song.title + " - " + song.artist)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_music)))
        }.onFailure {
            android.widget.Toast.makeText(context, R.string.share_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(context: Context, song: Song, onDeleted: (() -> Unit)?) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete_song)
            .setMessage(context.getString(R.string.delete_song_confirm, song.title))
            .setPositiveButton(R.string.delete) { d, _ ->
                d.dismiss()
                MusicDeleter.delete(context, song) { ok ->
                    android.widget.Toast.makeText(
                        context,
                        if (ok) R.string.song_deleted else R.string.delete_failed,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    if (ok) onDeleted?.invoke()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
