package com.pulsa.player.util
import com.pulsa.player.core.CrashLogger
import com.pulsa.player.core.Settings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pulsa.player.R

class UpdateService : Service() {

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_URL = "url"
        const val EXTRA_APK = "apk_path"
        const val CHANNEL_ID = "updates"
        const val NOTIF_ANNOUNCE = 9000
        const val NOTIF_PROGRESS = 9001

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Atualizações",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                channel.description = "Notificações de nova versão da Pulsa"
                nm.createNotificationChannel(channel)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apkPath = intent?.getStringExtra(EXTRA_APK)
        if (!apkPath.isNullOrEmpty()) {
            Thread {
                try {
                    UpdateChecker.installApk(this, java.io.File(apkPath))
                } finally {
                    stopSelf()
                }
            }.start()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "latest"
        val url = intent?.getStringExtra(EXTRA_URL) ?: ""
        Thread {
            try {
                download(name, url)
            } finally {
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun download(name: String, url: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            val pi = PendingIntent.getActivity(
                this, 2,
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            nm.notify(
                NOTIF_ANNOUNCE,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_music_note)
                    .setContentTitle(getString(R.string.update_title))
                    .setContentText(getString(R.string.update_needs_permission_text))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .addAction(0, getString(R.string.update_allow), pi)
                    .build()
            )
            CrashLogger.writeLog(this, "UPDATE: servico sem permissao de instalacao")
            return
        }
        nm.notify(NOTIF_PROGRESS, buildProgress(name, 0))
        val target = UpdateChecker.downloadFromServer(applicationContext, name, url) { pct ->
            nm.notify(NOTIF_PROGRESS, buildProgress(name, pct))
        }
        if (target != null) {
            nm.cancel(NOTIF_PROGRESS)
            CrashLogger.writeLog(this, "UPDATE: servico baixou ${target.absolutePath}")
            UpdateChecker.markAttempted(this, name)
            UpdateChecker.installApk(applicationContext, target)
        } else {
            UpdateChecker.clearAttempted(this)
            val pi = PendingIntent.getService(
                this, 1,
                Intent(this, UpdateService::class.java)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_URL, url),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(getString(R.string.update_title))
                .setContentText(getString(R.string.update_failed))
                .setAutoCancel(true)
                .addAction(0, getString(R.string.update_now), pi)
                .setContentIntent(pi)
                .build()
            nm.notify(NOTIF_ANNOUNCE, notif)
            CrashLogger.writeLog(this, "UPDATE: servico nao baixou")
        }
    }

    private fun buildProgress(name: String, pct: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(getString(R.string.update_title))
            .setContentText(getString(R.string.update_downloading, pct))
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
}
