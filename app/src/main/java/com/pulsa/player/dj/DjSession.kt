package com.pulsa.player.dj

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.DjActivity
import com.pulsa.player.R
import com.pulsa.player.core.Permissions
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.media.GalleryScanner
import com.pulsa.player.media.MusicEditor
import com.pulsa.player.model.Song
import com.pulsa.player.model.Video
import com.pulsa.player.playback.Playback
import com.pulsa.player.sync.Telemetry

/**
 * Sessão completa da cabine do DJ (antigo bloco de lógica do DjActivity).
 * Dono da mixagem, do microfone + TTS, dos comandos de voz, da sugestão com IA,
 * das operações de biblioteca (scan, duplicatas, pendrive, delete, reconhecimento)
 * e do aprendizado de hábitos enquanto a mix roda.
 */
class DjSession(
    private val activity: DjActivity,
    launchers: Launchers,
    private val host: Host
) {

    interface Host {
        fun render()
        fun resetCrossfader()
        fun refreshVoiceLabel()
        fun refreshMicUi(micOn: Boolean, recognizing: Boolean)
        fun setMixBusy(busy: Boolean)
        fun refreshConfigLabels()
    }

    class Launchers(
        val delete: ActivityResultLauncher<IntentSenderRequest>,
        val duplicates: ActivityResultLauncher<IntentSenderRequest>,
        val tree: ActivityResultLauncher<Uri?>
    )

    companion object {
        const val REQ_MIC = 1001
        const val REQ_STORAGE = 1002
        private const val RESUME_LISTENER_DELAY_MS = 800L
    }

    private val launcher = launchers

    var source = Settings.DJ_ALL
    var intensity = Settings.DJ_BALANCED

    private var djActive = false
    private var micOn = false

    private var learnId: Long = -1L
    private var learnStartedAt: Long = 0L
    private var lastCompleted = false
    private var suppressNextLearnSkip = false

    private var micShouldResume = false
    private var scanInFlight = false
    private var pendingDuplicateSongs: List<Song> = emptyList()
    private var pendingDuplicateVideos: List<Video> = emptyList()
    private var pendingPendriveFiles: List<VirginMedia.PendriveFile>? = null
    private var pendingVirginAction: (() -> Unit)? = null
    private var pendriveTotalFound = 0
    private var pendriveDuplicatesSkipped = 0

    private var djVoice: DjVoice? = null
    private var commandListener: DjCommandListener? = null
    private var pendingDelete: Song? = null
    private var recognizing = false
    private var pendingRecognize = false
    private var resumeAfterRecognize = false
    private var lastSpeechEndMs = 0L
    private val resumeListenerRunnable = Runnable { resumeListener() }
    private val uiHandler = Handler(Looper.getMainLooper())

    fun create(savedInstanceState: Bundle?) {
        source = Settings.djSource(activity)
        intensity = Settings.djIntensity(activity)
        djVoice = DjVoice(activity, Settings.languageTag(Settings.language(activity))).also { vm ->
            vm.init { _ ->
                if (Settings.djVoice(activity) && savedInstanceState == null) {
                    vm.speak(activity.getString(R.string.dj_voice_hello))
                }
            }
        }
        host.refreshConfigLabels()
        host.refreshVoiceLabel()
    }

    fun onPermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQ_MIC -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (pendingRecognize) {
                    pendingRecognize = false
                    if (granted) {
                        recognizeSong()
                    } else {
                        Toast.makeText(activity, R.string.dj_mic_denied, Toast.LENGTH_LONG).show()
                    }
                } else if (granted) {
                    startMic()
                } else {
                    Toast.makeText(activity, R.string.dj_mic_denied, Toast.LENGTH_LONG).show()
                }
            }
            REQ_STORAGE -> {
                val granted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                val action = pendingVirginAction
                pendingVirginAction = null
                if (granted && action != null) {
                    action()
                } else if (granted) {
                    performScan()
                } else {
                    speak(activity.getString(R.string.dj_voice_scan_denied))
                }
            }
        }
    }

    fun onDeleteRequestResult(success: Boolean) {
        val song = pendingDelete
        pendingDelete = null
        uiHandler.removeCallbacksAndMessages(null)
        if (success && song != null) {
            finalizeDelete(song, alreadyDeleted = true)
        } else if (song != null) {
            speak(activity.getString(R.string.dj_voice_delete_cancel))
        }
    }

    fun onDuplicatesRequestResult(success: Boolean) {
        val songs = pendingDuplicateSongs
        val videos = pendingDuplicateVideos
        pendingDuplicateSongs = emptyList()
        pendingDuplicateVideos = emptyList()
        uiHandler.removeCallbacksAndMessages(null)
        if (success && (songs.isNotEmpty() || videos.isNotEmpty())) {
            completeDuplicates(songs, videos, alreadyDeleted = true)
        } else if (songs.isNotEmpty() || videos.isNotEmpty()) {
            speak(activity.getString(R.string.dj_voice_dup_failed))
        }
    }

    fun onPendriveTreeResult(uri: Uri?) {
        if (uri == null) {
            if (activity.isFinishing || activity.isDestroyed) return
            pendingPendriveFiles = null
            scanInFlight = false
            resumeListener()
            speak(activity.getString(R.string.dj_voice_pen_none))
            return
        }
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        Settings.setPendriveTreeUri(activity, uri.toString())
        scanPendriveTree(uri)
    }

    fun onSongChanged(song: Song?, index: Int) {
        val app = activity.applicationContext
        var announce: String? = null
        if (djActive) {
            val newId = song?.id ?: -1L
            if (newId != learnId) {
                if (learnId >= 0L) {
                    val ms = SystemClock.elapsedRealtime() - learnStartedAt
                    DjLearn.recordListenMs(app, learnId, ms)
                    if (!lastCompleted && !suppressNextLearnSkip) {
                        DjLearn.recordSkip(app, learnId)
                    }
                    announce = activity.getString(R.string.dj_voice_track, song?.artist, song?.title)
                }
                suppressNextLearnSkip = false
                if (newId >= 0L) {
                    DjLearn.recordPlay(app, newId)
                }
                learnId = newId
                learnStartedAt = SystemClock.elapsedRealtime()
                lastCompleted = false
                if (Settings.djRadio(app)) {
                    val body = curiosityBody(song)
                    if (body != null) {
                        announce = "$body " +
                            activity.getString(R.string.dj_voice_track, song?.artist, song?.title)
                    }
                }
            }
        }
        if (!djActive && announce == null && Settings.djRadio(activity)) {
            val base = activity.getString(R.string.dj_voice_track, song?.artist, song?.title)
            announce = curiosityBody(song)?.let { "$it $base" } ?: base
        }
        if (announce != null) speak(announce)
    }

    fun onProgress(positionMs: Long, durationMs: Long) {
        if (djActive && learnId >= 0L && durationMs > 0L && !lastCompleted &&
            positionMs >= 0.95 * durationMs.toDouble()
        ) {
            lastCompleted = true
        }
    }

    fun toggleVoice() {
        val enabled = !Settings.djVoice(activity)
        Settings.setDjVoice(activity, enabled)
        host.refreshVoiceLabel()
        if (enabled) {
            speak(activity.getString(R.string.dj_voice_hello))
        }
    }

    fun toggleMic() {
        if (micOn) {
            stopMic()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startMic()
    }

    fun onMicLongPress() {
        if (!recognizing) recognizeSong()
    }

    fun startMix() {
        if (!Permissions.hasAccess(activity)) {
            Toast.makeText(activity, R.string.dj_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        val activeSource = source
        val activeIntensity = intensity
        host.setMixBusy(true)
        ThreadPool.post {
            val songs = Library.allSongs(activity.applicationContext)
            val favIds = runCatching {
                PlaylistDb.get(activity.applicationContext).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val learn = DjLearn.learn(activity.applicationContext)
            val set = DjEngine.build(
                songs, favIds,
                if (activeSource == Settings.DJ_FAVORITES) DjEngine.Source.FAVORITES else DjEngine.Source.ALL,
                when (activeIntensity) {
                    Settings.DJ_CALM -> DjEngine.Intensity.CALM
                    Settings.DJ_WILD -> DjEngine.Intensity.WILD
                    else -> DjEngine.Intensity.BALANCED
                },
                learn
            )
            ThreadPool.onUi {
                host.setMixBusy(false)
                if (set.isEmpty()) {
                    Toast.makeText(activity, R.string.dj_empty, Toast.LENGTH_LONG).show()
                    return@onUi
                }
                Telemetry.log(activity, "DJ Virgin mix source=$activeSource style=$activeIntensity n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                djActive = true
                learnId = -1L
                lastCompleted = false
                suppressNextLearnSkip = false
                Playback.start(set, 0)
                host.resetCrossfader()
                host.render()
                speak(activity.getString(R.string.dj_voice_start, set.size))
            }
        }
    }

    fun startSleepMix() {
        if (!Permissions.hasAccess(activity)) {
            Toast.makeText(activity, R.string.dj_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        ThreadPool.post {
            val songs = Library.allSongs(activity.applicationContext)
            val favIds = runCatching {
                PlaylistDb.get(activity.applicationContext).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val learn = DjLearn.learn(activity.applicationContext)
            val set = DjEngine.build(
                songs, favIds, DjEngine.Source.ALL, DjEngine.Intensity.CALM, learn,
                maxSize = 8
            )
            ThreadPool.onUi {
                if (set.isEmpty()) {
                    Toast.makeText(activity, R.string.dj_empty, Toast.LENGTH_LONG).show()
                    return@onUi
                }
                Telemetry.log(activity, "DJ Virgin sleep n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                djActive = true
                learnId = -1L
                lastCompleted = false
                suppressNextLearnSkip = false
                Playback.start(set, 0)
                host.resetCrossfader()
                host.render()
                speak(activity.getString(R.string.dj_voice_sleep, set.size))
            }
        }
    }

    fun startWildMix() {
        if (!Permissions.hasAccess(activity)) {
            Toast.makeText(activity, R.string.dj_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        ThreadPool.post {
            val songs = Library.allSongs(activity.applicationContext)
            val favIds = runCatching {
                PlaylistDb.get(activity.applicationContext).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val learn = DjLearn.learn(activity.applicationContext)
            val set = DjEngine.build(
                songs, favIds, DjEngine.Source.ALL, DjEngine.Intensity.WILD, learn,
                maxSize = 16
            )
            ThreadPool.onUi {
                if (set.isEmpty()) {
                    Toast.makeText(activity, R.string.dj_empty, Toast.LENGTH_LONG).show()
                    return@onUi
                }
                Telemetry.log(activity, "DJ Virgin wild n=${set.size}")
                Playback.setShuffle(true)
                Playback.setRepeatAll(true)
                djActive = true
                learnId = -1L
                lastCompleted = false
                suppressNextLearnSkip = false
                Playback.start(set.shuffled(), 0)
                host.resetCrossfader()
                host.render()
                speak(activity.getString(R.string.dj_voice_mood_wild, set.size))
            }
        }
    }

    fun startArtistOnly(query: String?) {
        val artist = VirginMedia.findArtist(activity, query)
        if (artist == null) {
            speak(activity.getString(R.string.dj_voice_only_none, query ?: ""))
            return
        }
        ThreadPool.post {
            val songs = Library.songsByArtist(activity.applicationContext, artist)
            ThreadPool.onUi {
                if (songs.isEmpty()) {
                    speak(activity.getString(R.string.dj_voice_only_none, artist))
                    return@onUi
                }
                Telemetry.log(activity, "DJ Virgin only artist=$artist n=${songs.size}")
                Playback.setShuffle(true)
                Playback.setRepeatAll(true)
                djActive = true
                learnId = -1L
                lastCompleted = false
                suppressNextLearnSkip = false
                Playback.start(songs.shuffled(), 0)
                host.resetCrossfader()
                host.render()
                speak(activity.getString(R.string.dj_voice_only, artist, songs.size))
            }
        }
    }

    fun startMixWithArtist(query: String?) {
        val artist = VirginMedia.findArtist(activity, query)
        if (artist == null) {
            speak(activity.getString(R.string.dj_voice_mixwith_none, query ?: ""))
            return
        }
        if (!Permissions.hasAccess(activity)) {
            Toast.makeText(activity, R.string.dj_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        ThreadPool.post {
            val songs = Library.allSongs(activity.applicationContext)
            val favIds = runCatching {
                PlaylistDb.get(activity.applicationContext).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val learn = DjLearn.learn(activity.applicationContext)
            val set = DjEngine.build(
                songs, favIds, DjEngine.Source.ALL, DjEngine.Intensity.BALANCED, learn,
                includeArtist = artist
            )
            ThreadPool.onUi {
                if (set.isEmpty()) {
                    Toast.makeText(activity, R.string.dj_empty, Toast.LENGTH_LONG).show()
                    return@onUi
                }
                Telemetry.log(activity, "DJ Virgin mixwith artist=$artist n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                djActive = true
                learnId = -1L
                lastCompleted = false
                suppressNextLearnSkip = false
                Playback.start(set, 0)
                host.resetCrossfader()
                host.render()
                speak(activity.getString(R.string.dj_voice_mixwith, artist, set.size))
            }
        }
    }

    fun chooseSource() {
        val options = arrayOf(
            activity.getString(R.string.dj_source_all) to Settings.DJ_ALL,
            activity.getString(R.string.dj_source_favorites) to Settings.DJ_FAVORITES
        )
        val labels = options.map { it.first }.toTypedArray()
        val current = options.indexOfFirst { it.second == source }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dj_source)
            .setSingleChoiceItems(labels, current) { d, which ->
                source = options[which].second
                Settings.setDjSource(activity, source)
                d.dismiss()
                host.refreshConfigLabels()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun chooseIntensity() {
        val options = listOf(
            activity.getString(R.string.dj_intensity_calm) to Settings.DJ_CALM,
            activity.getString(R.string.dj_intensity_balanced) to Settings.DJ_BALANCED,
            activity.getString(R.string.dj_intensity_wild) to Settings.DJ_WILD
        )
        val labels = options.map { it.first }.toTypedArray()
        val current = options.indexOfFirst { it.second == intensity }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dj_intensity)
            .setSingleChoiceItems(labels, current) { d, which ->
                intensity = options[which].second
                Settings.setDjIntensity(activity, intensity)
                d.dismiss()
                host.refreshConfigLabels()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun suggestSong() {
        val songsHere = Library.allSongs(activity)
        if (songsHere.isEmpty()) {
            if (activity.isFinishing || activity.isDestroyed) return
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dj_suggest_title)
                .setMessage(R.string.dj_suggest_needs_songs)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        if (!DjSuggest.isReady(activity)) {
            offlineSuggest(songsHere)
            return
        }
        pauseListener()
        speak(activity.getString(R.string.dj_suggest_thinking), holdEnabled = true)
        ThreadPool.post {
            val songs = Library.allSongs(activity)
            val suggestion = if (songs.isEmpty()) null else DjSuggest.suggest(activity, songs)
            if (suggestion == null) {
                presuggestOffline(songs)
                return@post
            }
            val freshSongs = Library.allSongs(activity.applicationContext)
            val queue = run {
                val playing = freshSongs.firstOrNull { it.id == findId(suggestion, songs) }
                if (playing != null) listOf(playing) + freshSongs.filter { it.id != playing.id } else emptyList()
            }
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                resumeListener()
                if (queue.isEmpty()) {
                    speak(DjSuggest.toSpeech(suggestion) + " " + activity.getString(R.string.dj_suggest_not_found))
                } else {
                    Telemetry.log(activity, "DJ Virgin AI sugeriu: ${queue.first().title}")
                    speak(DjSuggest.toSpeech(suggestion)) {
                        Playback.start(queue, 0)
                    }
                }
            }
        }
    }

    fun scanLibrary() {
        if (Build.VERSION.SDK_INT >= 33) {
            val hasAudio = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val hasVideo = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasAudio || !hasVideo) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO),
                    REQ_STORAGE
                )
                return
            }
        } else if (!Permissions.hasAccess(activity)) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE
            )
            return
        }
        performScan()
    }

    fun stopForBackground() {
        if (micOn) stopMic()
        commandListener?.destroy()
        commandListener = null
        djVoice?.stop()
    }

    fun destroy() {
        djVoice?.shutdown()
        djVoice = null
    }

    private fun speak(text: String, holdEnabled: Boolean = false, onDone: (() -> Unit)? = null) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (Settings.djVoice(activity)) {
            pauseListener()
            djVoice?.speak(text) {
                lastSpeechEndMs = SystemClock.elapsedRealtime()
                ThreadPool.onUi {
                    if (!holdEnabled) uiHandler.postDelayed(resumeListenerRunnable, RESUME_LISTENER_DELAY_MS)
                    onDone?.invoke()
                }
            }
        } else {
            resumeListener()
            onDone?.invoke()
        }
    }

    private fun pauseListener() {
        uiHandler.removeCallbacks(resumeListenerRunnable)
        if (micOn && !micShouldResume) {
            micShouldResume = true
            commandListener?.stop()
        }
    }

    private fun resumeListener() {
        if (!micShouldResume) return
        if (!micOn || activity.isFinishing || activity.isDestroyed) {
            micShouldResume = false
            return
        }
        if (djVoice?.isSpeaking == true) return
        micShouldResume = false
        commandListener?.start()
    }

    private fun startMic() {
        micOn = true
        host.refreshMicUi(true, false)
        Playback.setMicListening(true)
        commandListener?.destroy()
        commandListener = DjCommandListener(activity) { handleCommand(it) }
        commandListener?.start()
        speak(activity.getString(R.string.dj_mic_hint))
    }

    private fun stopMic() {
        micOn = false
        micShouldResume = false
        Playback.setMicListening(false)
        commandListener?.stop()
        host.refreshMicUi(false, false)
        djVoice?.stop()
    }

    private fun handleCommand(text: String) {
        if (text == "__unsupported__") {
            Toast.makeText(activity, R.string.dj_mic_denied, Toast.LENGTH_LONG).show()
            stopMic()
            return
        }
        ThreadPool.onUi { resolveVoiceCommand(text) }
    }

    private fun resolveVoiceCommand(text: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (SystemClock.elapsedRealtime() - lastSpeechEndMs < 1500L) return
        val norm = DjCommander.norm(text)
        val hasWake = DjCommander.hasWake(norm)
        val action = DjCommander.action(norm)
        if (action == null) {
            if (hasWake) speak(activity.getString(R.string.dj_voice_unknown))
            return
        }
        if (action !in setOf("confirm", "cancel", "delete")) {
            pendingDelete = null
            uiHandler.removeCallbacksAndMessages(null)
        }
        when (action) {
            "mix" -> {
                speak(activity.getString(R.string.dj_voice_mix))
                startMix()
            }
            "memory_save" -> voiceMemorySave(norm)
            "memory_recall" -> voiceMemoryRecall(norm)
            "mood_wild" -> startWildMix()
            "sleep" -> startSleepMix()
            "repeat" -> {
                val on = !Playback.repeatOne
                Playback.setRepeatOne(on)
                speak(activity.getString(
                    if (on) R.string.dj_voice_repeat_on else R.string.dj_voice_repeat_off
                ))
            }
            "only" -> {
                startArtistOnly(DjCommander.onlyArtist(norm))
            }
            "mixwith" -> {
                startMixWithArtist(DjCommander.mixArtist(norm))
            }
            "skip" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    DjLearn.recordSkip(activity.applicationContext, cur.id)
                    suppressNextLearnSkip = true
                }
                speak(activity.getString(R.string.dj_voice_skip))
                if (Playback.queue.isNotEmpty()) Playback.next() else startMix()
            }
            "dislike" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    DjLearn.recordDislike(activity.applicationContext, cur.id)
                    suppressNextLearnSkip = true
                }
                speak(activity.getString(R.string.dj_voice_dislike))
                if (Playback.queue.isNotEmpty()) Playback.next() else startMix()
            }
            "next" -> {
                speak(activity.getString(R.string.dj_voice_next))
                if (Playback.queue.isNotEmpty()) Playback.next() else startMix()
            }
            "prev" -> {
                speak(activity.getString(R.string.dj_voice_prev))
                Playback.prev()
            }
            "pause" -> {
                djVoice?.stop()
                if (Playback.isPlaying) {
                    Playback.toggle()
                    speak(activity.getString(R.string.dj_voice_pause))
                }
            }
            "play" -> {
                djVoice?.stop()
                if (!Playback.isPlaying && Playback.queue.isNotEmpty()) {
                    Playback.toggle()
                    speak(activity.getString(R.string.dj_voice_play))
                }
            }
            "fav" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    val db = PlaylistDb.get(activity.applicationContext)
                    val nextValue = !db.isFavorite(cur.id)
                    db.setFavorite(cur, nextValue)
                    if (nextValue) DjLearn.recordLiked(activity.applicationContext, cur.id)
                    Toast.makeText(activity, R.string.dj_voice_fav, Toast.LENGTH_SHORT).show()
                    speak(activity.getString(
                        if (nextValue) R.string.dj_voice_learn_like else R.string.dj_voice_fav
                    ))
                }
            }
            "info" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    speak(activity.getString(R.string.dj_voice_track, cur.artist, cur.title))
                } else {
                    speak(activity.getString(R.string.dj_voice_unknown))
                }
            }
            "scan" -> scanLibrary()
            "duplicates" -> findDuplicates()
            "pendrive" -> readPendrive()
            "recognize" -> {
                recognizeSong()
            }
            "delete" -> {
                val cur = Playback.currentSong
                if (cur == null) {
                    pendingDelete = null
                    uiHandler.removeCallbacksAndMessages(null)
                    speak(activity.getString(R.string.dj_voice_delete_no_song))
                } else if (pendingDelete?.id == cur.id) {
                    pendingDelete = null
                    uiHandler.removeCallbacksAndMessages(null)
                    doDelete(cur)
                } else {
                    pendingDelete = cur
                    uiHandler.removeCallbacksAndMessages(null)
                    uiHandler.postDelayed({ pendingDelete = null }, 15000)
                    speak(activity.getString(R.string.dj_voice_delete_confirm, cur.title))
                }
            }
            "confirm" -> {
                if (pendingPendriveFiles != null) {
                    uiHandler.removeCallbacksAndMessages(null)
                    completePendriveCopy()
                } else {
                    val song = pendingDelete
                    pendingDelete = null
                    uiHandler.removeCallbacksAndMessages(null)
                    if (song != null) doDelete(song)
                }
            }
            "cancel" -> {
                if (pendingPendriveFiles != null) {
                    pendingPendriveFiles = null
                    uiHandler.removeCallbacksAndMessages(null)
                    speak(activity.getString(R.string.dj_voice_pen_cancel))
                } else {
                    pendingDelete = null
                    uiHandler.removeCallbacksAndMessages(null)
                    speak(activity.getString(R.string.dj_voice_delete_cancel))
                }
            }
            "suggest" -> suggestSong()
            "identity" -> {
                speak(DjIdentity.introSpeech())
            }
            "thanks" -> speak(activity.getString(R.string.dj_voice_thanks))
            "hello" -> speak(activity.getString(R.string.dj_voice_hello))
        }
    }

    private fun memoryLabel(key: String): String = when (key) {
        DjMemory.WHATSAPP -> activity.getString(R.string.dj_memory_whatsapp)
        DjMemory.BLUETOOTH -> activity.getString(R.string.dj_memory_bluetooth)
        else -> activity.getString(R.string.dj_memory_phone)
    }

    private fun voiceMemorySave(norm: String) {
        val saved = DjMemory.save(activity.applicationContext, norm)
        if (saved != null) {
            DjMemory.log(activity.applicationContext, norm, "memorizado ${saved.first}")
            speak(activity.getString(R.string.dj_voice_memory_saved, memoryLabel(saved.first), saved.second))
        } else {
            speak(activity.getString(R.string.dj_voice_memory_ask, memoryLabel(DjMemory.PHONE)))
        }
    }

    private fun voiceMemoryRecall(norm: String) {
        val fact = DjMemory.recall(activity.applicationContext, norm)
        if (fact != null) {
            DjMemory.log(activity.applicationContext, norm, "lembra ${fact.first}")
            speak(activity.getString(R.string.dj_voice_memory_saved, memoryLabel(fact.first), fact.second))
        } else {
            speak(activity.getString(R.string.dj_voice_memory_ask, memoryLabel(DjMemory.PHONE)))
        }
    }

    private fun offlineSuggest(songs: List<Song>) {
        pauseListener()
        speak(activity.getString(R.string.dj_suggest_thinking), holdEnabled = true)
        ThreadPool.post { presuggestOffline(songs) }
    }

    private fun presuggestOffline(songs: List<Song>) {
        val freshSongs = Library.allSongs(activity.applicationContext)
        val suggestion = DjSuggest.offline(activity, songs)
        val queue = run {
            val playing = freshSongs.firstOrNull { it.id == suggestion.let { s -> findId(s, songs) } }
            if (playing != null) listOf(playing) + freshSongs.filter { it.id != playing.id } else emptyList()
        }
        ThreadPool.onUi {
            if (activity.isFinishing || activity.isDestroyed) return@onUi
            resumeListener()
            if (suggestion.title.isBlank() || queue.isEmpty()) {
                speak(activity.getString(R.string.dj_suggest_error))
                return@onUi
            }
            Telemetry.log(activity, "DJ Virgin offline sugeriu: ${queue.first().title}")
            speak(DjSuggest.toSpeech(suggestion)) {
                Playback.start(queue, 0)
            }
        }
    }

    private fun findId(suggestion: DjSuggest.Suggestion, songs: List<Song>): Long {
        return DjSuggest.findSong(songs, suggestion)?.id ?: -1L
    }

    @Suppress("unused")
    private fun showGeminiSetup() {
        if (activity.isFinishing || activity.isDestroyed) return
        val input = EditText(activity)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        input.hint = activity.getString(R.string.dj_suggest_key_hint)
        input.setText(Settings.geminiKey(activity))
        input.setSingleLine(true)
        val box = CheckBox(activity)
        box.text = activity.getString(R.string.dj_suggest_enable)
        box.isChecked = Settings.geminiOn(activity)
        val layout = LinearLayout(activity)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(48, 8, 48, 0)
        layout.addView(input)
        layout.addView(box)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dj_suggest_title)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                Settings.setGeminiKey(activity, input.text.toString())
                Settings.setGeminiOn(activity, box.isChecked)
                Telemetry.log(activity, "DJ Virgin AI config atualizada")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performScan() {
        if (scanInFlight) return
        scanInFlight = true
        speak(activity.getString(R.string.dj_voice_scan), holdEnabled = true)
        GalleryScanner.scan(activity.applicationContext) { songs, videos ->
            scanInFlight = false
            if (activity.isFinishing || activity.isDestroyed) {
                resumeListener()
                return@scan
            }
            speak(activity.getString(R.string.dj_voice_scan_result, songs, videos))
            Toast.makeText(
                activity, activity.getString(R.string.dj_voice_scan_result, songs, videos),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun hasMediaPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            Permissions.hasAccess(activity)
        }

    private fun mediaPermissionNeeded(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun findDuplicates() {
        if (scanInFlight) return
        if (!hasMediaPermission()) {
            pendingVirginAction = { performDuplicates() }
            ActivityCompat.requestPermissions(activity, mediaPermissionNeeded(), REQ_STORAGE)
            return
        }
        performDuplicates()
    }

    private fun performDuplicates() {
        if (scanInFlight) return
        scanInFlight = true
        speak(activity.getString(R.string.dj_voice_dup_search), holdEnabled = true)
        ThreadPool.post {
            val dup = VirginMedia.findDuplicates(activity.applicationContext)
            ThreadPool.onUi {
                scanInFlight = false
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (dup.copies == 0) {
                    speak(activity.getString(R.string.dj_voice_dup_none))
                    return@onUi
                }
                pendingDuplicateSongs = dup.songsCopies
                pendingDuplicateVideos = dup.videoCopiesList
                speak(activity.getString(R.string.dj_voice_dup_found, dup.pairs, dup.copies))
                if (Build.VERSION.SDK_INT >= 30) {
                    val uris = buildList {
                        dup.songsCopies.forEach {
                            add(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id))
                        }
                        dup.videoCopiesList.forEach { add(VideoLibrary.contentUri(it.id)) }
                    }
                    val sender = runCatching {
                        MediaStore.createDeleteRequest(activity.contentResolver, uris)
                    }.getOrNull()
                    if (sender == null) {
                        pendingDuplicateSongs = emptyList()
                        pendingDuplicateVideos = emptyList()
                        speak(activity.getString(R.string.dj_voice_dup_failed))
                        return@onUi
                    }
                    launcher.duplicates.launch(
                        IntentSenderRequest.Builder(sender).build()
                    )
                } else {
                    completeDuplicates(dup.songsCopies, dup.videoCopiesList, alreadyDeleted = false)
                }
            }
        }
    }

    private fun completeDuplicates(
        songsCopies: List<Song>,
        videoCopiesList: List<Video>,
        alreadyDeleted: Boolean
    ) {
        ThreadPool.post {
            val deleted = if (alreadyDeleted) true else
                VirginMedia.deleteDuplicates(activity.applicationContext, songsCopies, videoCopiesList)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (deleted) {
                    runCatching {
                        songsCopies.forEach { VirginMedia.removeFromPlaylists(activity.applicationContext, it) }
                    }
                    if (Playback.currentSong?.let { c -> songsCopies.any { it.id == c.id } } == true) {
                        Playback.next()
                    }
                    speak(activity.getString(R.string.dj_voice_dup_done, songsCopies.size + videoCopiesList.size))
                } else {
                    speak(activity.getString(R.string.dj_voice_dup_failed))
                }
            }
        }
    }

    private fun readPendrive() {
        if (scanInFlight) return
        if (!hasMediaPermission()) {
            pendingVirginAction = { performPendrive() }
            ActivityCompat.requestPermissions(activity, mediaPermissionNeeded(), REQ_STORAGE)
            return
        }
        performPendrive()
    }

    private fun performPendrive() {
        if (scanInFlight) return
        val savedTree = Settings.pendriveTreeUri(activity)
        if (savedTree.isNullOrBlank()) {
            scanInFlight = true
            speak(activity.getString(R.string.dj_voice_pen_search), holdEnabled = true)
            launcher.tree.launch(null)
            return
        }
        scanPendriveTree(runCatching { Uri.parse(savedTree) }.getOrNull() ?: return)
    }

    private fun scanPendriveTree(rootUri: Uri) {
        if (scanInFlight) return
        scanInFlight = true
        speak(activity.getString(R.string.dj_voice_pen_search), holdEnabled = true)
        ThreadPool.post {
            val scan = VirginMedia.scanPendrive(activity.applicationContext, rootUri)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) {
                    scanInFlight = false
                    resumeListener()
                    return@onUi
                }
                when {
                    scan.files.isEmpty() -> {
                        scanInFlight = false
                        speak(activity.getString(R.string.dj_voice_pen_none))
                    }
                    scan.newFiles.isEmpty() -> {
                        scanInFlight = false
                        speak(activity.getString(R.string.dj_voice_pen_all_dups, scan.files.size))
                    }
                    else -> {
                        pendriveTotalFound = scan.files.size
                        pendriveDuplicatesSkipped = scan.skipped
                        pendingPendriveFiles = scan.newFiles
                        uiHandler.removeCallbacksAndMessages(null)
                        uiHandler.postDelayed({
                            pendingPendriveFiles = null
                        }, 60000)
                        speak(penConfirmMessage(scan.newFiles.size))
                    }
                }
            }
        }
    }

    private fun penConfirmMessage(count: Int): String =
        activity.getString(R.string.dj_voice_pen_confirm, count)

    private fun completePendriveCopy() {
        val files = pendingPendriveFiles ?: return
        pendingPendriveFiles = null
        ThreadPool.post {
            var copied = 0
            for (f in files) {
                if (VirginMedia.copyToMusic(activity.applicationContext, f)) copied++
            }
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (copied == 0) {
                    speak(activity.getString(R.string.dj_voice_pen_copy_failed))
                    return@onUi
                }
                if (pendriveDuplicatesSkipped > 0) {
                    speak(activity.getString(
                        R.string.dj_voice_pen_copy_done_dups,
                        pendriveTotalFound, pendriveDuplicatesSkipped, copied
                    ))
                } else {
                    speak(activity.getString(R.string.dj_voice_pen_copy_done, copied))
                }
            }
        }
    }

    private fun doDelete(song: Song) {
        if (Build.VERSION.SDK_INT >= 30) {
            val sender = runCatching {
                MediaStore.createDeleteRequest(
                    activity.contentResolver,
                    listOf(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id))
                )
            }.getOrNull()
            if (sender == null) {
                speak(activity.getString(R.string.dj_voice_delete_failed))
                return
            }
            pendingDelete = song
            launcher.delete.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            finalizeDelete(song, alreadyDeleted = false)
        }
    }

    private fun finalizeDelete(song: Song, alreadyDeleted: Boolean) {
        ThreadPool.post {
            val deleted = if (alreadyDeleted) {
                true
            } else {
                VirginMedia.deleteSong(activity.applicationContext, song)
            }
            ThreadPool.onUi {
                if (deleted) {
                    VirginMedia.removeFromPlaylists(activity.applicationContext, song)
                    if (Playback.currentSong?.id == song.id) Playback.next()
                    speak(activity.getString(R.string.dj_voice_delete_done, song.title))
                    Telemetry.log(activity, "DJ Virgin delete vc ok id=${song.id}")
                } else {
                    speak(activity.getString(R.string.dj_voice_delete_failed))
                }
            }
        }
    }

    private fun recognizeSong() {
        if (recognizing) return
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingRecognize = true
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (micOn) stopMic()
        recognizing = true
        resumeAfterRecognize = Playback.isPlaying
        if (resumeAfterRecognize) Playback.pause()
        host.refreshMicUi(false, true)
        speak(activity.getString(R.string.dj_voice_recognize)) {
            DjRecognizer.recognize(activity.applicationContext) { result, error ->
                if (resumeAfterRecognize && !Playback.isPlaying && !activity.isFinishing && !activity.isDestroyed) {
                    Playback.toggle()
                }
                resumeAfterRecognize = false
                if (activity.isFinishing || activity.isDestroyed) return@recognize
                recognizing = false
                host.refreshMicUi(false, false)
                when {
                    error == "no_token" -> {
                        speak(activity.getString(R.string.dj_voice_recognize_configured))
                        Toast.makeText(activity, R.string.dj_voice_recognize_configured, Toast.LENGTH_LONG).show()
                    }
                    error == "network" || error?.startsWith("audd_error:") == true -> {
                        val msg = when {
                            error.contains("900") -> activity.getString(R.string.dj_voice_recognize_token)
                            error.contains("901") || error.contains("902") -> activity.getString(R.string.dj_voice_recognize_quota)
                            else -> activity.getString(R.string.dj_voice_recognize_error)
                        }
                        speak(msg)
                        Telemetry.log(activity, "REC erro: $error")
                    }
                    error != null -> speak(activity.getString(R.string.dj_voice_recognize_error))
                    result == null -> speak(activity.getString(R.string.dj_voice_recognize_none))
                    else -> {
                        speak(activity.getString(R.string.dj_voice_recognize_recognized, result.title, result.artist))
                        val cur = Playback.currentSong
                        if (cur != null) {
                            MusicEditor.renameDetected(
                                activity.applicationContext, cur,
                                result.title, result.artist, result.albumName
                            ) { ok ->
                                if (ok) {
                                    speak(activity.getString(R.string.dj_voice_recognize_renamed, result.title, result.artist))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun curiosityBody(song: Song?): String? {
        if (!DjFacts.curiosityDue()) return null
        val local = DjFacts.curiosityFor(song?.artist ?: "")
        if (local != null) {
            DjFacts.markCuriositySpoken()
            return "${DjFacts.leadIn(intensity)} $local"
        }
        val songId = song?.id
        if (songId == null) return null
        DjFacts.fetchRemoteCuriosity(activity, song?.artist ?: "") { remote ->
            if (remote == null || activity.isFinishing || activity.isDestroyed) return@fetchRemoteCuriosity
            if (Playback.currentSong?.id != songId || !DjFacts.curiosityDue()) return@fetchRemoteCuriosity
            DjFacts.markCuriositySpoken()
            speak("${DjFacts.leadIn(intensity)} $remote")
        }
        return null
    }
}