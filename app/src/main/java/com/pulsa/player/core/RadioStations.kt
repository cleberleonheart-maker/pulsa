package com.pulsa.player.core

import android.content.Context

data class UserStation(
    val name: String,
    val genre: String,
    val url: String
)

object RadioStations {
    private const val FILE = "pulsa_radio_stations"
    private const val SEP = "\u0001"

    /**
     * Estações que já vêm prontas, na ordem em que a tela de rádio mostra.
     *
     * Moravam hardcoded no `RadioActivity`, e isso impedia o `PlaybackService` de girar as
     * estações no botão de avançar: ele só enxergava asstations salvas por URL, então tocar
     * uma rádio padrão e apertar advance não encontrava a estação e não fazia nada.
     */
    val defaults: List<UserStation> = listOf(
        UserStation("Itaramã FM 97.1", "Hits / Litoral Gaúcho", "https://player.voxhd.com.br/proxy/7716"),
        UserStation("Rádio Pampa FM 97.5", "Notícias", "http://cast4.audiostream.com.br:8653/aac"),
        UserStation("Rádio Grenal 95.9", "Esportes", "https://grenal.audiostream.com.br:20000/aac"),
        UserStation("Rádio Campeira FM", "Gaúcha / Sertanejo", "https://servidor34-3.brlogic.com:8164/live?source=website"),
        UserStation("Rádio Nativa", "Sertanejo", "http://centova17.ciclanohost.com.br:8085/stream.mp3"),
        UserStation("Mix FM Porto Alegre", "Pop / Hits", "https://playerservices.streamtheworld.com/api/livestream-redirect/MIXFM_POAAAC.aac"),
        UserStation("Antena 1 Porto Alegre", "Smooth Jazz", "https://antenaone.crossradio.com.br/stream/1"),
        UserStation("Caiçara (Porto Alegre)", "MPB", "http://cast4.audiostream.com.br:8654/mp3"),
        UserStation("104 FM (Porto Alegre)", "Pop / MPB", "http://cast4.audiostream.com.br:8651/mp3"),
        UserStation("Torres FM 101.1", "Pop / Litoral", "https://cast4.audiostream.com.br:2661/mp3"),
        UserStation("Eldorado FM", "Pop / Contemporânea", "https://cast4.audiostream.com.br:2652/mp3"),
        UserStation("Antena 1 São Paulo 94.7", "Pop / Smooth Jazz", "http://antena1.newradio.it/stream?ext=.mp3"),
        UserStation("89 FM A Rádio Rock", "Rock", "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIO_89FM_ADP.aac?dist=site-89fm"),
        UserStation("Nova Brasil FM", "MPB", "https://playerservices.streamtheworld.com/api/livestream-redirect/NOVABRASIL_SPAAC.aac"),
        UserStation("Bossa Nova Brazil", "Bossa Nova", "http://54.38.43.201:8009/stream-128kmp3-BossaNovaBrazil"),
        UserStation("Rádio Cidade 102.9", "Rock Clássico", "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIOCIDADEAAC.aac"),
        UserStation("Alpha FM 101.7", "Light / Adult", "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIO_ALPHAFM_ADP.aac"),
        UserStation("Bossa Jazz Brasil", "Jazz / MPB", "https://centova5.transmissaodigital.com:20104/live"),
        UserStation("Rádio Itatiaia 95.7", "Notícias / Esportes", "https://8903.brasilstream.com.br/stream")
    )

    /**
     * Tudo que o botão de avançar deve percorrer: as padrão primeiro, depois as salvas pelo
     * usuário e as locais automáticas, na mesma ordem da tela de rádio. Uma estação salva
     * com a mesma URL de uma padrão entra uma vez só — a do usuário, que é a que ele nomeou.
     */
    fun all(context: Context): List<UserStation> {
        val known = list(context) + locals(context)
        val knownUrls = known.map { it.url }.toSet()
        return defaults.filterNot { it.url in knownUrls } + known
    }

    // --- locais automáticas (sintonia no automático) ---

    /**
     * As rádios Buscadas por estado e guardadas para a sintonia percorrer sem internet.
     *
     * Ficam num pref SEPARADO das do usuário de propósito: essas não aparecem na lista com
     * lixeira para remover, e uma busca nova substitui o grupo inteiro sem tocar nas que o
     * usuário salvou à mão.
     */
    fun locals(context: Context): List<UserStation> {
        val raw = prefs(context).getString("locals", "") ?: ""
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

    fun saveLocals(context: Context, stations: List<UserStation>) {
        prefs(context).edit().putString("locals", encode(stations)).apply()
    }

    fun hasLocals(context: Context) = locals(context).isNotEmpty()

    /**
     * Estado usado na sintonia automática. O padrão é o RS porque era o que a lista padrão do
     * app já apontava; fica salvo para o usuário não precisar mexer no código se mudar de região.
     */
    fun state(context: Context): String =
        prefs(context).getString("state", DEFAULT_STATE) ?: DEFAULT_STATE

    fun setState(context: Context, value: String) {
        val clean = value.trim().uppercase()
        if (clean.isBlank() || clean.length > 2) return
        prefs(context).edit().putString("state", clean).apply()
    }

    const val DEFAULT_STATE = "RS"

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
