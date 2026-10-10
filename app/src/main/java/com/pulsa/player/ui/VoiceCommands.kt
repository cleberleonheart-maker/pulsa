package com.pulsa.player.ui

import com.pulsa.player.R

/**
 * Lista única dos comandos de voz da Virgin, usada no diálogo do microfone (Home) e no
 * `DjActivity`. Antes ficava duplicada nos dois lugares e as duas listas divergiram sem
 * ninguém notar — a da Home perdeu comandos no caminho (reconhecer, apagar, alarme...).
 * Agora há uma fonte só: todo comando novo entra aqui e aparece nos dois diálogos.
 */
object VoiceCommands {
    val ALL: List<Pair<Int, Int>> = listOf(
        R.string.dj_commands_mix to R.drawable.ic_shuffle,
        R.string.dj_commands_sleep to R.drawable.ic_sleep,
        R.string.dj_commands_only to R.drawable.ic_music_note,
        R.string.dj_commands_mixwith to R.drawable.ic_queue_music,
        R.string.dj_commands_repeat to R.drawable.ic_repeat,
        R.string.dj_commands_next to R.drawable.ic_skip_next,
        R.string.dj_commands_prev to R.drawable.ic_skip_prev,
        R.string.dj_commands_skip to R.drawable.ic_skip_next,
        R.string.dj_commands_dislike to R.drawable.ic_heart,
        R.string.dj_commands_pause to R.drawable.ic_pause,
        R.string.dj_commands_play to R.drawable.ic_play,
        R.string.dj_commands_fav to R.drawable.ic_favorite,
        R.string.dj_commands_dedicate to R.drawable.ic_favorite,
        R.string.dj_commands_info to R.drawable.ic_album,
        R.string.dj_commands_playcount to R.drawable.ic_album,
        R.string.dj_commands_scan to R.drawable.ic_search,
        R.string.dj_commands_recognize to R.drawable.ic_mic,
        R.string.dj_commands_delete to R.drawable.ic_delete,
        R.string.dj_commands_duplicates to R.drawable.ic_copy,
        R.string.dj_commands_pendrive to R.drawable.ic_download,
        R.string.dj_commands_suggest to R.drawable.ic_play_circle,
        R.string.dj_commands_visualizer to R.drawable.ic_dj,
        R.string.dj_commands_identity to R.drawable.virgin_avatar,
        R.string.dj_commands_thanks to R.drawable.ic_favorite,
        R.string.dj_commands_dynq to R.drawable.ic_queue_music,
        R.string.dj_commands_queue_save to R.drawable.ic_queue_music,
        R.string.dj_commands_decade to R.drawable.ic_album,
        R.string.dj_commands_scene to R.drawable.ic_play_circle,
        R.string.dj_commands_ambient_vol to R.drawable.ic_ambient,
        R.string.dj_commands_weekly to R.drawable.ic_album,
        R.string.dj_commands_sleeptimer to R.drawable.ic_sleep,
        R.string.dj_commands_sleep_end to R.drawable.ic_sleep,
        R.string.dj_commands_alarm to R.drawable.ic_sleep,
        R.string.dj_commands_video to R.drawable.ic_play_circle,
        R.string.dj_commands_video_open to R.drawable.ic_videocam,
        R.string.dj_commands_video_back to R.drawable.ic_replay_15,
        R.string.dj_commands_hello to R.drawable.ic_mic
    )
}
