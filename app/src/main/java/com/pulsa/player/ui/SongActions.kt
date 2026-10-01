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
                MusicDeleter.delete(context, song) { outcome ->
                    report(context, outcome, 1)
                    if (outcome is MusicDeleter.Outcome.Deleted) onDeleted?.invoke()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Confirma e apaga várias músicas de uma vez, vinda da seleção múltipla da lista.
     *
     * O `MediaStore` aceita todas as URIs num pedido só, então o diálogo do sistema aparece
     * **uma vez** para o lote inteiro — senão 30 músicas seriam 30 confirmações.
     */
    fun confirmDeleteMany(context: Context, songs: List<Song>, onDeleted: (() -> Unit)?) {
        if (songs.isEmpty()) return
        if (songs.size == 1) {
            confirmDelete(context, songs[0], onDeleted)
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete_songs)
            .setMessage(context.getString(R.string.delete_songs_confirm, songs.size))
            .setPositiveButton(R.string.delete) { d, _ ->
                d.dismiss()
                MusicDeleter.delete(context, songs) { outcome ->
                    report(context, outcome, songs.size)
                    if (outcome is MusicDeleter.Outcome.Deleted) onDeleted?.invoke()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Avisa o resultado, em uma linha só para o singular e para o lote. */
    private fun report(context: Context, outcome: MusicDeleter.Outcome, count: Int) {
        val res = when (outcome) {
            is MusicDeleter.Outcome.Deleted ->
                if (count == 1) R.string.song_deleted else R.string.songs_deleted
            // Cancelar não é erro. Dizer "permissão negada" aqui foi o que fez parecer que
            // o app estava quebrado quando o usuário só disse não.
            is MusicDeleter.Outcome.Cancelled -> R.string.delete_cancelled
            is MusicDeleter.Outcome.Failed -> R.string.delete_failed
        }
        android.widget.Toast.makeText(context, res, android.widget.Toast.LENGTH_SHORT).show()
    }
}
