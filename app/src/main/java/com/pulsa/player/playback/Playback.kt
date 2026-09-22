package com.pulsa.player.playback

import android.os.Handler
import android.os.Looper
import com.pulsa.player.model.Song

object Playback {
    interface Listener {
        fun onSongChanged(song: Song?, index: Int)
        fun onPlayStateChanged(isPlaying: Boolean)
        fun onProgress(positionMs: Long, durationMs: Long)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var service: PlaybackService? = null

    @Volatile
    var listener: Listener? = null

    val currentSong: Song? get() = service?.currentSong
    val isPlaying: Boolean get() = service?.isPlaying ?: false
    val index: Int get() = service?.index ?: -1
    val shuffle: Boolean get() = service?.shuffle ?: false
    val repeatAll: Boolean get() = service?.repeatAll ?: true
    val repeatOne: Boolean get() = service?.repeatOne ?: false
    val abActive: Boolean get() = service?.abActive ?: false
    val markerA: Long get() = service?.abA ?: -1L
    val markerB: Long get() = service?.abB ?: -1L
    val position: Long get() = service?.positionMs ?: 0L
    val queue: List<Song> get() = service?.queue ?: emptyList()
    val audioSessionId: Int get() = service?.audioSessionId ?: 0

    fun refreshFx() {
        service?.refreshFx()
    }

    fun refreshCurrentMeta() {
        service?.refreshCurrentMeta()
    }

    fun start(songs: List<Song>, startIndex: Int) {
        service?.start(songs, startIndex)
    }

    fun toggle() {
        service?.toggle()
    }

    fun pause() {
        service?.pause()
    }

    fun next() {
        service?.next()
    }

    fun prev() {
        service?.prev()
    }

    fun seekTo(ms: Long) {
        service?.seekTo(ms)
    }

    fun setShuffle(value: Boolean) {
        service?.setShuffle(value)
    }

    fun setRepeatAll(value: Boolean) {
        service?.setRepeatAll(value)
    }

    fun setRepeatOne(value: Boolean) {
        service?.setRepeatOne(value)
    }

    fun setSleepMix(on: Boolean) {
        service?.setSleepMix(on)
    }

    fun setMarkerA() {
        service?.setMarkerA()
    }

    fun setMarkerB() {
        service?.setMarkerB()
    }

    fun clearAbLoop() {
        service?.clearAbLoop()
    }

    fun setMicListening(on: Boolean) {
        service?.setMicListening(on)
    }

    fun cycleRepeat() {
        service?.cycleRepeat()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    fun notifySong(song: Song?, index: Int) {
        val l = listener ?: return
        onMain { l.onSongChanged(song, index) }
    }

    fun notifyPlayState(isPlaying: Boolean) {
        val l = listener ?: return
        onMain { l.onPlayStateChanged(isPlaying) }
    }

    fun notifyProgress(positionMs: Long, durationMs: Long) {
        val l = listener ?: return
        onMain { l.onProgress(positionMs, durationMs) }
    }
}
