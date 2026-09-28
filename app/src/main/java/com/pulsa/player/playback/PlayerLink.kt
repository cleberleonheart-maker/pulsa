package com.pulsa.player.playback

import android.graphics.Bitmap
import com.pulsa.player.model.Song

/**
 * F1/E2 — a ponte entre a fachada [Playback] e o motor de música.
 *
 * É o "PlayerHolder" do plano: uma superfície só, com o que a fachada precisa, para que
 * **trocar a conexão não seja trocar o motor**. A implementação atual é o `ExoPlayer`
 * dentro de [PlaybackService] (E3a); nem a fachada nem os 29 consumidores de [Playback]
 * mudam.
 *
 * Regra do passo: a fachada fala com o motor **por aqui**, e nunca mais com o `Service`
 * diretamente. Quem cuida de `EQ`, 8D, crossfade, A/B e do `audioSessionId` continua sendo o
 * motor — o E2 não mexe em nenhum desses efeitos.
 */
interface PlayerLink {
    val currentSong: Song?
    val isPlaying: Boolean
    val index: Int
    val shuffle: Boolean
    val repeatAll: Boolean
    val repeatOne: Boolean
    val abActive: Boolean
    val markerA: Long
    val markerB: Long
    val position: Long
    val queue: List<Song>
    val audioSessionId: Int

    fun currentArt(): Bitmap?
    fun refreshFx()
    fun reapplyDanceParams()
    fun refreshCurrentMeta()
    fun start(songs: List<Song>, startIndex: Int)
    fun toggle()
    fun pause()
    fun next()
    fun prev()
    fun seekTo(ms: Long)
    fun setShuffle(value: Boolean)
    fun setRepeatAll(value: Boolean)
    fun setRepeatOne(value: Boolean)
    fun setSleepMix(on: Boolean)
    fun setMarkerA()
    fun setMarkerB()
    fun clearAbLoop()
    fun setMicListening(on: Boolean)
    fun cycleRepeat()
}

/**
 * E3a — implementação que roda o motor sobre o `ExoPlayer` dentro de [PlaybackService].
 * O `service` continua sendo um `Service` comum com `MediaSessionCompat`; a troca do motor
 * aconteceu sem mudar o contrato. Tudo aqui é repasse 1:1 dos membros públicos do serviço;
 * nenhum `private set` é violado porque só são lidos.
 *
 * No E3b esta classe é substituída pela que fala com o `MediaController`, que é a parte
 * que remove a `MediaSessionCompat` e usa a sessão do Media3.
 */
class ServicePlayerLink(val service: PlaybackService) : PlayerLink {

    override val currentSong: Song? get() = service.currentSong
    override val isPlaying: Boolean get() = service.isPlaying
    override val index: Int get() = service.index
    override val shuffle: Boolean get() = service.shuffle
    override val repeatAll: Boolean get() = service.repeatAll
    override val repeatOne: Boolean get() = service.repeatOne
    override val abActive: Boolean get() = service.abActive
    override val markerA: Long get() = service.abA
    override val markerB: Long get() = service.abB
    override val position: Long get() = service.positionMs
    override val queue: List<Song> get() = service.queue
    override val audioSessionId: Int get() = service.audioSessionId

    override fun currentArt(): Bitmap? = service.currentArt()
    override fun refreshFx() = service.refreshFx()
    override fun reapplyDanceParams() = service.applyDanceParamsForRefresh()
    override fun refreshCurrentMeta() = service.refreshCurrentMeta()
    override fun start(songs: List<Song>, startIndex: Int) = service.start(songs, startIndex)
    override fun toggle() = service.toggle()
    override fun pause() = service.pause()
    override fun next() = service.next()
    override fun prev() = service.prev()
    override fun seekTo(ms: Long) = service.seekTo(ms)
    override fun setShuffle(value: Boolean) = service.setShuffle(value)
    override fun setRepeatAll(value: Boolean) = service.setRepeatAll(value)
    override fun setRepeatOne(value: Boolean) = service.setRepeatOne(value)
    override fun setSleepMix(on: Boolean) = service.setSleepMix(on)
    override fun setMarkerA() = service.setMarkerA()
    override fun setMarkerB() = service.setMarkerB()
    override fun clearAbLoop() = service.clearAbLoop()
    override fun setMicListening(on: Boolean) = service.setMicListening(on)
    override fun cycleRepeat() = service.cycleRepeat()
}
