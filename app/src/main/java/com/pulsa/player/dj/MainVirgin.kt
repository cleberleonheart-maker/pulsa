package com.pulsa.player.dj

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
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
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pulsa.player.MainActivity
import com.pulsa.player.R
import com.pulsa.player.VideoPlayerActivity
import com.pulsa.player.audio.Ambient

import com.pulsa.player.audio.SleepTimer


import com.pulsa.player.core.Helper
import com.pulsa.player.core.Permissions
import com.pulsa.player.core.Profile
import com.pulsa.player.core.ResumeInfo
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.Library
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.data.StreamHistory

import com.pulsa.player.media.DownloadService
import com.pulsa.player.media.DownloadStore


import com.pulsa.player.media.GalleryScanner
import com.pulsa.player.media.MusicEditor
import com.pulsa.player.media.OfflineCleanupRunner
import com.pulsa.player.model.Song
import com.pulsa.player.model.Video
import com.pulsa.player.podcast.Chapter
import com.pulsa.player.podcast.Chapters
import com.pulsa.player.podcast.Episode
import com.pulsa.player.podcast.PodcastDb
import com.pulsa.player.podcast.PodcastFiles
import com.pulsa.player.playback.Playback
import com.pulsa.player.playback.QueueKey
import com.pulsa.player.sync.Telemetry

import com.pulsa.player.work.PulsaWork


