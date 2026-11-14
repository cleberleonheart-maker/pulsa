package com.pulsa.player.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.pulsa.player.ConverterActivity
import com.pulsa.player.R
import com.pulsa.player.core.Settings
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class ConversionService : Service() {

    companion object {
        const val ACTION_ENQUEUE = "com.pulsa.player.conversion.ENQUEUE"
        const val ACTION_CANCEL = "com.pulsa.player.conversion.CANCEL"
        const val ACTION_RETRY = "com.pulsa.player.conversion.RETRY"
        const val ACTION_DELETE = "com.pulsa.player.conversion.DELETE"
        const val EXTRA_JOB = "job"
        const val EXTRA_ID = "id"

        private const val CHANNEL_ID = "conversions"
        private const val NOTIFICATION_ID = 12

        fun enqueue(context: Context, item: ConversionStore.Item) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, ConversionService::class.java)
                    .setAction(ACTION_ENQUEUE)
                    .putExtra(EXTRA_JOB, ConversionStore.toJson(item).toString())
            )
        }

        fun cancel(context: Context, id: String) = send(context, ACTION_CANCEL, id)

        fun retry(context: Context, id: String) = send(context, ACTION_RETRY, id)

        fun delete(context: Context, id: String) = send(context, ACTION_DELETE, id)

        private fun send(context: Context, action: String, id: String) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, ConversionService::class.java)
                    .setAction(action)
                    .putExtra(EXTRA_ID, id)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val publisher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "conversion-publisher").apply { isDaemon = true }
    }
    private val foreground = AtomicBoolean(false)
    private val progressHolder = ProgressHolder()
    private var transformer: Transformer? = null
    private var currentId: String? = null
    private var cancelRequested = false
    private var deleteRequested = false
    private var destroyed = false

    private val listener = object : Transformer.Listener {
        override fun onCompleted(composition: Composition, result: ExportResult) {
            finishTransform()
        }

        override fun onError(
            composition: Composition,
            result: ExportResult,
            exception: ExportException
        ) {
            failTransform(exception.message ?: exception.errorCodeName)
        }
    }

    private val progressTask = object : Runnable {
        override fun run() {
            val activeTransformer = transformer ?: return
            val id = currentId ?: return
            var changed = false
            runCatching {
                if (activeTransformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val item = ConversionStore.byId(id) ?: return@runCatching
                    if (item.progress != progressHolder.progress) {
                        ConversionStore.put(
                            this@ConversionService,
                            item.copy(progress = progressHolder.progress.coerceIn(0, 100)),
                            persist = false
                        )
                        changed = true
                    }
                }
            }
            // A notificação é o único lugar onde o progresso aparece quando a tela do
            // conversor não está aberta: sem isto ela ficava parada em "na fila".
            if (changed && !destroyed) ensureForeground()
            if (!destroyed) handler.postDelayed(this, 500L)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = pump()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) pump()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ConversionStore.ensureLoaded(this)
        createChannel()
        registerNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE -> {
                val item = intent.getStringExtra(EXTRA_JOB)?.let { ConversionStore.fromJson(it) }
                if (item != null && item.id.isNotBlank() && item.sourceUri.isNotBlank()) {
                    ConversionStore.put(this, item.copy(status = ConversionStore.Status.QUEUED))
                }
            }
            ACTION_CANCEL -> cancelJob(intent.getStringExtra(EXTRA_ID).orEmpty())
            ACTION_RETRY -> retryJob(intent.getStringExtra(EXTRA_ID).orEmpty())
            ACTION_DELETE -> deleteJob(intent.getStringExtra(EXTRA_ID).orEmpty())
        }
        ensureForeground()
        pump()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(progressTask)
        transformer?.removeAllListeners()
        runCatching { transformer?.cancel() }
        transformer = null
        publisher.shutdownNow()
        runCatching {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.unregisterNetworkCallback(networkCallback)
        }
        super.onDestroy()
    }

    private fun pump() {
        if (destroyed || currentId != null) return
        val item = ConversionStore.list().firstOrNull { it.status == ConversionStore.Status.QUEUED }
        if (item == null) {
            ensureForeground()
            return
        }
        if (item.sourceType == ConverterSourceType.URL && !networkAllowsConversion()) {
            ensureForeground()
            return
        }
        val estimate = ConverterRules.estimatedBytes(item.durationMs, item.options(), item.sourceHeight)
        val needed = if (estimate > 0L) estimate else item.sourceSizeBytes
        if (!OfflineSpace.hasRoomFor(this, needed) || !OfflineSpace.hasRoomOnExternal(this, needed)) {
            failJob(item, getString(R.string.downloads_no_space))
            return
        }
        if (OfflineSpace.limitExceeded(this, needed, fresh = true)) {
            failJob(item, getString(R.string.downloads_limit_reached))
            return
        }
        currentId = item.id
        cancelRequested = false
        deleteRequested = false
        ConversionStore.put(
            this,
            item.copy(status = ConversionStore.Status.RUNNING, progress = 0, error = null),
            persist = true
        )
        try {
            val output = outputFile(item)
            runCatching { output.delete() }
            output.parentFile?.mkdirs()
            transformer = buildTransformer(item)
            transformer?.start(buildEditedItem(item), output.absolutePath)
            handler.removeCallbacks(progressTask)
            handler.post(progressTask)
        } catch (t: Throwable) {
            failJob(item, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun buildTransformer(item: ConversionStore.Item): Transformer {
        val builder = Transformer.Builder(this)
            .setEnsureFileStartsOnVideoFrameEnabled(item.output.isVideo)
            .addListener(listener)
        if (item.output.isVideo) {
            builder.setVideoMimeType(MimeTypes.VIDEO_H264)
            builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
            // Com a resolução em "original", a altura do vídeo é a da fonte: sem isso a
            // qualidade escolhida não mudava nada.
            val bitrate = ConverterRules.targetBitrateForHeight(
                item.resolution.height ?: item.sourceHeight.takeIf { it > 0 },
                item.quality
            )
            if (bitrate != null) {
                val settings = VideoEncoderSettings.Builder().setBitrate(bitrate).build()
                builder.setEncoderFactory(
                    DefaultEncoderFactory.Builder(this)
                        .setRequestedVideoEncoderSettings(settings)
                        .build()
                )
            }
        } else {
            builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
        }
        return builder.build()
    }

    private fun buildEditedItem(item: ConversionStore.Item): EditedMediaItem {
        val mediaBuilder = MediaItem.Builder().setUri(item.sourceUri)
        if (item.trimStartMs > 0L || item.trimEndMs > 0L) {
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .apply {
                    if (item.trimStartMs > 0L) setStartPositionMs(item.trimStartMs)
                    if (item.trimEndMs > 0L) setEndPositionMs(item.trimEndMs)
                }
                .build()
            mediaBuilder.setClippingConfiguration(clipping)
        }
        return EditedMediaItem.Builder(mediaBuilder.build())
            .setEffects(buildEffects(item))
            .setRemoveVideo(!item.output.isVideo)
            // Tirar o áudio de um M4A deixaria o arquivo sem som nenhum: a opção é de vídeo.
            .setRemoveAudio(item.removeAudio && item.output.isVideo)
            .build()
    }

    /**
     * O efeito de escala do vídeo.
     *
     * Vídeo deitado escala pela altura (720 → 1280x720). Vídeo **em pé** — gravado no
     * celular — escala pela caixa (720 de largura por 1280 de altura), que é como todo
     * mundo chama "720p" nesses vídeo; escalar pela altura daria um arquivo estreito
     * (405x720).
     */
    private fun buildEffects(item: ConversionStore.Item): Effects {
        val resolution = item.resolution
        val height = resolution.height ?: return Effects.EMPTY
        val presentation = if (ConverterRules.isPortraitSource(item.sourceWidth, item.sourceHeight)) {
            val box = ConverterRules.portraitBox(resolution) ?: return Effects.EMPTY
            Presentation.createForWidthAndHeight(box.first, box.second, Presentation.LAYOUT_SCALE_TO_FIT)
        } else {
            Presentation.createForHeight(height)
        }
        return Effects(emptyList(), listOf(presentation))
    }

    private fun finishTransform() {
        val item = currentItem() ?: return
        handler.removeCallbacks(progressTask)
        ConversionStore.put(
            this,
            item.copy(status = ConversionStore.Status.FINALIZING, progress = 100),
            persist = true
        )
        ensureForeground()
        val file = outputFile(item)
        publisher.execute {
            val published = if (file.exists()) {
                MusicDownloader.publish(
                    this,
                    file,
                    "converted.${item.output.extension}",
                    item.title,
                    item.output.mime
                )
            } else {
                null
            }
            handler.post {
                if (destroyed) return@post
                val publishedItem = published
                if (publishedItem == null) {
                    runCatching { file.delete() }
                    failJob(item, getString(R.string.converter_failed_detail, "saída inválida"))
                    return@post
                }
                val outputBytes = file.length()
                runCatching { file.delete() }
                if (cancelRequested || deleteRequested) {
                    runCatching { contentResolver.delete(android.net.Uri.parse(publishedItem.uri), null, null) }
                    if (deleteRequested) ConversionStore.remove(this, item.id) else {
                        ConversionStore.put(
                            this,
                            item.copy(
                                status = ConversionStore.Status.CANCELLED,
                                progress = 0,
                                outputUri = "",
                                outputMime = "",
                                outputId = 0L,
                                outputBytes = 0L,
                                error = null
                            )
                        )
                    }
                    clearCurrent()
                    pump()
                    return@post
                }
                if (!OfflineSpace.hasRoomFor(this, outputBytes) ||
                    OfflineSpace.limitExceeded(this, outputBytes, fresh = true)
                ) {
                    runCatching { contentResolver.delete(android.net.Uri.parse(publishedItem.uri), null, null) }
                    failJob(item, getString(R.string.downloads_limit_reached))
                    return@post
                }
                ConversionStore.put(
                    this,
                    item.copy(
                        status = ConversionStore.Status.DONE,
                        progress = 100,
                        outputUri = publishedItem.uri,
                        outputMime = publishedItem.mime,
                        outputId = publishedItem.mediaId,
                        outputBytes = outputBytes,
                        error = null
                    )
                )
                clearCurrent()
                pump()
            }
        }
    }

    private fun failTransform(message: String) {
        val item = currentItem() ?: return
        handler.removeCallbacks(progressTask)
        runCatching { outputFile(item).delete() }
        if (cancelRequested || deleteRequested) {
            if (deleteRequested) ConversionStore.remove(this, item.id) else {
                ConversionStore.put(
                    this,
                    item.copy(status = ConversionStore.Status.CANCELLED, progress = 0, error = null)
                )
            }
        } else {
            failJob(item, message)
        }
        clearCurrent()
        pump()
    }

    private fun failJob(item: ConversionStore.Item, message: String) {
        runCatching { outputFile(item).delete() }
        ConversionStore.put(
            this,
            item.copy(
                status = ConversionStore.Status.FAILED,
                progress = 0,
                error = message,
                outputUri = "",
                outputMime = "",
                outputId = 0L,
                outputBytes = 0L
            )
        )
        if (currentId == item.id) clearCurrent()
        pump()
    }

    private fun cancelJob(id: String) {
        val item = ConversionStore.byId(id) ?: return
        if (item.id == currentId) {
            cancelRequested = true
            deleteRequested = false
            runCatching { transformer?.cancel() }
        } else if (item.status == ConversionStore.Status.QUEUED) {
            ConversionStore.put(this, item.copy(status = ConversionStore.Status.CANCELLED, progress = 0))
        }
    }

    private fun retryJob(id: String) {
        val item = ConversionStore.byId(id) ?: return
        if (item.outputUri.isNotBlank()) {
            runCatching { contentResolver.delete(android.net.Uri.parse(item.outputUri), null, null) }
        }
        runCatching { outputFile(item).delete() }
        ConversionStore.put(
            this,
            item.copy(
                status = ConversionStore.Status.QUEUED,
                progress = 0,
                outputUri = "",
                outputMime = "",
                outputId = 0L,
                outputBytes = 0L,
                error = null
            )
        )
        pump()
    }

    private fun deleteJob(id: String) {
        val item = ConversionStore.byId(id) ?: return
        if (item.id == currentId) {
            deleteRequested = true
            cancelRequested = true
            runCatching { transformer?.cancel() }
        } else {
            runCatching { outputFile(item).delete() }
            ConversionStore.remove(this, id)
        }
    }

    private fun currentItem(): ConversionStore.Item? = currentId?.let { ConversionStore.byId(it) }

    private fun clearCurrent() {
        currentId = null
        transformer?.removeAllListeners()
        transformer = null
        cancelRequested = false
        deleteRequested = false
    }

    private fun outputFile(item: ConversionStore.Item): File =
        File(File(filesDir, "conversions"), "${item.id}.${item.output.extension}")

    private fun ConversionStore.Item.options() = ConverterOptions(
        output = output,
        resolution = resolution,
        quality = quality,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
        removeAudio = removeAudio
    )

    private fun networkAllowsConversion(): Boolean {
        if (!Settings.downloadWifiOnly(this)) return true
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun registerNetwork() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, networkCallback) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.converter_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun ensureForeground() {
        val active = ConversionStore.list().filter { it.isActive() }
        if (foreground.compareAndSet(false, true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(active),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(active))
            }
        } else {
            runCatching {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(active))
            }
        }
        if (active.isEmpty()) {
            foreground.set(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun buildNotification(active: List<ConversionStore.Item>): Notification {
        val item = active.firstOrNull()
        val title = getString(R.string.converter_notification_title)
        val text = when (item?.status) {
            ConversionStore.Status.QUEUED -> getString(R.string.converter_notification_queued)
            ConversionStore.Status.FINALIZING -> getString(R.string.converter_finalizing)
            else -> getString(R.string.converter_notification_progress, item?.progress ?: 0)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ConverterActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (item?.status == ConversionStore.Status.RUNNING) {
            builder.setProgress(100, item.progress.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }
}
