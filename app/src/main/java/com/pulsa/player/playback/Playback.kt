package com.pulsa.player.playback

import com.pulsa.player.model.Song

object Playback {
    interface Listener {
        fun onSongChanged(song: Song?, index: Int)
        fun onPlayStateChanged(isPlaying: Boolean)
        fun onProgress(positionMs: Long, durationMs: Long)
    }

    @Volatile
    var service: PlaybackService? = null
    var listener: Listener? = null

    val currentSong: Song? get() = service?.currentSong
    val isPlaying: Boolean get() = service?.isPlaying ?: false
    val index: Int get() = service?.index ?: -1
    val shuffle: Boolean get() = service?.shuffle ?: false
    val repeatAll: Boolean get() = service?.repeatAll ?: true
    val repeatOne: Boolean get() = service?.repeatOne ?: false
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

    fun setMicListening(on: Boolean) {
        service?.setMicListening(on)
    }

    fun cycleRepeat() {
        service?.cycleRepeat()
    }

    fun notifySong(song: Song?, index: Int) {
        listener?.onSongChanged(song, index)
    }

    fun notifyPlayState(isPlaying: Boolean) {
        listener?.onPlayStateChanged(isPlaying)
    }

    fun notifyProgress(positionMs: Long, durationMs: Long) {
        listener?.onProgress(positionMs, durationMs)
    }
}
