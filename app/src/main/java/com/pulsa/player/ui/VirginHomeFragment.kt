package com.pulsa.player.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.BanActivity
import com.pulsa.player.R
import com.pulsa.player.core.Permissions
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.dj.DjEngine
import com.pulsa.player.dj.DjLearn
import com.pulsa.player.dj.DjSessionMemory
import com.pulsa.player.dj.DjSuggest
import com.pulsa.player.dj.DjVoice
import com.pulsa.player.dj.TamiRadio
import com.pulsa.player.playback.Playback
import com.pulsa.player.sync.Telemetry

/**
 * Home "Virgin" no modelo da TAMI: cards de funções (idioma, voz, comando de
 * voz, rádio Virgin FM, sono, recomendar, estatísticas, escanear) chamando as
 * funções que já existem. Sem card de curiosidade (a locutora já faz).
 */
class VirginHomeFragment : Fragment() {

    private class CardState(val status: TextView, val statusText: () -> String, val isOn: () -> Boolean)

    data class VirginCardData(
        val icon: String,
        val label: String,
        val status: () -> String,
        val isOn: () -> Boolean,
        val action: () -> Unit
    )

    private var voice: DjVoice? = null
    private val cardRefs = mutableListOf<View>()

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
        buildCards(view.findViewById(R.id.cards_container))
        refresh()
        renderHero(view)
        return view
    }

    override fun onResume() {
        super.onResume()
        refresh()
        renderHero()
    }

    override fun onDestroyView() {
        voice?.stop()
        voice?.shutdown()
        voice = null
        cardRefs.clear()
        super.onDestroyView()
    }

    private fun buildCards(container: ViewGroup) {
        val ctx = requireContext()
        val rows = listOf(
            VirginCardData("🌐", getString(R.string.v_language),
                { getString(Settings.languageLabelRes(Settings.language(ctx))) },
                { false }, { cycleLanguage() }),
            VirginCardData("🗣", getString(R.string.v_voice),
                { getString(if (Settings.djVoice(ctx)) R.string.status_on else R.string.status_off) },
                { Settings.djVoice(ctx) }, { toggleVoice() }),
            VirginCardData("🎭", getString(R.string.v_avatar),
                {
                    getString(
                        if (Settings.masculineAvatar(ctx)) R.string.dj_voice_name_male else R.string.dj_voice_name
                    )
                },
                { false }, { toggleAvatar() }),
            VirginCardData("🎙", getString(R.string.v_mic),
                { getString(if (Settings.recToken(ctx).isNotBlank()) R.string.status_active else R.string.v_mic_off) },
                { Settings.recToken(ctx).isNotBlank() }, { showMicDialog() }),
            VirginCardData("📻", getString(R.string.v_radio_format, Settings.assistantName(ctx)),
                { getString(if (Settings.tamiRadio(ctx)) R.string.status_on else R.string.status_off) },
                { Settings.tamiRadio(ctx) }, { toggleRadio() }),
            VirginCardData("💤", getString(R.string.v_sleep),
                { getString(R.string.v_sleep_sub) }, { false }, { sleep() }),
            VirginCardData("🎯", getString(R.string.v_rec),
                { getString(R.string.v_rec_sub) }, { false }, { recommend() }),
            VirginCardData("📊", getString(R.string.v_stats),
                { getString(R.string.v_stats_sub) }, { false }, { showStats() }),
            VirginCardData("📂", getString(R.string.v_scan),
                {
                    val n = settingsSongs()
                    getString(R.string.v_scan_status, n)
                }, { false }, { scanLibrary() })
        )

        rows.chunked(2).forEach { pair ->
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12)
            row.layoutParams = lp
            pair.forEachIndexed { i, r ->
                val card = makeCard(r)
                val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (i > 0) clp.marginStart = dp(12)
                card.layoutParams = clp
                cardRefs.add(card)
                row.addView(card)
            }
            container.addView(row)
        }
    }

    private fun makeCard(r: VirginCardData): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER
        card.setPadding(dp(12), dp(14), dp(12), dp(14))
        card.background = ContextCompat.getDrawable(ctx, R.drawable.bg_vcard)

        val icon = TextView(ctx)
        icon.text = r.icon
        icon.textSize = 26f
        icon.gravity = Gravity.CENTER

        val label = TextView(ctx)
        label.text = r.label
        label.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        label.textSize = 13f
        label.gravity = Gravity.CENTER
        label.maxLines = 2

        val status = TextView(ctx)
        status.textSize = 11f
        status.setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
        status.gravity = Gravity.CENTER
        status.minHeight = dp(16)

        card.addView(icon)
        card.addView(label)
        card.addView(status)
        card.tag = CardState(status, r.status, r.isOn)
        card.setOnClickListener { r.action() }
        return card
    }

    private fun refresh() {
        val ctx = requireContext()
        for (view in cardRefs) {
            val st = view.tag as CardState
            st.status.text = st.statusText()
            val on = st.isOn()
            view.background = ContextCompat.getDrawable(
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
        if (!DjSuggest.isReady(ctx)) {
            speak(getString(R.string.v_rec_off))
            return
        }
        speak(getString(R.string.v_rec_thinking))
        ThreadPool.post {
            val suggestion = DjSuggest.suggest(ctx, Library.allSongs(ctx))
            ThreadPool.onUi {
                if (suggestion == null) {
                    speak(getString(R.string.v_rec_fail))
                    return@onUi
                }
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
        }
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

    private fun speak(text: String, force: Boolean = false, onDone: (() -> Unit)? = null) {
        val ctx = requireContext()
        if (text.isBlank()) return
        if (force || Settings.djVoice(ctx)) {
            if (voice == null) {
                voice = DjVoice(ctx, Settings.languageTag(Settings.language(ctx)))
            }
            voice!!.init { ready -> if (ready) voice!!.speak(text, onDone) }
        }
    }

    private fun settingsSongs(): Int = runCatching { Library.allSongs(requireContext()).size }.getOrDefault(0)

    private fun renderHero() {
        val v = view ?: return
        renderHero(v)
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