package com.pulsa.player

import android.app.Application
import android.content.pm.PackageManager
import com.pulsa.player.core.CrashLogger
import com.pulsa.player.core.Migrations
import com.pulsa.player.sync.MirrorSync
import com.pulsa.player.sync.RemoteSync
import com.pulsa.player.core.Settings
import com.pulsa.player.sync.Telemetry
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PulsaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            Migrations.run(this)
        } catch (t: Throwable) {
        }
        try {
            val lang = Settings.language(this)
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(Settings.languageTag(lang))
            )
        } catch (t: Throwable) {
        }
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            Telemetry.log(this, "APP start ${info.versionName} (${info.versionCode})")
        } catch (e: Exception) {
            Telemetry.log(this, "APP start (sem versao)")
        }
        try {
            RemoteSync.start(this)
        } catch (t: Throwable) {
        }
        try {
            MirrorSync.start(this)
        } catch (t: Throwable) {
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Telemetry.log(this, "UNCAUGHT: [$thread] $throwable")
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val version = try {
                    val info = packageManager.getPackageInfo(packageName, 0)
                    "${info.versionName} (${info.versionCode})"
                } catch (e: PackageManager.NameNotFoundException) {
                    "? (?)"
                }
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = "Pulsa $version\nTIME: $time\nTHREAD: ${thread.name}\n" +
                    "REASON: ${throwable}\n---STACK---\n$sw\n"
                CrashLogger.writeLog(this, text)
            } catch (ignored: Throwable) {
            }
        }
    }
}
