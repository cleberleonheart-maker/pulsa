package com.pulsa.player.dj

import android.Manifest
import android.content.ContentUris
import android.content.Context
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
import com.pulsa.player.VideoPlayerActivity
import com.pulsa.player.core.Helper
import com.pulsa.player.core.Permissions
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.data.StreamHistory
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.media.GalleryScanner
import com.pulsa.player.media.MusicEditor
import com.pulsa.player.model.Song
import com.pulsa.player.model.Video
import com.pulsa.player.playback.Playback
import com.pulsa.player.playback.QueueKey
import com.pulsa.player.sync.Telemetry
import com.pulsa.player.core.CrashLogger

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
        private const val CHAIN_DELAY_MS = 1800L
        private const val MONTH_MS = 30L * 24 * 60 * 60 * 1000

        /** F2 · Vídeo por voz: o quanto "volta o filme" recua quando não há 30s na frase. */
        private const val VIDEO_BACK_MS = 30_000L
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
    private var activeLang = ""
    private val resumeListenerRunnable = Runnable { resumeListener() }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val chainHandler = Handler(Looper.getMainLooper())

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
        val lastPlayedBefore = DjLearn.lastPlayedMap(app)
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
                    announce = activity.getString(R.string.dj_voice_track, song?.title, song?.artist)
                }
                suppressNextLearnSkip = false
                if (newId >= 0L) {
                    // O `play_log` é gravado pelo motor ([PlaybackService.notePlay]) para
                    // toda troca de faixa, e não só quando a cabine está aberta. Registrar
                    // aqui também contaria o mesmo toque duas vezes na cabine, e as
                    // tendências apareceriam com o dobro das reproduções.
                    DjSessionMemory.notePlayed(newId)
                }
                learnId = newId
                learnStartedAt = SystemClock.elapsedRealtime()
                lastCompleted = false
                if (Settings.djRadio(app)) {
                    val body = curiosityBody(song)
                    if (body != null) {
                        announce = "$body " +
                            activity.getString(R.string.dj_voice_track, song?.title, song?.artist)
                    }
                }
            }
        }
        if (!djActive && announce == null && Settings.djRadio(activity)) {
            val base = activity.getString(R.string.dj_voice_track, song?.title, song?.artist)
            announce = curiosityBody(song)?.let { "$it $base" } ?: base
        }
        if (song != null) {
            val note = lastHeardNote(app, lastPlayedBefore, song.id)
            if (note != null && announce != null) {
                announce = "$note $announce"
            }
        }
        val dedication = if (announce != null) DjDedication.take() else null
        if (announce != null) {
            if (dedication != null) {
                announce = activity.getString(R.string.dj_voice_dedication_lead, dedication) + " " + announce
            }
            speak(announce)
        }
    }

    /** Rádio com memória: se a faixa está há dias sem ser ouvida, a Virgin avisa. */
    private fun lastHeardNote(app: Context, lastPlayed: Map<Long, Long>, songId: Long): String? {
        val last = lastPlayed[songId]
            ?: return activity.getString(R.string.dj_voice_mem_first)
        val gapDays = (System.currentTimeMillis() / 1000L - last) / 86400L
        if (gapDays < 5) return null
        return when {
            gapDays < 14 -> activity.getString(R.string.dj_voice_mem_days, gapDays)
            gapDays < 60 -> activity.getString(R.string.dj_voice_mem_weeks, gapDays / 7)
            else -> activity.getString(R.string.dj_voice_mem_months, gapDays / 30)
        }
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
                    learn,
                    exclude = DjSessionMemory.recentIds()
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
                Playback.setSleepMix(false)
                djActive = true
                DjSessionMemory.notePlayed(set.map { it.id })
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

    fun startMonthMix() {
        if (!Permissions.hasAccess(activity)) {
            Toast.makeText(activity, R.string.dj_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        val activeIntensity = intensity
        host.setMixBusy(true)
        ThreadPool.post {
            val ctx = activity.applicationContext
            val songs = Library.allSongs(ctx)
            val favIds = runCatching {
                PlaylistDb.get(ctx).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val monthFavs = runCatching {
                PlaylistDb.get(ctx).favoritesLikedSince(System.currentTimeMillis() - MONTH_MS)
            }.getOrDefault(emptyList())
            val pool = if (monthFavs.size >= 3) monthFavs else songs.filter { it.id in favIds }
            val learn = DjLearn.learn(ctx)
            val set = DjEngine.build(
                pool, favIds, DjEngine.Source.FAVORITES,
                when (activeIntensity) {
                    Settings.DJ_CALM -> DjEngine.Intensity.CALM
                    Settings.DJ_WILD -> DjEngine.Intensity.WILD
                    else -> DjEngine.Intensity.BALANCED
                },
                learn,
                exclude = DjSessionMemory.recentIds()
            )
            ThreadPool.onUi {
                host.setMixBusy(false)
                if (set.isEmpty()) {
                    Toast.makeText(activity, R.string.dj_empty, Toast.LENGTH_LONG).show()
                    return@onUi
                }
                Telemetry.log(activity, "DJ Virgin mix month-favs n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                djActive = true
                DjSessionMemory.notePlayed(set.map { it.id })
                learnId = -1L
                lastCompleted = false
                suppressNextLearnSkip = false
                Playback.start(set, 0)
                host.resetCrossfader()
                host.render()
                speak(
                    if (pool === monthFavs) say(R.string.dj_voice_month_favs, set.size)
                    else say(R.string.dj_voice_month_favs_fallback, set.size)
                )
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
                maxSize = 8,
                exclude = DjSessionMemory.recentIds()
            )
            ThreadPool.onUi {
                if (set.isEmpty()) {
                    Toast.makeText(activity, R.string.dj_empty, Toast.LENGTH_LONG).show()
                    return@onUi
                }
                Telemetry.log(activity, "DJ Virgin sleep n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(true)
                djActive = true
                DjSessionMemory.notePlayed(set.map { it.id })
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
                maxSize = 16,
                exclude = DjSessionMemory.recentIds()
            )
            ThreadPool.onUi {
                if (set.isEmpty()) {
                    Toast.makeText(activity, R.string.dj_empty, Toast.LENGTH_LONG).show()
                    return@onUi
                }
                Telemetry.log(activity, "DJ Virgin wild n=${set.size}")
                Playback.setShuffle(true)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                djActive = true
                DjSessionMemory.notePlayed(set.map { it.id })
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
                Playback.setSleepMix(false)
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
                includeArtist = artist,
                exclude = DjSessionMemory.recentIds()
            )
            ThreadPool.onUi {
                if (set.isEmpty()) {
                    Toast.makeText(activity, R.string.dj_empty, Toast.LENGTH_LONG).show()
                    return@onUi
                }
                Telemetry.log(activity, "DJ Virgin mixwith artist=$artist n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                djActive = true
                DjSessionMemory.notePlayed(set.map { it.id })
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

    /**
     * F2 · Vídeo por voz. Mesma regra da Virgin da tela principal: só vale com o vídeo no
     * motor, porque a posição de vídeo não é salva (o `PlaybackService` pula vídeo no save e
     * no restore — id de vídeo e id de música dividem o mesmo espaço do MediaStore).
     */
    private fun videoPlaying(): Song? =
        Playback.currentSong?.takeIf { it.isVideo || it.isStream }

    /** Retoma o vídeo sem pedir tela: `showPlaying` do fundo é bloqueado pelo Android 10+. */
    private fun virgVideoPlay() {
        val song = videoPlaying()
        if (song == null) {
            speak(say(R.string.dj_voice_video_none))
            return
        }
        Playback.play()
        speak(say(R.string.dj_voice_video_play, song.title))
    }

    private fun virgVideoOpen() {
        val song = videoPlaying()
        if (song == null) {
            speak(say(R.string.dj_voice_video_none))
            return
        }
        speak(say(R.string.dj_voice_video_open, song.title))
        // `showPlaying` já é no-op se a tela está no ar, e abre em modo anexo: não troca a
        // fila nem mexe em shuffle/repeat.
        VideoPlayerActivity.showPlaying(activity)
    }

    private fun virgVideoBack() {
        if (videoPlaying() == null) {
            // Sem vídeo, a palavra de direção sozinha é a faixa anterior de sempre: ver
            // `virgVideoBack` em MainVirgin, que tem o mesmo porquê.
            speak(say(R.string.dj_voice_prev))
            Playback.prev()
            return
        }
        val alvo = (Playback.position - VIDEO_BACK_MS).coerceAtLeast(0L)
        Playback.seekTo(alvo)
        speak(say(R.string.dj_voice_video_back, Helper.formatDuration(alvo)))
    }

    /**
     * F2 · "Toca o filme X" — abre o vídeo pelo nome (local primeiro, depois histórico de
     * streams). Mesma regra de [MainVirgin.virgVideoByName]: a consulta ao MediaStore/disco
     * sai da main thread.
     */
    private fun virgVideoByName(query: String?) {
        val q = query.orEmpty()
        if (q.isBlank()) {
            speak(say(R.string.dj_voice_video_none))
            return
        }
        val ctx = activity.applicationContext
        ThreadPool.post {
            val video = VirginMedia.findVideo(ctx, q)
            val stream = if (video == null) StreamHistory.find(ctx, q) else null
            val vistos = if (video == null && stream == null) {
                runCatching { VideoLibrary.all(ctx).size }.getOrDefault(-1)
            } else 0
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                when {
                    video != null -> {
                        Telemetry.log(activity, "DJ video_by_name local=${video.title}")
                        speak(activity.getString(R.string.dj_voice_video_open, video.title))
                        VideoPlayerActivity.start(activity, listOf(video), 0)
                    }
                    stream != null -> {
                        Telemetry.log(activity, "DJ video_by_name stream=${stream.title}")
                        speak(activity.getString(R.string.dj_voice_video_open, stream.title))
                        VideoPlayerActivity.startStream(
                            activity, stream.url, stream.title,
                            uuid = stream.uuid, pageUrl = stream.pageUrl,
                            thumbnail = stream.thumbnail
                        )
                    }
                    else -> {
                        Telemetry.log(activity, "DJ video_by_name miss q='$q' videos=$vistos")
                        speak(say(R.string.dj_voice_video_not_found))
                    }
                }
            }
        }
    }

    /**
     * Fila universal — reordenação por voz ("virgi, joga o vídeo pro fim"). Mesma regra do
     * [MainVirgin.virgQueueMove]: o motor escolhe o item, aqui só decide o que falar.
     */
    private fun voiceQueueMove(query: DjCommander.QueueMove?) {
        val q = query ?: return
        if (Playback.queue.isEmpty()) {
            speak(say(R.string.dj_voice_queue_move_none))
            return
        }
        val moved = Playback.moveToEnd(q.video, q.episode)
        if (moved == null) {
            speak(say(R.string.dj_voice_queue_move_none))
            return
        }
        Telemetry.log(activity, "DJ queue_move type=video:${q.video} episode:${q.episode} -> ${moved.title}")
        speak(say(R.string.dj_voice_queue_move_done, moved.title))
    }

    private fun resumeLastSession() {
        val ctx = activity.applicationContext
        val songId = Settings.resumeSongId(ctx)
        if (songId < 0L) {
            speak(activity.getString(R.string.dj_voice_resume_none))
            return
        }
        speak(activity.getString(R.string.dj_voice_resume_searching))
        ThreadPool.post {
            val song = Library.songsById(ctx, songId).firstOrNull()
            ThreadPool.onUi {
                if (song == null) {
                    speak(activity.getString(R.string.dj_voice_resume_none))
                    return@onUi
                }
                Telemetry.log(activity, "DJ Virgin resume id=$songId pos=${Settings.resumePosition(ctx)}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                djActive = true
                learnId = -1L
                lastCompleted = false
                suppressNextLearnSkip = false
                Playback.start(listOf(song), 0)
                host.resetCrossfader()
                host.render()
                speak(activity.getString(R.string.dj_voice_resume, song.title, song.artist))
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
        chainHandler.removeCallbacksAndMessages(null)
        djVoice?.shutdown()
        djVoice = null
    }

    private fun speak(text: String, holdEnabled: Boolean = false, onDone: (() -> Unit)? = null) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (Settings.djVoice(activity)) {
            pauseListener()
            djVoice?.speak(text, activeLang.takeIf { it in arrayOf("en", "es") }) {
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

    /** String de voz no idioma da pergunta; cai para o idioma do app se faltar. */
    private fun say(resId: Int, vararg args: Any?): String {
        if (activeLang !in arrayOf("en", "es")) return activity.getString(resId, *args)
        val ctx = reactCtx()
        return runCatching { ctx.getString(resId, *args) }.getOrElse { activity.getString(resId, *args) }
    }

    private fun reactCtx(): android.content.Context {
        if (activeLang !in arrayOf("en", "es")) return activity
        return runCatching {
            val conf = android.content.res.Configuration(activity.resources.configuration)
            conf.setLocale(java.util.Locale.forLanguageTag(activeLang))
            activity.createConfigurationContext(conf)
        }.getOrNull() ?: activity
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
        commandListener = DjCommandListener(activity, onResult = { handleCommand(it) })
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
        dispatchVoiceCommand(text)
    }

    private fun dispatchVoiceCommand(text: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        activeLang = DjCommander.languageOf(text)
        val norm = DjCommander.norm(text)
        val chainParts = DjCommander.chain(norm)
        if (chainParts.size == 2) {
            dispatchVoiceCommand(chainParts[0])
            chainHandler.postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) dispatchVoiceCommand(chainParts[1])
            }, CHAIN_DELAY_MS)
            return
        }
        val hasWake = DjCommander.hasWake(norm)
        val action = DjCommander.action(norm)
        if (action == null) {
            if (hasWake) speak(say(R.string.dj_voice_unknown))
            return
        }
        if (action !in setOf("confirm", "cancel", "delete")) {
            pendingDelete = null
            uiHandler.removeCallbacksAndMessages(null)
        }
        when (action) {
            "mix" -> {
                speak(say(R.string.dj_voice_mix))
                startMix()
            }
            "month_favs" -> startMonthMix()
            "memory_save" -> voiceMemorySave(norm)
            "memory_recall" -> voiceMemoryRecall(norm)
            "mood_wild" -> startWildMix()
            "sleep" -> startSleepMix()
            "repeat" -> {
                val on = !Playback.repeatOne
                Playback.setRepeatOne(on)
                speak(say(
                    if (on) R.string.dj_voice_repeat_on else R.string.dj_voice_repeat_off
                ))
            }
            "only" -> {
                startArtistOnly(DjCommander.onlyArtist(norm))
            }
            "mixwith" -> {
                startMixWithArtist(DjCommander.mixArtist(norm))
            }
            "video_by_name" -> virgVideoByName(DjCommander.videoQuery(norm))
            "video_play" -> virgVideoPlay()
            "video_open" -> virgVideoOpen()
            "video_back" -> virgVideoBack()
            "video_pause" -> {
                if (videoPlaying() == null) {
                    speak(say(R.string.dj_voice_video_none))
                } else if (Playback.isPlaying) {
                    djVoice?.stop()
                    Playback.pause()
                    speak(say(R.string.dj_voice_pause))
                }
            }
            "queue_move" -> voiceQueueMove(DjCommander.moveToEndQuery(norm))
            "skip" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    DjLearn.recordSkip(activity.applicationContext, cur.id)
                    suppressNextLearnSkip = true
                }
                speak(DjReactions.skip(reactCtx()))
                if (Playback.queue.isNotEmpty()) Playback.next() else startMix()
            }
            "dislike" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    DjLearn.recordDislike(activity.applicationContext, cur.id)
                    suppressNextLearnSkip = true
                }
                speak(DjReactions.dislike(reactCtx()))
                if (Playback.queue.isNotEmpty()) Playback.next() else startMix()
            }
            "next" -> {
                speak(DjReactions.next(reactCtx()))
                if (Playback.queue.isNotEmpty()) Playback.next() else startMix()
            }
            "prev" -> {
                speak(say(R.string.dj_voice_prev))
                Playback.prev()
            }
            "pause" -> {
                djVoice?.stop()
                if (Playback.isPlaying) {
                    Playback.toggle()
                    speak(say(R.string.dj_voice_pause))
                }
            }
            "play" -> {
                djVoice?.stop()
                if (!Playback.isPlaying && Playback.queue.isNotEmpty()) {
                    Playback.toggle()
                    speak(say(R.string.dj_voice_play))
                }
            }
            "resume" -> resumeLastSession()
            "yesterday" -> speakYesterday()
            "fav" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    // `handleCommand` vem de `SpeechRecognizer.onResults`, que o Android
                    // entrega na main thread. Ler e gravar favorito ali era query de Room na
                    // main — falar "favoritar" derrubava o app. O inverso é calculado no pool.
                    val appCtx = activity.applicationContext
                    ThreadPool.post {
                        val nextValue = runCatching {
                            val db = PlaylistDb.get(appCtx)
                            val next = !db.isFavorite(cur.id)
                            db.setFavorite(cur, next)
                            next
                        }.getOrElse {
                            CrashLogger.writeLog(appCtx, "DJ: favoritar por voz falhou id=${cur.id} -> $it")
                            return@post
                        }
                        if (nextValue) DjLearn.recordLiked(appCtx, cur.id)
                        ThreadPool.onUi {
                            Toast.makeText(activity, R.string.dj_voice_fav, Toast.LENGTH_SHORT).show()
                            speak(
                                if (nextValue) DjReactions.like(reactCtx())
                                else DjReactions.unliked(reactCtx())
                            )
                        }
                    }
                }
            }
            "info" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    speak(say(R.string.dj_voice_track, cur.title, cur.artist))
                } else {
                    speak(say(R.string.dj_voice_unknown))
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
                    speak(say(R.string.dj_voice_delete_no_song))
                } else if (pendingDelete?.id == cur.id) {
                    pendingDelete = null
                    uiHandler.removeCallbacksAndMessages(null)
                    doDelete(cur)
                } else {
                    pendingDelete = cur
                    uiHandler.removeCallbacksAndMessages(null)
                    uiHandler.postDelayed({ pendingDelete = null }, 15000)
                    speak(say(R.string.dj_voice_delete_confirm, cur.title))
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
                    speak(say(R.string.dj_voice_pen_cancel))
                } else {
                    pendingDelete = null
                    uiHandler.removeCallbacksAndMessages(null)
                    speak(say(R.string.dj_voice_delete_cancel))
                }
            }
            "suggest" -> suggestSong()
            "identity" -> {
                speak(DjIdentity.introSpeech())
            }
            "count" -> voiceLibraryCount()
            "daily_set" -> startDailySet()
            "dedicate" -> {
                val name = DjCommander.dedicatee(norm)
                if (name.isNullOrBlank()) {
                    speak(say(R.string.dj_voice_dedicate_ask))
                } else {
                    DjDedication.pending = name
                    speak(say(R.string.dj_voice_dedicate_ok, name))
                }
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

    private fun voiceLibraryCount() {
        ThreadPool.post {
            val n = Library.allSongs(activity.applicationContext).size
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (n == 0) {
                    speak(activity.getString(R.string.dj_voice_count_none))
                } else {
                    speak(activity.getString(R.string.dj_voice_count, n))
                }
            }
        }
    }

    private fun startDailySet() {
        val act = activity
        if (!Permissions.hasAccess(act)) {
            Toast.makeText(act, R.string.dj_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        val ctx = act.applicationContext
        ThreadPool.post {
            val songs = Library.allSongs(ctx)
            if (songs.isEmpty()) {
                ThreadPool.onUi {
                    if (act.isFinishing || act.isDestroyed) return@onUi
                    speak(activity.getString(R.string.dj_empty))
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
                if (act.isFinishing || act.isDestroyed) return@onUi
                Telemetry.log(ctx, "Virgin set do dia n=${set.size} seed=$seed")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.start(set, 0)
                speak(activity.getString(R.string.dj_voice_daily_set, set.size))
            }
        }
    }

    private fun speakYesterday() {
        ThreadPool.post {
            val ranks = DjLearn.playedOnDay(activity.applicationContext, -1, 8)
            val songs = Library.allSongs(activity.applicationContext)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (ranks.isEmpty()) {
                    speak(activity.getString(R.string.dj_voice_yesterday_none))
                    return@onUi
                }
                val byId = songs.associateBy { it.id }
                val items = ranks.mapNotNull { (id, _) ->
                    byId[id]?.let { s ->
                        activity.getString(R.string.dj_voice_yesterday_item, s.artist, s.title)
                    }
                }
                if (items.isEmpty()) {
                    speak(activity.getString(R.string.dj_voice_yesterday_none))
                } else {
                    speak(activity.getString(
                        R.string.dj_voice_yesterday_all, items.joinToString("; ")
                    ))
                }
            }
        }
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
            // `removeFromPlaylists` é query de Room: fica no `post`, não no `onUi`. Dentro do
            // `onUi` e embrulhado em `runCatching`, o Room lançava e o `runCatching` engolia —
            // o duplicado sumia do aparelho mas continuava listado nas playlists.
            if (deleted) {
                runCatching {
                    songsCopies.forEach { VirginMedia.removeFromPlaylists(activity.applicationContext, it) }
                }.onFailure {
                    CrashLogger.writeLog(activity.applicationContext, "DJ: falha ao limpar playlists dos duplicados -> $it")
                }
            }
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (deleted) {
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
        val key = QueueKey.encode(song)
        ThreadPool.post {
            val deleted = if (alreadyDeleted) {
                true
            } else {
                VirginMedia.deleteSong(activity.applicationContext, song)
            }
            // Mesma razão do outro caminho: query de Room não roda dentro do `onUi`. Aqui sem
            // `runCatching`, então apagar por voz derrubava o app em vez de só não limpar a
            // playlist.
            if (deleted) {
                runCatching { VirginMedia.removeFromPlaylists(activity.applicationContext, song) }
                    .onFailure {
                        CrashLogger.writeLog(activity.applicationContext, "DJ: falha ao limpar playlist da faixa apagada -> $it")
                    }
            }
            ThreadPool.onUi {
                if (deleted) {
                    if (QueueKey.sameType(Playback.currentKey, key)) Playback.next()
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