package com.pulsa.player.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.Player
import com.pulsa.player.core.Settings
import com.pulsa.player.model.Song
import com.pulsa.player.playback.PlaybackSpeeds.VALUES
import kotlin.math.abs

/**
 * A fachada do playback. É o **único** lugar do app que sabe que existe um motor de música.
 *
 * F1/E2: o motor continua sendo o legado ([ServicePlayerLink] → [PlaybackService]), mas
 * ninguém fora de `playback/` mais fala com o `Service`: nem `SettingsActivity`, nem
 * `PulsaWidget`, nem `MainActivity`/`DjActivity`, nem a Virgin. O publicador `Playback.service`
 * foi embora — o que existia era API pública de fato e foi o que a auditoria do E0 achou.
 *
 * Contrato: os 29 arquivos que chamam [Playback] não mudam neste passo. O que mudou é o
 * **transporte** por baixo: em E3 a mesma fachada passa a falar com o `MediaController`, e é
 * só isso que troca.
 */
object Playback {
    interface Listener {
        fun onSongChanged(song: Song?, index: Int)
        fun onPlayStateChanged(isPlaying: Boolean)
        fun onProgress(positionMs: Long, durationMs: Long)

        /** Falha ao tocar a faixa atual (E4: a tela de rádio usa para mostrar erro/retry). */
        fun onTrackError(song: Song?) {}

        /** F2: a velocidade efetiva mudou (usuário mexeu, ou a faixa mudou de tipo). */
        fun onSpeedChanged(speed: Float) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * O contexto da aplicação, para o [Settings] de velocidade.
     *
     * Fica guardado aqui porque a tela de vídeo só tem a `Activity`, e `Settings` é
     * process-wide. `internal` para não virar mais API pública da fachada.
     */
    internal var appContext: Context? = null

    private fun nextSpeed(atual: Float): Float {
        val i = VALUES.indexOfFirst { abs(it - atual) < 0.01f }
        return VALUES[(i + 1).mod(VALUES.size)]
    }

    /**
     * O motor, quando ele está vivo. Preenchido pelo próprio [PlaybackService] no `onCreate`
     * (e não pelo `bindService`) porque é o que permite ao widget e aos broadcasts desenharem
     * estado sem precisar connectar — o mesmo comportamento de antes, quando o serviço se
     * registrava em `Playback.service`.
     */
    @Volatile
    private var link: PlayerLink? = null

    @Volatile
    var listener: Listener? = null

    /**
     * O motor está no ar? Substitui o antigo `Playback.service != null`, que três arquivos
     * usavam como "o app já subiu?" — inclusive a Virgin esperando o serviço ficar pronto
     * depois do alarme.
     */
    val isReady: Boolean get() = link != null

    val currentSong: Song? get() = link?.currentSong
    val isPlaying: Boolean get() = link?.isPlaying ?: false

    /** F2b — `false` quando o motor está pausado por decisão de quem está usando. */
    val pausedDeliberately: Boolean get() = link?.pausedDeliberately ?: false

    /**
     * F2b — a chave do que está tocando, para comparar sem cair na colisão de id.
     *
     * Existe porque `song.id` deixou de bastar: áudio e vídeo são coleções separadas do
     * MediaStore, cada uma numerada por conta própria, então `currentSong?.id == song.id`
     * dá positivo para a faixa errada sempre que um vídeo e uma música compartilham o
     * número. Os callbacks assíncronos ("a curiosidade ainda é da faixa que está
     * tocando?") eram os mais expostos: um fetch que voltava tarde achava que a faixa tinha
     * mudado para a música de mesmo id e descartava a fala.
     */
    val currentKey: String? get() = link?.currentSong?.let { QueueKey.encode(it) }
    val index: Int get() = link?.index ?: -1
    val shuffle: Boolean get() = link?.shuffle ?: false
    val repeatAll: Boolean get() = link?.repeatAll ?: true
    val repeatOne: Boolean get() = link?.repeatOne ?: false
    val abActive: Boolean get() = link?.abActive ?: false
    val markerA: Long get() = link?.markerA ?: -1L
    val markerB: Long get() = link?.markerB ?: -1L
    val position: Long get() = link?.position ?: 0L
    val queue: List<Song> get() = link?.queue ?: emptyList()
    val audioSessionId: Int get() = link?.audioSessionId ?: 0

    /**
     * O `Player` do motor (E5). Só a tela de vídeo usa: ela anexa o `SurfaceView` e lê estado
     * de vídeo (buffer/erro) diretamente. Para tudo o mais continua valendo: o transporte é
     * por aqui, e não pelos comandos do `Player`.
     */
    val player: Player? get() = link?.player

    // ---------------------------------------------------------------- ligação (bind)

    private var bindRefs = 0
    private var bindRequested = false
    private var bindConnected = false
    private var bindContext: Context? = null
    private val pendingReady = mutableListOf<() -> Unit>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bindConnected = true
            val callbacks = pendingReady.toList()
            pendingReady.clear()
            callbacks.forEach { onMain { it() } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bindConnected = false
        }
    }

