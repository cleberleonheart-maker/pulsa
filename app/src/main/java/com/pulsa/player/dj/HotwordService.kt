package com.pulsa.player.dj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pulsa.player.MainActivity
import com.pulsa.player.R
import com.pulsa.player.core.CrashLogger
import com.pulsa.player.core.Settings
import com.pulsa.player.playback.Playback

/**
 * Mãos-livres: mantém a Virgínia escutando enquanto a música toca,
 * mesmo com o app em segundo plano (foreground service de microfono).
 */
class HotwordService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: DjCommandListener? = null
    private var windowOpen = false

    private val keepAlive = Runnable { checkAlive() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // O servico entra por startForegroundService: no Android 12+ o
        // startForeground() e obrigatorio ANTES de qualquer return, senao o
        // sistema mata o processo com ForegroundServiceDidNotStartInTime e o
        // app entra em loop de splash. Por isso ele vem primeiro, e a decisao
        // de continuar ou parar vem depois.
        if (!goForeground() || !staying()) {
            stopNow()
            return
        }
        Hotword.running = true
        // Sem microfone por padrao: a notificacao fica "esperando" e quem abre a janela de
        // escuta e o botao "Ouvir". Abrir sozinho era o laço infinito — nao existe escuta de
        // palavra com o ponto laranja apagado, e o usuario escolheu pedir para escutar.
        Playback.setMicListening(false)
        mainHandler.postDelayed(keepAlive, POLL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!staying() || !HotwordBridge.hasTarget()) {
            stopNow()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_LISTEN) openWindow()
        return START_STICKY
    }

    override fun onDestroy() {
        Hotword.running = false
        windowOpen = false
        mainHandler.removeCallbacks(keepAlive)
        listener?.destroy()
        listener = null
        Playback.setMicListening(false)
        super.onDestroy()
    }

    /**
     * Abre a janela unica de escuta, chamada pelo botao "Ouvir" da notificacao. Abre,
     * escuta ate [LISTEN_WINDOW_MS], fecha sozinho. Nao reabre por conta propria.
     */
    private fun openWindow() {
        listener?.destroy()
        listener = DjCommandListener(
            this, MicMode.ONE_SHOT,
            onResult = { text ->
                if (!HotwordBridge.deliver(text)) stopNow()
            }
        ) {
            windowEnded()
        }
        windowOpen = true
        listener?.start(LISTEN_WINDOW_MS)
        Playback.setMicListening(true)
        refreshNotification()
    }

    /** A janela fechou (ouviu comando, silencio, ou o tempo acabou): volta a esperar. */
    private fun windowEnded() {
        if (!Hotword.running) return
        windowOpen = false
        Playback.setMicListening(false)
        refreshNotification()
    }

    private fun refreshNotification() {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIF_ID, notification(windowOpen))
        }
    }

    /** Devolve false se o sistema recusou o primeiro plano: ai o servico nem deve continuar. */
    private fun goForeground(): Boolean {
        createChannel()
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID,
                    notification(windowOpen),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, notification(windowOpen))
            }
            true
        }.getOrElse {
            CrashLogger.writeLog(this, "HOTWORD: startForeground falhou $it")
            false
        }
    }

    /** Para o servico tirando a notificacao junto (senao fica residue na bandeja). */
    private fun stopNow() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    // O serviço só faz sentido com o toggle ligado, música tocando e microfone liberado.
    private fun staying(): Boolean =
        Settings.hotword(this) && Playback.isPlaying && micGranted()

    private fun checkAlive() {
        // Se algo mudou (pausou a musica, tirou o toggle, sem destino) para o servico.
        if (!staying() || !HotwordBridge.hasTarget()) {
            stopNow()
            return
        }
        mainHandler.postDelayed(keepAlive, POLL_MS)
    }

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun notification(listeningNow: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ouvir = PendingIntent.getService(
            this, 1,
            Intent(this, HotwordService::class.java).setAction(ACTION_LISTEN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.hotword_notif_title))
            .setContentText(
                if (listeningNow) getString(R.string.hotword_notif_listening)
                else getString(R.string.hotword_notif_text)
            )
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_mic, getString(R.string.hotword_action_listen), ouvir)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.hotword_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.hotword_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "hotword"
        private const val NOTIF_ID = 1001
        private const val POLL_MS = 5000L

        /** Janela unica de escuta do botao "Ouvir". */
        private const val LISTEN_WINDOW_MS = 6000L
        const val ACTION_LISTEN = "com.pulsa.player.action.LISTEN"
    }
}