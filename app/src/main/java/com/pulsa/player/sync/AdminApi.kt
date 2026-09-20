package com.pulsa.player.sync

import android.content.Context
import com.pulsa.player.core.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AdminDevice(
    val id: String,
    val events: Int,
    val first: String,
    val last: String,
    val version: String,
    val banned: Boolean
)

/**
 * Cliente do painel admin do servidor (pulsa-telemetry): login por token,
 * listagem de dispositivos e banir/desbanir. Toda ação exige o
 * X-Admin-Token — quem tem o app mas não o token não consegue banir nada.
 */
object AdminApi {

    fun login(context: Context, token: String): Boolean {
        for (base in Settings.serverCandidates(context)) {
            try {
                val conn = URL("$base/admin/login").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use {
                    it.write(JSONObject().put("token", token).toString().toByteArray(Charsets.UTF_8))
                }
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    if (JSONObject(text).optBoolean("ok")) return true
                }
            } catch (ignored: Throwable) {
            }
        }
        return false
    }

    fun devices(context: Context, token: String): List<AdminDevice>? {
        for (base in Settings.serverCandidates(context)) {
            try {
                val conn = URL("$base/admin/devices").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("X-Admin-Token", token)
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val arr = JSONObject(text).getJSONArray("devices")
                    val out = ArrayList<AdminDevice>(arr.length())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        out.add(
                            AdminDevice(
                                id = o.optString("id"),
                                events = o.optInt("events"),
                                first = o.optString("first"),
                                last = o.optString("last"),
                                version = o.optString("version"),
                                banned = o.optBoolean("banned")
                            )
                        )
                    }
                    return out
                }
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    fun setBan(context: Context, token: String, dev: String, ban: Boolean): Boolean {
        for (base in Settings.serverCandidates(context)) {
            try {
                val conn = URL("$base/admin/${if (ban) "ban" else "unban"}")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("X-Admin-Token", token)
                conn.outputStream.use {
                    it.write(JSONObject().put("dev", dev).toString().toByteArray(Charsets.UTF_8))
                }
                if (conn.responseCode == 200) return true
            } catch (ignored: Throwable) {
            }
        }
        return false
    }
}