import com.pulsa.player.core.CrashLogger

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
        const val ACTION_VIRGIN_ALARM = "com.pulsa.player.action.VIRGIN_ALARM"
        const val EXTRA_ALARM_AMBIENT = "virgin_alarm_ambient"
        private const val RESUME_LISTENER_DELAY_MS = 800L

        /** Item 170 — despertador progressivo: tempo do ramp e faixa de volume do ambiente. */
        private const val ALARM_RAMP_MS = 45_000L
        private const val ALARM_AMBIENT_START = 0.12f
        private const val ALARM_AMBIENT_FULL = 0.7f

        /** F2 · Vídeo por voz: o quanto "volta o filme" recua quando não há 30s na frase. */
        private const val VIDEO_BACK_MS = 30_000L

        /** Janela unica de escuta da Virgin na tela, antes de fechar sozinha. */
        private const val VIRGIN_WINDOW_MS = 6000L
        private const val CHAPTER_RESTART_MS = 3000L
        private const val CHAIN_DELAY_MS = 1800L
        private const val MONTH_MS = 30L * 24 * 60 * 60 * 1000
        private const val AMBIENT_DUCK_FACTOR = 0.2f

        /** Relançar antigas: só entra o que está sem toque há 6 meses ou mais. */
        private const val RELAUNCH_GAP_DAYS = 180L
        private const val RELAUNCH_MAX = 40
        private val HANDS_FREE_BLOCKED = setOf(
            "scan", "duplicates", "never_played", "pendrive", "delete", "confirm", "cancel",
            "clean_space", "quiz",
            "visualizer", "skin", "karaoke"
            // `video_open` NÃO fica bloqueado: era o único jeito de voltar para a tela do
            // vídeo depois que ela fecha sozinha. Travar o comando só entregava o vídeo
            // parado, e sem comando para retomá-lo — que é o que "continua o filme" faz.

            // `download_episode` também NÃO fica bloqueado: "baixa esse episódio pra ouvir
            // no carro" é literalmente o comando para usar no carro, em voo.


        )
    }

    private val launcher = launchers

    init {
        HotwordBridge.bind(this)
    }

    val isActive: Boolean
        get() = virginOn || virginRecognizing

    private var welcomeVoice: DjVoice? = null
    private var virginVoice: DjVoice? = null
    private var radioLastId = -1L

    /** Faixas do último mix "Relança antigas" — recebem o aviso de intervalo em [announceRadioSong]. */
    private var relaunchIds: Set<Long> = emptySet()
    private var virginListener: DjCommandListener? = null
    private var virginOn = false
    private var virginRecognizing = false
    private var pendingRecognize = false
    private var pendingDelete: Song? = null
    private var pendingClean = false
    private var pendingDuplicateSongs: List<Song> = emptyList()
    private var pendingDuplicateVideos: List<Video> = emptyList()
    private var pendingUnplayedSongs: List<Song> = emptyList()
    private var pendingBatchUnplayed = false
    private var pendingPendriveFiles: List<VirginMedia.PendriveFile>? = null

    /** Item 122 · quiz falado: a pergunta pendente (o próximo texto vira resposta). */
    private data class QuizQuestion(val prompt: String, val answer: Long, val tol: Long)
    private var pendingQuiz: QuizQuestion? = null
    private var quizQuestions: List<QuizQuestion> = emptyList()
    private var quizIndex = 0
    private var quizScore = 0

    /** Item 159 · cronômetro de treino: ativo e se foi ele quem ligou o ambiente. */
    private var intervalActive = false
    private var intervalStartedAmbient = false
    private var pendriveTotalFound = 0
    private var pendriveDuplicatesSkipped = 0
    private var virginSpeechPaused = false
    private var virginScanInFlight = false
    private var alarmAmbientRamp: Runnable? = null
    private val virginHandler = Handler(Looper.getMainLooper())
    private val chainHandler = Handler(Looper.getMainLooper())
    private val resumeListenerRunnable = Runnable { doResumeVirginListener() }
    private var virginLastSpeechEndMs = 0L
    private var virginLang = ""

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
                    // O Worker de limpeza roda com o app fechado e não tem quem ouvir: ele
                    // guarda o que fez e o aviso sai aqui, junto da saudação (uma locução só,
                    // porque `speak` duas vezes seguidas cortaria a primeira).
                    val notice = OfflineCleanupRunner.takePendingNotice(activity)
                    val full = if (notice == null) {
                        msg
                    } else {
                        val (count, bytes) = notice
                        "$msg ${activity.getString(R.string.dj_voice_clean_auto, Helper.formatBytes(bytes), count)}"
                    }
                    vm.speak(full)
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
                    virginSpeak(say(R.string.dj_voice_scan_denied))
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
            virginSpeak(say(R.string.dj_voice_delete_cancel))
        }
    }

    fun onDuplicatesRequestResult(success: Boolean) {
        val songs = pendingDuplicateSongs
        val videos = pendingDuplicateVideos
        val unplayed = pendingBatchUnplayed
        pendingDuplicateSongs = emptyList()
        pendingDuplicateVideos = emptyList()
        pendingBatchUnplayed = false
        virginHandler.removeCallbacksAndMessages(null)
        if (success && (songs.isNotEmpty() || videos.isNotEmpty())) {
            completeVirginDuplicates(songs, videos, alreadyDeleted = true, unplayed = unplayed)
        } else if (songs.isNotEmpty() || videos.isNotEmpty()) {
            virginSpeak(say(if (unplayed) R.string.dj_voice_delete_failed else R.string.dj_voice_dup_failed))
        }
    }

    fun onPendriveTreeResult(uri: Uri?) {
        if (uri == null) {
            if (activity.isFinishing || activity.isDestroyed) return
            pendingPendriveFiles = null
            virginScanInFlight = false
            resumeVirginSpeech()
            virginSpeak(say(R.string.dj_voice_pen_none))
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

    /** Comando chegando do mãos-livres (serviço em segundo plano, música tocando). */
    fun onHandsFree(text: String) {
        if (virginOn) return
        if (text == "__unsupported__") {
            Hotword.stopIfRunning(activity.applicationContext)
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            Hotword.stopIfRunning(activity.applicationContext)
            return
        }
        if (SystemClock.elapsedRealtime() - virginLastSpeechEndMs < 1500L) return
        ThreadPool.onUi { resolveHandsFreeCommand(text) }
    }

    private fun resolveHandsFreeCommand(text: String) {
        if (activity.isFinishing || activity.isDestroyed) {
            Hotword.stopIfRunning(activity.applicationContext)
            return
        }
        val norm = DjCommander.norm(text)
        val action = DjCommander.action(norm)
        // Comandos que pedem tela/diálogo não fazem sentido ao fundo — avisa e deixa pra lá.
        if (action != null && action in HANDS_FREE_BLOCKED) {
            if (DjCommander.hasWake(norm)) virginSpeak(say(R.string.dj_voice_hands_free_ui))
            return
        }
        dispatchVirginCommand(text)
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
        // Mãos-livres em segundo plano sai da frente (um microfone só).
        Hotword.stopIfRunning(activity)
        virginOn = true
        host.syncVirginIcon()
        virginListener?.destroy()
        // Escuta por pedido (janela unica): o microfone so abre depois que ela termina de
        // falar, quando resumeVirginSpeech chama doResumeVirginListener com a janela. Se
        // abrisse aqui, ela ouviria a própria saudação (eco) e a janela pegaria a boca dela.
        // Duck so durante a janela ou a fala, nunca permanente.
        virginListener = DjCommandListener(
            activity,
            MicMode.ONE_SHOT,
            onResult = { handleCommand(it) },
            onClosed = { expired ->
                // `expired` é lido aqui, no mesmo instante em que a janela fechou, e não
                // depois na fila da UI. A versão anterior decidia olhando `virginSpeechPaused`
                // já na fila: toda resposta curta ("voltando", "tocando") terminava de falar
                // antes do callback rodar, o estado parecia o de fim de janela e a Virgin se
                // desligava. Era o "fecha depois de voltar ou avançar".
                if (expired && !activity.isFinishing && !activity.isDestroyed) {
                    // Fim de janela sem ouvir nada: a Virgin ficava ligada SEM microfone
                    // (`virginOn` `true`, ícone aceso, nenhuma janela aberta). O toque
                    // seguinte no ícone é toggle, e com `virginOn` `true` ele desligava
                    // tudo em vez de reabrir a janela — o "dou dois toques e ela some".
                    ThreadPool.onUi { stopVirgin(silent = true) }
                }
            }
        )
        val cur = Playback.currentSong
        val msg = if (cur != null) {
            activity.getString(R.string.dj_voice_track, cur.title, cur.artist)
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
        if (!silent) virginSpeak(say(R.string.dj_voice_goodbye))
        virginVoice?.stop()
        Hotword.startIfNeeded(activity)
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
        // Janela unica de escuta: abre aqui (depois da fala dela), 6s, fecha sozinha.
        // Depois de ouvir um comando ela fala de novo e a proxima janela abre daqui mesmo.
        virginListener?.start(VIRGIN_WINDOW_MS)
    }

    fun announceRadioSong(song: Song) {
        if (activity.isFinishing || activity.isDestroyed) return
        // "Relança antigas": a cada faixa do mix, a Virgin avisa quanto tempo ela ficou parada.
        // Independente do modo rádio — o mix foi pedido, o aviso é o combinado dele.
        if (song.id in relaunchIds && Settings.djVoice(activity)) {
            val note = lastHeardNote(activity.applicationContext, song.id)
            if (note != null) {
                virginSpeak(note)
                return
            }
        }
        if (!Settings.djRadio(activity) || !Settings.djVoice(activity)) return
        DjSessionMemory.notePlayed(song.id)
        val newId = song.id
        if (radioLastId == newId) return
        radioLastId = newId
        val deduction = DjDedication.take()
        val track = activity.getString(
            R.string.dj_voice_track, song.title, song.artist
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
        val lang = virginLang
        val duckAmbient = Ambient.isOn() && Ambient.duckFactor() >= 1f
        if (duckAmbient) Ambient.setDuck(AMBIENT_DUCK_FACTOR)
        // Enquanto ela fala, a musica fica baixa (e volta so no fim da fala). Nao deixar
        // o duck permanente: era por ele que a musica ficava a 35% a sessão inteira.
        Playback.setMicListening(true)
        voice.init { ready ->
            if (!ready || activity.isDestroyed) {
                if (duckAmbient) Ambient.setDuck(1f)
                Playback.setMicListening(false)
                if (!hold) resumeVirginSpeech()
                return@init
            }
            voice.speak(text, lang) {
                virginLastSpeechEndMs = SystemClock.elapsedRealtime()
                if (duckAmbient) Ambient.setDuck(1f)
                ThreadPool.onUi {
                    Playback.setMicListening(false)
                    if (!hold) resumeVirginSpeech()
                }
            }
        }
    }

    /** String de voz no idioma da pergunta; cai para o idioma do app se faltar. */
    private fun say(resId: Int, vararg args: Any?): String {
        val ctx = langContext() ?: return activity.getString(resId, *args)
        return runCatching { ctx.getString(resId, *args) }.getOrElse { activity.getString(resId, *args) }
    }

    /**
     * Contexto no idioma da pergunta. `en`/`es` sempre; `pt` só quando o app está em outro
     * idioma — aí a resposta sai em português mesmo com a interface em inglês/espanhol. Se o
     * app já está em português, devolve `null` e a string sai pelo próprio idioma da interface.
     */
    private fun langContext(): android.content.Context? {
        val tag = when (virginLang) {
            "en", "es" -> virginLang
            "pt" -> {
                val app = Settings.language(activity)
                if (app == Settings.LANG_EN || app == Settings.LANG_ES) "pt" else return null
            }
            else -> return null
        }
        return runCatching {
            val conf = android.content.res.Configuration(activity.resources.configuration)
            conf.setLocale(java.util.Locale.forLanguageTag(tag))
            activity.createConfigurationContext(conf)
        }.getOrNull()
    }

    private fun reactCtx(): android.content.Context = langContext() ?: activity

    private fun virgSleepMix() {
        ThreadPool.post {
            val songs = Library.allSongs(activity.applicationContext)
            if (songs.isEmpty()) {
                ThreadPool.onUi { virginSpeak(say(R.string.dj_voice_only_none, "")) }
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
                    virginSpeak(say(R.string.dj_voice_only_none, ""))
                    return@onUi
                }
                Telemetry.log(activity, "Virgin sleep n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(true)
                DjSessionMemory.notePlayed(set.map { it.id })
                Playback.start(set, 0)
                virginSpeak(say(R.string.dj_voice_sleep, set.size) + avoidNote(learn, songs))
            }
        }
    }

    private fun virgMonthMix() {
        ThreadPool.post {
            val ctx = activity.applicationContext
            val songs = Library.allSongs(ctx)
            if (songs.isEmpty()) {
                ThreadPool.onUi { virginSpeak(say(R.string.dj_voice_only_none, "")) }
                return@post
            }
            val favIds = runCatching {
                PlaylistDb.get(ctx).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val monthFavs = runCatching {
                PlaylistDb.get(ctx).favoritesLikedSince(System.currentTimeMillis() - MONTH_MS)
            }.getOrDefault(emptyList())
            val pool = if (monthFavs.size >= 3) monthFavs else songs.filter { it.id in favIds }
            val learn = DjLearn.learn(ctx)
            val set = DjEngine.build(
                pool, favIds, DjEngine.Source.FAVORITES, DjEngine.Intensity.BALANCED, learn,
                exclude = DjSessionMemory.recentIds()
            )
            ThreadPool.onUi {
                if (set.isEmpty()) {
                    virginSpeak(say(R.string.dj_voice_only_none, ""))
                    return@onUi
                }
                Telemetry.log(activity, "Virgin month-favs n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                DjSessionMemory.notePlayed(set.map { it.id })
                Playback.start(set, 0)
                virginSpeak(
                    (if (pool === monthFavs) say(R.string.dj_voice_month_favs, set.size)
                    else say(R.string.dj_voice_month_favs_fallback, set.size)) + avoidNote(learn, pool)
                )
            }
        }
    }

    private fun virgArtistOnly(query: String?) {
        val artist = VirginMedia.findArtist(activity, query)
        if (artist == null) {
            virginSpeak(say(R.string.dj_voice_only_none, query ?: ""))
            return
        }
        ThreadPool.post {
            val songs = Library.songsByArtist(activity.applicationContext, artist)
            ThreadPool.onUi {
                if (songs.isEmpty()) {
                    virginSpeak(say(R.string.dj_voice_only_none, artist))
                    return@onUi
                }
                Telemetry.log(activity, "Virgin only artist=$artist n=${songs.size}")
                Playback.setShuffle(true)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                Playback.start(songs.shuffled(), 0)
                virginSpeak(say(R.string.dj_voice_only, artist, songs.size))
            }
        }
    }

    private fun virgDynamicQueue(query: DjCommander.DynQuery?) {
        if (query == null) {
            virginSpeak(say(R.string.dj_voice_dynq_none))
            return
        }
        ThreadPool.post {
            val ctx = activity.applicationContext
            val songs = Library.allSongs(ctx)
            if (songs.isEmpty()) {
                ThreadPool.onUi { virginSpeak(say(R.string.dj_voice_dynq_none)) }
                return@post
            }
            val favIds = runCatching {
                PlaylistDb.get(ctx).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val learn = DjLearn.learn(ctx)
            val lastPlayed = DjLearn.lastPlayedMap(ctx)
            val nowSec = System.currentTimeMillis() / 1000L
            val recent = DjSessionMemory.recentIds()
            val baseFits = songs.filter { s ->
                if (recent.contains(s.id)) return@filter false
                if (learn.disliked.contains(s.id)) return@filter false
                if (query.genres.isNotEmpty() &&
                    query.genres.none { DjCommander.matchesGenre(Library.genreOf(ctx, s.id), it) }
                ) return@filter false
                if (query.favoritesOnly && s.id !in favIds) return@filter false
                query.maxAgeDays?.let { max ->
                    val last = lastPlayed[s.id]
                    if (last != null && nowSec - last < max * 86400L) return@filter false
                }
                query.playsLessThan?.let { n ->
                    if ((learn.plays[s.id] ?: 0) >= n) return@filter false
                }
                query.skipsLessThan?.let { n ->
                    if ((learn.skipCount[s.id] ?: 0) >= n) return@filter false
                }
                true
            }
            val avoidNoteStr = avoidNote(learn, baseFits)
            val pool = baseFits.filter { it.id !in learn.avoided }
            ThreadPool.onUi {
                if (pool.isEmpty()) {
                    virginSpeak(say(R.string.dj_voice_dynq_none))
                    return@onUi
                }
                val set = pool.shuffled().take(60)
                Telemetry.log(activity, "Virgin dynq g=${query.genres} age=${query.maxAgeDays} plays<${query.playsLessThan} skips<${query.skipsLessThan}")
                Playback.setShuffle(true)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                DjSessionMemory.notePlayed(set.map { it.id })
                Playback.start(set, 0)
                virginSpeak(say(R.string.dj_voice_dynq_done, set.size, dynqReason(query)) + avoidNoteStr)
            }
        }
    }

    /**
     * "Relança antigas": mix das faixas que você não ouve há 6 meses ou mais.
     *
     * O oposto do "Rádio com memória": lá o aviso de intervalo aparece por acaso, aqui o mix
     * é montado só com as esquecidas (sem toque em [RELAUNCH_GAP_DAYS] dias, fora do que já
     * tocou por perto e das odiadas). Ao começar cada faixa, [announceRadioSong] avisa o
     * intervalo — "essa você não ouvia há 8 meses".
     */
    private fun virgRelaunch() {
        ThreadPool.post {
            val ctx = activity.applicationContext
            val songs = Library.allSongs(ctx)
            if (songs.isEmpty()) {
                ThreadPool.onUi { virginSpeak(say(R.string.dj_voice_relaunch_none)) }
                return@post
            }
            val learn = DjLearn.learn(ctx)
            val lastPlayed = DjLearn.lastPlayedMap(ctx)
            val nowSec = System.currentTimeMillis() / 1000L
            val gapSec = RELAUNCH_GAP_DAYS * 86400L
            val recent = DjSessionMemory.recentIds()
            val forgotten = songs.filter { s ->
                if (recent.contains(s.id)) return@filter false
                if (learn.disliked.contains(s.id)) return@filter false
                val last = lastPlayed[s.id] ?: return@filter false
                nowSec - last >= gapSec
            }.sortedByDescending { nowSec - (lastPlayed[it.id] ?: nowSec) }
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (forgotten.isEmpty()) {
                    virginSpeak(say(R.string.dj_voice_relaunch_none))
                    return@onUi
                }
                val set = forgotten.take(RELAUNCH_MAX)
                relaunchIds = set.map { it.id }.toSet()
                Telemetry.log(activity, "Virgin relança antigas n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                DjSessionMemory.notePlayed(set.map { it.id })
                Playback.start(set, 0)
                virginSpeak(say(R.string.dj_voice_relaunch_done, set.size))
            }
        }
    }

    /** Régua de intervalo da mesma família do "Rádio com memória" ([DjSession.lastHeardNote]). */
    private fun lastHeardNote(app: Context, songId: Long): String? {
        val last = DjLearn.lastPlayedMap(app)[songId] ?: return null
        val gapDays = (System.currentTimeMillis() / 1000L - last) / 86400L
        if (gapDays < 5) return null
        return when {
            gapDays < 14 -> activity.getString(R.string.dj_voice_mem_days, gapDays)
            gapDays < 60 -> activity.getString(R.string.dj_voice_mem_weeks, gapDays / 7)
            else -> activity.getString(R.string.dj_voice_mem_months, gapDays / 30)
        }
    }

    /**
     * Cria uma playlist salva por voz.
     *
     * Grava o nome como foi falado, sem a cortesia do começo, mas com a primeira letra
     * maiúscula — "cria uma playlist chamada batidinhas" tem que aparecer na lista como
     * "Batidinhas", e não como " batidinhas".
     */
    private fun virgPlaylistCreate(norm: String) {
        val raw = DjCommander.playlistName(norm)
        if (raw.isNullOrBlank()) {
            virginSpeak(say(R.string.dj_voice_playlist_ask_name, "batidinhas"))
            return
        }
        val name = raw.replaceFirstChar { it.titlecase() }
        ThreadPool.post {
            val ctx = activity.applicationContext
            val db = PlaylistDb.get(ctx)
            // Comparação sem acento e sem caixa: o nome falado quase nunca bate com o salvo.
            val key = DjCommander.norm(name)
            val existing = runCatching { db.playlists() }.getOrDefault(emptyList())
                .firstOrNull { DjCommander.norm(it.name) == key }
            if (existing != null) {
                ThreadPool.onUi { virginSpeak(say(R.string.dj_voice_playlist_exists, existing.name)) }
                return@post
            }
            // `createPlaylist` é INSERT de Room: ele fica **aqui**, dentro do `post`, e não no
            // `onUi` como estava. Dizer "cria uma playlist chamada batidinhas" derrubava o app.
            val id = runCatching { db.createPlaylist(name) }.getOrNull()
            Telemetry.log(activity, "Virgin playlist nova \"$name\" ok=${id != null}")
            ThreadPool.onUi {
                virginSpeak(
                    if (id != null) say(R.string.dj_voice_playlist_created, name)
                    else say(R.string.dj_voice_dynq_none)
                )
            }
        }
    }

    /**
     * Toca uma playlist salva por voz.
     *
     * Se não existir playlist com esse nome, o comando **cai na fila dinâmica** em vez de
     * falhar: "toca a playlist de rock" é ambíguo de propósito, e quem responde é quem sabe
     * o que existe na biblioteca. A tela de Playlists lê do banco no [Fragment.onResume],
     * então não há nada a recarregar aqui.
     */
    private fun virgPlaylistPlay(norm: String) {
        val raw = DjCommander.playlistName(norm) ?: run {
            virgDynamicQueue(DjCommander.dynamicQuery(norm))
            return
        }
        ThreadPool.post {
            val ctx = activity.applicationContext
            val db = PlaylistDb.get(ctx)
            val key = DjCommander.norm(raw)
            val playlists = runCatching { db.playlists() }.getOrDefault(emptyList())
            val found = playlists.firstOrNull { DjCommander.norm(it.name) == key }
            // Só as do usuário contam como "existe alguma playlist": as de sistema nascem
            // sozinhas, e responder "você não tem nenhuma playlist" quando o app criou quatro
            // automaticamente seria pior que cair na fila dinâmica.
            val hasAnyPlaylist = playlists.any { !it.system }
            if (found == null) {
                // O que a documentação deste método promete: "se não existir playlist com esse
                // nome, cai na fila dinâmica". Só havia uma parte disso — o fallback existia
                // quando o nome **nem** era falado, mas não quando o nome era falado e não
                // batia com nada. Resultado: "toca a playlist de rock" sem playlist Rock
                // respondia "não achei", que é a resposta mais inútil possível.
                if (!hasAnyPlaylist) {
                    ThreadPool.onUi { virginSpeak(say(R.string.dj_voice_playlist_none, raw)) }
                } else {
                    virgDynamicQueue(DjCommander.dynamicQuery(norm))
                }
                return@post
            }
            val songs = runCatching { db.songs(found.id) }.getOrDefault(emptyList())
            ThreadPool.onUi {
                if (songs.isEmpty()) {
                    virginSpeak(say(R.string.dj_voice_playlist_empty, found.name))
                    return@onUi
                }
                Telemetry.log(activity, "Virgin playlist \"${found.name}\" n=${songs.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(false)
                Playback.setSleepMix(false)
                DjSessionMemory.notePlayed(songs.map { it.id })
                Playback.start(songs, 0)
                virginSpeak(say(R.string.dj_voice_playlist_playing, found.name, songs.size))
            }
        }
    }

    private fun dynqReason(q: DjCommander.DynQuery): String {
        val parts = ArrayList<String>()
        q.genres.forEach {
            parts.add(it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() })
        }
        if (q.favoritesOnly) parts.add(say(R.string.dj_voice_dynq_fav))
        q.maxAgeDays?.let { parts.add(say(R.string.dj_voice_dynq_age, it)) }
        q.playsLessThan?.let { parts.add(say(R.string.dj_voice_dynq_plays, it)) }
        q.skipsLessThan?.let { parts.add(say(R.string.dj_voice_dynq_skips, it)) }
        return parts.joinToString(", ")
    }

    /** Túnel do tempo: monta um mix só com músicas de uma década (anos 80, 90, 2000). */
    private fun virgDecadeMix(decade: Int?) {
        if (decade == null) {
            virginSpeak(say(R.string.dj_voice_decade_none))
            return
        }
        ThreadPool.post {
            val ctx = activity.applicationContext
            val songs = Library.allSongs(ctx)
            if (songs.isEmpty()) {
                ThreadPool.onUi { virginSpeak(say(R.string.dj_voice_decade_none)) }
                return@post
            }
            val learn = DjLearn.learn(ctx)
            val recent = DjSessionMemory.recentIds()
            val from = decade
            val to = decade + 9
            val candidates = songs.filter { s ->
                s.year in from..to && s.id !in recent && s.id !in learn.disliked
            }
            val avoidNoteStr = avoidNote(learn, candidates)
            val pool = candidates.filter { it.id !in learn.avoided }
            ThreadPool.onUi {
                if (pool.isEmpty()) {
                    virginSpeak(say(R.string.dj_voice_decade_none))
                    return@onUi
                }
                val set = pool.shuffled().take(60)
                Telemetry.log(activity, "Virgin decade=$decade n=${set.size}")
                Playback.setShuffle(true)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                DjSessionMemory.notePlayed(set.map { it.id })
                Playback.start(set, 0)
                virginSpeak(say(R.string.dj_voice_decade_done, set.size, decadeLabel(decade)) + avoidNoteStr)
            }
        }
    }

    private fun decadeLabel(decade: Int): String =
        if (decade >= 2000) "$decade" else "${decade - 1900}"

    private fun avoidNote(learn: DjEngine.Learn, pool: List<Song>): String {
        val n = pool.count { it.id in learn.avoided }
        if (n == 0) return ""
        return " " + say(R.string.dj_voice_avoid_note, n)
    }

    /**
     * F2 · Vídeo por voz. Só vale com o vídeo **no motor**: a posição de vídeo não é salva
     * (`PlaybackService` pula vídeo no save e no restore, porque id de vídeo e id de música
     * dividem o mesmo espaço do MediaStore), então "continua o filme" só alcança o que ainda
     * está na fila. Fora disso a Virgin avisa, em vez de abrir uma tela de vídeo preta.
     *
     * `isStream` entra junto porque o stream (HLS/DASH do PeerTube) é o mesmo item na fila
     * e a mesma tela — é ele que faz o download do vídeo ser offline de verdade.
     */
    private fun videoPlaying(): Song? =
        Playback.currentSong?.takeIf { it.isVideo || it.isStream }

    /**
     * Retoma o vídeo sem pedir tela nenhuma. É o que "continua o filme" tem que fazer:
     * `showPlaying` chama `startActivity`, que do fundo o Android 10+ bloqueia, então
     * abrir a tela ao fundo só produzia a resposta "Abrindo X" sem abrir nada.
     */
    private fun virgVideoPlay() {
        val song = videoPlaying()
        if (song == null) {
            virginSpeak(say(R.string.dj_voice_video_none))
            return
        }
        Playback.play()
        virginSpeak(say(R.string.dj_voice_video_play, song.title))
    }

    private fun virgVideoOpen() {
        val song = videoPlaying()
        if (song == null) {
            virginSpeak(say(R.string.dj_voice_video_none))
            return
        }
        virginSpeak(say(R.string.dj_voice_video_open, song.title))
        // `showPlaying` já é no-op se a tela está no ar, e abre em modo anexo: não troca a
        // fila nem mexe em shuffle/repeat, que é o que o F2b deixou para a entrada pela
        // biblioteca.
        VideoPlayerActivity.showPlaying(activity)
    }

    private fun virgVideoBack() {
        if (videoPlaying() == null) {
            // Sem vídeo, "volta pra trás" é o que sempre foi: faixa anterior. A palavra de
            // direção sozinha chega aqui sem citar o filme, e responder "não tem vídeo
            // tocando" seria devolver um "não" para quem só queria voltar uma faixa. A
            // decisão fica no handler porque `DjCommander` só vê texto: ele não sabe o que
            // está tocando para escolher.
            virginSpeak(say(R.string.dj_voice_prev))
            Playback.prev()
            return
        }
        // `Playback` não expõe a duração (o `seekBy` dela exige uma), então o clamp é só no
        // zero. Recuar 30 s de um vídeo de 4 minutos não corre risco de estourar a ponta.
        val alvo = (Playback.position - VIDEO_BACK_MS).coerceAtLeast(0L)
        Playback.seekTo(alvo)
        virginSpeak(say(R.string.dj_voice_video_back, Helper.formatDuration(alvo)))
    }

    /**
     * F2 · "Toca o filme X" — abre o vídeo **pelo nome**, não o que já está na fila.
     *
     * A busca é offline primeiro ([VirginMedia.findVideo], título do MediaStore) e só depois
     * no histórico de streams ([StreamHistory.find], PeerTube/URL colada): o vídeo local é o
     * que existe sem rede, e um stream com o mesmo nome que um arquivo baixado não deve
     * roubar o pedido. A consulta sai da main thread porque as duas leem MediaStore/disco.
     */
    private fun virgVideoByName(query: String?) {
        val q = query.orEmpty()
        if (q.isBlank()) {
            virginSpeak(say(R.string.dj_voice_video_none))
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
                        Telemetry.log(activity, "Virgin video_by_name local=${video.title}")
                        virginSpeak(say(R.string.dj_voice_video_open, video.title))
                        VideoPlayerActivity.start(activity, listOf(video), 0)
                    }
                    stream != null -> {
                        Telemetry.log(activity, "Virgin video_by_name stream=${stream.title}")
                        virginSpeak(say(R.string.dj_voice_video_open, stream.title))
                        VideoPlayerActivity.startStream(
                            activity, stream.url, stream.title,
                            uuid = stream.uuid, pageUrl = stream.pageUrl,
                            thumbnail = stream.thumbnail
                        )
                    }
                    else -> {
                        Telemetry.log(activity, "Virgin video_by_name miss q='$q' videos=$vistos")
                        virginSpeak(say(R.string.dj_voice_video_not_found))
                    }
                }
            }
        }
    }


    /**
     * Fila universal — reordenação por voz ("virgi, joga o vídeo pro fim").
     *
     * Quem escolhe **qual** item do tipo mover é o motor ([Playback.moveToEnd]): o atual
     * quando a frase não cita tipo, senão o item do tipo citado mais próximo à frente. Aqui
     * só traduz: sem fila -> o que não há para mover, item movido -> o nome dele.
     */
    private fun virgQueueMove(query: DjCommander.QueueMove?) {
        val q = query ?: return
        if (Playback.queue.isEmpty()) {
            virginSpeak(say(R.string.dj_voice_queue_move_none))
            return
        }
        val moved = Playback.moveToEnd(q.video, q.episode)
        if (moved == null) {
            virginSpeak(say(R.string.dj_voice_queue_move_none))
            return
        }
        host.syncMiniPlayer()
        Telemetry.log(activity, "Virgin queue_move type=video:${q.video} episode:${q.episode} -> ${moved.title}")
        virginSpeak(say(R.string.dj_voice_queue_move_done, moved.title))
    }

    /**
     * Histórico do dia por tipo ("virgi, o que já tocou de vídeo hoje?"). Fala só os mais
     * recentes, porque uma lista inteira do dia não é legível de ouvido.
     */
    private fun virgQueueHistory(type: String?) {
        val t = type ?: return
        ThreadPool.post {
            val ranks = DjLearn.playedByKind(activity.applicationContext, t, 0, 8)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (ranks.isEmpty()) {
                    virginSpeak(say(R.string.dj_voice_history_none))
                    return@onUi
                }
                val items = ranks.map { (artist, title) ->
                    if (artist.isBlank()) title else
                        activity.getString(R.string.dj_voice_yesterday_item, artist, title)
                }
                Telemetry.log(activity, "Virgin queue_history kind=$t items=${items.size}")
                virginSpeak(say(R.string.dj_voice_history_all, items.joinToString("; ")))
            }
        }
    }

    /**
     * F4 · Download por voz — "virgi, baixa esse episódio pra ouvir no carro".
     *
     * Resolve o episódio pela faixa que está tocando agora (`song.podcastId`). A leitura do
     * banco sai da main thread; o download vai para o WorkManager (fila única por episódio,
     * com rede como constraint — o "pra ouvir no carro" é isso: ele baixa quando puder). O
     * `selectManually` NÃO entra aqui: comando de voz não pode abrir escolha, pega o atual.
     */
    private fun virgDownloadEpisode() {
        val current = Playback.currentSong
        val episodeId = current?.podcastId
        if (episodeId == null) {
            virginSpeak(say(R.string.dj_voice_download_no_ep))
            return
        }
        val ctx = activity.applicationContext
        ThreadPool.post {
            val episode = PodcastDb.get(ctx).episode(episodeId)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (episode == null) {
                    virginSpeak(say(R.string.dj_voice_download_fail))
                    return@onUi
                }
                if (episode.filePath.isNotBlank()) {
                    virginSpeak(say(R.string.dj_voice_download_already, episode.title))
                    return@onUi
                }
                Telemetry.log(activity, "Virgin download_episode id=${episode.id}")
                val job = PulsaWork.download(ctx, episode.audioUrl, "podcast-ep${episode.id}")
                if (job == null) {
                    virginSpeak(say(R.string.dj_voice_download_fail))
                    return@onUi
                }
                virginSpeak(say(R.string.dj_voice_download_start, episode.title))
                PulsaWork.watchDownload(ctx, job) { ok, path ->
                    if (ok && path.isNotBlank()) {
                        ThreadPool.post { PodcastDb.get(ctx).setFilePath(episode.id, path) }
                        virginSpeak(say(R.string.dj_voice_download_done, episode.title))
                    } else {
                        virginSpeak(say(R.string.dj_voice_download_fail))
                    }
                }
            }
        }
    }

    /**
     * F4 · Limpeza por voz — "virgi, limpa o que tá ocupando espaço".
     *
     * Primeira fala é perguntar: mostra quanto dos vídeos e quanto dos episódios, e pede a
     * confirmação (o "sim" reusa o ramo `confirm`). Apagar download por engano é mais caro
     * que perguntar, então a confirmação dupla é de propósito — igual ao delete de música.
     */
    private fun virgCleanSpace() {
        val ctx = activity.applicationContext
        ThreadPool.post {
            DownloadStore.ensureLoaded(ctx)
            val done = DownloadStore.list().filter { it.status == DownloadStore.Status.DONE }
            val videoBytes = done.sumOf { item ->
                runCatching { DownloadStore.fileFor(ctx, item).length() }.getOrDefault(0L)
            }
            val episodes = downloadedEpisodes(ctx)
            val podcastBytes = episodes.sumOf { ep ->
                runCatching { java.io.File(ep.filePath).length() }.getOrDefault(0L)
            }
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (videoBytes <= 0L && podcastBytes <= 0L) {
                    virginSpeak(say(R.string.dj_voice_clean_none))
                    return@onUi
                }
                pendingClean = true
                virginHandler.removeCallbacksAndMessages(null)
                virginHandler.postDelayed({ pendingClean = false }, 15000)
                Telemetry.log(
                    activity,
                    "Virgin clean_space videos=${Helper.formatBytes(videoBytes)} podcasts=${Helper.formatBytes(podcastBytes)}"
                )
                virginSpeak(say(
                    R.string.dj_voice_clean_confirm,
                    Helper.formatBytes(videoBytes), Helper.formatBytes(podcastBytes)
                ))
            }
        }
    }

    /** Confirmação do "limpa o que tá ocupando espaço": apaga e conta o que liberou. */
    private fun virgCleanSpaceConfirm() {
        val ctx = activity.applicationContext
        ThreadPool.post {
            DownloadStore.ensureLoaded(ctx)
            val done = DownloadStore.list().filter { it.status == DownloadStore.Status.DONE }
            val videoBytes = done.sumOf { item ->
                runCatching { DownloadStore.fileFor(ctx, item).length() }.getOrDefault(0L)
            }
            // O episódio que está nos ouvidos agora não é apagado: derrubar o arquivo que
            // o engine está lendo para o carro parar o que já estava descendo.
            val currentEpisode = Playback.currentSong?.podcastId
            val episodes = downloadedEpisodes(ctx).filter { it.id != currentEpisode }
            val podcastBytes = episodes.sumOf { ep ->
                runCatching { java.io.File(ep.filePath).length() }.getOrDefault(0L)
            }
            done.forEach { DownloadService.delete(ctx, it.id) }
            val db = PodcastDb.get(ctx)
            episodes.forEach { ep ->
                if (ep.filePath.isNotBlank()) {
                    PodcastFiles.delete(ctx, ep.filePath)
                    db.setFilePath(ep.id, "")
                }
            }
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                Telemetry.log(
                    activity,
                    "Virgin clean_space freed=${Helper.formatBytes(videoBytes + podcastBytes)}"
                )
                virginSpeak(say(R.string.dj_voice_clean_done, Helper.formatBytes(videoBytes + podcastBytes)))
            }
        }
    }

    /** Todos os episódios baixados, em qualquer feed. Fora da main thread. */
    private fun downloadedEpisodes(ctx: Context): List<Episode> {
        val db = PodcastDb.get(ctx)
        return db.feeds().flatMap { feed ->
            runCatching { db.downloadedEpisodes(feed.id) }.getOrDefault(emptyList())
        }
    }



    /**
     * F5 — retomada da última sessão.
     *
     * Era `private` (só o comando de voz "volta a tocar" chamava). Virou pública porque o
     * atalho "Continuar" da tela inicial é a mesma intenção sem passar pela fala: mesma
     * fila, mesma posição, mesmo anúncio.
     */
    fun resumeLastSession() {
        val ctx = activity.applicationContext
        // F5 — a própria fila restaurada já é a "última coisa tocada", e ela é ciente do
        // tipo sem precisar remontar nada do banco: o item atual pode ser episódio, rádio
        // ou stream, não só música. Vídeo fica de fora de propósito (não se retoma vídeo
        // sem a tela), então ele cai no caminho de música abaixo.
        val current = Playback.currentSong
        if (current != null && !current.needsVideoScreen) {
            virginSpeak(say(R.string.dj_voice_resume_searching))
            val pos = Playback.position
            if (!Playback.isPlaying) Playback.toggle()
            Telemetry.log(activity, "Virgin resume atual tipo=${ResumeInfo.kindOf(current)} pos=$pos")
            virginSpeak(ResumeInfo.spoken(activity, current, pos, Settings.queueSavedAt(ctx)))
            return
        }
        val songId = Settings.resumeSongId(ctx)
        if (songId < 0L) {
            virginSpeak(say(R.string.dj_voice_resume_none))
            return
        }
        virginSpeak(say(R.string.dj_voice_resume_searching))
        ThreadPool.post {
            val song = Library.songsById(ctx, songId).firstOrNull()
            ThreadPool.onUi {
                if (song == null) {
                    virginSpeak(say(R.string.dj_voice_resume_none))
                    return@onUi
                }
                Telemetry.log(activity, "Virgin resume id=$songId pos=${Settings.resumePosition(ctx)}")
                Playback.setSleepMix(false)
                Playback.start(listOf(song), 0)
                virginSpeak(say(R.string.dj_voice_resume, song.title, song.artist))
            }
        }
    }

    private fun virgMixWithArtist(query: String?) {
        val artist = VirginMedia.findArtist(activity, query)
        if (artist == null) {
            virginSpeak(say(R.string.dj_voice_mixwith_none, query ?: ""))
            return
        }
        ThreadPool.post {
            val songs = Library.allSongs(activity.applicationContext)
            if (songs.isEmpty()) {
                ThreadPool.onUi { virginSpeak(say(R.string.dj_voice_only_none, "")) }
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
                    virginSpeak(say(R.string.dj_voice_only_none, ""))
                    return@onUi
                }
                Telemetry.log(activity, "Virgin mixwith artist=$artist n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                DjSessionMemory.notePlayed(set.map { it.id })
                Playback.start(set, 0)
                virginSpeak(say(R.string.dj_voice_mixwith, artist, set.size) + avoidNote(learn, songs))
            }
        }
    }

    private fun virgWildMix() {
        ThreadPool.post {
            val songs = Library.allSongs(activity.applicationContext)
            if (songs.isEmpty()) {
                ThreadPool.onUi { virginSpeak(say(R.string.dj_voice_only_none, "")) }
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
                    virginSpeak(say(R.string.dj_voice_only_none, ""))
                    return@onUi
                }
                Telemetry.log(activity, "Virgin wild n=${set.size}")
                Playback.setShuffle(true)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                DjSessionMemory.notePlayed(set.map { it.id })
                Playback.start(set.shuffled(), 0)
                virginSpeak(say(R.string.dj_voice_mood_wild, set.size) + avoidNote(learn, songs))
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
                    virginSpeak(say(R.string.dj_voice_yesterday_none))
                    return@onUi
                }
                val byId = songs.associateBy { it.id }
                val items = ranks.mapNotNull { (id, _) ->
                    byId[id]?.let { s ->
                        activity.getString(R.string.dj_voice_yesterday_item, s.artist, s.title)
                    }
                }
                if (items.isEmpty()) {
                    virginSpeak(say(R.string.dj_voice_yesterday_none))
                } else {
                    virginSpeak(say(
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
            virginSpeak(say(R.string.dj_voice_memory_saved, memoryLabel(saved.first), saved.second))
        } else {
            virginSpeak(say(R.string.dj_voice_memory_ask, memoryLabel(DjMemory.PHONE)))
        }
    }

    private fun virgMemoryRecall(norm: String) {
        val fact = DjMemory.recall(activity.applicationContext, norm)
        if (fact != null) {
            DjMemory.log(activity.applicationContext, norm, "lembra ${fact.first}")
            virginSpeak(say(R.string.dj_voice_memory_saved, memoryLabel(fact.first), fact.second))
        } else {
            virginSpeak(say(R.string.dj_voice_memory_ask, memoryLabel(DjMemory.PHONE)))
        }
    }

    private fun resolveVirginCommand(text: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        // Quiz pendente: o próximo texto é a resposta da pergunta, não um comando novo.
        val quiz = pendingQuiz
        if (quiz != null) {
            answerQuiz(quiz, text)
            return
        }
        dispatchVirginCommand(text)
    }

    private fun dispatchVirginCommand(text: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        virginLang = DjCommander.languageOf(text)
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
            if (hasWake) virginSpeak(say(R.string.dj_voice_unknown))
            return
        }
        if (action !in setOf("confirm", "cancel", "delete", "clean_space", "interval_cancel")) {
            pendingDelete = null
            pendingUnplayedSongs = emptyList()
            // Comando novo encerra o cronômetro de treino (senão o ambiente ficaria solto).
            stopInterval()
            virginHandler.removeCallbacksAndMessages(null)
        }
        // Um comando novo encerra o mix "Relança antigas" (só ele mantém o aviso de intervalo).
        if (action != "relaunch") relaunchIds = emptySet()
        when (action) {
            "mix" -> virginSpeak(say(R.string.dj_voice_mix_main))
            "month_favs" -> virgMonthMix()
            "memory_save" -> virgMemorySave(norm)
            "memory_recall" -> virgMemoryRecall(norm)
            "mood_wild" -> virgWildMix()
            "sleep" -> virgSleepMix()
            "ambient_vol" -> {
                val vol = DjCommander.ambientVolume(norm)
                if (vol == null) return
                if (!Ambient.isOn()) {
                    virginSpeak(say(R.string.dj_voice_ambient_off))
                    return
                }
                val step = 0.1f
                val cur = Ambient.state().volume
                val target = if (vol.up) cur + step else cur - step
                Ambient.setVolume(target.coerceIn(0.05f, 1f))
                virginSpeak(say(R.string.dj_voice_ambient_vol, (Ambient.state().volume * 100).toInt()))
            }
            "ambient_mix" -> {
                val mode = DjCommander.ambientMix(norm) ?: return
                if (!Permissions.hasAccess(activity)) {
                    Toast.makeText(activity, R.string.dj_no_permission, Toast.LENGTH_LONG).show()
                    return
                }
                Ambient.start(mode, 0.7f)
                virginSpeak(say(R.string.dj_voice_ambient_mix, ambientLabel(mode)))
            }
            "repeat" -> {
                val on = !Playback.repeatOne
                Playback.setRepeatOne(on)
                virginSpeak(say(
                    if (on) R.string.dj_voice_repeat_on else R.string.dj_voice_repeat_off
                ))
            }
            "only" -> virgArtistOnly(DjCommander.onlyArtist(norm))
            "dynq" -> virgDynamicQueue(DjCommander.dynamicQuery(norm))
            "relaunch" -> virgRelaunch()
            "playlist_new" -> virgPlaylistCreate(norm)
            "playlist_play" -> virgPlaylistPlay(norm)
            "decade" -> virgDecadeMix(DjCommander.decadeQuery(norm))
            "scene" -> virgScene(DjCommander.sceneQuery(norm), norm)
            "mood" -> virgMoodState(DjCommander.moodStateMatch(norm))
            "weekend" -> virgWeekendSummary()
            "weekly" -> virgWeekSummary()
            "year" -> virgYearSummary()
            "sleeptimer" -> virgSleepTimer(DjCommander.sleepTimerQuery(norm))
            "alarm" -> virgAlarmSet(DjCommander.alarmQuery(norm))
            "alarm_cancel" -> virgAlarmCancel()
            "interval" -> virgInterval(DjCommander.intervalQuery(norm))
            "interval_cancel" -> virgIntervalCancel()
            "quiz" -> virgQuiz()
            "chapter" -> virgChapter(DjCommander.chapterQuery(norm))
            "mixwith" -> virgMixWithArtist(DjCommander.mixArtist(norm))
            "video_by_name" -> virgVideoByName(DjCommander.videoQuery(norm))
            "video_play" -> virgVideoPlay()
            "video_open" -> virgVideoOpen()
            "video_back" -> virgVideoBack()
            "video_pause" -> {
                if (videoPlaying() == null) {
                    virginSpeak(say(R.string.dj_voice_video_none))
                } else if (Playback.isPlaying) {
                    Playback.pause()
                    virginSpeak(say(R.string.dj_voice_pause))
                }
            }

"queue_move"    -> virgQueueMove(DjCommander.moveToEndQuery(norm))
            "queue_history" -> virgQueueHistory(DjCommander.historyQuery(norm))
            "download_episode" -> virgDownloadEpisode()
            "clean_space" -> virgCleanSpace()


            "skip", "next", "dislike" -> {
                val cur = Playback.currentSong
                if (action == "dislike" && cur != null) {
                    DjLearn.recordDislike(activity.applicationContext, cur.id)
                    virginSpeak(DjReactions.dislike(reactCtx()))
                } else if (action == "skip" && cur != null) {
                    DjLearn.recordSkip(activity.applicationContext, cur.id)
                    virginSpeak(DjReactions.skip(reactCtx()))
                } else {
                    virginSpeak(DjReactions.next(reactCtx()))
                }
                if (Playback.queue.isNotEmpty()) Playback.next()
            }
            "prev" -> {
                virginSpeak(say(R.string.dj_voice_prev))
                Playback.prev()
            }
            "pause" -> {
                if (Playback.isPlaying) {
                    Playback.toggle()
                    virginSpeak(say(R.string.dj_voice_pause))
                }
            }
            "play" -> {
                if (!Playback.isPlaying && Playback.queue.isNotEmpty()) {
                    Playback.toggle()
                    virginSpeak(say(R.string.dj_voice_play))
                }
            }
            "resume" -> resumeLastSession()
            "yesterday" -> virginSpeakYesterday()
            "fav" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    // Mesmo motivo do DjSession: `onResults` do reconhecimento chega na main,
                    // e ler/gravar favorito ali é query de Room na main thread.
                    val appCtx = activity.applicationContext
                    ThreadPool.post {
                        val nextValue = runCatching {
                            val db = PlaylistDb.get(appCtx)
                            val next = !db.isFavorite(cur.id)
                            db.setFavorite(cur, next)
                            next
                        }.getOrElse {
                            CrashLogger.writeLog(appCtx, "VIRGIN: favoritar por voz falhou id=${cur.id} -> $it")
                            return@post
                        }
                        if (nextValue) DjLearn.recordLiked(appCtx, cur.id)
                        ThreadPool.onUi {
                            host.syncMiniPlayer()
                            virginSpeak(
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
                    virginSpeak(say(R.string.dj_voice_track, cur.title, cur.artist))
                } else {
                    virginSpeak(say(R.string.dj_voice_unknown))
                }
            }
            "scan" -> virginScanLibrary()
            "duplicates" -> virginFindDuplicates()
            "never_played" -> virginCleanUnplayed()
            "pendrive" -> virginReadPendrive()
            "recognize" -> startVirginRecognize()
            "delete" -> {
                val cur = Playback.currentSong
                if (cur == null) {
                    pendingDelete = null
                    virginHandler.removeCallbacksAndMessages(null)
                    virginSpeak(say(R.string.dj_voice_delete_no_song))
                } else if (pendingDelete?.id == cur.id) {
                    pendingDelete = null
                    virginHandler.removeCallbacksAndMessages(null)
                    deleteVirginSong(cur)
                } else {
                    pendingDelete = cur
                    virginHandler.removeCallbacksAndMessages(null)
                    virginHandler.postDelayed({ pendingDelete = null }, 15000)
                    virginSpeak(say(R.string.dj_voice_delete_confirm, cur.title))
                }
            }
            "confirm" -> {
                val pen = pendingPendriveFiles
                if (pen != null) {
                    pendingPendriveFiles = null
                    virginHandler.removeCallbacksAndMessages(null)
                    completePendriveCopy(pen)
                } else if (pendingClean) {
                    pendingClean = false
                    virginHandler.removeCallbacksAndMessages(null)
                    virgCleanSpaceConfirm()
                } else if (pendingUnplayedSongs.isNotEmpty()) {
                    val list = pendingUnplayedSongs
                    pendingUnplayedSongs = emptyList()
                    virginHandler.removeCallbacksAndMessages(null)
                    deleteVirginUnplayed(list)
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
                    virginSpeak(say(R.string.dj_voice_pen_cancel))
                } else if (pendingClean || pendingUnplayedSongs.isNotEmpty()) {
                    pendingClean = false
                    pendingUnplayedSongs = emptyList()
                    virginHandler.removeCallbacksAndMessages(null)
                    virginSpeak(say(R.string.dj_voice_delete_cancel))
                } else {
                    pendingDelete = null
                    virginHandler.removeCallbacksAndMessages(null)
                    virginSpeak(say(R.string.dj_voice_delete_cancel))
                }
            }
            "thanks" -> virginSpeak(say(R.string.dj_voice_thanks))
            "hello" -> virginSpeak(say(R.string.dj_voice_hello))
            "count" -> virgVoiceLibraryCount()
            "playcount" -> virgPlayCount()
            "daily_set" -> virgStartDailySet()
            "queue_save" -> virgSaveQueueAsPlaylist(norm)
            "sleep_end" -> {
                if (!Playback.isPlaying) {
                    virginSpeak(say(R.string.dj_voice_sleeptimer_none))
                } else {
                    SleepTimer.scheduleEndOfTrack()
                    virginSpeak(say(R.string.dj_voice_sleep_end_set))
                }
            }
            "dedicate" -> {
                val name = DjCommander.dedicatee(norm)
                if (name.isNullOrBlank()) {
                    virginSpeak(say(R.string.dj_voice_dedicate_ask))
                } else if (DjCommander.dedicateNow(norm)) {
                    val cur = Playback.currentSong
                    if (cur != null) {
                        virginSpeak(say(R.string.dj_voice_dedication_lead, name) + " " +
                            say(R.string.dj_voice_track, cur.title, cur.artist))
                    } else {
                        DjDedication.pending = name
                        virginSpeak(say(R.string.dj_voice_dedicate_ok, name))
                    }
                } else {
                    DjDedication.pending = name
                    virginSpeak(say(R.string.dj_voice_dedicate_ok, name))
                }
            }
            "visualizer" -> toggleVisualizer()
            "skin" -> cycleSkin()
            "karaoke" -> toggleKaraoke()
            "suggest" -> virgSuggestSong()
            "identity" -> virginSpeak(DjIdentity.introSpeech())
            "stems" -> virginSpeak(say(R.string.dj_voice_stems_soon))
        }
    }

    private fun virgVoiceLibraryCount() {
        ThreadPool.post {
            val n = Library.allSongs(activity.applicationContext).size
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (n == 0) {
                    virginSpeak(say(R.string.dj_voice_count_none))
                } else {
                    virginSpeak(say(R.string.dj_voice_count, n))
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
                    virginSpeak(say(R.string.dj_empty))
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
                    virginSpeak(say(R.string.dj_empty))
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
                virginSpeak(say(R.string.dj_voice_daily_set, set.size))
            }
        }
    }

    /** "Quantas vezes toquei essa?" — lê o play_log da faixa atual e fala o resumo. */
    private fun virgPlayCount() {
        val cur = Playback.currentSong
        if (cur == null) {
            virginSpeak(say(R.string.dj_voice_unknown))
            return
        }
        val app = activity.applicationContext
        val key = DjLearn.typedKey(cur) ?: cur.id
        ThreadPool.post {
            val info = DjLearn.playCount(app, key)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (info == null) {
                    virginSpeak(say(R.string.dj_voice_playcount_none, cur.title))
                    return@onUi
                }
                val gap = info.lastTs?.let {
                    val days = (System.currentTimeMillis() / 1000L - it) / 86400L
                    when {
                        days < 1L -> activity.getString(R.string.dj_voice_playcount_today)
                        days < 2L -> activity.getString(R.string.dj_voice_playcount_yesterday)
                        days < 30L -> activity.getString(R.string.dj_voice_playcount_days, days)
                        else -> activity.getString(R.string.dj_voice_playcount_months, days / 30)
                    }
                } ?: ""
                virginSpeak(say(R.string.dj_voice_playcount, cur.title, info.total, gap))
            }
        }
    }

    /** "Salva essa fila como playlist X" — grava a fila atual inteira no banco. */
    private fun virgSaveQueueAsPlaylist(norm: String) {
        val queue = Playback.queue
        if (queue.isEmpty()) {
            virginSpeak(say(R.string.dj_voice_queue_save_empty))
            return
        }
        val raw = DjCommander.queueSaveName(norm)
        if (raw.isNullOrBlank()) {
            virginSpeak(say(R.string.dj_voice_queue_save_ask))
            return
        }
        val name = raw.replaceFirstChar { it.titlecase() }
        ThreadPool.post {
            val ctx = activity.applicationContext
            val db = PlaylistDb.get(ctx)
            val key = DjCommander.norm(name)
            val existing = runCatching { db.playlists() }.getOrDefault(emptyList())
                .firstOrNull { DjCommander.norm(it.name) == key }
            val id = if (existing != null) {
                existing.id
            } else {
                runCatching { db.createPlaylist(name) }.getOrNull()
            }
            if (id == null) {
                ThreadPool.onUi { virginSpeak(say(R.string.dj_voice_dynq_none)) }
                return@post
            }
            val songs = queue.filter { !it.isVideo && !it.isPodcast && !it.isRadio && !it.isStream }
            runCatching { db.addAllMissingSongs(id, songs) }
            Telemetry.log(activity, "Virgin queue->playlist \"$name\" n=${songs.size}")
            ThreadPool.onUi {
                virginSpeak(
                    if (existing != null) say(R.string.dj_voice_queue_save_merged, existing.name, songs.size)
                    else say(R.string.dj_voice_queue_save_done, name, songs.size)
                )
            }
        }
    }

    private fun virgScene(scene: String?, norm: String = "") {
        if (scene == null) return
        val genres = when (scene) {
            DjCommander.SCENE_MALHAR -> listOf("eletronica", "dance", "rock", "funk", "pop rock")
            DjCommander.SCENE_ESTUDAR -> listOf("instrumental", "clasica", "classica", "mpb", "jazz", "bossa nova", "pop")
            DjCommander.SCENE_VIAJAR -> listOf("pop", "mpb", "samba", "forro", "rock", "axe", "sertanejo")
            DjCommander.SCENE_DIRIGIR -> listOf("eletronica", "dance", "rock", "pop rock", "pop", "funk")
            else -> emptyList()
        }
        val intensity = when (scene) {
            DjCommander.SCENE_ESTUDAR -> DjEngine.Intensity.CALM
            DjCommander.SCENE_MALHAR -> DjEngine.Intensity.WILD
            DjCommander.SCENE_DIRIGIR -> DjEngine.Intensity.WILD
            else -> DjEngine.Intensity.BALANCED
        }
        val labelRes = when (scene) {
            DjCommander.SCENE_MALHAR -> R.string.dj_scene_malhar
            DjCommander.SCENE_ESTUDAR -> R.string.dj_scene_estudar
            DjCommander.SCENE_VIAJAR -> R.string.dj_scene_viajar
            else -> R.string.dj_scene_dirigir
        }
        // "toca pra estudar com chuva" liga o ambiente de fundo junto da fila da cena.
        virgCurated(genres, intensity, labelRes, DjCommander.ambientModeOf(norm), "cena $scene")
    }

    /**
     * Rádio do humor: "virgi, tô nervoso" monta uma fila calmante; "tô pra baixo" uma pra
     * cima. Mesmo motor das cenas, mas guiado pelo estado de espírito.
     */
    private fun virgMoodState(mood: String?) {
        if (mood == null) return
        val genres = if (mood == "calm") {
            listOf("instrumental", "clasica", "classica", "mpb", "jazz", "bossa nova", "acustico", "chill")
        } else {
            listOf("eletronica", "dance", "rock", "funk", "pop rock", "pop")
        }
        val intensity = if (mood == "calm") DjEngine.Intensity.CALM else DjEngine.Intensity.WILD
        val labelRes = if (mood == "calm") R.string.dj_mood_calm else R.string.dj_mood_up
        virgCurated(genres, intensity, labelRes, null, "humor $mood")
    }

    /** Motor compartilhado das cenas/humor: fila curada por gênero + intensidade e fala. */
    private fun virgCurated(
        genres: List<String>,
        intensity: DjEngine.Intensity,
        labelRes: Int,
        ambientMode: String?,
        logTag: String
    ) {
        if (!Permissions.hasAccess(activity)) {
            Toast.makeText(activity, R.string.dj_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        val ctx = activity.applicationContext
        ThreadPool.post {
            val songs = Library.allSongs(ctx)
            if (songs.isEmpty()) {
                ThreadPool.onUi { virginSpeak(say(R.string.dj_empty)) }
                return@post
            }
            val favIds = runCatching { PlaylistDb.get(ctx).favorites().map { it.id }.toSet() }
                .getOrDefault(emptySet())
            val learn = DjLearn.learn(ctx)
            val pref = if (genres.isEmpty()) emptyList() else {
                songs.filter { s ->
                    genres.any { g -> DjCommander.matchesGenre(Library.genreOf(ctx, s.id), g) }
                }
            }
            val pool = if (pref.size >= 6) pref else songs
            val set = DjEngine.build(
                pool, favIds, DjEngine.Source.ALL, intensity, learn,
                maxSize = 20, exclude = DjSessionMemory.recentIds()
            )
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (set.isEmpty()) {
                    virginSpeak(say(R.string.dj_empty))
                    return@onUi
                }
                Telemetry.log(ctx, "Virgin $logTag n=${set.size} amb=${ambientMode ?: '-'}")
                if (ambientMode != null) Ambient.start(ambientMode, 0.7f)
                Playback.setShuffle(true)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                Playback.start(set, 0)
                host.syncMiniPlayer()
                val label = activity.getString(labelRes)
                if (ambientMode != null) {
                    virginSpeak(say(
                        R.string.dj_voice_scene_amb, label, ambientLabel(ambientMode), set.size
                    ))
                } else {
                    virginSpeak(say(R.string.dj_voice_scene, label, set.size))
                }
            }
        }
    }

    private fun virgWeekSummary() {
        ThreadPool.post {
            val songs = Library.allSongs(activity.applicationContext)
            val rew = DjLearn.rewind(activity.applicationContext, 7, 10)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (rew.plays == 0) {
                    virginSpeak(say(R.string.dj_voice_week_none))
                    return@onUi
                }
                val topId = rew.top.firstOrNull()?.first ?: -1L
                val top = songs.firstOrNull { it.id == topId }
                val topCount = rew.top.firstOrNull()?.second ?: 0
                val title = top?.title ?: activity.getString(R.string.dj_voice_week_unknown)
                val artist = top?.artist ?: ""
                virginSpeak(say(
                    R.string.dj_voice_week, rew.unique, rew.plays, title, artist, topCount, bestWeekday(rew.week)
                ))
            }
        }
    }

    /**
     * Resumo do fim de semana falado: "resumo do fim de semana" / "como foi meu fim de semana".
     * Soma o sábado + domingo mais recentes (se hoje for sábado/domingo, só o que já rolou).
     */
    private fun virgWeekendSummary() {
        ThreadPool.post {
            val songs = Library.allSongs(activity.applicationContext)
            val (fromSec, toSec) = lastWeekendRange()
            val res = DjLearn.rangeSummary(activity.applicationContext, fromSec, toSec, 1)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (res.plays == 0) {
                    virginSpeak(say(R.string.dj_voice_weekend_none))
                    return@onUi
                }
                val topId = res.top.firstOrNull()?.first ?: -1L
                val top = songs.firstOrNull { it.id == topId }
                val topCount = res.top.firstOrNull()?.second ?: 0
                virginSpeak(say(
                    R.string.dj_voice_weekend, res.unique, res.plays,
                    top?.title ?: activity.getString(R.string.dj_voice_week_unknown),
                    top?.artist ?: "", topCount
                ))
            }
        }
    }

    /** [início, fim) em segundos do último fim de semana (sábado 00:00 até domingo/agora). */
    private fun lastWeekendRange(): Pair<Long, Long> {
        val now = java.util.Calendar.getInstance()
        val nowSec = now.timeInMillis / 1000L
        val dow = now.get(java.util.Calendar.DAY_OF_WEEK) // 1=domingo .. 7=sábado
        val backToSat = when (dow) {
            java.util.Calendar.SATURDAY -> 0
            java.util.Calendar.SUNDAY -> 1
            java.util.Calendar.MONDAY -> 2
            java.util.Calendar.TUESDAY -> 3
            java.util.Calendar.WEDNESDAY -> 4
            java.util.Calendar.THURSDAY -> 5
            else -> 6 // sexta
        }
        val sat = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.DAY_OF_YEAR, -backToSat)
        }
        val fromSec = sat.timeInMillis / 1000L
        val toSec = if (dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY) {
            nowSec
        } else {
            fromSec + 2 * 86400L
        }
        return fromSec to toSec
    }

    /**
     * Replay anual falado: "resumo do ano" / "replay anual". Usa o [DjLearn.rewind] de 365
     * dias para montar o resumo — músicas distintas, toques, faixa campeã, artista do ano
     * (quem soma mais toques dentro do top 10) e mês mais forte.
     */
    private fun virgYearSummary() {
        ThreadPool.post {
            val songs = Library.allSongs(activity.applicationContext)
            val rew = DjLearn.rewind(activity.applicationContext, 365, 10)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (rew.plays == 0) {
                    virginSpeak(say(R.string.dj_voice_year_none))
                    return@onUi
                }
                val byId = songs.associateBy { it.id }
                val topId = rew.top.firstOrNull()?.first ?: -1L
                val top = byId[topId]
                val topCount = rew.top.firstOrNull()?.second ?: 0
                val title = top?.title ?: activity.getString(R.string.dj_voice_week_unknown)
                val artist = top?.artist ?: ""
                val artistTally = HashMap<String, Int>()
                rew.top.forEach { (id, n) ->
                    val a = byId[id]?.artist
                    if (!a.isNullOrBlank()) artistTally[a] = (artistTally[a] ?: 0) + n
                }
                val topArtist = artistTally.maxByOrNull { it.value }?.key ?: artist
                val bestMonth = rew.months.maxByOrNull { it.second }?.first
                virginSpeak(say(
                    R.string.dj_voice_year, rew.unique, rew.plays, title, artist, topCount,
                    topArtist, monthName(bestMonth)
                ))
            }
        }
    }

    /** "mm/aaaa" → nome do mês no idioma da Virgin (vazio se não der para ler). */
    private fun monthName(mmyyyy: String?): String {
        val mm = mmyyyy?.substringBefore("/")?.toIntOrNull() ?: return ""
        if (mm !in 1..12) return ""
        val locale = when (Settings.language(activity.applicationContext)) {
            Settings.LANG_EN -> java.util.Locale.ENGLISH
            Settings.LANG_ES -> java.util.Locale("es")
            else -> java.util.Locale("pt")
        }
        return runCatching { java.text.DateFormatSymbols(locale).months[mm - 1] }.getOrElse { "" }
    }

    private fun bestWeekday(week: IntArray): String {
        var best = 0
        for (i in week.indices) if (week[i] > week[best]) best = i
        val locale = when (Settings.language(activity.applicationContext)) {
            Settings.LANG_EN -> java.util.Locale.ENGLISH
            Settings.LANG_ES -> java.util.Locale("es")
            else -> java.util.Locale("pt")
        }
        return runCatching { java.text.DateFormatSymbols(locale).weekdays[best + 1] }.getOrElse { "" }
    }

    private fun virgAlarmSet(spec: DjCommander.AlarmSpec?) {
        if (spec == null) return
        if (Permissions.hasAccess(activity).not()) {
            Toast.makeText(activity, R.string.dj_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        val next = nextAlarmMs(spec.hour, spec.minute)
        val show = PendingIntent.getActivity(
            activity, 0,
            Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val op = PendingIntent.getActivity(
            activity, 1,
            Intent(activity, MainActivity::class.java)
                .setAction(ACTION_VIRGIN_ALARM)
                .putExtra(EXTRA_ALARM_AMBIENT, spec.ambient)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            (activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .setAlarmClock(AlarmManager.AlarmClockInfo(next, show), op)
        }
        Settings.setAlarm(activity.applicationContext, spec.hour, spec.minute, spec.ambient)
        spec.gentle?.let { Settings.setAlarmGentle(activity.applicationContext, it) }
        val with = spec.ambient?.let { " " + say(R.string.dj_voice_alarm_with, ambientLabel(it)) } ?: ""
        virginSpeak(say(R.string.dj_voice_alarm_set, sayClock(spec.hour, spec.minute), with))
    }

    private fun virgAlarmCancel() {
        runCatching {
            val op = PendingIntent.getActivity(
                activity, 1,
                Intent(activity, MainActivity::class.java).setAction(ACTION_VIRGIN_ALARM),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            (activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(op)
        }
        Settings.clearAlarm(activity.applicationContext)
        virginSpeak(say(R.string.dj_voice_alarm_canceled))
    }

    private fun nextAlarmMs(hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        var t = cal.timeInMillis
        if (t <= System.currentTimeMillis() + 60_000L) {
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            t = cal.timeInMillis
        }
        return t
    }

    private fun virgSleepTimer(minutes: Int?) {
        if (minutes == null) {
            virginSpeak(say(R.string.dj_voice_sleeptimer_ask))
            return
        }
        if (!Playback.isPlaying) {
            virginSpeak(say(R.string.dj_voice_sleeptimer_none))
            return
        }
        virginSpeak(say(R.string.dj_voice_sleeptimer_set, minutes))
        virginHandler.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            Playback.toggle()
            virginSpeak(say(R.string.dj_voice_sleeptimer_done))
        }, minutes * 60_000L)
    }

    /**
     * Item 159 · Cronômetro de treino — "virgi, cronometra 3 rounds de 1 min com chuva".
     *
     * Fala o round, conta os 3 últimos segundos e emenda o próximo. O ambiente (se pedido)
     * fica de fundo e é desligado no fim, no cancelamento ou quando chega um comando novo.
     * Tudo agendado no `virginHandler` (mesmo handler do sleep-timer): um comando novo limpa
     * a fila e encerra o treino, como já acontece com os outros timers falados.
     */
    private fun virgInterval(spec: DjCommander.IntervalSpec?) {
        if (spec == null) {
            virginSpeak(say(R.string.dj_voice_interval_ask))
            return
        }
        val amb = spec.ambient
        intervalActive = true
        intervalStartedAmbient = amb != null
        if (amb != null) Ambient.start(amb, 0.7f)
        val dur = if (spec.seconds % 60 == 0) {
            say(R.string.dj_voice_interval_minutes, spec.seconds / 60)
        } else {
            say(R.string.dj_voice_interval_seconds, spec.seconds)
        }
        Telemetry.log(
            activity,
            "Virgin interval rounds=${spec.rounds} sec=${spec.seconds} amb=${spec.ambient ?: '-'}"
        )
        // Intro + "Round 1" numa locução só: duas chamadas seguidas de `virginSpeak` cortam
        // a primeira (o TTS usa QUEUE_FLUSH).
        val intro = say(R.string.dj_voice_interval_start, spec.rounds, dur) + " " +
            say(R.string.dj_voice_interval_round, 1, spec.rounds)
        virginSpeak(intro)
        scheduleIntervalTicks(1, spec)
    }

    private fun scheduleIntervalTicks(round: Int, spec: DjCommander.IntervalSpec) {
        if (!intervalActive) return
        val secs = spec.seconds
        val last = round >= spec.rounds
        for (s in intArrayOf(3, 2, 1)) {
            if (secs <= s + 1) continue
            virginHandler.postDelayed({
                if (intervalActive) virginSpeak(say(R.string.dj_voice_interval_count, s))
            }, (secs - s) * 1000L)
        }
        virginHandler.postDelayed({
            if (!intervalActive || activity.isFinishing || activity.isDestroyed) return@postDelayed
            if (last) {
                stopInterval()
                virginSpeak(say(R.string.dj_voice_interval_done, spec.rounds))
            } else {
                virginSpeak(say(R.string.dj_voice_interval_round, round + 1, spec.rounds))
                scheduleIntervalTicks(round + 1, spec)
            }
        }, secs * 1000L)
    }

    private fun virgIntervalCancel() {
        if (!intervalActive) {
            virginSpeak(say(R.string.dj_voice_interval_none))
            return
        }
        stopInterval()
        virginHandler.removeCallbacksAndMessages(null)
        virginSpeak(say(R.string.dj_voice_interval_canceled))
    }

    /** Encerra o cronômetro e desliga o ambiente, se foi ele quem ligou. */
    private fun stopInterval() {
        intervalActive = false
        if (intervalStartedAmbient) {
            intervalStartedAmbient = false
            Ambient.stop()
        }
    }

    /**
     * Item 122 · Quiz falado — "virgi, quiz". A Virgin pergunta um número do seu histórico
     * (quantas vezes tocou a faixa top, o total, etc.) e você adivinha. Os números são
     * robustos no reconhecimento (ao contrário de acertar o nome de uma música); perto do
     * valor conta como "quase".
     */
    private fun virgQuiz() {
        val cur = Playback.currentSong
        ThreadPool.post {
            val ctx = activity.applicationContext
            val songs = Library.allSongs(ctx)
            val byId = songs.associateBy { it.id }
            val rew = DjLearn.rewind(ctx, Int.MAX_VALUE, 10)
            val qs = ArrayList<QuizQuestion>()
            rew.top.firstOrNull()?.let { (id, n) ->
                byId[id]?.let {
                    qs.add(QuizQuestion(say(R.string.dj_voice_quiz_q_song, it.title), n.toLong(), maxOf(2L, (n * 0.2).toLong())))
                }
            }
            if (rew.plays > 0) {
                qs.add(QuizQuestion(say(R.string.dj_voice_quiz_q_total), rew.plays.toLong(), maxOf(20L, (rew.plays * 0.15).toLong())))
            }
            if (rew.unique > 0) {
                qs.add(QuizQuestion(say(R.string.dj_voice_quiz_q_unique), rew.unique.toLong(), maxOf(20L, (rew.unique * 0.15).toLong())))
            }
            rew.top.getOrNull(1)?.let { (id, n) ->
                byId[id]?.let {
                    qs.add(QuizQuestion(say(R.string.dj_voice_quiz_q_song, it.title), n.toLong(), maxOf(2L, (n * 0.2).toLong())))
                }
            }
            if (cur != null) {
                val pc = DjLearn.playCount(ctx, DjLearn.typedKey(cur) ?: cur.id)
                if (pc != null && pc.total > 0) {
                    qs.add(QuizQuestion(say(R.string.dj_voice_quiz_q_current), pc.total.toLong(), maxOf(2L, (pc.total * 0.2).toLong())))
                }
            }
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (qs.isEmpty()) {
                    virginSpeak(say(R.string.dj_voice_quiz_none))
                    return@onUi
                }
                quizQuestions = qs.take(5)
                quizIndex = 0
                quizScore = 0
                val first = quizQuestions[0]
                pendingQuiz = first
                virginSpeak(say(R.string.dj_voice_quiz_ask) + " " + first.prompt)
            }
        }
    }

    private fun answerQuiz(q: QuizQuestion, text: String) {
        val norm = DjCommander.norm(text)
        val quit = listOf("para", "cancela", "cancelar", "sai", "sair", "chega", "desisto", "acabou", "stop", "encerra")
        if (quit.any { norm == it || norm.startsWith("$it ") || norm.endsWith(" $it") }) {
            pendingQuiz = null
            virginSpeak(say(R.string.dj_voice_quiz_cancel, quizScore, quizQuestions.size))
            return
        }
        val skips = listOf("nao sei", "passa", "pula", "proxima", "skip", "nao lembro", "nao faco ideia", "desisto")
        if (skips.any { norm.contains(it) }) {
            finishQuizRound(say(R.string.dj_voice_quiz_wrong, q.answer))
            return
        }
        val guess = DjCommander.spokenNumber(norm)
        if (guess == null) {
            virginSpeak(say(R.string.dj_voice_quiz_nonumber))
            return
        }
        val exact = guess.toLong() == q.answer
        val close = !exact && kotlin.math.abs(guess.toLong() - q.answer) <= q.tol
        if (exact) quizScore++
        val line = when {
            exact -> say(R.string.dj_voice_quiz_exact, q.answer)
            close -> say(R.string.dj_voice_quiz_close, q.answer)
            else -> say(R.string.dj_voice_quiz_wrong, q.answer)
        }
        finishQuizRound(line)
    }

    /** Fecha a rodada atual e emenda a próxima pergunta na mesma locução (evita o corte). */
    private fun finishQuizRound(line: String) {
        quizIndex++
        val next = quizQuestions.getOrNull(quizIndex)
        if (next == null) {
            pendingQuiz = null
            virginSpeak(line + " " + say(R.string.dj_voice_quiz_done, quizScore, quizQuestions.size))
        } else {
            pendingQuiz = next
            virginSpeak(line + " " + next.prompt)
        }
    }

    /**
     * Item 280 (voz) · Capítulos de podcast — "Virgin, próximo capítulo", "volta um capítulo",
     * "pula pro capítulo 3" ou "lista os capítulos". Reaproveita o parser do feed
     * ([Chapters.parse] sobre a descrição do episódio) e pula com `Playback.seekTo`, o mesmo
     * caminho da folha de capítulos da tela.
     */
    private fun virgChapter(query: DjCommander.ChapterQuery?) {
        val song = Playback.currentSong
        val epId = song?.podcastId
        if (song == null || epId == null) {
            virginSpeak(say(R.string.dj_voice_chapter_not))
            return
        }
        val mode = query?.mode ?: "next"
        val wanted = query?.index
        val pos = Playback.position
        val duration = song.durationMs
        val ctx = activity.applicationContext
        ThreadPool.post {
            val desc = runCatching { PodcastDb.get(ctx).episode(epId)?.description }
                .getOrNull().orEmpty()
            val chapters = Chapters.parse(desc)
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (chapters.isEmpty()) {
                    virginSpeak(say(R.string.chapters_empty))
                    return@onUi
                }
                val current = chapterIndexAt(chapters, pos, duration)
                when (mode) {
                    "list" -> {
                        val shown = minOf(chapters.size, 6)
                        val list = chapters.take(shown).joinToString("; ") {
                            "${Chapters.formatTime(it.startMs)} ${it.title}"
                        }
                        val extra = if (chapters.size > shown) " e mais ${chapters.size - shown}" else ""
                        virginSpeak(say(R.string.dj_voice_chapter_list, chapters.size, list + extra))
                    }
                    "go" -> {
                        val idx = ((wanted ?: 1) - 1).coerceIn(0, chapters.size - 1)
                        seekChapter(chapters, idx)
                    }
                    "prev" -> {
                        val idx = if (pos - chapters[current].startMs > CHAPTER_RESTART_MS) current else current - 1
                        if (idx < 0) virginSpeak(say(R.string.dj_voice_chapter_first))
                        else seekChapter(chapters, idx)
                    }
                    else -> {
                        val idx = current + 1
                        if (idx >= chapters.size) virginSpeak(say(R.string.dj_voice_chapter_last))
                        else seekChapter(chapters, idx)
                    }
                }
            }
        }
    }

    private fun chapterIndexAt(chapters: List<Chapter>, posMs: Long, durationMs: Long): Int {
        for (i in chapters.indices) {
            val start = chapters[i].startMs
            val end = chapters.getOrNull(i + 1)?.startMs
                ?: if (durationMs > 0L) durationMs else Long.MAX_VALUE
            if (posMs >= start && posMs < end) return i
        }
        return 0
    }

    private fun seekChapter(chapters: List<Chapter>, index: Int) {
        val chapter = chapters[index]
        Playback.seekTo(chapter.startMs)
        Telemetry.log(activity, "Virgin chapter -> ${index + 1} (${Chapters.formatTime(chapter.startMs)})")
        virginSpeak(say(R.string.dj_voice_chapter_go, index + 1, chapter.title))
    }

    /** Despertador disparou: abre a atividade, liga o ambiente e toca uma seleção. */
    fun onVirginAlarm(intent: Intent?) {
        if (intent == null || activity.isFinishing || activity.isDestroyed) return
        val ambient = intent.getStringExtra(EXTRA_ALARM_AMBIENT)
        val gentle = Settings.alarmGentle(activity)
        if (ambient != null) {
            Ambient.start(ambient, if (gentle) ALARM_AMBIENT_START else ALARM_AMBIENT_FULL)
        }
        if (gentle && ambient != null) rampAlarmAmbient()
        val cal = java.util.Calendar.getInstance()
        val time = sayClock(
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)
        )
        val with = ambient?.let { " " + say(R.string.dj_voice_alarm_with, ambientLabel(it)) } ?: ""
        virginSpeak(say(R.string.dj_voice_alarm_hello, time, with))
        virgAlarmMix(gentle)
    }

    /** Item 170 — sobe o volume do ambiente aos poucos (o do player sobe pelo motor). */
    private fun rampAlarmAmbient() {
        alarmAmbientRamp?.let { virginHandler.removeCallbacks(it) }
        val steps = (ALARM_RAMP_MS / 1000L).toInt().coerceAtLeast(2)
        var step = 0
        val r = object : Runnable {
            override fun run() {
                step++
                val vol = ALARM_AMBIENT_START +
                    (ALARM_AMBIENT_FULL - ALARM_AMBIENT_START) * (step.toFloat() / steps)
                Ambient.setVolume(vol.coerceIn(0f, ALARM_AMBIENT_FULL))
                if (step < steps) virginHandler.postDelayed(this, 1000L) else alarmAmbientRamp = null
            }
        }
        alarmAmbientRamp = r
        virginHandler.postDelayed(r, 1000L)
    }

    private fun ambientLabel(mode: String): String = activity.getString(when (mode) {
        Ambient.NIGHT -> R.string.ambient_night
        Ambient.OCEAN -> R.string.ambient_ocean
        Ambient.WIND -> R.string.ambient_wind
        Ambient.FOREST -> R.string.ambient_forest
        Ambient.WHITE -> R.string.ambient_noise
        Ambient.PINK -> R.string.ambient_pink
        Ambient.BROWN -> R.string.ambient_brown
        Ambient.STORM -> R.string.ambient_storm
        Ambient.FIRE -> R.string.ambient_fire
        Ambient.RIVER -> R.string.ambient_river
        Ambient.BIRDS -> R.string.ambient_birds
        else -> R.string.ambient_rain
    })

    /** Hora falada por extenso no idioma da pergunta (melhor pro TTS que "7:00"). */
    private fun sayClock(hour: Int, minute: Int): String {
        val mm = "%02d".format(minute)
        return when (Settings.language(activity.applicationContext)) {
            "en" -> if (minute == 0) "$hour:00" else "${hour}:$mm"
            "es" -> if (minute == 0) "${hour}:00" else "${hour}:$mm"
            else -> if (minute == 0) "$hour horas" else "$hour horas e $minute"
        }
    }

    private fun virgAlarmMix(gentle: Boolean = false) {
        if (!Permissions.hasAccess(activity)) return
        ThreadPool.post {
            val ctx = activity.applicationContext
            val songs = Library.allSongs(ctx)
            if (songs.isEmpty()) return@post
            val favIds = runCatching { PlaylistDb.get(ctx).favorites().map { it.id }.toSet() }
                .getOrDefault(emptySet())
            val learn = DjLearn.learn(ctx)
            val pool = if (favIds.size >= 3) songs.filter { it.id in favIds } else songs
            val set = DjEngine.build(
                pool, favIds, DjEngine.Source.ALL, DjEngine.Intensity.BALANCED, learn,
                maxSize = 20, exclude = DjSessionMemory.recentIds()
            )
            if (set.isEmpty()) return@post
            ThreadPool.onUi { playWhenBound(set, 0, gentle) }
        }
    }

    /** Aguarda o motor ficar disponível (o app acabou de abrir pelo alarme). */
    private fun playWhenBound(set: List<Song>, tries: Int, gentle: Boolean = false) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (Playback.isReady) {
            Playback.setShuffle(true)
            Playback.setRepeatAll(true)
            Playback.setSleepMix(false)
            Playback.start(set, 0)
            if (gentle) Playback.rampVolumeUp(ALARM_RAMP_MS.toInt())
        } else if (tries < 8) {
            virginHandler.postDelayed({ playWhenBound(set, tries + 1, gentle) }, 250)
        }
    }

    private fun toggleVisualizer() {
        val on = !Settings.visualizerOn(activity)
        Settings.setVisualizerOn(activity, on)
        // Sem isto a Virgin só redesenha a tela: a captura de áudio continuaria ligada (ou
        // desligada) até a próxima faixa, porque o `Visualizer` vive na sessão do player.
        Playback.refreshFx()
        host.refreshPlayerVisuals()
        virginSpeak(say(
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
        virginSpeak(say(
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
        virginSpeak(say(R.string.dj_voice_scan), hold = true)
        GalleryScanner.scan(activity.applicationContext) { songs, videos ->
            virginScanInFlight = false
            if (activity.isFinishing || activity.isDestroyed) {
                resumeVirginSpeech()
                return@scan
            }
            virginSpeak(say(R.string.dj_voice_scan_result, songs, videos))
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
        virginSpeak(say(R.string.dj_voice_dup_search), hold = true)
        ThreadPool.post {
            val dup = VirginMedia.findDuplicates(activity.applicationContext)
            ThreadPool.onUi {
                virginScanInFlight = false
                if (activity.isFinishing || activity.isDestroyed) {
                    resumeVirginSpeech()
                    return@onUi
                }
                if (dup.copies == 0) {
                    virginSpeak(say(R.string.dj_voice_dup_none))
                    return@onUi
                }
                pendingDuplicateSongs = dup.songsCopies
                pendingDuplicateVideos = dup.videoCopiesList
                virginSpeak(say(R.string.dj_voice_dup_found, dup.pairs, dup.copies))
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
                        virginSpeak(say(R.string.dj_voice_dup_failed))
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
        alreadyDeleted: Boolean,
        unplayed: Boolean = false
    ) {
        ThreadPool.post {
            val deleted = if (alreadyDeleted) true else
                VirginMedia.deleteDuplicates(activity.applicationContext, songsCopies, videoCopiesList)
            // A limpeza das playlists é query de Room, então fica **aqui**, dentro do `post`, e
            // não no `onUi`. Ela estava no `onUi` embrulhada em `runCatching`: o Room lançava
            // IllegalStateException, o `runCatching` engolia, e o resultado era silencioso — a
            // música sumia do aparelho mas continuava em todas as playlists, invisível e
            // impossível de diagnosticar sem o log.
            if (deleted) {
                runCatching {
                    songsCopies.forEach { VirginMedia.removeFromPlaylists(activity.applicationContext, it) }
                }.onFailure {
                    CrashLogger.writeLog(activity.applicationContext, "VIRGIN: falha ao limpar playlists dos duplicados -> $it")
                }
            }
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (deleted) {
                    if (Playback.currentSong?.let { c -> songsCopies.any { it.id == c.id } } == true) {
                        Playback.next()
                    }
                    val n = songsCopies.size + videoCopiesList.size
                    virginSpeak(
                        say(if (unplayed) R.string.dj_voice_unplayed_done else R.string.dj_voice_dup_done, n)
                    )
                } else {
                    virginSpeak(say(if (unplayed) R.string.dj_voice_delete_failed else R.string.dj_voice_dup_failed))
                }
            }
        }
    }

    /**
     * Item 142 · Limpeza guiada por voz — "apaga as músicas que eu nunca ouvi".
     *
     * Cruza a biblioteca com o histórico (`DjLearn.lastPlayedMap`): o que nunca apareceu no
     * `play_log` está intocado. Primeiro fala quantas são e pede o "pode apagar"; o apagar de
     * verdade reusa o caminho das duplicatas (delete do MediaStore com a caixa do sistema no
     * Android 11+), então a confirmação dupla é de propósito.
     */
    private fun virginCleanUnplayed() {
        if (virginScanInFlight) return
        virginScanInFlight = true
        virginSpeak(say(R.string.dj_voice_unplayed_search), hold = true)
        ThreadPool.post {
            val ctx = activity.applicationContext
            val played = DjLearn.lastPlayedMap(ctx)
            val never = runCatching { Library.allSongs(ctx).filter { it.id !in played } }
                .getOrDefault(emptyList())
            ThreadPool.onUi {
                virginScanInFlight = false
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (never.isEmpty()) {
                    virginSpeak(say(R.string.dj_voice_unplayed_none))
                    return@onUi
                }
                pendingUnplayedSongs = never
                virginHandler.removeCallbacksAndMessages(null)
                virginHandler.postDelayed({ pendingUnplayedSongs = emptyList() }, 15000)
                Telemetry.log(activity, "Virgin never_played count=${never.size}")
                virginSpeak(say(R.string.dj_voice_unplayed_confirm, never.size))
            }
        }
    }

    private fun deleteVirginUnplayed(songs: List<Song>) {
        if (songs.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            val uris = songs.map {
                android.content.ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id
                )
            }
            val sender = runCatching {
                MediaStore.createDeleteRequest(activity.contentResolver, uris)
            }.getOrNull()
            if (sender == null) {
                virginSpeak(say(R.string.dj_voice_delete_failed))
                return
            }
            pendingDuplicateSongs = songs
            pendingDuplicateVideos = emptyList()
            pendingBatchUnplayed = true
            launcher.duplicates.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            completeVirginDuplicates(songs, emptyList(), alreadyDeleted = false, unplayed = true)
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
            virginSpeak(say(R.string.dj_voice_pen_search), hold = true)
            launcher.tree.launch(null)
            return
        }
        scanPendriveTree(runCatching { Uri.parse(savedTree) }.getOrNull() ?: return)
    }

    private fun scanPendriveTree(rootUri: Uri) {
        if (virginScanInFlight) return
        virginScanInFlight = true
        virginSpeak(say(R.string.dj_voice_pen_search), hold = true)
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
                        virginSpeak(say(R.string.dj_voice_pen_none))
                    }
                    scan.newFiles.isEmpty() -> {
                        virginScanInFlight = false
                        virginSpeak(say(R.string.dj_voice_pen_all_dups, scan.files.size))
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
                    virginSpeak(say(R.string.dj_voice_pen_copy_failed))
                    return@onUi
                }
                if (pendriveDuplicatesSkipped > 0) {
                    virginSpeak(say(
                        R.string.dj_voice_pen_copy_done_dups,
                        pendriveTotalFound, pendriveDuplicatesSkipped, copied
                    ))
                } else {
                    virginSpeak(say(R.string.dj_voice_pen_copy_done, copied))
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
                virginSpeak(say(R.string.dj_voice_delete_failed))
                return
            }
            pendingDelete = song
            launcher.delete.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            completeVirginDelete(song, alreadyDeleted = false)
        }
    }

    private fun completeVirginDelete(song: Song, alreadyDeleted: Boolean) {
        val key = QueueKey.encode(song)
        ThreadPool.post {
            val deleted = if (alreadyDeleted) true else VirginMedia.deleteSong(activity.applicationContext, song)
            // Fora do `onUi` pelo mesmo motivo dos duplicados: `removeFromPlaylists` é query de
            // Room. Aqui nem havia `runCatching`, então apagar uma faixa pela Virgin derrubava o
            // app — e o usuário via o sumiço da música como se o app tivesse crashado.
            if (deleted) {
                runCatching { VirginMedia.removeFromPlaylists(activity.applicationContext, song) }
                    .onFailure {
                        CrashLogger.writeLog(activity.applicationContext, "VIRGIN: falha ao limpar playlist da faixa apagada -> $it")
                    }
            }
            ThreadPool.onUi {
                if (activity.isFinishing || activity.isDestroyed) return@onUi
                if (deleted) {
                    if (QueueKey.sameType(Playback.currentKey, key)) Playback.next()
                    virginSpeak(say(R.string.dj_voice_delete_done, song.title))
                } else {
                    virginSpeak(say(R.string.dj_voice_delete_failed))
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
        virginSpeak(say(R.string.dj_voice_recognize))
        DjRecognizer.recognize(activity.applicationContext) { result, error ->
            if (activity.isFinishing || activity.isDestroyed) return@recognize
            virginRecognizing = false
            host.syncVirginIcon()
            when {
                error == "no_token" -> {
                    virginSpeak(say(R.string.dj_voice_recognize_configured))
                    Toast.makeText(activity, R.string.dj_voice_recognize_configured, Toast.LENGTH_LONG).show()
                }
                error != null -> virginSpeak(say(R.string.dj_voice_recognize_error))
                result == null -> virginSpeak(say(R.string.dj_voice_recognize_none))
                else -> {
                    virginSpeak(say(
                        R.string.dj_voice_recognize_recognized, result.title, result.artist
                    ))
                    val cur = Playback.currentSong
                    if (cur != null) {
                        MusicEditor.renameDetected(
                            activity.applicationContext, cur,
                            result.title, result.artist, result.albumName
                        ) { ok ->
                            if (ok && !activity.isDestroyed) {
                                virginSpeak(say(
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
        Hotword.startIfNeeded(activity)
    }

    fun destroy() {
        HotwordBridge.unbind(this)
        chainHandler.removeCallbacksAndMessages(null)
        virginListener?.destroy()
        virginListener = null
        welcomeVoice?.shutdown()
        welcomeVoice = null
        virginVoice?.shutdown()
        virginVoice = null
    }
}