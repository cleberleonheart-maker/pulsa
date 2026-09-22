package com.pulsa.player.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.BanActivity
import com.pulsa.player.DjActivity
import com.pulsa.player.MainActivity
import com.pulsa.player.NowPlayingActivity
import com.pulsa.player.R
import com.pulsa.player.RadioActivity
import com.pulsa.player.core.Permissions
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.dj.AvatarFavorites
import com.pulsa.player.dj.DjEngine
import com.pulsa.player.dj.DjLearn
import com.pulsa.player.dj.DjSessionMemory
import com.pulsa.player.dj.DjSuggest
import com.pulsa.player.dj.DjVoice
import com.pulsa.player.dj.TamiRadio
import com.pulsa.player.playback.Playback
import com.pulsa.player.sync.Telemetry

/**
 * Home "Virgin" no modelo dashboard: deck do avatar, console Bombar/Relaxar,
 * card "Tocando agora", atalhos da biblioteca (horizontais) e linhas do
 * Assistente (DJ/Rádio/card funcionalidades) — esqueleto novo, seções.
 */
class VirginHomeFragment : Fragment() {

    private class RowState(val status: TextView, val statusText: () -> String, val isOn: () -> Boolean)

    data class AssistantData(
        val icon: String,
        val label: String,
        val status: () -> String,
        val isOn: () -> Boolean,
        val action: () -> Unit
    )

    data class ShortcutData(val icon: String, val label: String, val key: String)

