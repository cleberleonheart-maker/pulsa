package com.pulsa.player.sync

import org.json.JSONObject

/**
 * Parte pura do controle remoto: lê a resposta de GET /session?code= e monta o
 * corpo de POST /cmd. Sem Android, para poder testar no JVM.
 *
 * O servidor devolve, para o convidado, o id do dispositivo anfitrião:
 *   {"ok":true,"code":"ABCDE","host":"...","playing":true,
 *    "state":{"playing":true,"current":{"title":..,"artist":..,"durationMs":..},
 *             "positionMs":123,"volume":70}}
 */
object RemoteProtocol {

    data class Snap(
        val ok: Boolean,
        val code: String,
        val host: String,
        val playing: Boolean,
        val hasTrack: Boolean,
        val title: String,
        val artist: String,
        val album: String,
        val positionMs: Long,
        val durationMs: Long,
        val volume: Int
    ) {
        val online: Boolean get() = ok && host.isNotBlank()
    }

    fun parseSession(body: String): Snap {
        val o = runCatching { JSONObject(body) }.getOrNull()
            ?: return Snap(false, "", "", false, false, "", "", "", 0L, 0L, -1)
        val ok = o.optBoolean("ok", false)
        val state = o.optJSONObject("state")
        val cur = state?.optJSONObject("current")
        val hasTrack = cur != null && !cur.isNull("id") &&
            (cur.optString("title").isNotEmpty() || cur.optLong("id", -1L) > 0L)
        val playing = (state?.optBoolean("playing", false) ?: false) && hasTrack
        return Snap(
            ok = ok,
            code = o.optString("code"),
            host = o.optString("host"),
            playing = playing,
            hasTrack = hasTrack,
            title = cur?.optString("title") ?: "",
            artist = cur?.optString("artist") ?: "",
            album = cur?.optString("album") ?: "",
            positionMs = state?.optLong("positionMs", 0L) ?: 0L,
            durationMs = cur?.optLong("durationMs", 0L) ?: 0L,
            volume = state?.optInt("volume", -1) ?: -1
        )
    }

    /** Corpo de POST /cmd. value nulo vira só {"cmd":...}. */
    fun cmdJson(cmd: String, value: Any? = null): String {
        val o = JSONObject()
        o.put("cmd", cmd)
        if (value != null) o.put("value", value)
        return o.toString()
    }
}
