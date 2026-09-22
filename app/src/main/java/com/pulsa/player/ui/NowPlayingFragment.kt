package com.pulsa.player.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.pulsa.player.R
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.playback.Playback
import com.pulsa.player.audio.Ambient
import com.pulsa.player.audio.AudioFx
import com.pulsa.player.core.Helper
import com.pulsa.player.sync.Lyrics
import com.pulsa.player.dj.AvatarFavorites
import com.pulsa.player.core.Settings
import com.pulsa.player.audio.SleepTimer
import com.pulsa.player.core.ThreadPool

class NowPlayingFragment : Fragment() {

    private var art: ImageView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var seekBar: SeekBar? = null
    private var currentView: TextView? = null
    private var durationView: TextView? = null
    private var playButton: FloatingActionButton? = null
    private var shuffleView: ImageView? = null
    private var repeatView: ImageView? = null
    private var likeView: ImageView? = null
    private var sleepButton: com.google.android.material.button.MaterialButton? = null
    private var ambientButton: com.google.android.material.button.MaterialButton? = null
    private var abButtonA: com.google.android.material.button.MaterialButton? = null
    private var abButtonB: com.google.android.material.button.MaterialButton? = null
    private var abButtonClear: com.google.android.material.button.MaterialButton? = null
    private var abAButton: com.google.android.material.button.MaterialButton? = null
    private var abBButton: com.google.android.material.button.MaterialButton? = null
    private var abClearButton: com.google.android.material.button.MaterialButton? = null
    private var visualizerView: AudioVisualizerView? = null
    private var shaderView: MusicShaderView? = null
    private var userSeeking = false
    private var lyricsPanel: View? = null
    private var lyricsCur: TextView? = null
    private var lyricsNext: TextView? = null
    private var lyricsForSong = -1L
    private var lyricsLines: List<Lyrics.Line> = emptyList()
    private var lyricsLoading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_now_playing, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        art = view.findViewById(R.id.np_art)
        titleView = view.findViewById(R.id.np_title)
        artistView = view.findViewById(R.id.np_artist)
        seekBar = view.findViewById(R.id.np_seekbar)
        currentView = view.findViewById(R.id.np_current)
        durationView = view.findViewById(R.id.np_duration)
        playButton = view.findViewById(R.id.np_play)
        shuffleView = view.findViewById(R.id.np_shuffle)
        repeatView = view.findViewById(R.id.np_repeat)
        likeView = view.findViewById(R.id.np_like)
        sleepButton = view.findViewById(R.id.np_sleep)
        ambientButton = view.findViewById(R.id.np_ambient)
        abButtonA = view.findViewById(R.id.np_ab_a)
        abButtonB = view.findViewById(R.id.np_ab_b)
        abButtonClear = view.findViewById(R.id.np_ab_clear)
        sleepButton?.setOnClickListener { showSleepDialog() }
        ambientButton?.setOnClickListener { showAmbientDialog() }
        abButtonA?.setOnClickListener {
            Playback.setMarkerA()
            refreshAbButtons()
        }
        abButtonB?.setOnClickListener {
            if (Playback.markerA < 0L) {
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.ab_needs_a),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                Playback.setMarkerB()
            }
            refreshAbButtons()
        }
        abButtonClear?.setOnClickListener {
            Playback.clearAbLoop()
            refreshAbButtons()
        }
        view.findViewById<View>(R.id.np_eq).setOnClickListener { showEqDialog() }
        view.findViewById<View>(R.id.np_lyrics).setOnClickListener { showLyricsDialog() }
        lyricsPanel = view.findViewById(R.id.np_lyrics_panel)
        lyricsCur = view.findViewById(R.id.np_lyrics_cur)
        lyricsNext = view.findViewById(R.id.np_lyrics_next)
        lyricsPanel?.setOnClickListener { showLyricsDialog() }
        visualizerView = view.findViewById(R.id.np_visualizer)
        shaderView = view.findViewById(R.id.np_shader)

        view.findViewById<View>(R.id.np_prev).setOnClickListener { Playback.prev() }
        view.findViewById<View>(R.id.np_next).setOnClickListener { Playback.next() }
        playButton?.setOnClickListener { Playback.toggle() }
        shuffleView?.setOnClickListener {
            Playback.setShuffle(!Playback.shuffle)
            updateModeIcons()
        }
        repeatView?.setOnClickListener {
            Playback.cycleRepeat()
            updateModeIcons()
        }
        likeView?.setOnClickListener { togglerLike() }
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val song = Playback.currentSong
                    if (song != null) {
                        currentView?.text = Helper.formatDuration(progress.toLong() * song.durationMs / 1000)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val song = Playback.currentSong
                if (song != null && seekBar != null) {
                    Playback.seekTo(seekBar.progress.toLong() * song.durationMs / 1000)
                }
                userSeeking = false
            }
        })
        view.findViewById<View>(R.id.np_add_playlist).setOnClickListener {
            Playback.currentSong?.let { song -> PlaylistDialog.showAdd(requireContext(), song) }
        }
    }

    fun render() {
        val song = Playback.currentSong ?: return
        titleView?.text = song.title
        artistView?.text = song.artist + " • " + song.album
        durationView?.text = Helper.formatDuration(song.durationMs)
        art?.let { ArtLoader.load(song.albumId, song.path, it) }
        updatePlayIcon()
        updateModeIcons()
        updateLikeIcon()
        refreshProgress(Playback.position, song.durationMs)
        syncLyricsFor(song)
        refreshModButtons()
        refreshAbButtons()
        refreshDreamTeam(song.id)
    }

    /** Quando a música favorita do avatar toca, o casal dança junto na capa. */
    private fun refreshDreamTeam(songId: Long) {
        val partner = view?.findViewById<com.pulsa.player.ui.DancingVirginView>(R.id.np_virgin_dance_b) ?: return
        val single = view?.findViewById<com.pulsa.player.ui.DancingVirginView>(R.id.np_virgin_dance)
        val male = Settings.masculineAvatar(requireContext())
        if (AvatarFavorites.favoriteId(requireContext()) != songId) {
            single?.forceMale = null
            partner.visibility = View.GONE
            return
        }
        single?.forceMale = male
        partner.forceMale = !male
        partner.visibility = View.VISIBLE
    }

    fun refreshAbButtons() {
        val a = abButtonA ?: return
        val b = abButtonB ?: return
        val clear = abButtonClear ?: return
        val onColor = requireContext().getColor(R.color.primary)
        val offColor = requireContext().getColor(R.color.text_secondary)
        a.setTextColor(if (Playback.markerA >= 0L) onColor else offColor)
        b.setTextColor(if (Playback.markerB >= 0L) onColor else offColor)
        clear.visibility = if (Playback.abActive) View.VISIBLE else View.GONE
    }

    fun refreshPlayIcon() {
        updatePlayIcon()
    }

    fun refreshVisuals() {
        if (!isAdded) return
        val skin = Settings.skin(requireContext())
        visualizerView?.refresh(skin)
        shaderView?.refresh(skin)
    }

    fun refreshProgress(positionMs: Long, durationMs: Long) {
        if (userSeeking) return
        val dur = durationMs.coerceAtLeast(0L)
        if (dur <= 0) return
        seekBar?.progress = ((positionMs.coerceIn(0, dur)) * 1000 / dur).toInt()
        currentView?.text = Helper.formatDuration(positionMs)
        durationView?.text = Helper.formatDuration(dur)
        updateLyricsKaraoke(positionMs)
    }

    /** Carrega as letras da música atual (uma vez por troca) e mostra o painel. */
    private fun syncLyricsFor(song: com.pulsa.player.model.Song) {
        if (song.id == lyricsForSong || lyricsLoading) return
        lyricsLoading = true
        lyricsForSong = song.id
        lyricsLines = emptyList()
        lyricsPanel?.visibility = View.GONE
        ThreadPool.post {
            val result = Lyrics.resolve(song, requireActivity().applicationContext)
            ThreadPool.onUi {
                if (!isAdded || Playback.currentSong?.id != song.id) {
                    lyricsLoading = false
                    lyricsForSong = -1L
                    return@onUi
                }
                lyricsLoading = false
                lyricsLines = result?.lines.orEmpty()
                if (lyricsLines.isEmpty()) {
                    lyricsPanel?.visibility = View.GONE
                } else {
                    lyricsPanel?.visibility = View.VISIBLE
                    updateLyricsKaraoke(Playback.position)
                }
            }
        }
    }

    /** Destaque karaokê: linha atual em destaque + as próximas embaixo, roladas no tempo. */
    private fun updateLyricsKaraoke(positionMs: Long) {
        val lines = lyricsLines
        val cur = lyricsCur ?: return
        if (lines.isEmpty()) return
        val idx = Lyrics.lineAt(lines, positionMs)
        val currentText = lines[idx].text.ifBlank { "\u266A" }
        if (cur.text != currentText) cur.text = currentText
        val next = buildString {
            var shown = 0
            for (i in idx + 1 until lines.size) {
                if (shown >= 3) break
                val t = lines[i].text
                if (t.isBlank()) continue
                if (shown > 0) append('\n')
                append(t)
                shown++
            }
        }
        lyricsNext?.apply {
            setText(if (next.isEmpty()) "" else next)
        }
        lyricsPanel?.apply {
            if (visibility != View.VISIBLE) visibility = View.VISIBLE
        }
    }

    private fun updatePlayIcon() {
        val icon = if (Playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        playButton?.setImageResource(icon)
    }

    private fun updateModeIcons() {
        shuffleView?.tint(if (Playback.shuffle) R.color.primary else R.color.text_secondary)
        val (icon, color) = when {
            Playback.repeatOne -> R.drawable.ic_repeat_one to R.color.primary
            Playback.repeatAll -> R.drawable.ic_repeat to R.color.primary
            else -> R.drawable.ic_repeat to R.color.text_secondary
        }
        repeatView?.setImageResource(icon)
        repeatView?.tint(color)
    }

    private fun updateLikeIcon() {
        val song = Playback.currentSong ?: return
        updateLikeIconFor(song)
    }

    private fun updateLikeIconFor(song: com.pulsa.player.model.Song) {
        val songId = song.id
        ThreadPool.post {
            val fav = PlaylistDb.get(requireActivity().applicationContext).isFavorite(songId)
            ThreadPool.onUi {
                if (!isAdded) return@onUi
                if (Playback.currentSong?.id != songId) return@onUi
                likeView?.setImageResource(if (fav) R.drawable.ic_favorite else R.drawable.ic_heart)
                likeView?.tint(if (fav) R.color.primary else R.color.text_secondary)
            }
        }
    }

    private fun togglerLike() {
        val song = Playback.currentSong ?: return
        ThreadPool.post {
            val db = PlaylistDb.get(requireActivity().applicationContext)
            val fav = db.isFavorite(song.id)
            db.setFavorite(song, !fav)
            ThreadPool.onUi {
                if (isAdded) updateLikeIconFor(song)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SleepTimer.setListener(sleepListener)
        render()
        refreshVisuals()
    }

    override fun onPause() {
        super.onPause()
        SleepTimer.setListener(null)
    }

    private val sleepListener = object : SleepTimer.Listener {
        override fun onSleepActive(remainingMs: Long) {
            if (!isAdded) return
            val label = if (remainingMs < 0L) getString(R.string.sleep_end_track)
            else getString(R.string.sleep_active, (remainingMs / 60_000).toInt())
            sleepButton?.text = label
        }

        override fun onSleepDone() {
            if (!isAdded) return
            sleepButton?.text = getString(R.string.sleep_title)
            ambientButton?.let {
                if (Ambient.isOn()) it.text = ambientLabel(Ambient.currentMode())
                else it.text = getString(R.string.ambient_title)
            }
            requireActivity().let { ctx ->
                android.widget.Toast.makeText(ctx, getString(R.string.sleep_done), android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        override fun onSleepCancelled() {
            if (!isAdded) return
            sleepButton?.text = getString(R.string.sleep_title)
        }
    }

    private fun showSleepDialog() {
        val options = arrayOf(
            getString(R.string.sleep_off),
            getString(R.string.sleep_15),
            getString(R.string.sleep_30),
            getString(R.string.sleep_45),
            getString(R.string.sleep_60),
            getString(R.string.sleep_end_track)
        )
        val minutes = intArrayOf(0, 15, 30, 45, 60)
        var checked = if (!SleepTimer.isActive()) 0 else {
            val rem = SleepTimer.remainingMs()
            if (rem <= 0L) 5 else minutes.indices.reversed().firstOrNull { minutes[it] * 60_000L <= rem } ?: 0
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sleep_title)
            .setSingleChoiceItems(options, checked) { _, which -> checked = which }
            .setPositiveButton(android.R.string.ok) { d, _ ->
                d.dismiss()
                when {
                    checked == 0 -> if (SleepTimer.isActive()) SleepTimer.cancel() else Unit
                    checked == 5 -> SleepTimer.scheduleEndOfTrack()
                    else -> SleepTimer.schedule(minutes[checked])
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAmbientDialog() {
        val modes = listOf(
            Ambient.NIGHT, Ambient.RAIN, Ambient.OCEAN, Ambient.WIND,
            Ambient.FOREST, Ambient.WHITE, Ambient.PINK, Ambient.BROWN
        )
        val labels = ArrayList<String>()
        labels.add(getString(R.string.ambient_off))
        modes.forEach { labels.add(ambientLabel(it)) }
        val current = Ambient.currentMode()
        val checked = if (Ambient.isOn() && current != null) modes.indexOf(current) + 1 else 0
        var selected = checked
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ambient_select)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { _, which -> selected = which }
            .setPositiveButton(android.R.string.ok) { d, _ ->
                d.dismiss()
                when {
                    selected == 0 -> if (Ambient.isOn()) {
                        Ambient.stop()
                        Toast.makeText(requireContext(), getString(R.string.ambient_stopped), Toast.LENGTH_SHORT).show()
                    }
                    selected - 1 in modes.indices -> {
                        val mode = modes[selected - 1]
                        Ambient.start(mode, Ambient.state().volume)
                        Toast.makeText(requireContext(), getString(R.string.ambient_started, ambientLabel(mode)), Toast.LENGTH_SHORT).show()
                    }
                }
                refreshModButtons()
            }
            .setNeutralButton(R.string.ambient_volume) { d, _ ->
                d.dismiss()
                showAmbientVolume()
            }
            .show()
    }

    private fun showAmbientVolume() {
        val slider = SeekBar(requireContext()).apply {
            max = 100
            progress = (Ambient.state().volume * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) Ambient.setVolume(progress / 100f)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val box = LinearLayout(requireContext()).apply {
            setPadding(56, 24, 56, 8)
            addView(slider)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ambient_volume)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun ambientLabel(mode: String?): String = when (mode) {
        Ambient.NIGHT -> getString(R.string.ambient_night)
        Ambient.RAIN -> getString(R.string.ambient_rain)
        Ambient.OCEAN -> getString(R.string.ambient_ocean)
        Ambient.WIND -> getString(R.string.ambient_wind)
        Ambient.FOREST -> getString(R.string.ambient_forest)
        Ambient.WHITE -> getString(R.string.ambient_noise)
        Ambient.PINK -> getString(R.string.ambient_pink)
        Ambient.BROWN -> getString(R.string.ambient_brown)
        else -> getString(R.string.ambient_title)
    }

    private fun showEqDialog() {
        val context = requireContext()
        val freqs = AudioFx.deviceFrequencies(Playback.audioSessionId)
        val realBands = freqs.isNotEmpty()
        val bandCount = if (realBands) freqs.size.coerceAtMost(10) else 5
        val stored = if (Settings.customEqOn(context)) AudioFx.customBands(context)
        else intArrayOf(0, 0, 0, 0, 0)
        val initial = AudioFx.rescale(stored, bandCount)
        val bandNames = if (realBands) {
            Array(bandCount) { "${AudioFx.formatFreq(freqs[it])}Hz" }
        } else arrayOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
        val sliders = arrayOfNulls<SeekBar>(bandCount)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        for (i in 0 until bandCount) {
            val label = TextView(context).apply {
                text = bandNames[i]
                textSize = 13f
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary))
                minWidth = 64.dp()
                gravity = android.view.Gravity.END
            }
            val slider = SeekBar(context).apply {
                max = 200
                progress = initial[i] + 100
            }
            sliders[i] = slider
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(label, LinearLayout.LayoutParams(96.dp(), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(slider, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val subtitle = TextView(context).apply {
            text = if (realBands)
                context.getString(R.string.eq_device_bands, bandCount)
            else context.getString(R.string.eq_fallback_bands)
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(48, 0, 48, 4)
        }
        box.addView(subtitle, 0, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.eq_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val bands = IntArray(bandCount) { (sliders[it]?.progress ?: 100) - 100 }
                Settings.setCustomEqBands(context, bands.joinToString(","))
                Settings.setCustomEqOn(context, true)
                Playback.refreshFx()
                Toast.makeText(context, getString(R.string.eq_saved), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.eq_reset) { _, _ ->
                val flat = IntArray(bandCount)
                Settings.setCustomEqBands(context, flat.joinToString(","))
                Settings.setCustomEqOn(context, false)
                Playback.refreshFx()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLyricsDialog() {
        val song = Playback.currentSong ?: return
        Toast.makeText(requireContext(), getString(R.string.lyrics_searching), Toast.LENGTH_SHORT).show()
        ThreadPool.post {
            val result = Lyrics.resolve(song, requireContext().applicationContext)
            ThreadPool.onUi {
                if (!isAdded) return@onUi
                if (result == null || result.lines.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.lyrics_none), Toast.LENGTH_SHORT).show()
                    return@onUi
                }
                if (result.source == Lyrics.Result.Source.ONLINE_SYNCED) {
                    Toast.makeText(requireContext(), getString(R.string.lyrics_downloaded), Toast.LENGTH_SHORT).show()
                }
                showLyricsDialogContent(song, result)
            }
        }
    }

    private fun showLyricsDialogContent(song: com.pulsa.player.model.Song, result: Lyrics.Result) {
        val context = requireContext()
        val lines = result.lines
        val lineTexts = lines.map { it.text }
        val tv = TextView(context).apply {
            textSize = 16f
            setLineSpacing(0f, 1.25f)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
            setPadding(32, 24, 32, 24)
            text = lineTexts.joinToString("\n")
            movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
        }
        val scroll = ScrollView(context).apply {
            addView(tv)
        }
        val dialogTitle = buildString {
            append(song.title)
            when (result.source) {
                Lyrics.Result.Source.ONLINE_SYNCED -> append(" — ").append(getString(R.string.lyrics_online_lrc))
                Lyrics.Result.Source.ONLINE_PLAIN -> append(" — ").append(getString(R.string.lyrics_online_plain))
                Lyrics.Result.Source.LOCAL -> Unit
            }
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(dialogTitle)
            .setView(scroll)
            .setNegativeButton(R.string.close, null)
            .show()
        val lineHeight = (tv.paint.fontMetrics.run { bottom - top } * 1.25f).toInt().coerceAtLeast(1)
        val handler = Handler(Looper.getMainLooper())
        val updater = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || !isAdded) return
                val idx = Lyrics.lineAt(lines, Playback.position)
                val viewportHalf = (scroll.height / 2).coerceAtLeast(0)
                scroll.smoothScrollTo(0, (idx * lineHeight - viewportHalf).coerceAtLeast(0))
                handler.postDelayed(this, 300)
            }
        }
        handler.post(updater)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun refreshModButtons() {
        if (!isAdded) return
        sleepButton?.text = when {
            !SleepTimer.isActive() -> getString(R.string.sleep_title)
            SleepTimer.isEndOfTrack() -> getString(R.string.sleep_end_track)
            else -> getString(R.string.sleep_active, (SleepTimer.remainingMs() / 60_000).toInt())
        }
        ambientButton?.text = if (Ambient.isOn()) ambientLabel(Ambient.currentMode())
        else getString(R.string.ambient_title)
    }

    private fun ImageView.tint(colorRes: Int) {
        setColorFilter(androidx.core.content.ContextCompat.getColor(context, colorRes))
    }

    fun title(): String = getString(R.string.now_playing)
}