    private var voice: DjVoice? = null
    private val rowRefs = mutableListOf<View>()

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_virgin_home, container, false)
        view.findViewById<View>(R.id.btn_party).setOnClickListener { party() }
        view.findViewById<View>(R.id.btn_relax).setOnClickListener { relax() }
        val hero = view.findViewById<View>(R.id.hero_avatar)
        hero.setOnClickListener { speak(getString(R.string.dj_voice_greeting)) }
        hero.setOnLongClickListener {
            startActivity(Intent(requireContext(), BanActivity::class.java))
            true
        }
        val greet = view.findViewById<TextView>(R.id.btn_greet)
        greet.setOnClickListener { greet() }
        bindResumeCard(view)
        buildShortcuts(view.findViewById(R.id.shortcuts_row))
        buildAssistantRows(view.findViewById(R.id.assistant_list))
        buildSkinRow(view.findViewById(R.id.skin_row))
        loadRecents(view)
        refresh()
        refreshResume()
        renderHero(view)
        return view
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshResume()
        renderHero()
        loadRecents(view)
        view?.findViewById<ViewGroup>(R.id.skin_row)?.let { buildSkinRow(it) }
    }

    override fun onDestroyView() {
        voice?.stop()
        voice?.shutdown()
        voice = null
        rowRefs.clear()
        super.onDestroyView()
    }

    private fun bindResumeCard(view: View) {
        val card = view.findViewById<View>(R.id.resume_card)
        card.setOnClickListener { (activity as? MainActivity)?.openNowPlaying() }
        view.findViewById<View>(R.id.resume_play).setOnClickListener {
            Playback.toggle()
            refreshResume()
        }
    }

    /** Sessão "Adicionadas recentemente": capas horizontais; toque inicia a partir da faixa. */
    private fun loadRecents(view: View?) {
        val v = view ?: return
        if (!isAdded) return
        val title = v.findViewById<TextView>(R.id.home_section_recent)
        val scroll = v.findViewById<View>(R.id.recents_scroll)
        val act = activity ?: return
        val ctx = act.applicationContext
        ThreadPool.post {
            val recents = Library.recentSongs(ctx)
            ThreadPool.onUi {
                if (!isAdded || view != this.view) return@onUi
                if (recents.isEmpty()) {
                    title?.visibility = View.GONE
                    scroll?.visibility = View.GONE
                    return@onUi
                }
                val row = v.findViewById<ViewGroup>(R.id.recents_row)
                row.visibility = View.VISIBLE
                row.removeAllViews()
                recents.forEachIndexed { i, song ->
                    val item = makeRecentCover(ctx, song)
                    val startIndex = i
                    item.setOnClickListener {
                        val all = Library.recentSongs(ctx)
                        if (all.isNotEmpty()) {
                            Playback.setShuffle(false)
                            Playback.setRepeatAll(true)
                            Playback.start(all, startIndex.coerceIn(0, all.lastIndex))
                        }
                    }
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    if (i > 0) lp.marginStart = dp(10)
                    row.addView(item, lp)
                }
                title?.visibility = View.VISIBLE
                scroll?.visibility = View.VISIBLE
            }
        }
    }

    private fun makeRecentCover(ctx: Context, song: com.pulsa.player.model.Song): View {
        val item = LinearLayout(ctx)
        item.orientation = LinearLayout.VERTICAL
        item.gravity = Gravity.CENTER_HORIZONTAL
        val cover = com.google.android.material.imageview.ShapeableImageView(ctx)
        cover.layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        cover.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        cover.setImageResource(R.drawable.ic_music_note)
        cover.imageTintList = androidx.core.content.res.ResourcesCompat.getColorStateList(
            ctx.resources, R.color.ic_placeholder, ctx.theme
        )
        cover.background = ContextCompat.getDrawable(ctx, R.drawable.bg_album_art)
        cover.shapeAppearanceModel = cover.shapeAppearanceModel
            .toBuilder()
            .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, dp(14).toFloat())
            .build()
        val label = TextView(ctx)
        label.text = song.title
        label.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        label.textSize = 10f
        label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        val labelLp = LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT)
        labelLp.topMargin = dp(4)
        item.addView(cover)
        item.addView(label, labelLp)
        ArtLoader.load(song.albumId, song.path, cover)
        return item
    }

    /** Seletor rápido de efeitos visuais (skin) direto na home. */
    private fun buildSkinRow(container: ViewGroup) {
        val ctx = requireContext()
        container.removeAllViews()
        val current = Settings.skin(ctx)
        Settings.SKIN_ORDER.forEachIndexed { i, key ->
            val selected = key == current
            val item = LinearLayout(ctx)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.setPadding(dp(2), dp(4), dp(2), dp(2))

            val dot = View(ctx)
            val size = dp(if (selected) 40 else 34)
            val shape = android.graphics.drawable.GradientDrawable()
            shape.shape = android.graphics.drawable.GradientDrawable.OVAL
            shape.setColor(skinColor(key))
            shape.setStroke(
                dp(if (selected) 3 else 0),
                android.graphics.Color.WHITE
            )
            dot.background = shape
            dot.contentDescription = getString(skinLabelRes(key))

            val label = TextView(ctx)
            label.text = getString(skinLabelRes(key))
            label.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (selected) R.color.primary_light else R.color.text_secondary
                )
            )
            label.textSize = 10f
            label.maxLines = 1

            item.addView(dot, LinearLayout.LayoutParams(size, size))
            val labelLp = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)
            labelLp.topMargin = dp(3)
            labelLp.marginStart = dp(-11)
            item.addView(label, labelLp)
            item.setOnClickListener {
                Settings.setSkin(ctx, key)
                NowPlayingActivity.current?.refreshVisuals()
                Telemetry.log(ctx, "home skin=$key")
                view?.findViewById<ViewGroup>(R.id.skin_row)?.let { buildSkinRow(it) }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (i > 0) lp.marginStart = dp(4)
            container.addView(item, lp)
        }
    }

    private fun skinColor(skin: String): Int = android.graphics.Color.parseColor(
        when (skin) {
            Settings.SKIN_NEON -> "#FF2B72"
            Settings.SKIN_AURORA -> "#A78BFA"
            Settings.SKIN_PARTICLES -> "#34D399"
            Settings.SKIN_NEBULA -> "#22D3EE"
            else -> "#475569"
        }
    )

    private fun skinLabelRes(skin: String): Int = when (skin) {
        Settings.SKIN_NEON -> R.string.skin_short_neon
        Settings.SKIN_AURORA -> R.string.skin_short_aurora
        Settings.SKIN_PARTICLES -> R.string.skin_short_particles
        Settings.SKIN_NEBULA -> R.string.skin_short_nebula
        else -> R.string.skin_short_off
    }

    private fun buildShortcuts(container: ViewGroup) {
        val ctx = requireContext()
        val items = listOf(
            ShortcutData("🎵", getString(R.string.tab_songs), "songs"),
            ShortcutData("💟", getString(R.string.tab_favorites), BibliotecaFragment.SECTION_FAVORITES),
            ShortcutData("💿", getString(R.string.tab_albums), BibliotecaFragment.SECTION_ALBUMS),
            ShortcutData("🧑‍🎤", getString(R.string.tab_artists), BibliotecaFragment.SECTION_ARTISTS),
            ShortcutData("🎞️", getString(R.string.tab_videos), BibliotecaFragment.SECTION_VIDEOS),
            ShortcutData("📃", getString(R.string.tab_playlists), BibliotecaFragment.SECTION_PLAYLISTS)
        )
        items.forEachIndexed { i, s ->
            val chip = LinearLayout(ctx)
            chip.orientation = LinearLayout.VERTICAL
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(10), dp(10), dp(10), dp(10))
            chip.background = ContextCompat.getDrawable(ctx, R.drawable.bg_vcard)
            val icon = TextView(ctx)
            icon.text = s.icon
            icon.textSize = 22f
            val label = TextView(ctx)
            label.text = s.label
            label.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            label.textSize = 11f
            label.maxLines = 2
            label.gravity = Gravity.CENTER
            chip.addView(icon, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
            chip.addView(label, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
            chip.setOnClickListener { (activity as? MainActivity)?.openHomeShortcut(s.key) }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (i > 0) lp.marginStart = dp(10)
            chip.layoutParams = lp
            container.addView(chip)
        }
    }

    private fun buildAssistantRows(container: ViewGroup) {
        val ctx = requireContext()
        val rows = listOf(
            AssistantData("🎭", getString(R.string.v_avatar),
                {
                    getString(
                        if (Settings.masculineAvatar(ctx)) R.string.dj_voice_name_male else R.string.dj_voice_name
                    )
                },
                { false }, { toggleAvatar() }),
            AssistantData("🗣", getString(R.string.v_voice),
                { getString(if (Settings.djVoice(ctx)) R.string.status_on else R.string.status_off) },
                { Settings.djVoice(ctx) }, { toggleVoice() }),
            AssistantData("🌐", getString(R.string.v_language),
                { getString(Settings.languageLabelRes(Settings.language(ctx))) },
                { false }, { cycleLanguage() }),
            AssistantData("🎙", getString(R.string.v_mic),
                { getString(if (Settings.recToken(ctx).isNotBlank()) R.string.status_active else R.string.v_mic_off) },
                { Settings.recToken(ctx).isNotBlank() }, { showMicDialog() }),
            AssistantData("📻", getString(R.string.v_radio_format, Settings.assistantName(ctx)),
                { getString(if (Settings.tamiRadio(ctx)) R.string.status_on else R.string.status_off) },
                { Settings.tamiRadio(ctx) }, { toggleRadio() }),
            AssistantData("📡", getString(R.string.radio),
                { getString(R.string.v_radio_sub) }, { false }, {
                    startActivity(Intent(requireContext(), RadioActivity::class.java))
                }),
            AssistantData("🎧", getString(R.string.dj_title),
                { getString(R.string.v_dj_sub) }, { false }, {
                    startActivity(Intent(requireContext(), DjActivity::class.java))
                }),
            AssistantData("💤", getString(R.string.v_sleep),
                { getString(R.string.v_sleep_sub) }, { false }, { sleep() }),
            AssistantData("🎯", getString(R.string.v_rec),
                { getString(R.string.v_rec_sub) }, { false }, { recommend() }),
            AssistantData("📊", getString(R.string.v_stats),
                { getString(R.string.v_stats_sub) }, { false }, { showStats() }),
            AssistantData("📂", getString(R.string.v_scan),
                {
                    val n = settingsSongs()
                    getString(R.string.v_scan_status, n)
                }, { false }, { scanLibrary() })
        )

        rows.forEach { r ->
            val row = makeRow(r)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10)
            row.layoutParams = lp
            rowRefs.add(row)
            container.addView(row)
        }
    }

    private fun makeRow(r: AssistantData): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(12), dp(12), dp(12))
        row.background = ContextCompat.getDrawable(ctx, R.drawable.bg_vcard)

        val icon = TextView(ctx)
        icon.text = r.icon
        icon.textSize = 20f

        val label = TextView(ctx)
        label.text = r.label
        label.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        label.textSize = 14f
        label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END

        val status = TextView(ctx)
        status.textSize = 12f
        status.setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
        status.maxLines = 1
        status.ellipsize = android.text.TextUtils.TruncateAt.END

        val chevron = ImageView(ctx)
        chevron.setImageResource(R.drawable.ic_arrow_forward)
        chevron.setColorFilter(ContextCompat.getColor(ctx, R.color.text_tertiary))
        chevron.setPadding(dp(4), dp(4), dp(4), dp(4))

        row.addView(icon)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })
        row.addView(status)
        row.addView(chevron, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            marginStart = dp(8)
        })
        row.tag = RowState(status, r.status, r.isOn)
        row.setOnClickListener { r.action() }
        return row
    }

    private fun refresh() {
        if (view == null) return
        val ctx = requireContext()
        for (row in rowRefs) {
            val st = row.tag as RowState
            st.status.text = st.statusText()
            val on = st.isOn()
            row.background = ContextCompat.getDrawable(
                ctx,
                if (on) R.drawable.bg_vcard_on else R.drawable.bg_vcard
            )
            st.status.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (on) R.color.primary_light else R.color.text_tertiary
                )
            )
        }
    }

    private fun refreshResume() {
        val v = view ?: return
        val card = v.findViewById<View>(R.id.resume_card) ?: return
        val song = Playback.currentSong
        if (song == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        v.findViewById<TextView>(R.id.resume_title)?.text = song.title
        v.findViewById<TextView>(R.id.resume_artist)?.text = song.artist + " • " + song.album
        ArtLoader.load(song.albumId, song.path, v.findViewById(R.id.resume_art))
        v.findViewById<ImageView>(R.id.resume_play)?.setImageResource(
            if (Playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    // ---------- ações ----------

    private fun party() = buildMix(DjEngine.Intensity.WILD, 16, sleep = false, speakRes = R.string.dj_voice_mood_wild)

    private fun relax() {
        val ctx = requireContext()
        Telemetry.log(ctx, "Virgin home relaxar")
        buildMix(DjEngine.Intensity.CALM, 8, sleep = false, speakRes = R.string.v_relax_msg)
    }

    private fun sleep() = buildMix(DjEngine.Intensity.CALM, 8, sleep = true, speakRes = R.string.dj_voice_sleep)

    private fun buildMix(
        intensity: DjEngine.Intensity,
        maxSize: Int,
        sleep: Boolean,
        speakRes: Int
    ) {
        val act = requireActivity()
        if (!Permissions.hasAccess(act)) {
            Toast.makeText(act, R.string.dj_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        val ctx = act.applicationContext
        ThreadPool.post {
            val songs = Library.allSongs(ctx)
            val favIds = runCatching {
                PlaylistDb.get(ctx).favorites().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val learn = DjLearn.learn(ctx)
            val set = DjEngine.build(
                songs, favIds, DjEngine.Source.ALL, intensity, learn,
                maxSize = maxSize,
                exclude = DjSessionMemory.recentIds()
            )
            ThreadPool.onUi {
                if (act.isFinishing || act.isDestroyed) return@onUi
                if (set.isEmpty()) {
                    Toast.makeText(act, R.string.dj_empty, Toast.LENGTH_LONG).show()
                    return@onUi
                }
                Telemetry.log(ctx, "Virgin home mix n=${set.size}")
                Playback.setShuffle(intensity == DjEngine.Intensity.WILD)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(sleep)
                Playback.start(if (intensity == DjEngine.Intensity.WILD) set.shuffled() else set, 0)
                speak(getString(speakRes, set.size))
            }
        }
    }

    private fun cycleLanguage() {
        val ctx = requireContext()
        val next = when (Settings.language(ctx)) {
            Settings.LANG_PT -> Settings.LANG_EN
            Settings.LANG_EN -> Settings.LANG_ES
            else -> Settings.LANG_PT
        }
        Settings.setLanguage(ctx, next)
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(Settings.languageTag(next))
        )
        voice = null
        requireActivity().recreate()
    }

    private fun toggleVoice() {
        val ctx = requireContext()
        val now = !Settings.djVoice(ctx)
        Settings.setDjVoice(ctx, now)
        Telemetry.log(ctx, "Virgin home voz $now")
        speak(getString(if (now) R.string.v_voice_on else R.string.v_voice_off), force = now)
        refresh()
    }

    private fun toggleAvatar() {
        val ctx = requireContext()
        val next = !Settings.masculineAvatar(ctx)
        Settings.setMasculineAvatar(ctx, next)
        DancingVirginView.refreshAll()
        Telemetry.log(ctx, "Virgin avatar masculino=$next")
        val name = getString(
            if (next) R.string.dj_voice_name_male else R.string.dj_voice_name
        )
        Toast.makeText(
            requireActivity(),
            getString(R.string.v_avatar_switched, name),
            Toast.LENGTH_SHORT
        ).show()
        speak(
            getString(if (next) R.string.v_avatar_speak_male else R.string.v_avatar_speak_female, name),
            force = true
        )
        refresh()
    }

    private fun toggleRadio() {
        val ctx = requireContext()
        if (Settings.tamiRadio(ctx)) {
            TamiRadio.stop(ctx)
        } else {
            TamiRadio.start(ctx)
        }
        refresh()
    }

    private fun recommend() {
        val ctx = requireContext()
        val songs = Library.allSongs(ctx)
        if (songs.isEmpty()) {
            Toast.makeText(requireActivity(), R.string.dj_empty, Toast.LENGTH_LONG).show()
            return
        }
        if (DjSuggest.isReady(ctx)) {
            speak(getString(R.string.v_rec_thinking))
            ThreadPool.post {
                val suggestion = DjSuggest.suggest(ctx, Library.allSongs(ctx))
                ThreadPool.onUi {
                    if (suggestion == null) {
                        speak(getString(R.string.v_rec_fail))
                        return@onUi
                    }
                    playSuggestion(ctx, suggestion)
                }
            }
        } else {
            val suggestion = DjSuggest.offline(ctx, songs)
            if (suggestion.title.isBlank()) {
                speak(getString(R.string.v_rec_fail))
                return
            }
            speak(getString(R.string.v_rec_thinking))
            ThreadPool.post {
                ThreadPool.onUi { playSuggestion(ctx, suggestion) }
            }
        }
    }

    private fun playSuggestion(ctx: Context, suggestion: DjSuggest.Suggestion) {
        val all = Library.allSongs(ctx)
        val found = all.firstOrNull {
            it.title.equals(suggestion.title, ignoreCase = true)
        }
        Telemetry.log(ctx, "Virgin AI sugeriu: ${suggestion.title}")
        speak(DjSuggest.toSpeech(suggestion), onDone = {
            if (found != null) {
                Playback.start(listOf(found) + all.filter { it.id != found.id }, 0)
            }
        })
    }

    private fun showStats() {
        val act = requireActivity()
        ThreadPool.post {
            val ctx = act.applicationContext
            val songs = Library.allSongs(ctx).size
            val favs = runCatching { PlaylistDb.get(ctx).favorites().size }.getOrDefault(0)
            val playlists = runCatching { PlaylistDb.get(ctx).playlists().size }.getOrDefault(0)
            val cacheMb = Settings.cacheSize(ctx) / (1024L * 1024L)
            ThreadPool.onUi {
                if (act.isFinishing || act.isDestroyed) return@onUi
                MaterialAlertDialogBuilder(act)
                    .setTitle(R.string.v_stats_title)
                    .setMessage(getString(R.string.v_stats_msg, songs, favs, playlists, cacheMb))
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }
    }

    private fun scanLibrary() {
        val act = requireActivity()
        if (!Permissions.hasAccess(act)) {
            Permissions.request(act)
            return
        }
        ThreadPool.post {
            val n = Library.allSongs(act.applicationContext).size
            ThreadPool.onUi {
                if (act.isFinishing || act.isDestroyed) return@onUi
                Telemetry.log(act, "Virgin home scan n=$n")
                Toast.makeText(act, getString(R.string.v_scan_done, n), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showMicDialog() {
        val act = requireActivity()
        val commands = listOf(
            R.string.dj_commands_mix to R.drawable.ic_shuffle,
            R.string.dj_commands_sleep to R.drawable.ic_sleep,
            R.string.dj_commands_suggest to R.drawable.ic_play_circle,
            R.string.dj_commands_repeat to R.drawable.ic_repeat,
            R.string.dj_commands_next to R.drawable.ic_skip_next,
            R.string.dj_commands_skip to R.drawable.ic_skip_next,
            R.string.dj_commands_pause to R.drawable.ic_pause,
            R.string.dj_commands_play to R.drawable.ic_play,
            R.string.dj_commands_fav to R.drawable.ic_favorite,
            R.string.dj_commands_scan to R.drawable.ic_search,
            R.string.dj_commands_hello to R.drawable.ic_mic
        )
        val accent = ContextCompat.getColor(act, R.color.primary)
        val view = layoutInflater.inflate(R.layout.dialog_voice_commands, null)
        val container = view.findViewById<ViewGroup>(R.id.commands_container)
        for ((strRes, iconRes) in commands) {
            val row = layoutInflater.inflate(R.layout.item_command, container, false)
            val text = getString(strRes)
            val dash = text.indexOf(" — ")
            row.findViewById<TextView>(R.id.command_phrase).text =
                if (dash > 0) text.substring(0, dash).trim() else text
            row.findViewById<TextView>(R.id.command_desc).text =
                if (dash > 0) text.substring(dash + 3).trim() else ""
            val icon = row.findViewById<android.widget.ImageView>(R.id.command_icon)
            icon.setImageResource(iconRes)
            icon.setColorFilter(accent)
            container.addView(row)
        }
        val dialog = MaterialAlertDialogBuilder(act)
            .setView(view)
            .setCancelable(true)
            .create()
        view.findViewById<MaterialButton>(R.id.commands_close)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    // ---------- voz ----------

    private fun greet() {
        val ctx = requireContext()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greetRes = when {
            hour < 12 -> R.string.tami_clock_greet_day
            hour < 18 -> R.string.tami_clock_greet_after
            else -> R.string.tami_clock_greet_night
        }
        val lineRes = when (greetRes) {
            R.string.tami_clock_greet_day -> R.string.home_greet_day
            R.string.tami_clock_greet_after -> R.string.home_greet_after
            else -> R.string.home_greet_night
        }
        val name = getString(
            if (Settings.masculineAvatar(ctx)) R.string.dj_voice_name_male else R.string.dj_voice_name
        )
        view?.findViewById<TextView>(R.id.btn_greet)?.text = "👋 " + getString(greetRes)
        speak("$name, ${getString(lineRes)}")
    }

    private fun speak(text: String, force: Boolean = false, onDone: (() -> Unit)? = null) {
        val ctx = requireContext()
        if (text.isBlank()) return
        if (force || Settings.djVoice(ctx)) {
            if (voice == null) {
                voice = DjVoice(ctx, Settings.languageTag(Settings.language(ctx)))
            }
            voice!!.init { ready -> if (ready) voice!!.speak(text, null, onDone) }
        }
    }

    private fun settingsSongs(): Int = runCatching { Library.allSongs(requireContext()).size }.getOrDefault(0)

    private fun renderHero() {
        val v = view ?: return
        renderHero(v)
        val ctx = requireContext()
        AvatarFavorites.favorite(ctx) { fav ->
            ThreadPool.onUi {
                if (!isAdded) return@onUi
                view?.findViewById<TextView>(R.id.hero_fav)?.apply {
                    if (fav == null) {
                        visibility = View.GONE
                    } else {
                        text = "♥ " + fav.title + " — " + fav.artist
                        visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun renderHero(view: View) {
        val ctx = requireContext()
        view.findViewById<TextView>(R.id.hero_name)?.text = Settings.assistantName(ctx)
        view.findViewById<TextView>(R.id.hero_status)?.let { st ->
            st.text = if (Playback.isPlaying) {
                getString(R.string.hero_status_playing)
            } else {
                val n = settingsSongs()
                when {
                    n > 0 -> getString(
                        if (Settings.masculineAvatar(ctx)) R.string.hero_status_ready_m else R.string.hero_status_ready_f
                    )
                    else -> getString(R.string.hero_status_no_songs)
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}