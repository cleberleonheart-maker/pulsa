package com.pulsa.player.dj

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pulsa.player.MainActivity
import com.pulsa.player.R
import com.pulsa.player.core.Permissions
import com.pulsa.player.core.Profile
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.media.GalleryScanner
import com.pulsa.player.media.MusicEditor
import com.pulsa.player.model.Song
import com.pulsa.player.model.Video
import com.pulsa.player.playback.Playback
import com.pulsa.player.sync.Telemetry

/**
 * Assistente por voz da tela principal (antigo bloco "Virgin" do MainActivity).
 * Dono de todo o ciclo de vida do microfone + TTS, dos comandos de voz e das
 * operações de biblioteca (scan, duplicatas, pendrive, delete, reconhecimento).
 */
class MainVirgin(
    private val activity: MainActivity,
    launchers: Launchers,
    private val host: Host
) {

    interface Host {
        fun syncVirginIcon()
        fun syncMiniPlayer()
        fun refreshPlayerVisuals()
    }

    class Launchers(
        val delete: ActivityResultLauncher<IntentSenderRequest>,
        val duplicates: ActivityResultLauncher<IntentSenderRequest>,
        val tree: ActivityResultLauncher<Uri?>
    )

    companion object {
        const val REQ_VIRGIN_MIC = 2001
        const val REQ_VIRGIN_STORAGE = 2002
        private const val RESUME_LISTENER_DELAY_MS = 800L
        private const val CHAIN_DELAY_MS = 1800L
    }

    private val launcher = launchers

    val isActive: Boolean
        get() = virginOn || virginRecognizing

    private var welcomeVoice: DjVoice? = null
    private var virginVoice: DjVoice? = null
    private var radioLastId = -1L
    private var virginListener: DjCommandListener? = null
    private var virginOn = false
    private var virginRecognizing = false
    private var pendingRecognize = false
    private var pendingDelete: Song? = null
    private var pendingDuplicateSongs: List<Song> = emptyList()
    private var pendingDuplicateVideos: List<Video> = emptyList()
    private var pendingPendriveFiles: List<VirginMedia.PendriveFile>? = null
    private var pendriveTotalFound = 0
    private var pendriveDuplicatesSkipped = 0
    private var virginSpeechPaused = false
    private var virginScanInFlight = false
    private val virginHandler = Handler(Looper.getMainLooper())
    private val chainHandler = Handler(Looper.getMainLooper())
    private val resumeListenerRunnable = Runnable { doResumeVirginListener() }
    private var virginLastSpeechEndMs = 0L

    fun initWelcomeVoice(savedInstanceState: Bundle?) {
        if (savedInstanceState != null || !Settings.djVoice(activity)) return
        welcomeVoice = DjVoice(activity, Settings.languageTag(Settings.language(activity))).also { vm ->
            vm.init { ready ->
                if (ready && !activity.isDestroyed) {
                    val nick = Profile.nick(activity).trim()
                    val msg = if (nick.isEmpty()) {
                        activity.getString(R.string.dj_voice_hello)
                    } else {
                        activity.getString(R.string.dj_voice_welcome, nick)
                    }
                    vm.speak(msg)
                }
            }
        }
    }

    fun onPermissionResult(requestCode: Int, granted: Boolean) {
        when (requestCode) {
            REQ_VIRGIN_MIC -> {
                if (pendingRecognize) {
                    pendingRecognize = false
                    if (granted) startVirginRecognize()
                } else if (granted) {
                    startVirgin()
                }
            }
            REQ_VIRGIN_STORAGE -> {
                val action = pendingVirginAction
                pendingVirginAction = null
                if (granted && action != null) {
                    action()
                } else if (granted) {
                    virginScanLibrary()
                } else {
                    virginSpeak(activity.getString(R.string.dj_voice_scan_denied))
                }
            }
        }
    }

    fun onDeleteRequestResult(success: Boolean) {
        val song = pendingDelete
        pendingDelete = null
        virginHandler.removeCallbacksAndMessages(null)
        if (success && song != null) {
            completeVirginDelete(song, alreadyDeleted = true)
        } else if (song != null) {
            virginSpeak(activity.getString(R.string.dj_voice_delete_cancel))
        }
    }

    fun onDuplicatesRequestResult(success: Boolean) {
        val songs = pendingDuplicateSongs
        val videos = pendingDuplicateVideos
        pendingDuplicateSongs = emptyList()
        pendingDuplicateVideos = emptyList()
        virginHandler.removeCallbacksAndMessages(null)
        if (success && (songs.isNotEmpty() || videos.isNotEmpty())) {
            completeVirginDuplicates(songs, videos, alreadyDeleted = true)
        } else if (songs.isNotEmpty() || videos.isNotEmpty()) {
            virginSpeak(activity.getString(R.string.dj_voice_dup_failed))
        }
    }

    fun onPendriveTreeResult(uri: Uri?) {
        if (uri == null) {
            if (activity.isFinishing || activity.isDestroyed) return
            pendingPendriveFiles = null
            virginScanInFlight = false
            resumeVirginSpeech()
            virginSpeak(activity.getString(R.string.dj_voice_pen_none))
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

    fun handleCommand(text: String) {
        if (text == "__unsupported__") {
            stopVirgin(silent = false)
            return
        }
        // Ignora o eco da própria voz da Virgin logo apos ela terminar de falar,
        // evitando o loop em que ela se escuta e reage.
        if (SystemClock.elapsedRealtime() - virginLastSpeechEndMs < 1500L) return
        ThreadPool.onUi { resolveVirginCommand(text) }
    }

    fun onMiniVirginLongPress() {
        if (!virginRecognizing) startVirginRecognize()
    }

    fun toggleVirgin() {
        if (virginOn) {
            stopVirgin(silent = false)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VIRGIN_MIC
            )
            return
        }
        startVirgin()
    }

    private fun startVirgin() {
        virginOn = true
        host.syncVirginIcon()
        Playback.setMicListening(true)
        virginListener?.destroy()
        virginListener = DjCommandListener(activity) { handleCommand(it) }
        virginListener?.start()
        val cur = Playback.currentSong
        val msg = if (cur != null) {
            activity.getString(R.string.dj_voice_track, cur.artist, cur.title)
        } else {
            activity.getString(R.string.dj_voice_hello)
        }
        virginSpeak(msg)
    }

    private fun stopVirgin(silent: Boolean) {
        virginOn = false
        virginHandler.removeCallbacks(resumeListenerRunnable)
        Playback.setMicListening(false)
        virginListener?.stop()
        host.syncVirginIcon()
        if (!silent) virginSpeak(activity.getString(R.string.dj_voice_goodbye))
        virginVoice?.stop()
    }

    private fun pauseVirginSpeech() {
        if (!virginOn || virginSpeechPaused) return
        virginSpeechPaused = true
        virginHandler.removeCallbacks(resumeListenerRunnable)
        virginListener?.stop()
    }

    private fun resumeVirginSpeech() {
        if (!virginSpeechPaused) return
        virginSpeechPaused = false
        if (virginOn && !activity.isFinishing && !activity.isDestroyed &&
            virginVoice?.isSpeaking != true
        ) {
            // Espera a ressonância/eco do alto-falante acabar antes de religar o microfone,
            // para a voz da própria Virgin não ser ouvida como um comando (loop).
            virginHandler.postDelayed(resumeListenerRunnable, RESUME_LISTENER_DELAY_MS)
        }
    }

    private fun doResumeVirginListener() {
        if (activity.isFinishing || activity.isDestroyed || !virginOn || virginSpeechPaused) return
        virginListener?.start()
    }

    fun announceRadioSong(song: Song) {
        if (activity.isFinishing || activity.isDestroyed || !Settings.djRadio(activity) || !Settings.djVoice(activity)) return
        DjSessionMemory.notePlayed(song.id)
        val newId = song.id
        if (radioLastId == newId) return
        radioLastId = newId
        val deduction = DjDedication.take()
        val track = activity.getString(
            R.string.dj_voice_track, song.artist, song.title
        )
        val named = if (deduction != null) {
            activity.getString(R.string.dj_voice_dedication_lead, deduction) + " " + track
        } else {
            track
        }
        val leading = DjFacts.leadIn(Settings.djIntensity(activity))
        if (!DjFacts.curiosityDue()) {
            virginSpeak(named)
            return
        }
        val fact = DjFacts.curiosityFor(song.artist ?: "")
        if (fact != null) {
            DjFacts.markCuriositySpoken()
            virginSpeak("$leading $fact $named")
            return
        }
        virginSpeak(named)
        val songId = song.id
        DjFacts.fetchRemoteCuriosity(activity, song.artist ?: "") { remote ->
            if (remote == null || Playback.currentSong?.id != songId) return@fetchRemoteCuriosity
            if (!DjFacts.curiosityDue()) return@fetchRemoteCuriosity
            DjFacts.markCuriositySpoken()
            virginSpeak("$leading $remote")
        }
    }

    private fun virginSpeak(text: String, hold: Boolean = false) {
        if (text.isBlank() || activity.isFinishing || activity.isDestroyed) return
        pauseVirginSpeech()
        val voice = virginVoice ?: DjVoice(activity, Settings.languageTag(Settings.language(activity))).also {
            virginVoice = it
        }
        voice.init { ready ->
            if (!ready || activity.isDestroyed) {
                if (!hold) resumeVirginSpeech()
                return@init
            }
            voice.speak(text) {
                virginLastSpeechEndMs = SystemClock.elapsedRealtime()
                ThreadPool.onUi {
                    if (!hold) resumeVirginSpeech()
                }
            }
        }
    }

    private fun virgSleepMix() {
        ThreadPool.post {
            val songs = Library.allSongs(activity.applicationContext)
            if (songs.isEmpty()) {
                ThreadPool.onUi { virginSpeak(activity.getString(R.string.dj_voice_only_none, "")) }
                return@post
            }
            val favIds = runCatching {
                PlaylistDb.get(activity.applicationContext).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val learn = DjLearn.learn(activity.applicationContext)
            val set = DjEngine.build(
                songs, favIds, DjEngine.Source.ALL, DjEngine.Intensity.CALM, learn,
                maxSize = 8,
                exclude = DjSessionMemory.recentIds()
            )
            ThreadPool.onUi {
                if (set.isEmpty()) {
                    virginSpeak(activity.getString(R.string.dj_voice_only_none, ""))
                    return@onUi
                }
                Telemetry.log(activity, "Virgin sleep n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(true)
                DjSessionMemory.notePlayed(set.map { it.id })
                Playback.start(set, 0)
                virginSpeak(activity.getString(R.string.dj_voice_sleep, set.size))
            }
        }
    }

    private fun virgArtistOnly(query: String?) {
        val artist = VirginMedia.findArtist(activity, query)
        if (artist == null) {
            virginSpeak(activity.getString(R.string.dj_voice_only_none, query ?: ""))
            return
        }
        ThreadPool.post {
            val songs = Library.songsByArtist(activity.applicationContext, artist)
            ThreadPool.onUi {
                if (songs.isEmpty()) {
                    virginSpeak(activity.getString(R.string.dj_voice_only_none, artist))
                    return@onUi
                }
                Telemetry.log(activity, "Virgin only artist=$artist n=${songs.size}")
                Playback.setShuffle(true)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                Playback.start(songs.shuffled(), 0)
                virginSpeak(activity.getString(R.string.dj_voice_only, artist, songs.size))
            }
        }
    }

    private fun resumeLastSession() {
        val ctx = activity.applicationContext
        val songId = Settings.resumeSongId(ctx)
        if (songId < 0L) {
            virginSpeak(activity.getString(R.string.dj_voice_resume_none))
            return
        }
        virginSpeak(activity.getString(R.string.dj_voice_resume_searching))
        ThreadPool.post {
            val song = Library.songsById(ctx, songId).firstOrNull()
            ThreadPool.onUi {
                if (song == null) {
                    virginSpeak(activity.getString(R.string.dj_voice_resume_none))
                    return@onUi
                }
                Telemetry.log(activity, "Virgin resume id=$songId pos=${Settings.resumePosition(ctx)}")
                Playback.setSleepMix(false)
                Playback.start(listOf(song), 0)
                virginSpeak(activity.getString(R.string.dj_voice_resume, song.title, song.artist))
            }
        }
    }

    private fun virgMixWithArtist(query: String?) {
        val artist = VirginMedia.findArtist(activity, query)
        if (artist == null) {
            virginSpeak(activity.getString(R.string.dj_voice_mixwith_none, query ?: ""))
            return
        }
        ThreadPool.post {
            val songs = Library.allSongs(activity.applicationContext)
            if (songs.isEmpty()) {
                ThreadPool.onUi { virginSpeak(activity.getString(R.string.dj_voice_only_none, "")) }
                return@post
            }
            val favIds = runCatching {
                PlaylistDb.get(activity.applicationContext).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val learn = DjLearn.learn(activity.applicationContext)
            val set = DjEngine.build(
                songs, favIds, DjEngine.Source.ALL, DjEngine.Intensity.BALANCED, learn,
                includeArtist = artist,
                exclude = DjSessionMemory.recentIds()
            )
            ThreadPool.onUi {
                if (set.isEmpty()) {
                    virginSpeak(activity.getString(R.string.dj_voice_only_none, ""))
                    return@onUi
                }
                Telemetry.log(activity, "Virgin mixwith artist=$artist n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                DjSessionMemory.notePlayed(set.map { it.id })
                Playback.start(set, 0)
                virginSpeak(activity.getString(R.string.dj_voice_mixwith, artist, set.size))
            }
        }
    }

    private fun virgWildMix() {
        ThreadPool.post {
            val songs = Library.allSongs(activity.applicationContext)
            if (songs.isEmpty()) {
                ThreadPool.onUi { virginSpeak(activity.getString(R.string.dj_voice_only_none, "")) }
                return@post
            }
            val favIds = runCatching {
                PlaylistDb.get(activity.applicationContext).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val learn = DjLearn.learn(activity.applicationContext)
            val set = DjEngine.build(
                songs, favIds, DjEngine.Source.ALL, DjEngine.Intensity.WILD, learn,
                maxSize = 16,
                exclude = DjSessionMemory.recentIds()
            )
            ThreadPool.onUi {
                if (set.isEmpty()) {
                    virginSpeak(activity.getString(R.string.dj_voice_only_none, ""))
                    return@onUi
                }
                Telemetry.log(activity, "Virgin wild n=${set.size}")
                Playback.setShuffle(true)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                DjSessionMemory.notePlayed(set.map { it.id })
                Playback.start(set.shuffled(), 0)
                virginSpeak(activity.getString(R.string.dj_voice_mood_wild, set.size))
            }
        }
    }

    private fun memoryLabel(key: String): String = when (key) {
        DjMemory.WHATSAPP -> activity.getString(R.string.dj_memory_whatsapp)
        DjMemory.BLUETOOTH -> activity.getString(R.string.dj_memory_bluetooth)
        else -> activity.getString(R.string.dj_memory_phone)
    }

    private fun virginSpeakYesterday() {
        ThreadPool.post {
            val ranks = DjLearn.playedOnDay(activity.applicationContext, -1, 8)
            val songs = Library.allSongs(activity.applicationContext)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (ranks.isEmpty()) {
                    virginSpeak(activity.getString(R.string.dj_voice_yesterday_none))
                    return@onUi
                }
                val byId = songs.associateBy { it.id }
                val items = ranks.mapNotNull { (id, _) ->
                    byId[id]?.let { s ->
                        activity.getString(R.string.dj_voice_yesterday_item, s.artist, s.title)
                    }
                }
                if (items.isEmpty()) {
                    virginSpeak(activity.getString(R.string.dj_voice_yesterday_none))
                } else {
                    virginSpeak(activity.getString(
                        R.string.dj_voice_yesterday_all, items.joinToString("; ")
                    ))
                }
            }
        }
    }

    private fun virgMemorySave(norm: String) {
        val saved = DjMemory.save(activity.applicationContext, norm)
        if (saved != null) {
            DjMemory.log(activity.applicationContext, norm, "memorizado ${saved.first}")
            virginSpeak(activity.getString(R.string.dj_voice_memory_saved, memoryLabel(saved.first), saved.second))
        } else {
            virginSpeak(activity.getString(R.string.dj_voice_memory_ask, memoryLabel(DjMemory.PHONE)))
        }
    }

    private fun virgMemoryRecall(norm: String) {
        val fact = DjMemory.recall(activity.applicationContext, norm)
        if (fact != null) {
            DjMemory.log(activity.applicationContext, norm, "lembra ${fact.first}")
            virginSpeak(activity.getString(R.string.dj_voice_memory_saved, memoryLabel(fact.first), fact.second))
        } else {
            virginSpeak(activity.getString(R.string.dj_voice_memory_ask, memoryLabel(DjMemory.PHONE)))
        }
    }

    private fun resolveVirginCommand(text: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        dispatchVirginCommand(text)
    }

    private fun dispatchVirginCommand(text: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val norm = DjCommander.norm(text)
        val chainParts = DjCommander.chain(norm)
        if (chainParts.size == 2) {
            dispatchVirginCommand(chainParts[0])
            chainHandler.postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) dispatchVirginCommand(chainParts[1])
            }, CHAIN_DELAY_MS)
            return
        }
        val hasWake = DjCommander.hasWake(norm)
        val action = DjCommander.action(norm)
        if (action == null) {
            if (hasWake) virginSpeak(activity.getString(R.string.dj_voice_unknown))
            return
        }
        if (action !in setOf("confirm", "cancel", "delete")) {
            pendingDelete = null
            virginHandler.removeCallbacksAndMessages(null)
        }
        when (action) {
            "mix" -> virginSpeak(activity.getString(R.string.dj_voice_mix_main))
            "memory_save" -> virgMemorySave(norm)
            "memory_recall" -> virgMemoryRecall(norm)
            "mood_wild" -> virgWildMix()
            "sleep" -> virgSleepMix()
            "repeat" -> {
                val on = !Playback.repeatOne
                Playback.setRepeatOne(on)
                virginSpeak(activity.getString(
                    if (on) R.string.dj_voice_repeat_on else R.string.dj_voice_repeat_off
                ))
            }
            "only" -> virgArtistOnly(DjCommander.onlyArtist(norm))
            "mixwith" -> virgMixWithArtist(DjCommander.mixArtist(norm))
            "skip", "next", "dislike" -> {
                val cur = Playback.currentSong
                if (action == "dislike" && cur != null) {
                    DjLearn.recordDislike(activity.applicationContext, cur.id)
                    virginSpeak(DjReactions.dislike(activity))
                } else if (action == "skip" && cur != null) {
                    DjLearn.recordSkip(activity.applicationContext, cur.id)
                    virginSpeak(DjReactions.skip(activity))
                } else {
                    virginSpeak(DjReactions.next(activity))
                }
                if (Playback.queue.isNotEmpty()) Playback.next()
            }
            "prev" -> {
                virginSpeak(activity.getString(R.string.dj_voice_prev))
                Playback.prev()
            }
            "pause" -> {
                if (Playback.isPlaying) {
                    Playback.toggle()
                    virginSpeak(activity.getString(R.string.dj_voice_pause))
                }
            }
            "play" -> {
                if (!Playback.isPlaying && Playback.queue.isNotEmpty()) {
                    Playback.toggle()
                    virginSpeak(activity.getString(R.string.dj_voice_play))
                }
            }
            "resume" -> resumeLastSession()
            "yesterday" -> virginSpeakYesterday()
            "fav" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    val db = PlaylistDb.get(activity.applicationContext)
                    val nextValue = !db.isFavorite(cur.id)
                    db.setFavorite(cur, nextValue)
                    if (nextValue) DjLearn.recordLiked(activity.applicationContext, cur.id)
                    host.syncMiniPlayer()
                    virginSpeak(if (nextValue) DjReactions.like(activity) else DjReactions.unliked(activity))
                }
            }
            "info" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    virginSpeak(activity.getString(R.string.dj_voice_track, cur.artist, cur.title))
                } else {
                    virginSpeak(activity.getString(R.string.dj_voice_unknown))
                }
            }
            "scan" -> virginScanLibrary()
            "duplicates" -> virginFindDuplicates()
            "pendrive" -> virginReadPendrive()
            "recognize" -> startVirginRecognize()
            "delete" -> {
                val cur = Playback.currentSong
                if (cur == null) {
                    pendingDelete = null
                    virginHandler.removeCallbacksAndMessages(null)
                    virginSpeak(activity.getString(R.string.dj_voice_delete_no_song))
                } else if (pendingDelete?.id == cur.id) {
                    pendingDelete = null
                    virginHandler.removeCallbacksAndMessages(null)
                    deleteVirginSong(cur)
                } else {
                    pendingDelete = cur
                    virginHandler.removeCallbacksAndMessages(null)
                    virginHandler.postDelayed({ pendingDelete = null }, 15000)
                    virginSpeak(activity.getString(R.string.dj_voice_delete_confirm, cur.title))
                }
            }
            "confirm" -> {
                val pen = pendingPendriveFiles
                if (pen != null) {
                    pendingPendriveFiles = null
                    virginHandler.removeCallbacksAndMessages(null)
                    completePendriveCopy(pen)
                } else {
                    val song = pendingDelete
                    pendingDelete = null
                    virginHandler.removeCallbacksAndMessages(null)
                    if (song != null) deleteVirginSong(song)
                }
            }
            "cancel" -> {
                if (pendingPendriveFiles != null) {
                    pendingPendriveFiles = null
                    virginHandler.removeCallbacksAndMessages(null)
                    virginSpeak(activity.getString(R.string.dj_voice_pen_cancel))
                } else {
                    pendingDelete = null
                    virginHandler.removeCallbacksAndMessages(null)
                    virginSpeak(activity.getString(R.string.dj_voice_delete_cancel))
                }
            }
            "thanks" -> virginSpeak(activity.getString(R.string.dj_voice_thanks))
            "hello" -> virginSpeak(activity.getString(R.string.dj_voice_hello))
            "count" -> virgVoiceLibraryCount()
            "daily_set" -> virgStartDailySet()
            "dedicate" -> {
                val name = DjCommander.dedicatee(norm)
                if (name.isNullOrBlank()) {
                    virginSpeak(activity.getString(R.string.dj_voice_dedicate_ask))
                } else {
                    DjDedication.pending = name
                    virginSpeak(activity.getString(R.string.dj_voice_dedicate_ok, name))
                }
            }
            "visualizer" -> toggleVisualizer()
            "skin" -> cycleSkin()
            "karaoke" -> toggleKaraoke()
            "suggest" -> virgSuggestSong()
            "identity" -> virginSpeak(DjIdentity.introSpeech())
            "stems" -> virginSpeak(activity.getString(R.string.dj_voice_stems_soon))
        }
    }

    private fun virgVoiceLibraryCount() {
        ThreadPool.post {
            val n = Library.allSongs(activity.applicationContext).size
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (n == 0) {
                    virginSpeak(activity.getString(R.string.dj_voice_count_none))
                } else {
                    virginSpeak(activity.getString(R.string.dj_voice_count, n))
                }
            }
        }
    }

    private fun virgSuggestSong() {
        val ctx = activity.applicationContext
        ThreadPool.post {
            val songs = Library.allSongs(ctx)
            if (songs.isEmpty()) {
                ThreadPool.onUi {
                    if (activity.isFinishing || activity.isDestroyed) return@onUi
                    virginSpeak(activity.getString(R.string.dj_empty))
                }
                return@post
            }
            val suggestion = if (DjSuggest.isReady(ctx)) {
                DjSuggest.suggest(ctx, songs) ?: DjSuggest.offline(ctx, songs)
            } else {
                DjSuggest.offline(ctx, songs)
            }
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                virginSpeak(DjSuggest.toSpeech(suggestion))
            }
        }
    }

    private fun virgStartDailySet() {
        if (!Permissions.hasAccess(activity)) {
            Toast.makeText(activity, R.string.dj_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        val ctx = activity.applicationContext
        ThreadPool.post {
            val songs = Library.allSongs(ctx)
            if (songs.isEmpty()) {
                ThreadPool.onUi {
                    if (activity.isFinishing || activity.isDestroyed) return@onUi
                    virginSpeak(activity.getString(R.string.dj_empty))
                }
                return@post
            }
            val favs = runCatching {
                PlaylistDb.get(ctx).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val seed = java.time.LocalDate.now().toEpochDay()
            val rand = java.util.Random(seed)
            val sorted = songs.sortedBy { it.id }
            val fanned = sorted.filter { it.id in favs }
            val base = if (fanned.isNotEmpty()) {
                (fanned.shuffled(rand) + sorted.shuffled(rand)).distinctBy { it.id }
            } else {
                sorted.shuffled(rand)
            }
            val set = base.take(16)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                Telemetry.log(ctx, "Virgin set do dia n=${set.size} seed=$seed")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.start(set, 0)
                virginSpeak(activity.getString(R.string.dj_voice_daily_set, set.size))
            }
        }
    }

    private fun toggleVisualizer() {
        val on = !Settings.visualizerOn(activity)
        Settings.setVisualizerOn(activity, on)
        host.refreshPlayerVisuals()
        virginSpeak(activity.getString(
            if (on) R.string.dj_voice_visualizer_on else R.string.dj_voice_visualizer_off
        ))
    }

    private fun cycleSkin() {
        val current = Settings.skin(activity)
        val next = Settings.SKIN_ORDER[(Settings.SKIN_ORDER.indexOf(current).coerceAtLeast(0) + 1) % Settings.SKIN_ORDER.size]
        Settings.setSkin(activity, next)
        host.refreshPlayerVisuals()
        virginSpeak(skinLabel(next))
    }

    private fun toggleKaraoke() {
        val on = !Settings.karaokeOn(activity)
        Settings.setKaraokeOn(activity, on)
        Playback.refreshFx()
        virginSpeak(activity.getString(
            if (on) R.string.dj_voice_karaoke_on else R.string.dj_voice_karaoke_off
        ))
    }

    private fun skinLabel(skin: String): String = activity.getString(when (skin) {
        Settings.SKIN_NEON -> R.string.skin_neon
        Settings.SKIN_AURORA -> R.string.skin_aurora
        Settings.SKIN_PARTICLES -> R.string.skin_particles
        Settings.SKIN_NEBULA -> R.string.skin_nebula
        else -> R.string.skin_off
    })

    private fun virginScanLibrary() {
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
                    REQ_VIRGIN_STORAGE
                )
                return
            }
        } else if (!Permissions.hasAccess(activity)) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_VIRGIN_STORAGE
            )
            return
        }
        performVirginScan()
    }

    private fun performVirginScan() {
        if (virginScanInFlight) return
        virginScanInFlight = true
        virginSpeak(activity.getString(R.string.dj_voice_scan), hold = true)
        GalleryScanner.scan(activity.applicationContext) { songs, videos ->
            virginScanInFlight = false
            if (activity.isFinishing || activity.isDestroyed) {
                resumeVirginSpeech()
                return@scan
            }
            virginSpeak(activity.getString(R.string.dj_voice_scan_result, songs, videos))
            Toast.makeText(
                activity, activity.getString(R.string.dj_voice_scan_result, songs, videos),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun virginFindDuplicates() {
        if (virginScanInFlight) return
        if (!hasMediaPermission()) {
            pendingVirginAction = { performVirginDuplicates() }
            ActivityCompat.requestPermissions(
                activity, mediaPermissionNeeded(), REQ_VIRGIN_STORAGE
            )
            return
        }
        performVirginDuplicates()
    }

    private fun performVirginDuplicates() {
        if (virginScanInFlight) return
        virginScanInFlight = true
        virginSpeak(activity.getString(R.string.dj_voice_dup_search), hold = true)
        ThreadPool.post {
            val dup = VirginMedia.findDuplicates(activity.applicationContext)
            ThreadPool.onUi {
                virginScanInFlight = false
                if (activity.isFinishing || activity.isDestroyed) {
                    resumeVirginSpeech()
                    return@onUi
                }
                if (dup.copies == 0) {
                    virginSpeak(activity.getString(R.string.dj_voice_dup_none))
                    return@onUi
                }
                pendingDuplicateSongs = dup.songsCopies
                pendingDuplicateVideos = dup.videoCopiesList
                virginSpeak(activity.getString(R.string.dj_voice_dup_found, dup.pairs, dup.copies))
                if (Build.VERSION.SDK_INT >= 30) {
                    val uris = buildList {
                        dup.songsCopies.forEach {
                            add(android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id))
                        }
                        dup.videoCopiesList.forEach { add(com.pulsa.player.data.VideoLibrary.contentUri(it.id)) }
                    }
                    val sender = runCatching {
                        MediaStore.createDeleteRequest(activity.contentResolver, uris)
                    }.getOrNull()
                    if (sender == null) {
                        pendingDuplicateSongs = emptyList()
                        pendingDuplicateVideos = emptyList()
                        virginSpeak(activity.getString(R.string.dj_voice_dup_failed))
                        return@onUi
                    }
                    launcher.duplicates.launch(
                        IntentSenderRequest.Builder(sender).build()
                    )
                } else {
                    completeVirginDuplicates(dup.songsCopies, dup.videoCopiesList, alreadyDeleted = false)
                }
            }
        }
    }

    private fun completeVirginDuplicates(
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
                    virginSpeak(activity.getString(R.string.dj_voice_dup_done, songsCopies.size + videoCopiesList.size))
                } else {
                    virginSpeak(activity.getString(R.string.dj_voice_dup_failed))
                }
            }
        }
    }

    private fun virginReadPendrive() {
        if (virginScanInFlight) return
        if (!hasMediaPermission()) {
            pendingVirginAction = { performVirginPendrive() }
            ActivityCompat.requestPermissions(
                activity, mediaPermissionNeeded(), REQ_VIRGIN_STORAGE
            )
            return
        }
        performVirginPendrive()
    }

    private fun performVirginPendrive() {
        if (virginScanInFlight) return
        val savedTree = Settings.pendriveTreeUri(activity)
        if (savedTree.isNullOrBlank()) {
            virginScanInFlight = true
            virginSpeak(activity.getString(R.string.dj_voice_pen_search), hold = true)
            launcher.tree.launch(null)
            return
        }
        scanPendriveTree(runCatching { Uri.parse(savedTree) }.getOrNull() ?: return)
    }

    private fun scanPendriveTree(rootUri: Uri) {
        if (virginScanInFlight) return
        virginScanInFlight = true
        virginSpeak(activity.getString(R.string.dj_voice_pen_search), hold = true)
        ThreadPool.post {
            val scan = VirginMedia.scanPendrive(activity.applicationContext, rootUri)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) {
                    virginScanInFlight = false
                    resumeVirginSpeech()
                    return@onUi
                }
                when {
                    scan.files.isEmpty() -> {
                        virginScanInFlight = false
                        virginSpeak(activity.getString(R.string.dj_voice_pen_none))
                    }
                    scan.newFiles.isEmpty() -> {
                        virginScanInFlight = false
                        virginSpeak(activity.getString(R.string.dj_voice_pen_all_dups, scan.files.size))
                    }
                    else -> {
                        pendriveTotalFound = scan.files.size
                        pendriveDuplicatesSkipped = scan.skipped
                        pendingPendriveFiles = scan.newFiles
                        virginHandler.removeCallbacksAndMessages(null)
                        virginHandler.postDelayed({
                            pendingPendriveFiles = null
                        }, 60000)
                        virginSpeak(penConfirmMessage(scan.newFiles.size))
                    }
                }
            }
        }
    }

    private fun penConfirmMessage(count: Int): String =
        activity.getString(R.string.dj_voice_pen_confirm, count)

    private fun completePendriveCopy(files: List<VirginMedia.PendriveFile>) {
        ThreadPool.post {
            var copied = 0
            for (f in files) {
                if (VirginMedia.copyToMusic(activity.applicationContext, f)) copied++
            }
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (copied == 0) {
                    virginSpeak(activity.getString(R.string.dj_voice_pen_copy_failed))
                    return@onUi
                }
                if (pendriveDuplicatesSkipped > 0) {
                    virginSpeak(activity.getString(
                        R.string.dj_voice_pen_copy_done_dups,
                        pendriveTotalFound, pendriveDuplicatesSkipped, copied
                    ))
                } else {
                    virginSpeak(activity.getString(R.string.dj_voice_pen_copy_done, copied))
                }
            }
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

    private var pendingVirginAction: (() -> Unit)? = null

    private fun deleteVirginSong(song: Song) {
        if (Build.VERSION.SDK_INT >= 30) {
            val sender = runCatching {
                MediaStore.createDeleteRequest(
                    activity.contentResolver,
                    listOf(android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id))
                )
            }.getOrNull()
            if (sender == null) {
                virginSpeak(activity.getString(R.string.dj_voice_delete_failed))
                return
            }
            pendingDelete = song
            launcher.delete.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            completeVirginDelete(song, alreadyDeleted = false)
        }
    }

    private fun completeVirginDelete(song: Song, alreadyDeleted: Boolean) {
        ThreadPool.post {
            val deleted = if (alreadyDeleted) true else VirginMedia.deleteSong(activity.applicationContext, song)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (deleted) {
                    VirginMedia.removeFromPlaylists(activity.applicationContext, song)
                    if (Playback.currentSong?.id == song.id) Playback.next()
                    virginSpeak(activity.getString(R.string.dj_voice_delete_done, song.title))
                } else {
                    virginSpeak(activity.getString(R.string.dj_voice_delete_failed))
                }
            }
        }
    }

    private fun startVirginRecognize() {
        if (virginRecognizing) return
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingRecognize = true
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VIRGIN_MIC
            )
            return
        }
        if (virginOn) stopVirgin(silent = true)
        virginRecognizing = true
        host.syncVirginIcon()
        virginSpeak(activity.getString(R.string.dj_voice_recognize))
        DjRecognizer.recognize(activity.applicationContext) { result, error ->
            if (activity.isFinishing || activity.isDestroyed) return@recognize
            virginRecognizing = false
            host.syncVirginIcon()
            when {
                error == "no_token" -> {
                    virginSpeak(activity.getString(R.string.dj_voice_recognize_configured))
                    Toast.makeText(activity, R.string.dj_voice_recognize_configured, Toast.LENGTH_LONG).show()
                }
                error != null -> virginSpeak(activity.getString(R.string.dj_voice_recognize_error))
                result == null -> virginSpeak(activity.getString(R.string.dj_voice_recognize_none))
                else -> {
                    virginSpeak(activity.getString(
                        R.string.dj_voice_recognize_recognized, result.title, result.artist
                    ))
                    val cur = Playback.currentSong
                    if (cur != null) {
                        MusicEditor.renameDetected(
                            activity.applicationContext, cur,
                            result.title, result.artist, result.albumName
                        ) { ok ->
                            if (ok && !activity.isDestroyed) {
                                virginSpeak(activity.getString(
                                    R.string.dj_voice_recognize_renamed, result.title, result.artist
                                ))
                            }
                        }
                    }
                }
            }
        }
    }

    fun stopForBackground() {
        if (virginOn) {
            virginOn = false
            host.syncVirginIcon()
        }
        Playback.setMicListening(false)
        virginListener?.stop()
        virginVoice?.stop()
    }

    fun destroy() {
        chainHandler.removeCallbacksAndMessages(null)
        virginListener?.destroy()
        virginListener = null
        welcomeVoice?.shutdown()
        welcomeVoice = null
        virginVoice?.shutdown()
        virginVoice = null
    }
}