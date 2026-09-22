package com.pulsa.player

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.core.Settings
import com.pulsa.player.ui.AnimatedBackground
import com.pulsa.player.ui.NowPlayingFragment

/**
 * Now Playing como Activity própria: abre por cima da navegação, tem toolbar
 * e back próprios — desacopla o player da tira de abas (esqueleto novo).
 */
class NowPlayingActivity : AppCompatActivity(), Playback.Listener {

    private fun fragment(): NowPlayingFragment? =
        supportFragmentManager.findFragmentByTag(TAG) as? NowPlayingFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)
        AnimatedBackground.apply(this)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.np_toolbar)
            .setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.np_container, NowPlayingFragment(), TAG)
                .commit()
        }
    }

    override fun onStart() {
        super.onStart()
        current = this
        Playback.listener = this
    }

    override fun onResume() {
        super.onResume()
        fragment()?.render()
    }

    override fun onStop() {
        if (Playback.listener === this) Playback.listener = null
        if (current === this) current = null
        super.onStop()
    }

    fun refreshVisuals() {
        fragment()?.refreshVisuals()
    }

    override fun onSongChanged(song: Song?, index: Int) {
        fragment()?.render()
    }

    override fun onPlayStateChanged(isPlaying: Boolean) {
        fragment()?.refreshPlayIcon()
    }

    override fun onProgress(positionMs: Long, durationMs: Long) {
        fragment()?.refreshProgress(positionMs, durationMs)
    }

    companion object {
        private const val TAG = "now_playing"

        @Volatile
        var current: NowPlayingActivity? = null
    }
}