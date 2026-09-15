package com.pulsa.player.util

import android.content.Context
import com.pulsa.player.model.Song
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Scrobble para o Last.fm usando a API Web.
 * Requer api_key e secret configurados em Settings (lastfm_key/lastfm_secret)
 * além de uma sk (session key) obtida via getToken/getSessionUrl/auth.getSession.
 */
object LastFm {

    private const val API = "https://ws.audioscrobbler.com/2.0/"
    private const val AUTH = "https://www.last.fm/api/auth/"

    fun isConfigured(context: Context): Boolean =
        Settings.lastFmKey(context).isNotBlank() &&
            Settings.lastFmSecret(context).isNotBlank() &&
            Settings.lastFmSession(context).isNotBlank()

    /** Gera a URL que o usuário abre para autorizar o app e receber o token. */
    fun getSessionUrl(context: Context): String {
        val key = Settings.lastFmKey(context)
        return "$AUTH?api_key=$key"
    }

    fun getToken(context: Context): String? {
        val key = Settings.lastFmKey(context)
        val secret = Settings.lastFmSecret(context)
        if (key.isBlank() || secret.isBlank()) return null
        val method = "auth.gettoken"
        val apiSig = sign(mapOf("api_key" to key, "method" to method), secret)
        val json = post(mapOf(
            "method" to method,
            "api_key" to key,
            "api_sig" to apiSig,
            "format" to "json"
        )) ?: return null
        return runCatching {
            org.json.JSONObject(json).getString("token")
        }.getOrNull()
    }

    fun getSession(context: Context, token: String): String? {
        val key = Settings.lastFmKey(context)
        val secret = Settings.lastFmSecret(context)
        val method = "auth.getsession"
        val apiSig = sign(mapOf("api_key" to key, "method" to method, "token" to token), secret)
        val json = post(mapOf(
            "method" to method,
            "api_key" to key,
            "token" to token,
            "api_sig" to apiSig,
            "format" to "json"
        )) ?: return null
        return runCatching {
            org.json.JSONObject(json)
                .getJSONObject("session")
                .getString("key")
        }.getOrNull()
    }

    /** Avisa o Last.fm que a música está tocando agora. */
    fun nowPlaying(context: Context, song: Song, durationMs: Long) {
        if (!isConfigured(context)) return
        val params = mutableMapOf<String, String>()
        params["method"] = "track.updatenowplaying"
        params["api_key"] = Settings.lastFmKey(context)
        params["sk"] = Settings.lastFmSession(context)
        params["artist"] = song.artist
        params["track"] = song.title
        params["album"] = song.album
        if (durationMs > 0) params["duration"] = (durationMs / 1000).toString()
        postSigned(context, params)
    }

    /** Registra a audição completa (>=50% da duração). */
    fun scrobble(context: Context, song: Song, durationMs: Long) {
        if (!isConfigured(context)) return
        val ts = (System.currentTimeMillis() / 1000).toString()
        val params = mutableMapOf<String, String>()
        params["method"] = "track.scrobble"
        params["api_key"] = Settings.lastFmKey(context)
        params["sk"] = Settings.lastFmSession(context)
        params["artist"] = song.artist
        params["track"] = song.title
        params["album"] = song.album
        params["timestamp"] = ts
        if (durationMs > 0) params["duration"] = (durationMs / 1000).toString()
        postSigned(context, params)
    }

    private fun postSigned(context: Context, params: MutableMap<String, String>) {
        val apiSig = sign(params, Settings.lastFmSecret(context))
        params["api_sig"] = apiSig
        params["format"] = "json"
        ThreadPool.post {
            post(params)
        }
    }

    private fun sign(params: Map<String, String>, secret: String): String {
        val sorted = params.toSortedMap()
        val raw = sorted.toList().joinToString("") { "${it.first}${it.second}" }
        return md5(raw + secret)
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun post(params: Map<String, String>): String? {
        return try {
            val body = params.map { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }.joinToString("&")
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) null else {
                conn.inputStream.bufferedReader().readText()
            }
        } catch (t: Throwable) {
            null
        }
    }
}