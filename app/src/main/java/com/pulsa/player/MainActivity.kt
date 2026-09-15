package com.pulsa.player

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import androidx.documentfile.provider.DocumentFile
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.imageview.ShapeableImageView
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.model.Album
import com.pulsa.player.model.Artist
import com.pulsa.player.model.Playlist
import com.pulsa.player.model.Song
import com.pulsa.player.model.Video
import com.pulsa.player.playback.Playback
import com.pulsa.player.playback.PlaybackService
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.ui.AlbumsTabFragment
import com.pulsa.player.ui.ArtistTimelineFragment
import com.pulsa.player.ui.ArtistsTabFragment
import com.pulsa.player.ui.FavoritesTabFragment
import com.pulsa.player.ui.LibraryDetailFragment
import com.pulsa.player.ui.NowPlayingFragment
import com.pulsa.player.ui.PlaylistDetailFragment
import com.pulsa.player.ui.PlaylistDialog
import com.pulsa.player.ui.PlaylistsTabFragment
import com.pulsa.player.ui.SongsTabFragment
import com.pulsa.player.ui.TrendsFragment
import com.pulsa.player.ui.VideosTabFragment
import com.pulsa.player.util.Account
import com.pulsa.player.util.AnimatedBackground
import com.pulsa.player.util.Changelog
import com.pulsa.player.util.CrashLogger
import com.pulsa.player.util.DjCommandListener
import com.pulsa.player.util.DjCommander
import com.pulsa.player.util.DjEngine
import com.pulsa.player.util.DjFacts
import com.pulsa.player.util.DjLearn
import com.pulsa.player.util.DjRecognizer
import com.pulsa.player.util.DjVoice
import com.pulsa.player.util.GalleryScanner
import com.pulsa.player.util.MotionControls
import com.pulsa.player.util.MusicEditor
import com.pulsa.player.util.Permissions
import com.pulsa.player.util.Profile
import com.pulsa.player.util.Settings
import com.pulsa.player.util.Telemetry
import com.pulsa.player.util.ThreadPool
import com.pulsa.player.util.UpdateChecker

class MainActivity : AppCompatActivity(), Playback.Listener {

    companion object {
        private const val KEY_TAG = "tab"
        private const val REQ_VIRGIN_MIC = 2001
        private const val REQ_VIRGIN_STORAGE = 2002
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var miniPlayer: View
    private lateinit var miniArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: ImageView
    private lateinit var miniShuffle: ImageView
    private lateinit var miniRepeat: ImageView
    private lateinit var miniLike: ImageView
    private lateinit var miniVirgin: ImageView
    private var currentTag = SongsTabFragment::class.java.simpleName
    private var bound = false
    private var serviceBound = false
    private var appliedAccent: String = Settings.ACCENT_PURPLE
    private var avatarView: ShapeableImageView? = null
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
    private var pendingPendriveFiles: List<PendriveFile>? = null
    private var pendriveTotalFound = 0
    private var pendriveDuplicatesSkipped = 0
    private var pendingVirginAction: (() -> Unit)? = null
    private var virginSpeechPaused = false
    private var virginScanInFlight = false
    private val virginHandler = Handler(Looper.getMainLooper())
    private val RESUME_LISTENER_DELAY_MS = 800L
    private val resumeListenerRunnable = Runnable { doResumeVirginListener() }
    private var virginLastSpeechEndMs = 0L

    private val mainViewsReady: Boolean
        get() = ::toolbar.isInitialized && ::bottomNav.isInitialized &&
            ::miniPlayer.isInitialized && ::miniArt.isInitialized &&
            ::miniTitle.isInitialized && ::miniArtist.isInitialized && ::miniPlay.isInitialized &&
            ::miniShuffle.isInitialized && ::miniRepeat.isInitialized && ::miniLike.isInitialized &&
            ::miniVirgin.isInitialized

    private val writeRequest =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            MusicEditor.onWriteRequestResult(result.resultCode == RESULT_OK)
        }

