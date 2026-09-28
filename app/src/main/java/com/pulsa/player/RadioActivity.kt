package com.pulsa.player
import com.pulsa.player.core.UserStation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.ui.AnimatedBackground
import com.pulsa.player.core.CrashLogger
import com.pulsa.player.core.RadioStations
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import org.json.JSONArray
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class RadioActivity : AppCompatActivity() {

    private data class Station(
        val name: String,
        val genre: String,
        val url: String
    )

    private val defaultStations = listOf(
        Station("Itaramã FM 97.1", "Hits / Litoral Gaúcho", "https://player.voxhd.com.br/proxy/7716"),
        Station("Rádio Pampa FM 97.5", "Notícias", "http://cast4.audiostream.com.br:8653/aac"),
        Station("Rádio Grenal 95.9", "Esportes", "https://grenal.audiostream.com.br:20000/aac"),
        Station("Rádio Campeira FM", "Gaúcha / Sertanejo", "https://servidor34-3.brlogic.com:8164/live?source=website"),
        Station("Rádio Nativa", "Sertanejo", "http://centova17.ciclanohost.com.br:8085/stream.mp3"),
        Station("Mix FM Porto Alegre", "Pop / Hits", "https://playerservices.streamtheworld.com/api/livestream-redirect/MIXFM_POAAAC.aac"),
        Station("Antena 1 Porto Alegre", "Smooth Jazz", "https://antenaone.crossradio.com.br/stream/1"),
        Station("Caiçara (Porto Alegre)", "MPB", "http://cast4.audiostream.com.br:8654/mp3"),
        Station("104 FM (Porto Alegre)", "Pop / MPB", "http://cast4.audiostream.com.br:8651/mp3"),
        Station("Torres FM 101.1", "Pop / Litoral", "https://cast4.audiostream.com.br:2661/mp3"),
        Station("Eldorado FM", "Pop / Contemporânea", "https://cast4.audiostream.com.br:2652/mp3"),
        Station("Antena 1 São Paulo 94.7", "Pop / Smooth Jazz", "http://antena1.newradio.it/stream?ext=.mp3"),
        Station("89 FM A Rádio Rock", "Rock", "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIO_89FM_ADP.aac?dist=site-89fm"),
        Station("Nova Brasil FM", "MPB", "https://playerservices.streamtheworld.com/api/livestream-redirect/NOVABRASIL_SPAAC.aac"),
        Station("Bossa Nova Brazil", "Bossa Nova", "http://54.38.43.201:8009/stream-128kmp3-BossaNovaBrazil"),
        Station("Rádio Cidade 102.9", "Rock Clássico", "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIOCIDADEAAC.aac"),
        Station("Alpha FM 101.7", "Light / Adult", "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIO_ALPHAFM_ADP.aac"),
        Station("Bossa Jazz Brasil", "Jazz / MPB", "https://centova5.transmissaodigital.com:20104/live"),
        Station("Rádio Itatiaia 95.7", "Notícias / Esportes", "https://8903.brasilstream.com.br/stream")
    )

    private var playbackBind: Playback.Bind? = null
    private var activeStation: Station? = null
    private var errorShownFor: String? = null
    private var pendingStation: Station? = null
    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
    private val rowByIndex = mutableListOf<View>()
    private var stations: List<Station> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_radio)
        AnimatedBackground.apply(this)

        statusText = findViewById(R.id.radio_status)
        listContainer = findViewById(R.id.radio_list)

        findViewById<View>(R.id.btn_radio_back).setOnClickListener { finish() }
        rebuildList()
        renderStatus()
    }

    override fun onStart() {
        super.onStart()
        playbackBind = Playback.connect(this) {
            pendingStation?.let { flushPending(it) }
            renderStatus()
            highlightActive()
        }
    }

    override fun onStop() {
        if (Playback.listener === playbackListener) Playback.listener = null
        playbackBind?.let { Playback.release(it) }
        playbackBind = null
        super.onStop()
    }

    /** E4: o rádio agora toca no mesmo motor do app; esta tela só dirige a fachada. */
    private val playbackListener = object : Playback.Listener {
        override fun onSongChanged(song: Song?, index: Int) {
            syncActiveFromPlayback()
        }

        override fun onPlayStateChanged(isPlaying: Boolean) {
            renderStatus()
            highlightActive()
        }

        override fun onProgress(positionMs: Long, durationMs: Long) = Unit

        override fun onTrackError(song: Song?) {
            errorShownFor = song?.radioUrl
            renderStatus()
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this@RadioActivity, R.string.radio_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Playback.listener = playbackListener
        syncActiveFromPlayback()
        renderStatus()
        highlightActive()
    }

    private fun rebuildList() {
        stations = defaultStations + RadioStations.list(this).map {
            Station(it.name, if (it.genre.isBlank()) getString(R.string.radio) else it.genre, it.url)
        }
        listContainer.removeAllViews()
        rowByIndex.clear()
        buildRows()
    }

    private fun buildRows() {
        val addRow = LayoutInflater.from(this).inflate(R.layout.item_radio, listContainer, false)
        addRow.findViewById<TextView>(R.id.radio_row_name).text = getString(R.string.radio_add)
        addRow.findViewById<TextView>(R.id.radio_row_genre).text = getString(R.string.radio_add_subtitle)
        addRow.findViewById<ImageView>(R.id.radio_row_icon).setImageResource(R.drawable.ic_add)
        val addBtn = addRow.findViewById<ImageButton>(R.id.radio_row_btn)
        addBtn.setImageResource(R.drawable.ic_add)
        addRow.setOnClickListener { openAddDialog() }
        addBtn.setOnClickListener { openAddDialog() }
        listContainer.addView(addRow)

        val saved = RadioStations.list(this)
        if (saved.isNotEmpty()) {
            val header = TextView(this)
            header.text = getString(R.string.radio_your_stations)
            header.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            header.textSize = 12f
            header.setPadding(
                (16 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                0,
                (4 * resources.displayMetrics.density).toInt()
            )
            listContainer.addView(header)
        }

        stations.forEachIndexed { idx, station ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_radio, listContainer, false)
            row.findViewById<TextView>(R.id.radio_row_name).text = station.name
            row.findViewById<TextView>(R.id.radio_row_genre).text = station.genre
            val btn = row.findViewById<ImageButton>(R.id.radio_row_btn)
            btn.contentDescription = getString(R.string.radio_play)
            row.setOnClickListener { toggleStation(idx) }
            btn.setOnClickListener { toggleStation(idx) }
            row.setOnLongClickListener {
                if (RadioStations.list(this).any { it.url == station.url }) {
                    confirmRemove(station)
                }
                true
            }
            listContainer.addView(row)
            rowByIndex.add(row)
        }
    }

    private fun openAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_radio_add, null)
        val nameInput = view.findViewById<EditText>(R.id.radio_add_name)
        val urlInput = view.findViewById<EditText>(R.id.radio_add_url)
        view.findViewById<View>(R.id.btn_radio_search).setOnClickListener {
            val query = nameInput.text.toString().trim()
            if (query.isEmpty()) {
                Toast.makeText(this, R.string.radio_add_name_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            searchOnline(query)
        }
        AlertDialog.Builder(this, Settings.accentStyle(this))
            .setTitle(R.string.radio_add)
            .setView(view)
            .setPositiveButton(R.string.radio_add_save) { d, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    Toast.makeText(this, R.string.radio_add_url_hint, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                d.dismiss()
                saveStation(name, url)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveStation(name: String, url: String) {
        RadioStations.save(this, name, "", url)
        Toast.makeText(this, getString(R.string.radio_saved, name), Toast.LENGTH_SHORT).show()
        rebuildList()
    }

    private fun confirmRemove(station: Station) {
        AlertDialog.Builder(this, Settings.accentStyle(this))
            .setTitle(R.string.radio_removed)
            .setMessage(getString(R.string.radio_remove_confirm, station.name))
            .setPositiveButton(R.string.delete) { d, _ ->
                d.dismiss()
                RadioStations.remove(this, station.url)
                if (activeStation?.url == station.url) Playback.toggle()
                Toast.makeText(this, R.string.radio_removed, Toast.LENGTH_SHORT).show()
                rebuildList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun searchOnline(query: String) {
        statusText.text = getString(R.string.radio_status_connecting)
        ThreadPool.post {
            val results = try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://de1.api.radio-browser.info/json/stations/search?name=$encoded&limit=15&hidebroken=true")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "PulsaRadio/3.42 (Android)")
                }
                val body = try {
                    val reader = BufferedReader(conn.inputStream.reader(Charsets.UTF_8))
                    reader.use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                parseSearchResults(body)
            } catch (e: Exception) {
                CrashLogger.writeLog(this, "RADIO busca falhou $query -> $e")
                null
            }
            ThreadPool.onUi {
                if (isFinishing || isDestroyed) return@onUi
                setStatusStopped()
                if (results == null) {
                    Toast.makeText(this@RadioActivity, R.string.radio_search_fail, Toast.LENGTH_SHORT).show()
                    return@onUi
                }
                if (results.isEmpty()) {
                    Toast.makeText(this@RadioActivity, R.string.radio_search_empty, Toast.LENGTH_SHORT).show()
                    return@onUi
                }
                showSearchResults(results)
            }
        }
    }

    private fun parseSearchResults(body: String): List<UserStation> {
        if (body.isBlank()) return emptyList()
        val out = mutableListOf<UserStation>()
        val arr = JSONArray(body)
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawUrl = obj.optString("url_resolved").ifBlank { obj.optString("url") }
            if (rawUrl.isBlank() || !rawUrl.startsWith("http")) continue
            if (!seen.add(rawUrl)) continue
            val name = obj.optString("name").trim()
            if (name.isEmpty()) continue
            var genre = obj.optString("tags").trim()
            val country = obj.optString("country").trim()
            if (country.isNotEmpty()) {
                genre = if (genre.isEmpty()) country else "$genre · $country"
            }
            out += UserStation(name, genre, rawUrl)
            if (out.size >= 10) break
        }
        return out
    }

    private fun showSearchResults(results: List<UserStation>) {
        val names = results.map {
            if (it.genre.isBlank()) it.name else "${it.name} — ${it.genre}"
        }.toTypedArray()
        AlertDialog.Builder(this, Settings.accentStyle(this))
            .setTitle(R.string.radio_add_search_btn)
            .setItems(names) { _, which ->
                val pick = results[which]
                saveStation(pick.name, pick.url)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleStation(idx: Int) {
        val station = stations[idx]
        val current = Playback.currentSong
        if (current?.isRadio == true && current.radioUrl == station.url) {
            Playback.toggle()
            return
        }
        startStation(station)
    }

    private fun startStation(station: Station) {
        activeStation = station
        errorShownFor = null
        pendingStation = station
        setStatusConnecting()
        highlightActive()
        if (Playback.isReady) flushPending(station)
    }

    private fun flushPending(station: Station) {
        if (pendingStation?.url != station.url) return
        pendingStation = null
        activeStation = station
        errorShownFor = null
        Playback.start(listOf(songFor(station)), 0)
        renderStatus()
    }

    private fun songFor(station: Station) = Song(
        id = (station.url.hashCode() and 0x7fffffff).toLong(),
        title = station.name,
        artist = station.genre,
        album = getString(R.string.radio),
        albumId = 0L,
        durationMs = 0L,
        path = Song.RADIO_PREFIX + station.url,
        year = 0
    )

    private fun syncActiveFromPlayback() {
        val current = Playback.currentSong
        if (current?.isRadio == true) {
            activeStation = stations.firstOrNull { it.url == current.radioUrl } ?: Station(
                current.title, current.artist, current.radioUrl ?: ""
            )
            errorShownFor = null
        }
    }

    private fun renderStatus() {
        val station = activeStation
        when {
            station == null -> {
                if (isFinishing || isDestroyed) return
                setStatusStopped()
            }
            errorShownFor == station.url -> {
                if (isFinishing || isDestroyed) return
                setStatusError()
            }
            Playback.isPlaying -> setStatusLive(station.name)
            else -> {
                if (isFinishing || isDestroyed) return
                setStatusStopped()
            }
        }
    }

    private fun highlightActive() {
        val activeUrl = activeStation?.url
        stations.forEachIndexed { idx, station ->
            val row = rowByIndex.getOrNull(idx) ?: return@forEachIndexed
            val btn = row.findViewById<ImageButton>(R.id.radio_row_btn)
            val icon = row.findViewById<ImageView>(R.id.radio_row_icon)
            if (station.url == activeUrl && Playback.isPlaying) {
                btn.setImageResource(R.drawable.ic_pause)
                btn.contentDescription = getString(R.string.radio_pause)
                icon.tint(R.color.primary)
                row.setBackgroundColor(
                    ContextCompat.getColor(this, R.color.surface_variant)
                )
            } else {
                btn.setImageResource(R.drawable.ic_play)
                btn.contentDescription = getString(R.string.radio_play)
                icon.tint(R.color.text_secondary)
                row.setBackgroundColor(0)
            }
        }
    }

    private fun setStatusStopped() {
        statusText.text = getString(R.string.radio_status_stopped)
    }

    private fun setStatusConnecting() {
        statusText.text = getString(R.string.radio_status_connecting)
    }

    private fun setStatusLive(stationName: String) {
        statusText.text = getString(R.string.radio_status_live) + " · " + stationName
    }

    private fun setStatusError() {
        statusText.text = getString(R.string.radio_status_error)
    }

    override fun onDestroy() {
        AnimatedBackground.stop()
        super.onDestroy()
    }

    private fun ImageView.tint(colorRes: Int) {
        setColorFilter(ContextCompat.getColor(context, colorRes))
    }
}
