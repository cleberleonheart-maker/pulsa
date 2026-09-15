package com.pulsa.player.util

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import com.pulsa.player.R

class InstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_RESULT = "com.pulsa.player.INSTALL_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val msg = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Pulsa atualizada com sucesso"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "aguardando confirmacao do usuario"
            PackageInstaller.STATUS_FAILURE -> "FALHOU: erro generico na instalacao"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "FALHOU: abortado"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "FALHOU: bloqueado pelo sistema"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "FALHOU: conflito de aplicativo"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "FALHOU: apk incompativel"
            PackageInstaller.STATUS_FAILURE_INVALID -> "FALHOU: pacote invalido"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "FALHOU: sem armazenamento"
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> "FALHOU: tempo esgotado"
            else -> "FALHOU: status $status"
        }
        CrashLogger.writeLog(context, "INSTALACAO: $msg")
        Telemetry.log(context, "UPDATE status instalacao=$msg")
        if (status != PackageInstaller.STATUS_SUCCESS) {
            try {
                UpdateService.ensureChannel(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(
                    UpdateService.NOTIF_ANNOUNCE,
                    NotificationCompat.Builder(context, UpdateService.CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_music_note)
                        .setContentTitle(context.getString(R.string.update_title))
                        .setContentText(msg)
                        .setAutoCancel(true)
                        .build()
                )
            } catch (t: Throwable) {
                CrashLogger.writeLog(context, "INSTALACAO: notif falhou $t")
            }
        }
    }
}