    private val virginDeleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val song = pendingDelete
            pendingDelete = null
            virginHandler.removeCallbacksAndMessages(null)
            if (result.resultCode == RESULT_OK && song != null) {
                completeVirginDelete(song, alreadyDeleted = true)
            } else if (song != null) {
                virginSpeak(getString(R.string.dj_voice_delete_cancel))
            }
        }

    private val virginDuplicatesLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val songs = pendingDuplicateSongs
            val videos = pendingDuplicateVideos
            pendingDuplicateSongs = emptyList()
            pendingDuplicateVideos = emptyList()
            virginHandler.removeCallbacksAndMessages(null)
            if (result.resultCode == RESULT_OK && (songs.isNotEmpty() || videos.isNotEmpty())) {
                completeVirginDuplicates(songs, videos, alreadyDeleted = true)
            } else if (songs.isNotEmpty() || videos.isNotEmpty()) {
                virginSpeak(getString(R.string.dj_voice_dup_failed))
            }
        }

    private val pendriveTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                if (isFinishing || isDestroyed) return@registerForActivityResult
                pendingPendriveFiles = null
                virginScanInFlight = false
                resumeVirginSpeech()
                virginSpeak(getString(R.string.dj_voice_pen_none))
                return@registerForActivityResult
            }
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            Settings.setPendriveTreeUri(this, uri.toString())
            scanPendriveTree(uri)
        }

    fun launchWriteRequest(intentSender: android.content.IntentSender?) {
        if (intentSender != null) {
            writeRequest.launch(
                androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
            )
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Playback.service = (service as PlaybackService.LocalBinder).service
            bound = true
            syncMiniPlayer()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Playback.service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Telemetry.log(this, "MAIN onCreate ENTRADA")
        if (!Account.loggedIn(this)) {
            super.onCreate(savedInstanceState)
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return
        }
        try {
            Telemetry.log(this, "MAIN logado, createMain inicio")
            createMain(savedInstanceState)
            CrashLogger.writeLog(this, "MARK ok: onCreate completou")
            Telemetry.log(this, "MAIN createMain FIM OK")
        } catch (t: Throwable) {
            CrashLogger.writeLog(this, "EXCEPTION em onCreate:\n" + stackTrace(t))
            Telemetry.log(this, "MAIN createMain EXCEPTION: " + t)
            showCrashFallback(t)
        }
    }

    private fun stackTrace(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }

    private fun showCrashFallback(t: Throwable) {
        try {
            val tv = TextView(this)
            tv.setTextSize(12f)
            tv.setPadding(32, 32, 32, 32)
            tv.text = "Pulsa: ocorreu um erro na tela inicial.\n\n" + t + "\n\n" + stackTrace(t)
            setContentView(tv)
        } catch (ignored: Throwable) {
        }
    }

    private fun initWelcomeVoice(savedInstanceState: Bundle?) {
        if (savedInstanceState != null || !Settings.djVoice(this)) return
        welcomeVoice = DjVoice(this, Settings.languageTag(Settings.language(this))).also { vm ->
            vm.init { ready ->
                if (ready && !isDestroyed) {
                    val nick = Profile.nick(this).trim()
                    val msg = if (nick.isEmpty()) {
                        getString(R.string.dj_voice_hello)
                    } else {
                        getString(R.string.dj_voice_welcome, nick)
                    }
                    vm.speak(msg)
                }
            }
        }
    }

    private fun createMain(savedInstanceState: Bundle?) {
        appliedAccent = Settings.accent(this)
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        Telemetry.log(this, "createMain: super.onCreate ok")
        setContentView(R.layout.activity_main)
        Telemetry.log(this, "createMain: setContentView ok")
        initWelcomeVoice(savedInstanceState)

        AnimatedBackground.apply(this)

        avatarView = findViewById(R.id.profile_avatar)
        avatarView?.setOnClickListener {
            startActivity(Intent(this, PerfilActivity::class.java))
        }
        renderAvatar()

        toolbar = findViewById(R.id.main_toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> {
                    startActivity(Intent(this, SearchActivity::class.java))
                    true
                }
                R.id.action_sort -> {
                    val on = !Settings.sortAlphabetical(this)
                    Settings.setSortAlphabetical(this, on)
                    toolbar.menu.findItem(R.id.action_sort)?.icon = ContextCompat.getDrawable(
                        this,
                        if (on) R.drawable.ic_sort_alpha else R.drawable.ic_sort
                    )
                    (topFragment() as? SongsTabFragment)?.load()
                    true
                }
                R.id.action_add -> {
                    PlaylistDialog.promptNew(this) { refreshPlaylists() }
                    true
                }
                R.id.action_playlists -> {
                    openTab(PlaylistsTabFragment::class.java.simpleName, PlaylistsTabFragment())
                    true
                }
                R.id.action_radio -> {
                    startActivity(Intent(this, RadioActivity::class.java))
                    true
                }
                R.id.action_dj -> {
                    startActivity(Intent(this, DjActivity::class.java))
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_trends -> {
                    pushDetail(TrendsFragment(), "trends")
                    true
                }
                else -> false
            }
        }
        toolbar.setNavigationOnClickListener {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            }
        }

        bottomNav = findViewById(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_songs -> {
                    openTab(SongsTabFragment::class.java.simpleName, SongsTabFragment())
                    true
                }
                R.id.nav_albums -> {
                    openTab(AlbumsTabFragment::class.java.simpleName, AlbumsTabFragment())
                    true
                }
                R.id.nav_artists -> {
                    openTab(ArtistsTabFragment::class.java.simpleName, ArtistsTabFragment())
                    true
                }
                R.id.nav_favorites -> {
                    openTab(FavoritesTabFragment::class.java.simpleName, FavoritesTabFragment())
                    true
                }
                R.id.nav_videos -> {
                    openTab(VideosTabFragment::class.java.simpleName, VideosTabFragment())
                    true
                }
                else -> false
            }
        }

        miniPlayer = findViewById(R.id.mini_player)
        miniArt = findViewById(R.id.mini_art)
        miniTitle = findViewById(R.id.mini_title)
        miniArtist = findViewById(R.id.mini_artist)
        miniPlay = findViewById(R.id.mini_play)
        miniShuffle = findViewById(R.id.mini_shuffle)
        miniRepeat = findViewById(R.id.mini_repeat)
        miniLike = findViewById(R.id.mini_like)
        miniVirgin = findViewById(R.id.mini_virgin)
        miniVirgin.setOnClickListener { toggleVirgin() }
        miniVirgin.setOnLongClickListener {
            if (!virginRecognizing) startVirginRecognize()
            true
        }
        miniPlayer.setOnClickListener { openNowPlaying() }
        miniPlay.setOnClickListener { Playback.toggle() }
        miniShuffle.setOnClickListener {
            Playback.setShuffle(!Playback.shuffle)
            syncMiniPlayer()
        }
        miniRepeat.setOnClickListener {
            Playback.cycleRepeat()
            syncMiniPlayer()
        }
        miniLike.setOnClickListener { toggleMiniLike() }

        supportFragmentManager.addOnBackStackChangedListener { syncToolbar() }

        if (savedInstanceState == null) {
            showTab(SongsTabFragment::class.java.simpleName, SongsTabFragment())
        } else {
            currentTag = savedInstanceState.getString(KEY_TAG) ?: SongsTabFragment::class.java.simpleName
            bottomNav.selectedItemId = navIdFor(currentTag)
            showTab(currentTag, fragmentFor(currentTag))
        }

        val playbackIntent = Intent(this, PlaybackService::class.java)
        runCatching { applicationContext.startService(playbackIntent) }
        applicationContext.bindService(
            playbackIntent,
            connection,
            Context.BIND_AUTO_CREATE
        )
        serviceBound = true
        Telemetry.log(this, "createMain: bindService ok")

        if (!Permissions.hasAccess(this)) {
            Permissions.request(this)
        }
        syncToolbar()
        refreshSortIcon()
        Telemetry.log(this, "createMain: fim")
    }

    override fun onStart() {
        super.onStart()
        Telemetry.log(this, "MAIN onStart")
        CrashLogger.writeLog(this, "MARK: onStart")
        AnimatedBackground.apply(this)
        if (!Account.loggedIn(this)) return
        if (!mainViewsReady) return
        if (appliedAccent != Settings.accent(this)) {
            appliedAccent = Settings.accent(this)
            recreate()
        }
        Playback.listener = this
        syncMiniPlayer()
        refreshSortIcon()
        renderAvatar()
        seedAutoPlaylists()
        UpdateChecker.check(this)
        Changelog.checkUpdated(this)
        Changelog.check(this)
    }

    private fun seedAutoPlaylists() {
        val ctx = applicationContext
        ThreadPool.post {
            try {
                val db = PlaylistDb.get(ctx)
                db.ensureSpecialPlaylists(ctx)
                if (Permissions.hasAccess(ctx)) {
                    db.syncAutoSongs(ctx, Library.allSongs(ctx))
                }
            } catch (t: Throwable) {
            }
        }
    }

    override fun onStop() {
        if (Playback.listener === this) Playback.listener = null
        AnimatedBackground.stop()
        MotionControls.detach()
        if (virginOn) {
            virginOn = false
            syncVirginIcon()
        }
        virginListener?.stop()
        virginVoice?.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Telemetry.log(this, "MAIN onResume")
        CrashLogger.writeLog(this, "MARK: onResume")
        if (!mainViewsReady) return
        syncToolbar()
        MotionControls.attachIfEnabled(
            this,
            onShake = {
                if (Playback.queue.isNotEmpty() && (Playback.service != null)) Playback.next()
            },
            onTilt = { dir ->
                val am = getSystemService(AudioManager::class.java)
                am?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
            }
        )
    }

    override fun onDestroy() {
        if (serviceBound) {
            serviceBound = false
            runCatching { applicationContext.unbindService(connection) }
        }
        virginListener?.destroy()
        virginListener = null
        welcomeVoice?.shutdown()
        welcomeVoice = null
        virginVoice?.shutdown()
        virginVoice = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_TAG, currentTag)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Permissions.REQUEST_AUDIO) {
            syncMiniPlayer()
            syncToolbar()
            return
        }
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
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
                    virginSpeak(getString(R.string.dj_voice_scan_denied))
                }
            }
        }
    }

    private fun toggleVirgin() {
        if (virginOn) {
            stopVirgin(silent = false)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VIRGIN_MIC
            )
            return
        }
        startVirgin()
    }

    private fun startVirgin() {
        virginOn = true
        syncVirginIcon()
        virginListener?.destroy()
        virginListener = DjCommandListener(this) { handleVirginCommand(it) }
        virginListener?.start()
        val cur = Playback.currentSong
        val msg = if (cur != null) {
            getString(R.string.dj_voice_track, cur.artist, cur.title)
        } else {
            getString(R.string.dj_voice_hello)
        }
        virginSpeak(msg)
    }

    private fun stopVirgin(silent: Boolean) {
        virginOn = false
        virginHandler.removeCallbacks(resumeListenerRunnable)
        virginListener?.stop()
        syncVirginIcon()
        if (!silent) virginSpeak(getString(R.string.dj_voice_goodbye))
        virginVoice?.stop()
    }

    private fun syncVirginIcon() {
        if (::miniVirgin.isInitialized) {
            val color = if (virginOn || virginRecognizing) {
                ContextCompat.getColor(this, R.color.primary)
            } else {
                ContextCompat.getColor(this, R.color.text_secondary)
            }
            miniVirgin.setColorFilter(color)
        }
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
        if (virginOn && !isFinishing && !isDestroyed &&
            virginVoice?.isSpeaking != true
        ) {
            // Espera a ressonância/eco do alto-falante acabar antes de religar o microfone,
            // para a voz da própria Virgin não ser ouvida como um comando (loop).
            virginHandler.postDelayed(resumeListenerRunnable, RESUME_LISTENER_DELAY_MS)
        }
    }

    private fun doResumeVirginListener() {
        if (isFinishing || isDestroyed || !virginOn || virginSpeechPaused) return
        virginLastSpeechEndMs = SystemClock.elapsedRealtime()
        virginListener?.start()
    }

    private fun announceRadioSong(song: Song) {
        if (isFinishing || isDestroyed || !Settings.djRadio(this) || !Settings.djVoice(this)) return
        val newId = song.id
        if (radioLastId == newId) return
        radioLastId = newId
        val track = getString(
            R.string.dj_voice_track, song.artist, song.title
        )
        val leading = DjFacts.leadIn(Settings.djIntensity(this))
        if (!DjFacts.curiosityDue()) {
            virginSpeak(track)
            return
        }
        val fact = DjFacts.curiosityFor(song.artist ?: "")
        if (fact != null) {
            DjFacts.markCuriositySpoken()
            virginSpeak("$leading $fact $track")
            return
        }
        virginSpeak(track)
        val songId = song.id
        DjFacts.fetchRemoteCuriosity(this, song.artist ?: "") { remote ->
            if (remote == null || Playback.currentSong?.id != songId) return@fetchRemoteCuriosity
            if (!DjFacts.curiosityDue()) return@fetchRemoteCuriosity
            DjFacts.markCuriositySpoken()
            virginSpeak("$leading $remote")
        }
    }

    private fun virginSpeak(text: String, hold: Boolean = false) {
        if (text.isBlank() || isFinishing || isDestroyed) return
        pauseVirginSpeech()
        val voice = virginVoice ?: DjVoice(this, Settings.languageTag(Settings.language(this))).also {
            virginVoice = it
        }
        voice.init { ready ->
            if (!ready || isDestroyed) {
                if (!hold) resumeVirginSpeech()
                return@init
            }
            voice.speak(text) {
                ThreadPool.onUi {
                    if (!hold) resumeVirginSpeech()
                }
            }
        }
    }

    private fun findVirginArtist(query: String?): String? {
        val q = DjCommander.norm(query.orEmpty()).trim()
        if (q.isEmpty()) return null
        val names = runCatching {
            Library.allSongs(this).map { it.artist }.toSet()
        }.getOrDefault(emptySet())
        var best: String? = null
        var bestScore = -1
        for (name in names) {
            val n = DjCommander.norm(name)
            if (n.isEmpty()) continue
            val score = when {
                n == q -> 1000
                n.contains(q) -> 100 + q.length
                q.contains(n) && q.length >= n.length -> 50 + n.length
                else -> -1
            }
            if (score > bestScore) {
                bestScore = score
                best = name
            }
        }
        return best
    }

    private fun virgSleepMix() {
        ThreadPool.post {
            val songs = Library.allSongs(applicationContext)
            if (songs.isEmpty()) {
                ThreadPool.onUi { virginSpeak(getString(R.string.dj_voice_only_none, "")) }
                return@post
            }
            val favIds = runCatching {
                PlaylistDb.get(applicationContext).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val learn = DjLearn.learn(applicationContext)
            val set = DjEngine.build(
                songs, favIds, DjEngine.Source.ALL, DjEngine.Intensity.CALM, learn,
                maxSize = 8
            )
            ThreadPool.onUi {
                if (set.isEmpty()) {
                    virginSpeak(getString(R.string.dj_voice_only_none, ""))
                    return@onUi
                }
                Telemetry.log(this, "Virgin sleep n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.start(set, 0)
                virginSpeak(getString(R.string.dj_voice_sleep, set.size))
            }
        }
    }

    private fun virgArtistOnly(query: String?) {
        val artist = findVirginArtist(query)
        if (artist == null) {
            virginSpeak(getString(R.string.dj_voice_only_none, query ?: ""))
            return
        }
        ThreadPool.post {
            val songs = Library.songsByArtist(applicationContext, artist)
            ThreadPool.onUi {
                if (songs.isEmpty()) {
                    virginSpeak(getString(R.string.dj_voice_only_none, artist))
                    return@onUi
                }
                Telemetry.log(this, "Virgin only artist=$artist n=${songs.size}")
                Playback.setShuffle(true)
                Playback.setRepeatAll(true)
                Playback.start(songs.shuffled(), 0)
                virginSpeak(getString(R.string.dj_voice_only, artist, songs.size))
            }
        }
    }

    private fun virgMixWithArtist(query: String?) {
        val artist = findVirginArtist(query)
        if (artist == null) {
            virginSpeak(getString(R.string.dj_voice_mixwith_none, query ?: ""))
            return
        }
        ThreadPool.post {
            val songs = Library.allSongs(applicationContext)
            if (songs.isEmpty()) {
                ThreadPool.onUi { virginSpeak(getString(R.string.dj_voice_only_none, "")) }
                return@post
            }
            val favIds = runCatching {
                PlaylistDb.get(applicationContext).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val learn = DjLearn.learn(applicationContext)
            val set = DjEngine.build(
                songs, favIds, DjEngine.Source.ALL, DjEngine.Intensity.BALANCED, learn,
                includeArtist = artist
            )
            ThreadPool.onUi {
                if (set.isEmpty()) {
                    virginSpeak(getString(R.string.dj_voice_only_none, ""))
                    return@onUi
                }
                Telemetry.log(this, "Virgin mixwith artist=$artist n=${set.size}")
                Playback.setShuffle(false)
                Playback.setRepeatAll(true)
                Playback.start(set, 0)
                virginSpeak(getString(R.string.dj_voice_mixwith, artist, set.size))
            }
        }
    }

    private fun handleVirginCommand(text: String) {
        if (text == "__unsupported__") {
            stopVirgin(silent = false)
            return
        }
        // Ignora o eco da própria voz da Virgin logo apos ela terminar de falar,
        // evitando o loop em que ela se escuta e reage.
        if (SystemClock.elapsedRealtime() - virginLastSpeechEndMs < 1500L) return
        ThreadPool.onUi { resolveVirginCommand(text) }
    }

    private fun resolveVirginCommand(text: String) {
        if (isFinishing || isDestroyed) return
        val norm = DjCommander.norm(text)
        val hasWake = DjCommander.hasWake(norm)
        val action = DjCommander.action(norm)
        if (action == null) {
            if (hasWake) virginSpeak(getString(R.string.dj_voice_unknown))
            return
        }
        if (action !in setOf("confirm", "cancel", "delete")) {
            pendingDelete = null
            virginHandler.removeCallbacksAndMessages(null)
        }
        when (action) {
            "mix" -> virginSpeak(getString(R.string.dj_voice_mix_main))
            "sleep" -> virgSleepMix()
            "repeat" -> {
                val on = !Playback.repeatOne
                Playback.setRepeatOne(on)
                virginSpeak(getString(
                    if (on) R.string.dj_voice_repeat_on else R.string.dj_voice_repeat_off
                ))
            }
            "only" -> virgArtistOnly(DjCommander.onlyArtist(norm))
            "mixwith" -> virgMixWithArtist(DjCommander.mixArtist(norm))
            "skip", "next", "dislike" -> {
                val cur = Playback.currentSong
                if (action == "dislike" && cur != null) {
                    DjLearn.recordDislike(applicationContext, cur.id)
                    virginSpeak(getString(R.string.dj_voice_dislike))
                } else if (action == "skip" && cur != null) {
                    DjLearn.recordSkip(applicationContext, cur.id)
                    virginSpeak(getString(R.string.dj_voice_skip))
                } else {
                    virginSpeak(getString(R.string.dj_voice_next))
                }
                if (Playback.queue.isNotEmpty()) Playback.next()
            }
            "prev" -> {
                virginSpeak(getString(R.string.dj_voice_prev))
                Playback.prev()
            }
            "pause" -> {
                if (Playback.isPlaying) {
                    Playback.toggle()
                    virginSpeak(getString(R.string.dj_voice_pause))
                }
            }
            "play" -> {
                if (!Playback.isPlaying && Playback.queue.isNotEmpty()) {
                    Playback.toggle()
                    virginSpeak(getString(R.string.dj_voice_play))
                }
            }
            "fav" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    val db = PlaylistDb.get(applicationContext)
                    val nextValue = !db.isFavorite(cur.id)
                    db.setFavorite(cur, nextValue)
                    if (nextValue) DjLearn.recordLiked(applicationContext, cur.id)
                    syncMiniPlayer()
                    virginSpeak(getString(
                        if (nextValue) R.string.dj_voice_learn_like else R.string.dj_voice_fav
                    ))
                }
            }
            "info" -> {
                val cur = Playback.currentSong
                if (cur != null) {
                    virginSpeak(getString(R.string.dj_voice_track, cur.artist, cur.title))
                } else {
                    virginSpeak(getString(R.string.dj_voice_unknown))
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
                    virginSpeak(getString(R.string.dj_voice_delete_no_song))
                } else if (pendingDelete?.id == cur.id) {
                    pendingDelete = null
                    virginHandler.removeCallbacksAndMessages(null)
                    deleteVirginSong(cur)
                } else {
                    pendingDelete = cur
                    virginHandler.removeCallbacksAndMessages(null)
                    virginHandler.postDelayed({ pendingDelete = null }, 15000)
                    virginSpeak(getString(R.string.dj_voice_delete_confirm, cur.title))
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
                    virginSpeak(getString(R.string.dj_voice_pen_cancel))
                } else {
                    pendingDelete = null
                    virginHandler.removeCallbacksAndMessages(null)
                    virginSpeak(getString(R.string.dj_voice_delete_cancel))
                }
            }
            "thanks" -> virginSpeak(getString(R.string.dj_voice_thanks))
            "hello" -> virginSpeak(getString(R.string.dj_voice_hello))
            "visualizer" -> toggleVisualizer()
            "skin" -> cycleSkin()
            "karaoke" -> toggleKaraoke()
            "stems" -> virginSpeak(getString(R.string.dj_voice_stems_soon))
        }
    }

    private fun toggleVisualizer() {
        val on = !Settings.visualizerOn(this)
        Settings.setVisualizerOn(this, on)
        refreshPlayerVisuals()
        virginSpeak(getString(
            if (on) R.string.dj_voice_visualizer_on else R.string.dj_voice_visualizer_off
        ))
    }

    private fun cycleSkin() {
        val current = Settings.skin(this)
        val next = Settings.SKIN_ORDER[(Settings.SKIN_ORDER.indexOf(current).coerceAtLeast(0) + 1) % Settings.SKIN_ORDER.size]
        Settings.setSkin(this, next)
        refreshPlayerVisuals()
        virginSpeak(skinLabel(next))
    }

    private fun toggleKaraoke() {
        val on = !Settings.karaokeOn(this)
        Settings.setKaraokeOn(this, on)
        Playback.refreshFx()
        virginSpeak(getString(
            if (on) R.string.dj_voice_karaoke_on else R.string.dj_voice_karaoke_off
        ))
    }

    private fun skinLabel(skin: String): String = getString(when (skin) {
        Settings.SKIN_NEON -> R.string.skin_neon
        Settings.SKIN_AURORA -> R.string.skin_aurora
        Settings.SKIN_PARTICLES -> R.string.skin_particles
        else -> R.string.skin_off
    })

    private fun refreshPlayerVisuals() {
        (topFragment() as? NowPlayingFragment)?.refreshVisuals()
    }

    private fun virginScanLibrary() {
        if (Build.VERSION.SDK_INT >= 33) {
            val hasAudio = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val hasVideo = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasAudio || !hasVideo) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO),
                    REQ_VIRGIN_STORAGE
                )
                return
            }
        } else if (!Permissions.hasAccess(this)) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_VIRGIN_STORAGE
            )
            return
        }
        performVirginScan()
    }

    private fun performVirginScan() {
        if (virginScanInFlight) return
        virginScanInFlight = true
        virginSpeak(getString(R.string.dj_voice_scan), hold = true)
        GalleryScanner.scan(applicationContext) { songs, videos ->
            virginScanInFlight = false
            if (isFinishing || isDestroyed) {
                resumeVirginSpeech()
                return@scan
            }
            virginSpeak(getString(R.string.dj_voice_scan_result, songs, videos))
            Toast.makeText(
                this, getString(R.string.dj_voice_scan_result, songs, videos),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun virginFindDuplicates() {
        if (virginScanInFlight) return
        if (!hasMediaPermission()) {
            pendingVirginAction = { performVirginDuplicates() }
            ActivityCompat.requestPermissions(
                this, mediaPermissionNeeded(), REQ_VIRGIN_STORAGE
            )
            return
        }
        performVirginDuplicates()
    }

    private fun performVirginDuplicates() {
        if (virginScanInFlight) return
        virginScanInFlight = true
        virginSpeak(getString(R.string.dj_voice_dup_search), hold = true)
        ThreadPool.post {
            val all = Library.allSongs(applicationContext)
            val groups = LinkedHashMap<String, MutableList<Song>>()
            for (s in all) {
                if (s.title.isBlank()) continue
                val key = "${s.artist.lowercase()}|${s.title.lowercase()}"
                    .trim().replace("\\s+".toRegex(), " ")
                groups.getOrPut(key) { mutableListOf() }.add(s)
            }
            val songsCopies = mutableListOf<Song>()
            var songPairs = 0
            for (list in groups.values) {
                if (list.size > 1) {
                    songsCopies += list.drop(1)
                    songPairs++
                }
            }

            val videos = VideoLibrary.all(applicationContext)
            val videoGroups = LinkedHashMap<String, MutableList<Video>>()
            for (v in videos) {
                if (v.title.isBlank()) continue
                val key = v.title.lowercase().trim().replace("\\s+".toRegex(), " ")
                videoGroups.getOrPut(key) { mutableListOf() }.add(v)
            }
            val videoCopiesList = mutableListOf<Video>()
            var videoPairs = 0
            for (list in videoGroups.values) {
                if (list.size > 1) {
                    videoCopiesList += list.drop(1)
                    videoPairs++
                }
            }

            val pairs = songPairs + videoPairs
            val copies = songsCopies.size + videoCopiesList.size
            ThreadPool.onUi {
                virginScanInFlight = false
                if (isFinishing || isDestroyed) {
                    resumeVirginSpeech()
                    return@onUi
                }
                if (copies == 0) {
                    virginSpeak(getString(R.string.dj_voice_dup_none))
                    return@onUi
                }
                pendingDuplicateSongs = songsCopies
                pendingDuplicateVideos = videoCopiesList
                virginSpeak(getString(R.string.dj_voice_dup_found, pairs, copies))
                if (Build.VERSION.SDK_INT >= 30) {
                    val uris = buildList {
                        songsCopies.forEach {
                            add(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id))
                        }
                        videoCopiesList.forEach { add(VideoLibrary.contentUri(it.id)) }
                    }
                    val sender = runCatching {
                        MediaStore.createDeleteRequest(contentResolver, uris)
                    }.getOrNull()
                    if (sender == null) {
                        pendingDuplicateSongs = emptyList()
                        pendingDuplicateVideos = emptyList()
                        virginSpeak(getString(R.string.dj_voice_dup_failed))
                        return@onUi
                    }
                    virginDuplicatesLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                    )
                } else {
                    completeVirginDuplicates(songsCopies, videoCopiesList, alreadyDeleted = false)
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
            val deleted = if (alreadyDeleted) true else runCatching {
                songsCopies.all { s ->
                    contentResolver.delete(
                        ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id
                        ),
                        null, null
                    ) > 0
                } && videoCopiesList.all { v ->
                    contentResolver.delete(VideoLibrary.contentUri(v.id), null, null) > 0
                }
            }.getOrDefault(false)
            ThreadPool.onUi {
                if (isFinishing || isDestroyed) return@onUi
                if (deleted) {
                    runCatching {
                        val db = PlaylistDb.get(applicationContext)
                        songsCopies.forEach {
                            db.removeSongFromAll(it.id)
                            db.removeFavorite(it.id)
                        }
                    }
                    if (Playback.currentSong?.let { c -> songsCopies.any { it.id == c.id } } == true) {
                        Playback.next()
                    }
                    virginSpeak(getString(R.string.dj_voice_dup_done, songsCopies.size + videoCopiesList.size))
                } else {
                    virginSpeak(getString(R.string.dj_voice_dup_failed))
                }
            }
        }
    }

    private fun virginReadPendrive() {
        if (virginScanInFlight) return
        if (!hasMediaPermission()) {
            pendingVirginAction = { performVirginPendrive() }
            ActivityCompat.requestPermissions(
                this, mediaPermissionNeeded(), REQ_VIRGIN_STORAGE
            )
            return
        }
        performVirginPendrive()
    }

    private fun performVirginPendrive() {
        if (virginScanInFlight) return
        val savedTree = Settings.pendriveTreeUri(this)
        if (savedTree.isNullOrBlank()) {
            virginScanInFlight = true
            virginSpeak(getString(R.string.dj_voice_pen_search), hold = true)
            pendriveTreeLauncher.launch(null)
            return
        }
        scanPendriveTree(runCatching { Uri.parse(savedTree) }.getOrNull() ?: return)
    }

    private fun scanPendriveTree(rootUri: Uri) {
        if (virginScanInFlight) return
        virginScanInFlight = true
        virginSpeak(getString(R.string.dj_voice_pen_search), hold = true)
        ThreadPool.post {
            val extras = librarySongKeys()
            val exts = setOf(
                "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma", "3gp", "mid", "midi", "amr"
            )
            val files = mutableListOf<PendriveFile>()
            val seen = hashSetOf<String>()
            fun walk(doc: DocumentFile?, depth: Int) {
                if (doc == null || depth > 4 || files.size >= 4000) return
                val children = runCatching { doc.listFiles() }.getOrNull() ?: return
                for (f in children) {
                    if (files.size >= 4000) return
                    if (f.isDirectory) {
                        walk(f, depth + 1)
                    } else {
                        val name = f.name ?: continue
                        if (exts.contains(name.substringAfterLast('.', "").lowercase())) {
                            if (seen.add(f.uri.toString())) {
                                val (title, artist) = readAudioTags(f.uri)
                                files.add(PendriveFile(f.uri, name, title, artist))
                            }
                        }
                    }
                }
            }
            walk(DocumentFile.fromTreeUri(applicationContext, rootUri), 0)

            val newFiles = mutableListOf<PendriveFile>()
            var skippedDuplicates = 0
            val pendriveSeen = hashSetOf<String>()
            val pendriveKeys = hashSetOf<String>()
            for (f in files) {
                val fname = f.name.substringBeforeLast('.').lowercase().trim()
                if (fname.isEmpty()) {
                    skippedDuplicates++
                    continue
                }
                val title = f.title?.trim().orEmpty()
                val artist = f.artist?.trim().orEmpty()
                val tagKey = if (title.isNotEmpty()) "${artist.lowercase()}|${title.lowercase()}" else ""
                val isDup =
                    extras.names.contains(fname) ||
                        (tagKey.isNotEmpty() && (extras.tags.contains(tagKey) || !pendriveKeys.add(tagKey))) ||
                        !pendriveSeen.add(fname)
                if (isDup) {
                    skippedDuplicates++
                } else {
                    newFiles.add(f)
                }
            }
            ThreadPool.onUi {
                if (isFinishing || isDestroyed) {
                    virginScanInFlight = false
                    resumeVirginSpeech()
                    return@onUi
                }
                when {
                    files.isEmpty() -> {
                        virginScanInFlight = false
                        virginSpeak(getString(R.string.dj_voice_pen_none))
                    }
                    newFiles.isEmpty() -> {
                        virginScanInFlight = false
                        virginSpeak(getString(R.string.dj_voice_pen_all_dups, files.size))
                    }
                    else -> {
                        pendriveTotalFound = files.size
                        pendriveDuplicatesSkipped = skippedDuplicates
                        pendingPendriveFiles = newFiles
                        virginHandler.removeCallbacksAndMessages(null)
                        virginHandler.postDelayed({
                            pendingPendriveFiles = null
                        }, 60000)
                        virginSpeak(penConfirmMessage(newFiles.size))
                    }
                }
            }
        }
    }

    private fun penConfirmMessage(count: Int): String =
        getString(R.string.dj_voice_pen_confirm, count)

    private fun librarySongKeys(): SongKeys {
        val names = hashSetOf<String>()
        val tags = hashSetOf<String>()
        runCatching {
            Library.allSongs(applicationContext)
        }.getOrDefault(emptyList()).forEach { s ->
            val pathName = s.path.substringAfterLast('/').substringBeforeLast('.').lowercase().trim()
            if (pathName.isNotEmpty()) names.add(pathName)
            if (s.title.isNotBlank()) tags.add("${s.artist.lowercase()}|${s.title.lowercase()}")
        }
        return SongKeys(names, tags)
    }

    private class SongKeys(
        val names: HashSet<String>,
        val tags: HashSet<String>
    )

    private fun readAudioTags(uri: Uri): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT < 23) return null to null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(applicationContext, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            title to artist
        } catch (t: Throwable) {
            null to null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun completePendriveCopy(files: List<PendriveFile>) {
        ThreadPool.post {
            var copied = 0
            for (f in files) {
                if (copyPendriveFileToMusic(f)) copied++
            }
            ThreadPool.onUi {
                if (isFinishing || isDestroyed) return@onUi
                if (copied == 0) {
                    virginSpeak(getString(R.string.dj_voice_pen_copy_failed))
                    return@onUi
                }
                if (pendriveDuplicatesSkipped > 0) {
                    virginSpeak(getString(
                        R.string.dj_voice_pen_copy_done_dups,
                        pendriveTotalFound, pendriveDuplicatesSkipped, copied
                    ))
                } else {
                    virginSpeak(getString(R.string.dj_voice_pen_copy_done, copied))
                }
            }
        }
    }

    private fun copyPendriveFileToMusic(f: PendriveFile): Boolean {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(f.name.substringAfterLast('.', "").lowercase())
            ?: "audio/mpeg"
        val input = runCatching {
            contentResolver.openInputStream(f.uri)
        }.getOrNull() ?: return false
        return try {
            input.use { stream ->
                if (Build.VERSION.SDK_INT >= 29) {
                    val resolver = contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/")
                        put(MediaStore.Audio.Media.IS_MUSIC, 1)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                        if (!f.title.isNullOrBlank()) put(MediaStore.Audio.Media.TITLE, f.title)
                        if (!f.artist.isNullOrBlank()) put(MediaStore.Audio.Media.ARTIST, f.artist)
                    }
                    val uri = resolver.insert(
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        values
                    ) ?: return false
                    try {
                        resolver.openOutputStream(uri)?.use { out ->
                            stream.copyTo(out)
                        } ?: return false
                        resolver.update(uri, ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }, null, null)
                        true
                    } catch (t: Throwable) {
                        runCatching { resolver.delete(uri, null, null) }
                        false
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    if (!dir.exists() && !dir.mkdirs()) return false
                    val target = java.io.File(dir, helperUniqueName(f.name))
                    target.outputStream().use { out -> stream.copyTo(out) }
                    val ok = target.exists() && target.length() > 0
                    if (ok) {
                        MediaScannerConnection.scanFile(
                            applicationContext, arrayOf(target.absolutePath), null
                        ) { _, _ -> }
                    }
                    ok
                }
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun helperUniqueName(name: String): String {
        if (!java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), name
            ).exists()
        ) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "${base}_$i$ext"
            if (!java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), candidate
                ).exists()
            ) return candidate
            i++
        }
    }

    private fun hasMediaPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            Permissions.hasAccess(this)
        }

    private fun mediaPermissionNeeded(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun deleteVirginSong(song: Song) {
        if (Build.VERSION.SDK_INT >= 30) {
            val sender = runCatching {
                MediaStore.createDeleteRequest(
                    contentResolver,
                    listOf(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id))
                )
            }.getOrNull()
            if (sender == null) {
                virginSpeak(getString(R.string.dj_voice_delete_failed))
                return
            }
            pendingDelete = song
            virginDeleteLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(sender).build()
            )
        } else {
            completeVirginDelete(song, alreadyDeleted = false)
        }
    }

    private fun completeVirginDelete(song: Song, alreadyDeleted: Boolean) {
        ThreadPool.post {
            val deleted = if (alreadyDeleted) true else runCatching {
                contentResolver.delete(
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id),
                    null, null
                ) > 0
            }.getOrDefault(false)
            ThreadPool.onUi {
                if (isFinishing || isDestroyed) return@onUi
                if (deleted) {
                    runCatching {
                        val db = PlaylistDb.get(applicationContext)
                        db.removeSongFromAll(song.id)
                        db.removeFavorite(song.id)
                    }
                    if (Playback.currentSong?.id == song.id) Playback.next()
                    virginSpeak(getString(R.string.dj_voice_delete_done, song.title))
                } else {
                    virginSpeak(getString(R.string.dj_voice_delete_failed))
                }
            }
        }
    }

    private fun startVirginRecognize() {
        if (virginRecognizing) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingRecognize = true
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VIRGIN_MIC
            )
            return
        }
        if (virginOn) stopVirgin(silent = true)
        virginRecognizing = true
        syncVirginIcon()
        virginSpeak(getString(R.string.dj_voice_recognize))
        DjRecognizer.recognize(applicationContext) { result, error ->
            if (isFinishing || isDestroyed) return@recognize
            virginRecognizing = false
            syncVirginIcon()
            when {
                error == "no_token" -> {
                    virginSpeak(getString(R.string.dj_voice_recognize_configured))
                    Toast.makeText(this, R.string.dj_voice_recognize_configured, Toast.LENGTH_LONG).show()
                }
                error != null -> virginSpeak(getString(R.string.dj_voice_recognize_error))
                result == null -> virginSpeak(getString(R.string.dj_voice_recognize_none))
                else -> {
                    virginSpeak(getString(
                        R.string.dj_voice_recognize_recognized, result.title, result.artist
                    ))
                    val cur = Playback.currentSong
                    if (cur != null) {
                        MusicEditor.renameDetected(
                            applicationContext, cur,
                            result.title, result.artist, result.albumName
                        ) { ok ->
                            if (ok && !isDestroyed) {
                                virginSpeak(getString(
                                    R.string.dj_voice_recognize_renamed, result.title, result.artist
                                ))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fragmentFor(tag: String): Fragment {
        return when (tag) {
            FavoritesTabFragment::class.java.simpleName -> FavoritesTabFragment()
            VideosTabFragment::class.java.simpleName -> VideosTabFragment()
            AlbumsTabFragment::class.java.simpleName -> AlbumsTabFragment()
            ArtistsTabFragment::class.java.simpleName -> ArtistsTabFragment()
            PlaylistsTabFragment::class.java.simpleName -> PlaylistsTabFragment()
            else -> SongsTabFragment()
        }
    }

    private fun navIdFor(tag: String): Int {
        return when (tag) {
            FavoritesTabFragment::class.java.simpleName -> R.id.nav_favorites
            VideosTabFragment::class.java.simpleName -> R.id.nav_videos
            AlbumsTabFragment::class.java.simpleName -> R.id.nav_albums
            ArtistsTabFragment::class.java.simpleName -> R.id.nav_artists
            else -> R.id.nav_songs
        }
    }

    private fun openTab(tag: String, fragment: Fragment) {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        showTab(tag, fragment)
    }

    private fun showTab(tag: String, fragment: Fragment) {
        currentTag = tag
        CrashLogger.writeLog(this, "MARK: showTab $tag iniciado")
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment, tag)
            .commit()
        crashContainerCheck()
        syncToolbar()
    }

    private fun crashContainerCheck() {
        CrashLogger.writeLog(
            this,
            "MARK: container apos commit visivel=${supportFragmentManager.findFragmentById(R.id.fragment_container)?.let { it.javaClass.simpleName } ?: "NULL"} " +
                "views=" + (findViewById<android.widget.FrameLayout>(R.id.fragment_container))?.let { it.childCount } ?: "sem container"
        )
    }

    private fun pushDetail(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment, tag)
            .addToBackStack(tag)
            .commit()
        syncToolbar()
    }

    fun openAlbum(album: Album) {
        pushDetail(LibraryDetailFragment.album(album), "album_detail")
    }

    fun openArtist(artist: Artist) {
        pushDetail(ArtistTimelineFragment.artist(artist), "artist_timeline")
    }

    fun openArtistLibrary(artist: Artist) {
        pushDetail(LibraryDetailFragment.artist(artist), "artist_detail")
    }

    fun openPlaylist(playlist: Playlist) {
        pushDetail(PlaylistDetailFragment.forPlaylist(playlist), "playlist_detail")
    }

    private fun openNowPlaying() {
        pushDetail(NowPlayingFragment(), NowPlayingFragment::class.java.simpleName)
    }

    private fun refreshPlaylists() {
        val top = topFragment()
        if (top is PlaylistsTabFragment) {
            top.reload()
        }
    }

    private fun syncToolbar() {
        if (!mainViewsReady) return
        val fragment = topFragment()
        val showingNow = fragment is NowPlayingFragment
        val hasBackStack = supportFragmentManager.backStackEntryCount > 0

        bottomNav.visibility = if (showingNow) View.GONE else View.VISIBLE
        miniPlayer.visibility = if (showingNow) {
            View.GONE
        } else if (Playback.currentSong != null) {
            View.VISIBLE
        } else {
            View.GONE
        }

        toolbar.navigationIcon = if (hasBackStack) {
            ContextCompat.getDrawable(this, R.drawable.ic_arrow_back)
        } else {
            null
        }

        toolbar.menu.findItem(R.id.action_add)?.isVisible = fragment is PlaylistsTabFragment

        toolbar.title = when (fragment) {
            is NowPlayingFragment -> getString(R.string.now_playing)
            is PlaylistsTabFragment -> getString(R.string.tab_playlists)
            is SongsTabFragment -> getString(R.string.tab_songs)
            is AlbumsTabFragment -> getString(R.string.tab_albums)
            is ArtistsTabFragment -> getString(R.string.tab_artists)
            is FavoritesTabFragment -> getString(R.string.tab_favorites)
            is VideosTabFragment -> getString(R.string.tab_videos)
            is LibraryDetailFragment -> fragment.title()
            is PlaylistDetailFragment -> fragment.title()
            else -> tabTitle(currentTag)
        }
    }

    private fun refreshSortIcon() {
        if (!mainViewsReady) return
        val item = toolbar.menu.findItem(R.id.action_sort) ?: return
        item.icon = ContextCompat.getDrawable(
            this,
            if (Settings.sortAlphabetical(this)) R.drawable.ic_sort_alpha else R.drawable.ic_sort
        )
    }

    private fun tabTitle(tag: String): String {
        return when (tag) {
            FavoritesTabFragment::class.java.simpleName -> getString(R.string.tab_favorites)
            VideosTabFragment::class.java.simpleName -> getString(R.string.tab_videos)
            AlbumsTabFragment::class.java.simpleName -> getString(R.string.tab_albums)
            ArtistsTabFragment::class.java.simpleName -> getString(R.string.tab_artists)
            PlaylistsTabFragment::class.java.simpleName -> getString(R.string.tab_playlists)
            else -> getString(R.string.tab_songs)
        }
    }

    private fun topFragment(): Fragment? {
        val fm = supportFragmentManager
        val count = fm.backStackEntryCount
        if (count > 0) {
            val name = fm.getBackStackEntryAt(count - 1).name
            return fm.findFragmentByTag(name)
        }
        return fm.findFragmentByTag(currentTag)
    }

    private fun syncMiniPlayer() {
        if (!mainViewsReady) return
        val song = Playback.currentSong ?: return
        miniTitle.text = song.title
        miniArtist.text = song.artist
        ArtLoader.load(song.albumId, song.path, miniArt)
        val playing = Playback.isPlaying
        miniPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        miniShuffle.tint(if (Playback.shuffle) R.color.primary else R.color.text_secondary)
        val (icon, color) = when {
            Playback.repeatOne -> R.drawable.ic_repeat_one to R.color.primary
            Playback.repeatAll -> R.drawable.ic_repeat to R.color.primary
            else -> R.drawable.ic_repeat to R.color.text_secondary
        }
        miniRepeat.setImageResource(icon)
        miniRepeat.tint(color)
        val songId = song.id
        ThreadPool.post {
            val fav = PlaylistDb.get(applicationContext).isFavorite(songId)
            ThreadPool.onUi {
                if (!mainViewsReady) return@onUi
                if (Playback.currentSong?.id != songId) return@onUi
                miniLike.setImageResource(if (fav) R.drawable.ic_favorite else R.drawable.ic_heart)
                miniLike.tint(if (fav) R.color.primary else R.color.text_secondary)
            }
        }
    }

    private fun ImageView.tint(colorRes: Int) {
        setColorFilter(androidx.core.content.ContextCompat.getColor(context, colorRes))
    }

    private fun toggleMiniLike() {
        val song = Playback.currentSong ?: return
        val songId = song.id
        ThreadPool.post {
            val db = PlaylistDb.get(applicationContext)
            val fav = db.isFavorite(songId)
            db.setFavorite(song, !fav)
            ThreadPool.onUi {
                if (mainViewsReady) syncMiniPlayer()
            }
        }
    }

    override fun onSongChanged(song: Song?, index: Int) {
        if (!mainViewsReady) return
        if (song == null) {
            if (miniPlayer.visibility != View.GONE) miniPlayer.visibility = View.GONE
        } else {
            syncMiniPlayer()
            syncToolbar()
            announceRadioSong(song)
        }
        (topFragment() as? NowPlayingFragment)?.render()
    }

    override fun onPlayStateChanged(isPlaying: Boolean) {
        if (!mainViewsReady) return
        miniPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        (topFragment() as? NowPlayingFragment)?.refreshPlayIcon()
    }

    override fun onProgress(positionMs: Long, durationMs: Long) {
        (topFragment() as? NowPlayingFragment)?.refreshProgress(positionMs, durationMs)
    }

    private fun renderAvatar() {
        renderPhotoInto(avatarView)
    }

    private fun renderPhotoInto(target: ShapeableImageView?) {
        val t = target ?: return
        val photo = Profile.getPhoto(this)
        if (photo != null) {
            t.setImageBitmap(photo)
            t.clearColorFilter()
        } else {
            t.setImageResource(R.drawable.ic_person)
        }
    }

    private data class PendriveFile(
        val uri: Uri,
        val name: String,
        val title: String?,
        val artist: String?
    )
}