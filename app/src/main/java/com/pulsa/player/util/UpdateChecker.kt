package com.pulsa.player.util

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.BuildConfig
import com.pulsa.player.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    @Volatile
    private var lastShownCode: Long? = null

    // Publicos sao a fonte de verdade; o servidor privado so e fallback offline/debug.
    private val publicHosts = listOf(
        "https://cleberleonheart-maker.github.io/pulsaweb/version",
        "https://pulsaweb.netlify.app/version",
        "https://docs.google.com/document/d/1FEj7Rvlyz6o67-EVWLVP9oTo3mU4ShG0jj6w206ZvQA/export?format=txt"
    )
    private fun privateHosts(context: Context): List<String> {
        return Settings.serverCandidates(context).map { "$it/version" }
    }

    // Servidor privado primeiro; publico (GitHub Pages / Netlify) fica de fallback.
    private val fallbackApkHosts = listOf(
        "http://192.168.100.7:8081/Pulsa.apk",
        "http://127.0.0.1:8081/Pulsa.apk",
        "https://cleberleonheart-maker.github.io/pulsaweb/Pulsa.apk"
    )

    private data class VersionResult(val code: Long, val name: String, val apkUrl: String)

    /** Consulta TODOS os hosts (publicos primeiro) e devolve o de MAIOR versionCode. */
    private fun queryLatest(context: Context): VersionResult? {
        return bestFrom(publicHosts) ?: bestFrom(privateHosts(context))
    }

    private fun bestFrom(hostList: List<String>): VersionResult? {
        var best: VersionResult? = null
        for (host in hostList) {
            val text = fetchVersion(host) ?: continue
            val parts = text.split("|")
            if (parts.size < 2) continue
            val code = parts[0].trim().toLongOrNull() ?: continue
            if (best != null && code <= best.code) continue
            best = VersionResult(
                code,
                parts[1].trim(),
                if (parts.size >= 3) parts[2].trim() else ""
            )
        }
        return best
    }

    fun downloadFromSite(context: Context) {
        if (context !is android.app.Activity) return
        ThreadPool.post {
            val latest = queryLatest(context)
            ThreadPool.onUi {
                val code = latest?.code
                if (code != null && code <= BuildConfig.VERSION_CODE.toLong()) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.update_title)
                        .setMessage(R.string.update_uptodate)
                        .setPositiveButton(R.string.close, null)
                        .show()
                    return@onUi
                }
                if (code == null) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.update_title)
                        .setMessage(R.string.update_error)
                        .setPositiveButton(R.string.close, null)
                        .show()
                    return@onUi
                }
                downloadFromSiteNow(context, latest!!.name, latest.apkUrl)
            }
        }
    }

    private fun downloadFromSiteNow(context: Context, latestName: String, versionUrl: String) {
        if (context !is android.app.Activity) return
        if (!ensureInstallPermission(context)) return
        val pd = android.app.ProgressDialog(context)
        pd.setTitle(context.getString(R.string.update_title))
        pd.setMessage(context.getString(R.string.update_downloading, 0))
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
        pd.setMax(100)
        pd.setProgress(0)
        pd.setCancelable(false)
        pd.show()
        ThreadPool.post {
            val target = downloadFromServer(context.applicationContext, latestName, versionUrl) { pct ->
                ThreadPool.onUi {
                    try {
                        pd.progress = pct
                        pd.setMessage(context.getString(R.string.update_downloading, pct))
                    } catch (t: Throwable) {
                    }
                }
            }
            ThreadPool.onUi {
                try {
                    pd.dismiss()
                } catch (t: Throwable) {
                }
                if (target != null) {
                    installApk(context.applicationContext, target)
                } else {
                    CrashLogger.writeLog(context, "UPDATE MANUAL: download nao concluido")
                    android.widget.Toast.makeText(
                        context,
                        R.string.update_failed,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun fetchVersion(host: String): String? {
        return try {
            val conn = URL(host).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.instanceFollowRedirects = true
            val text = conn.inputStream.bufferedReader().use { it.readText() }
                .replace("\uFEFF", "")
                .trim()
            conn.disconnect()
            text
        } catch (t: Throwable) {
            null
        }
    }

    fun check(context: Context) {
        ThreadPool.post {
            val latest = queryLatest(context)
            if (latest != null) handle(context, "${latest.code}|${latest.name}|${latest.apkUrl}")
        }
    }

    private fun handle(context: Context, text: String) {
        val parts = text.split("|")
        if (parts.size < 2) return
        val latestCode = parts[0].trim().toLongOrNull() ?: return
        val latestName = parts[1].trim()
        val apkUrl = if (parts.size >= 3) parts[2].trim() else ""
        try {
            if (latestCode <= BuildConfig.VERSION_CODE.toLong()) return
        } catch (t: Throwable) {
            return
        }
        if (lastShownCode == latestCode) return
        lastShownCode = latestCode
        try {
            val prefs = context.getSharedPreferences("pulsa_update", Context.MODE_PRIVATE)
            if (prefs.getLong("attempted_code", 0L) >= latestCode) return
            prefs.edit().putLong("attempted_code", latestCode).apply()
        } catch (t: Throwable) {
        }
        ThreadPool.onUi {
            if (context !is android.app.Activity) return@onUi
            if (context.isFinishing) return@onUi
            try {
                UpdateService.ensureChannel(context)
                context.startService(
                    Intent(context, UpdateService::class.java)
                        .putExtra(UpdateService.EXTRA_NAME, latestName)
                        .putExtra(UpdateService.EXTRA_URL, apkUrl)
                )
                CrashLogger.writeLog(context, "UPDATE: atualizacao automatica iniciada $latestName")
                Telemetry.log(context, "UPDATE automatico iniciado $latestName")
            } catch (t: Throwable) {
                CrashLogger.writeLog(context, "UPDATE: auto start falhou $t")
                postUpdateNotification(context, latestName, apkUrl)
            }
        }
    }

    private fun postUpdateNotification(context: Context, latestName: String, apkUrl: String) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            UpdateService.ensureChannel(context)
            val pi = PendingIntent.getService(
                context, 0,
                Intent(context, UpdateService::class.java)
                    .putExtra(UpdateService.EXTRA_NAME, latestName)
                    .putExtra(UpdateService.EXTRA_URL, apkUrl),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = NotificationCompat.Builder(context, UpdateService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(context.getString(R.string.update_title))
                .setContentText(context.getString(R.string.update_message, latestName))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .addAction(0, context.getString(R.string.update_now), pi)
                .build()
            nm.notify(UpdateService.NOTIF_ANNOUNCE, notif)
            CrashLogger.writeLog(context, "UPDATE: notificacao postada $latestName $apkUrl")
        } catch (t: Throwable) {
            CrashLogger.writeLog(context, "UPDATE: notificacao falhou $t")
        }
    }

    private fun ensureInstallPermission(context: android.app.Activity): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                Telemetry.log(context, "UPDATE sem permissao de instalacao")
                CrashLogger.writeLog(context, "UPDATE: permissao de instalacao ausente")
                android.widget.Toast.makeText(
                    context,
                    R.string.update_needs_permission,
                    android.widget.Toast.LENGTH_LONG
                ).show()
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.update_title)
                    .setMessage(R.string.update_needs_permission_text)
                    .setPositiveButton(R.string.update_allow) { _, _ ->
                        openUnknownSources(context)
                    }
                    .setNegativeButton(R.string.update_later, null)
                    .show()
                return false
            }
        } catch (t: Throwable) {
            CrashLogger.writeLog(context, "UPDATE: check permissao falhou $t")
        }
        return true
    }

    /** Instala um APK via PackageInstaller; se qualquer passo exigir confirmação/for bloqueado, cai no instalador do sistema (openApk). */
    fun installApk(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            Telemetry.log(context, "UPDATE sem permissao (installApk)")
            installFallback(context, apk)
            return
        }
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                val out = session.openWrite("pulsa.apk", 0, apk.length())
                try {
                    apk.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                } finally {
                    out.close()
                }
                val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, InstallReceiver::class.java),
                    piFlags
                )
                session.commit(pi.intentSender)
                CrashLogger.writeLog(context, "UPDATE: instalacao via PackageInstaller enviada ($sessionId)")
                Telemetry.log(context, "UPDATE instalacao silenciosa enviada")
            } finally {
                session.close()
            }
        } catch (t: Throwable) {
            CrashLogger.writeLog(context, "UPDATE: installApk excecao $t")
            Telemetry.log(context, "UPDATE instalacao silenciosa falhou -> fallback")
            if (sessionId >= 0) {
                try {
                    installer.abandonSession(sessionId)
                } catch (t2: Throwable) {
                }
            }
            installFallback(context, apk)
        }
    }

    private fun installFallback(context: Context, apk: File) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                openUnknownSources(context)
                return
            }
            openApk(context, apk)
        } catch (t: Throwable) {
            CrashLogger.writeLog(context, "UPDATE: installFallback excecao $t")
            openUnknownSources(context)
        }
    }

    fun openApk(context: Context, apk: File) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                Telemetry.log(context, "UPDATE sem permissao (openApk)")
                openUnknownSources(context)
                return
            }
            openApkView(context, apk)
        } catch (t: Throwable) {
            CrashLogger.writeLog(context, "UPDATE: openApk excecao $t")
            openUnknownSources(context)
        }
    }

    private fun openApkView(context: Context, apk: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + ".fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Telemetry.log(context, "UPDATE instalador VIEW aberto")
        } catch (t2: Throwable) {
            Telemetry.log(context, "UPDATE instalador VIEW falhou $t2")
            openUnknownSources(context)
        }
    }

    private fun openUnknownSources(context: Context) {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CrashLogger.writeLog(context, "UPDATE: abrindo permissoes de instalacao")
        } catch (t: Throwable) {
            CrashLogger.writeLog(context, "UPDATE: falha ao abrir permissao $t")
        }
    }

    fun downloadFromServer(context: Context, latestName: String, versionApkUrl: String, onProgress: (Int) -> Unit): File? {
        // URL vinda do /version tem prioridade (ex.: Google Drive publico);
        // os servidores privados ficam de fallback.
        val urls = buildList {
            if (versionApkUrl.startsWith("http://") || versionApkUrl.startsWith("https://")) {
                add(versionApkUrl)
            }
            addAll(fallbackApkHosts)
        }
        val target = File(context.cacheDir, "pulsa-$latestName.apk")
        for (u in urls) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 180000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept-Encoding", "identity")
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val total = conn.contentLength
                if (target.exists()) target.delete()
                conn.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var readTotal = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            readTotal += n
                            if (total > 0) {
                                val pct = (readTotal * 100 / total).toInt().coerceIn(0, 100)
                                onProgress(pct)
                            }
                        }
                        if (total > 0 && readTotal != total.toLong()) {
                            throw java.io.IOException("tamanho ${readTotal}B != ${total}B")
                        }
                    }
                }
                conn.disconnect()
                val ok = isApkFile(target)
                CrashLogger.writeLog(context, "UPDATE: baixado de $u -> $target (${total}B, ok=$ok)")
                if (ok) return target
                if (target.exists()) target.delete()
            } catch (t: Throwable) {
                CrashLogger.writeLog(context, "UPDATE: download falhou $u $t")
                Telemetry.log(context, "UPDATE download erro $u $t")
                if (target.exists()) target.delete()
            }
        }
        return null
    }

    private fun isApkFile(f: File): Boolean {
        return try {
            if (f.length() < 1024) return false
            java.util.zip.ZipFile(f).use { it.entries().hasMoreElements() }
        } catch (t: Throwable) {
            false
        }
    }
}