package com.pulsa.player.dj

import com.pulsa.player.model.Song

/**
 * Monta a fila de uma "sessão com objetivo" (item 266): a escolha é pela **duração
 * somada**, não só pelo gênero.
 *
 * Recebe as músicas já filtradas (por humor/gênero e sem recentes/odiadas) e na ordem
 * desejada, mais os episódios de podcast. As músicas vêm primeiro e o(s) episódio(s)
 * fecham a sessão — é a ordem natural de uma sessão que termina em podcast.
 *
 * Puro (só JVM) para poder testar a contagem de duração sem Android.
 */
object SessionMix {

    /** Trava de segurança: uma sessão nunca cresce sem fim, mesmo sem durações válidas. */
    const val MAX_TRACKS = 40

    /** Fallback quando o pedido não trouxe duração (a "sessão" sozinha). */
    const val DEFAULT_MINUTES = 40

    fun build(songs: List<Song>, podcasts: List<Song>, plan: DjCommander.SessionPlan): List<Song> {
        val targetMs = (plan.durationMin.takeIf { it > 0 } ?: DEFAULT_MINUTES) * 60_000L
        val episodes = podcasts.take((plan.podcasts ?: 0).coerceAtLeast(0))
        val episodeMs = episodes.sumOf { it.durationMs.coerceAtLeast(0L) }
        val minSongs = plan.songs ?: 0

        val music = ArrayList<Song>()
        var musicMs = 0L
        for (song in songs) {
            if (music.size >= MAX_TRACKS) break
            val enoughByCount = music.size >= minSongs
            val enoughByDuration = musicMs + episodeMs >= targetMs
            if (enoughByCount && enoughByDuration) break
            music.add(song)
            musicMs += song.durationMs.coerceAtLeast(0L)
        }
        return music + episodes
    }
}
