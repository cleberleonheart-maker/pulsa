package com.pulsa.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.pulsa.player.MainActivity
import com.pulsa.player.R
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.playback.PlaybackService

class PulsaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val svc = Playback.service
        render(
            context, ids,
            svc?.currentSong, svc?.isPlaying == true, svc?.currentArt()
        )
    }

    companion object {
        fun refresh(context: Context) {
            val svc = Playback.service ?: return
            val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, PulsaWidget::class.java)
            )
            if (ids.isEmpty()) return
            render(context, ids, svc.currentSong, svc.isPlaying, svc.currentArt())
        }

        private fun render(context: Context, ids: IntArray, song: Song?, playing: Boolean, art: Bitmap?) {
            if (ids.isEmpty()) return
            val rv = RemoteViews(context.packageName, R.layout.widget_pulsa)
            if (song != null) {
                rv.setTextViewText(R.id.widget_title, song.title)
                rv.setTextViewText(R.id.widget_artist, song.artist)
            } else {
                rv.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
                rv.setTextViewText(R.id.widget_artist, context.getString(R.string.widget_idle))
            }
            rv.setImageViewResource(
                R.id.widget_play,
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
            if (art != null) {
                rv.setImageViewBitmap(R.id.widget_art, art)
            } else {
                rv.setImageViewResource(R.id.widget_art, R.drawable.ic_music_note)
            }

            val toggle = PendingIntent.getService(
                context, 11,
                Intent(context, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val next = PendingIntent.getService(
                context, 12,
                Intent(context, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_NEXT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val prev = PendingIntent.getService(
                context, 13,
                Intent(context, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_PREV),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val open = PendingIntent.getActivity(
                context, 14,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            rv.setOnClickPendingIntent(R.id.widget_play, toggle)
            rv.setOnClickPendingIntent(R.id.widget_next, next)
            rv.setOnClickPendingIntent(R.id.widget_prev, prev)
            rv.setOnClickPendingIntent(R.id.widget_root, open)
            AppWidgetManager.getInstance(context).updateAppWidget(ids, rv)
        }
    }
}