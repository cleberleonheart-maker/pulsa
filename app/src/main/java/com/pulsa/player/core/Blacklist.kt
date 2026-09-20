package com.pulsa.player.core

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lista negra do servidor (pulsa-telemetry). O device banido tem bloqueadas:
 * atualização, telemetria, espelho/controle remoto e sessões — aqui no app e
 * também no servidor ("0||" no /version, 403 nos demais endpoints).
 */
object Blacklist {

    private const val KEY_WARNED = "blacklist_warned_once"
    private const val STALE_MS = 5L * 60_000L

    private val banned = mutableSetOf<String>()
    @Volatile private var loaded = false
    @Volatile private var lastRefreshMs = 0L

    fun isBanned(context: Context): Boolean {
        val dev = Settings.deviceId(context).trim().lowercase()
        if (dev.isEmpty()) return false
        synchronized(banned) { return dev in banned }
    }

    fun refresh(context: Context): Boolean {
        val hosts = Settings.serverCandidates(context).map { "$it/blacklist" }
        for (base in hosts) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(base).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONObject(text).getJSONArray("banned")
                synchronized(banned) {
                    banned.clear()
                    for (i in 0 until arr.length()) banned.add(arr.getString(i).trim().lowercase())
                }
                loaded = true
                lastRefreshMs = System.currentTimeMillis()
                return true
            } catch (t: Throwable) {
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        return false
    }

    fun refreshIfStale(context: Context) {
        if (loaded && System.currentTimeMillis() - lastRefreshMs < STALE_MS) return
        refresh(context)
    }

    fun warnedOnce(context: Context): Boolean =
        context.getSharedPreferences("pulsa_blacklist", Context.MODE_PRIVATE)
            .getBoolean(KEY_WARNED, false)

    fun markWarned(context: Context) {
        context.getSharedPreferences("pulsa_blacklist", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WARNED, true).apply()
    }
}