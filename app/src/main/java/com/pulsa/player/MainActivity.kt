package com.pulsa.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
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
import com.pulsa.player.playback.Playback
import com.pulsa.player.playback.PlaybackService
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
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
import com.pulsa.player.core.Account
import com.pulsa.player.audio.Ambient
import com.pulsa.player.ui.AnimatedBackground
import com.pulsa.player.core.Changelog
import com.pulsa.player.core.CrashLogger
import com.pulsa.player.dj.MainVirgin
import com.pulsa.player.media.MusicEditor
import com.pulsa.player.core.MotionControls
import com.pulsa.player.core.Permissions
import com.pulsa.player.core.Profile
import com.pulsa.player.core.Settings
import com.pulsa.player.sync.Telemetry
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.util.UpdateChecker

class MainActivity : AppCompatActivity(), Playback.Listener {

    companion object {
        private const val KEY_TAG = "tab"
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

    private val virginDeleteLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            virgin.onDeleteRequestResult(result.resultCode == RESULT_OK)
        }

    private val virginDuplicatesLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            virgin.onDuplicatesRequestResult(result.resultCode == RESULT_OK)
        }

    private val pendriveTreeLauncher: ActivityResultLauncher<Uri?> =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            virgin.onPendriveTreeResult(uri)
        }

    private val virgin by lazy {
        MainVirgin(
            this,
            MainVirgin.Launchers(virginDeleteLauncher, virginDuplicatesLauncher, pendriveTreeLauncher),
            object : MainVirgin.Host {
                override fun syncVirginIcon() = this@MainActivity.syncVirginIcon()
                override fun syncMiniPlayer() = this@MainActivity.syncMiniPlayer()
                override fun refreshPlayerVisuals() {
                    (topFragment() as? NowPlayingFragment)?.refreshVisuals()
                }
            }
        )
    }

    fun launchWriteRequest(intentSender: android.content.IntentSender?) {
        if (intentSender != null) {
            writeRequest.launch(IntentSenderRequest.Builder(intentSender).build())
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

    private fun createMain(savedInstanceState: Bundle?) {
        appliedAccent = Settings.accent(this)
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        Telemetry.log(this, "createMain: super.onCreate ok")
        setContentView(R.layout.activity_main)
        Telemetry.log(this, "createMain: setContentView ok")
        virgin.initWelcomeVoice(savedInstanceState)

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
        miniVirgin.setOnClickListener { virgin.toggleVirgin() }
        miniVirgin.setOnLongClickListener {
            virgin.onMiniVirginLongPress()
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
        virgin.stopForBackground()
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (Ambient.isOn()) {
            val step = 0.05f
            val vol = when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> Ambient.state().volume + step
                KeyEvent.KEYCODE_VOLUME_DOWN -> Ambient.state().volume - step
                else -> null
            }
            if (vol != null) {
                Ambient.setVolume(vol.coerceIn(0f, 1f))
                Toast.makeText(
                    this,
                    getString(R.string.ambient_volume, (Ambient.state().volume * 100).toInt()),
                    Toast.LENGTH_SHORT
                ).show()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (serviceBound) {
            serviceBound = false
            runCatching { applicationContext.unbindService(connection) }
        }
        virgin.destroy()
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
        if (requestCode == MainVirgin.REQ_VIRGIN_MIC || requestCode == MainVirgin.REQ_VIRGIN_STORAGE) {
            virgin.onPermissionResult(requestCode, granted)
        }
    }

    private fun syncVirginIcon() {
        if (::miniVirgin.isInitialized) {
            val color = ContextCompat.getColor(
                this,
                if (virgin.isActive) R.color.primary else R.color.text_secondary
            )
            miniVirgin.setColorFilter(color)
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
            .setCustomAnimations(R.anim.frag_tab_in, R.anim.frag_slide_up_out)
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
            .setCustomAnimations(R.anim.frag_slide_up_in, R.anim.frag_slide_up_out, R.anim.frag_pop_in, R.anim.frag_slide_down_out)
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
            virgin.announceRadioSong(song)
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
}