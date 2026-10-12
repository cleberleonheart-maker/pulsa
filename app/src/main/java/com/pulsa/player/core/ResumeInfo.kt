package com.pulsa.player.core

import android.content.Context
import com.pulsa.player.R
import com.pulsa.player.model.Song
import java.util.TimeZone

/**
 * F5 — "continuar de onde parei", ciente do tipo de mídia.
 *
 * A home e a Virgin já retomavam a última sessão, mas sempre com o vocabulário de música
 * ("artista • álbum", "tocando X"). Aqui mora a tradução de um [Song] para o que ele é de
 * verdade — música, episódio, vídeo, rádio ou stream — e para quanto tempo a pessoa andou.
 * É o único lugar que decide isso, para o card e a fala não divergirem (um deles mudar
 * sozinho é exatamente o tipo de cópia que já quebrou o app antes).
 *
 * A posição só entra quando faz sentido: música que ainda está tocando não "parou" em
 * lugar nenhum, e rádio ao vivo não tem posição para retomar.
 */
object ResumeInfo {

    enum class Kind { MUSIC, PODCAST, VIDEO, RADIO, STREAM }

    fun kindOf(song: Song): Kind = when {
        song.isPodcast -> Kind.PODCAST
        song.isVideo -> Kind.VIDEO
        song.isRadio -> Kind.RADIO
        song.isStream -> Kind.STREAM
        else -> Kind.MUSIC
    }

    /** Ícone do card quando a mídia não tem arte própria (episódio, rádio e stream). */
    fun iconRes(song: Song): Int = when (kindOf(song)) {
        Kind.PODCAST -> R.drawable.ic_mic
        Kind.VIDEO, Kind.STREAM -> R.drawable.ic_videocam
        Kind.RADIO -> R.drawable.ic_radio
        Kind.MUSIC -> R.drawable.ic_music_note
    }

    private fun kindLabel(context: Context, song: Song): String = when (kindOf(song)) {
        Kind.PODCAST -> context.getString(R.string.resume_kind_podcast)
        Kind.VIDEO -> context.getString(R.string.resume_kind_video)
        Kind.RADIO -> context.getString(R.string.resume_kind_radio)
        Kind.STREAM -> context.getString(R.string.resume_kind_stream)
        Kind.MUSIC -> ""
    }

    /**
     * Subtítulo do card da home.
     *
     * Música mantém o formato antigo ("artista • álbum"); os outros tipos ganham o nome do
     * tipo — e, no podcast, o feed — porque "artista • álbum" de um episódio não diz nada.
     * [positionMs] `0` significa "está tocando agora", e aí não se anuncia que parou.
     */
    fun cardSubtitle(context: Context, song: Song, positionMs: Long): String {
        when (kindOf(song)) {
            Kind.MUSIC -> return listOf(song.artist, song.album)
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            Kind.RADIO -> return song.artist
            else -> Unit
        }
        val parts = ArrayList<String>(3)
        parts.add(kindLabel(context, song))
        if (kindOf(song) == Kind.PODCAST && song.artist.isNotBlank()) parts.add(song.artist)
        if (positionMs > 0L) {
            parts.add(context.getString(R.string.resume_card_stopped_at, clock(positionMs)))
        }
        return parts.filter { it.isNotBlank() }.joinToString(" • ")
    }

    /**
     * A frase de retomada da Virgin: "Voltando pro episódio de ontem, do Café. Você parou
     * no minuto 34."
     *
     * [savedAtMs] é quando a sessão foi salva (ver `Settings.queueSavedAt`); `0` vira "da
     * outra vez", sem mentir uma data que não se sabe.
     */
    fun spoken(context: Context, song: Song, positionMs: Long, savedAtMs: Long): String {
        val whenText = whenBucket(context, savedAtMs)
        val base = when (kindOf(song)) {
            Kind.PODCAST -> context.getString(R.string.resume_spoken_podcast, whenText, song.artist)
            Kind.MUSIC -> context.getString(R.string.resume_spoken_music, song.title, song.artist)
            Kind.VIDEO, Kind.STREAM -> context.getString(R.string.resume_spoken_video, song.title)
            Kind.RADIO -> context.getString(R.string.resume_spoken_radio, song.title)
        }
        val stopped = stoppedClause(context, positionMs) ?: return base
        return base + " " + stopped
    }

    private fun stoppedClause(context: Context, positionMs: Long): String? {
        if (positionMs <= 0L) return null
        return if (positionMs >= 60_000L) {
            context.getString(R.string.resume_stopped_at_minutes, (positionMs / 60_000L).toInt())
        } else {
            context.getString(R.string.resume_stopped_at_clock, clock(positionMs))
        }
    }

    /** "de hoje", "de ontem" ou "da outra vez" — o dia de calendário em que foi salvo. */
    fun whenBucket(context: Context, savedAtMs: Long): String =
        when (daysAgo(savedAtMs, System.currentTimeMillis())) {
            0 -> context.getString(R.string.resume_when_today)
            1 -> context.getString(R.string.resume_when_yesterday)
            else -> context.getString(R.string.resume_when_past)
        }

    /**
     * Dias de calendário entre [thenMs] e [nowMs] — 0 é hoje, 1 é ontem.
     *
     * É a conta por **dia**, e não por 24 horas: 23h de ontem para 1h de hoje é "ontem"
     * mesmo com duas horas de diferença. Dias antes de 1970 ou tempo ausente viram um
     * número grande, que o [whenBucket] lê como "da outra vez".
     */
    fun daysAgo(thenMs: Long, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        if (thenMs <= 0L) return Int.MAX_VALUE
        val dayMs = 86_400_000L
        val thenDay = (thenMs + zone.getOffset(thenMs)) / dayMs
        val nowDay = (nowMs + zone.getOffset(nowMs)) / dayMs
        return (nowDay - thenDay).toInt().coerceAtLeast(0)
    }

    /** "34:12", "1:02", "0:42" — minutos e segundos, sem hora (posição de mídia). */
    fun clock(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
