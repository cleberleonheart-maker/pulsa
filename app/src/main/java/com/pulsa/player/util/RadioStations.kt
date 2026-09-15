package com.pulsa.player.util

import android.content.Context

data class UserStation(
    val name: String,
    val genre: String,
    val url: String
)

object RadioStations {
    private const val FILE = "pulsa_radio_stations"
    private const val SEP = "\u0001"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun list(context: Context): List<UserStation> {
        val raw = prefs(context).getString("stations", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split(SEP)
            if (parts.size < 3) return@mapNotNull null
            val name = parts[0].trim()
            val url = parts[2].trim()
            if (name.isBlank() || url.isBlank()) null
            else UserStation(name, parts[1].trim(), url)
        }
    }

    fun save(context: Context, name: String, genre: String, url: String) {
        val current = list(context).toMutableList()
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isBlank() || cleanUrl.isBlank()) return
        current.removeAll { it.url == cleanUrl }
        current += UserStation(cleanName, genre.trim(), cleanUrl)
        prefs(context).edit().putString("stations", encode(current)).apply()
    }

    fun remove(context: Context, url: String) {
        val current = list(context).filterNot { it.url == url }
        prefs(context).edit().putString("stations", encode(current)).apply()
    }

    private fun encode(stations: List<UserStation>): String {
        return stations.joinToString("\n") { s ->
            "${s.name}$SEP${s.genre}$SEP${s.url}"
        }
    }
}