    /**
     * Liga a tela ao motor. `onReady` roda assim que a ligação fecha (ou na hora, se já
     * estava ligada) — é o que a Virgin, a mini-player e o DJ usavam para desenhar depois
     * do `bindService`.
     *
     * O bind é contado: `MainActivity` liga por `onCreate`/`onDestroy` e `DjActivity` por
     * `onStart`/`onStop`, então as duas podem estar conectadas ao mesmo tempo sem brigar pelo
     * mesmo `ServiceConnection`. O [Bind] devolvido é o que desliga — passar o handle, em vez
     * de um `disconnect(context)` solto, é o que impede a contagem de sair de esquis no
     * caminho em que a tela morre antes de conseguir ligar (`createMain` lançou).
     */
    fun connect(context: Context, onReady: () -> Unit = {}): Bind {
        bindRefs++
        val handle = Bind()
        if (bindConnected) {
            onMain { onReady() }
            return handle
        }
        pendingReady += onReady
        if (bindRequested) return handle
        bindRequested = true
        val app = context.applicationContext
        bindContext = app
        val intent = Intent(app, PlaybackService::class.java)
        runCatching { app.startService(intent) }
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        return handle
    }

    /** O devolve [connect]. O motor em si continua de pé: quem desliga o som é o usuário. */
    fun release(bind: Bind) {
        if (!bind.released) {
            bind.released = true
            releaseBind()
        }
    }

    private fun releaseBind() {
        if (bindRefs == 0) return
        bindRefs--
        if (bindRefs > 0) return
        bindRequested = false
        bindConnected = false
        pendingReady.clear()
        bindContext?.let { runCatching { it.unbindService(serviceConnection) } }
        bindContext = null
    }

    class Bind internal constructor() {
        internal var released = false
    }

    // ------------------------------------------------- registro do motor (só playback/)

    internal fun attach(link: PlayerLink) {
        this.link = link
    }

    internal fun detach(link: PlayerLink) {
        if (this.link === link) this.link = null
    }

    // ------------------------------------------------------------------ transporte

    /**
     * Intenções dos botões do widget. O widget não deveria nem saber o nome do serviço: é a
     * fachada que sabe como falar com o motor.
     */
    fun toggleIntent(context: Context): Intent =
        Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_TOGGLE)

    fun nextIntent(context: Context): Intent =
        Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_NEXT)

    fun prevIntent(context: Context): Intent =
        Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_PREV)

    // ---------------------------------------------------------------------- comandos

    fun currentArt(): Bitmap? = link?.currentArt()

    fun refreshFx() {
        link?.refreshFx()
    }

    /** Reaplica velocidade/pitch depois de mudar a qualidade: era o `furo 1` da auditoria. */
    fun reapplyDanceParams() {
        link?.reapplyDanceParams()
    }

    fun refreshCurrentMeta() {
        link?.refreshCurrentMeta()
    }

    fun start(songs: List<Song>, startIndex: Int) {
        link?.start(songs, startIndex)
    }

    /**
     * F2b — junta ao fim da fila sem trocar o que está tocando.
     *
     * Devolve `0` quando nada entrou (fila vazia do motor ainda, ou todos os itens já
     * estavam na fila) para o chamador poder avisar.
     */
    fun enqueue(songs: List<Song>): Int = link?.enqueue(songs) ?: 0

    /** Retoma a reprodução (E5: a tela de vídeo volta do segundo plano e o motor estava pausado). */
    fun play() {
        link?.play()
    }

    /** Interrompe a fila e zera o motor (E5: sair do vídeo sem música anterior não deixa video encalhado). */
    fun stop() {
        link?.stop()
    }

    fun toggle() {
        link?.toggle()
    }

    fun pause() {
        link?.pause()
    }

    fun next() {
        link?.next()
    }

    fun prev() {
        link?.prev()
    }

    fun seekTo(ms: Long) {
        link?.seekTo(ms)
    }

    /** F2: soma a uma posição, clampado nas pontas. `Playback` não tem duração. */
    fun seekBy(ms: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        seekTo((position + ms).coerceIn(0L, durationMs))
    }

    /**
     * F2: velocidad do video, em Passos. Persiste e reaplica no motor.
     *
     * Recebe o valor **escolhido**, nao o efetivo: quem guarda o passo da lista e a tela.
     */
    fun cycleVideoSpeed(): Float {
        val l = link ?: return 1f
        val atual = Settings.videoSpeed(appContext ?: return 1f)
        val proximo = nextSpeed(atual)
        l.setVideoSpeed(proximo)
        return proximo
    }

    fun setVideoSpeed(value: Float) {
        link?.setVideoSpeed(value)
    }

    fun setShuffle(value: Boolean) {
        link?.setShuffle(value)
    }

    fun setRepeatAll(value: Boolean) {
        link?.setRepeatAll(value)
    }

    fun setRepeatOne(value: Boolean) {
        link?.setRepeatOne(value)
    }

    fun setSleepMix(on: Boolean) {
        link?.setSleepMix(on)
    }

    fun setMarkerA() {
        link?.setMarkerA()
    }

    fun setMarkerB() {
        link?.setMarkerB()
    }

    fun clearAbLoop() {
        link?.clearAbLoop()
    }

    fun setMicListening(on: Boolean) {
        link?.setMicListening(on)
    }

    fun cycleRepeat() {
        link?.cycleRepeat()
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

    fun notifyTrackError(song: Song?) {
        val l = listener ?: return
        onMain { l.onTrackError(song) }
    }

    /**
     * F2 — a velocidade que está valendo **agora**, já com o modo dance somado.
     *
     * A tela de vídeo precisa do número efetivo, não do escolhido: com o dance ligado
     * o botão marcava 1,5x e a tela mostrava 1,68x, o que parece bug de arredondamento.
     */
    fun notifySpeedChanged(speed: Float) {
        val l = listener ?: return
        onMain { l.onSpeedChanged(speed) }
    }
}
