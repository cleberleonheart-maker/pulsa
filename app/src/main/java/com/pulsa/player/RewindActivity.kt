package com.pulsa.player

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.data.Library
import com.pulsa.player.dj.DjLearn
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.ui.AnimatedBackground
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pulsa Rewind: resumo local da sua música (de sempre / 30d / 7d) gerado do
 * play_log. Top 10 com capa, gráficos por mês/semana/hora e fatos. Tocar um
 * item reproduz o pódio completo na ordem, como no rewind web.
 */
class RewindActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private var days = Int.MAX_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rewind)
        AnimatedBackground.apply(this)

        findViewById<View>(R.id.btn_rw_back).setOnClickListener { finish() }

        setupTab(R.id.rw_tab_all, Int.MAX_VALUE)
        setupTab(R.id.rw_tab_30, 30)
        setupTab(R.id.rw_tab_7, 7)

        container = findViewById(R.id.rw_container)
        styleTabs()
        load()
    }

    private fun setupTab(id: Int, d: Int) {
        findViewById<View>(id).setOnClickListener {
            days = d
            styleTabs()
            load()
        }
    }

    private fun styleTabs() {
        val all = findViewById<View>(R.id.rw_tab_all)
        val t30 = findViewById<View>(R.id.rw_tab_30)
        val t7 = findViewById<View>(R.id.rw_tab_7)
        listOf(all to Int.MAX_VALUE, t30 to 30, t7 to 7).forEach { (v, d) ->
            val on = d == days
            v.background = ContextCompat.getDrawable(
                this, if (on) R.drawable.bg_pill_cyan else R.drawable.bg_vcard
            )
            (v as TextView).setTextColor(
                ContextCompat.getColor(
                    this, if (on) R.color.on_primary else R.color.text_secondary
                )
            )
            (v as TextView).setTypeface(
                null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
    }

    private fun load() {
        container.removeAllViews()
        val loading = TextView(this)
        loading.gravity = Gravity.CENTER
        loading.setPadding(0, dp(40), 0, 0)
        loading.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        loading.text = getString(R.string.rw_loading)
        container.addView(loading)
        ThreadPool.post {
            val ctx = applicationContext
            val songs = Library.allSongs(ctx)
            val rw = DjLearn.rewind(ctx, days, 10)
            ThreadPool.onUi {
                if (isFinishing || isDestroyed) return@onUi
                render(rw, songs)
            }
        }
    }

    private fun render(rw: DjLearn.RewindResult, songs: List<Song>) {
        container.removeAllViews()
        val byId = songs.associateBy { it.id }

        if (rw.plays == 0) {
            val empty = TextView(this)
            empty.gravity = Gravity.CENTER
            empty.setPadding(0, dp(48), 0, 0)
            empty.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            empty.textSize = 14f
            empty.text = getString(R.string.rw_empty)
            container.addView(empty)
            return
        }

        // ---- hero: 2x2 ----
        val hours = rw.plays * 3.3f / 60f
        val hoursLabel = if (hours >= 1f)
            String.format(Locale.getDefault(), "%.1f h", hours)
        else String.format(Locale.getDefault(), "%d min", Math.round(hours * 60))
        val first = rw.firstTs?.let { ts ->
            val d = Date(ts * 1000L)
            val label = byId[rw.firstSongId]?.title
                ?: getString(R.string.rw_na)
            "$label\n" + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(d)
        } ?: getString(R.string.rw_na)

        addCardRow(listOf(
            heroCard(getString(R.string.rw_plays), rw.plays.toLocal(), rw.top.sumOf { it.second }.toLocal()),
            heroCard(getString(R.string.rw_hours), hoursLabel, "")
        ))
        addCardRow(listOf(
            heroCard(getString(R.string.rw_uniq), rw.unique.toLocal(), ""),
            heroCard(getString(R.string.rw_first), first, getString(R.string.rw_first_sub))
        ))

        // ---- top 10 ----
        container.addView(sectionTitle(getString(R.string.rw_top, rw.top.size)))
        val topRow = LinearLayout(this)
        topRow.orientation = LinearLayout.VERTICAL
        container.addView(topRow)
        rw.top.forEachIndexed { i, (id, c) ->
            topRow.addView(topRow(byId[id], i, c, rw.top))
        }

        // ---- gráficos ----
        container.addView(sectionTitle(getString(R.string.rw_when)))
        if (days != 7) {
            container.addView(chartCard(
                getString(R.string.rw_month),
                rw.months.map { it.second },
                rw.months.map { shortMonth(it.first) },
                multiLine = false
            ))
        }
        container.addView(chartCard(
            getString(R.string.rw_week), rw.week.map { it },
            getString(R.string.rw_wd_axis).split(","),
            multiLine = false
        ))
        container.addView(chartCard(
            getString(R.string.rw_hour),
            rw.hours.map { it },
            rw.hours.indices.map { if (it % 3 == 0) "$it" else "" },
            multiLine = false
        ))

        // ---- fatos ----
        container.addView(sectionTitle(getString(R.string.rw_facts)))
        val peak = rw.hours.indexOf(rw.hours.maxOrNull() ?: 0)
        val owl = rw.nightOwl?.let { (id, _) -> byId[id]?.title } ?: getString(R.string.rw_none)
        addCardRow(listOf(
            factCard(getString(R.string.rw_fact_day),
                rw.bestDay?.first ?: getString(R.string.rw_na),
                rw.bestDay?.let { getString(R.string.rw_toques, it.second.toLocal()) } ?: ""),
            factCard(getString(R.string.rw_fact_hour),
                if (rw.hours.sum() == 0) getString(R.string.rw_na) else "$peak" + getString(R.string.rw_hour_suffix),
                getString(R.string.rw_toques, rw.hours[peak].toLocal()))
        ))
        container.addView(factCard(getString(R.string.rw_fact_night), owl,
            getString(R.string.rw_night_owl)))
    }

    private fun Int.toLocal(): String = String.format(Locale.getDefault(), "%,d", this)

    private fun shortMonth(code: String): String {
        val mm = code.substring(0, 2).toInt()
        return getString(R.string.rw_months).split(",")
            .getOrElse(mm - 1) { code }.substring(0, Math.min(4, 3))
    }

    // ---------- componentes ----------

    private fun sectionTitle(text: String): TextView {
        val t = TextView(this)
        t.text = text.uppercase(Locale.getDefault())
        t.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        t.textSize = 11f
        t.setTypeface(null, android.graphics.Typeface.BOLD)
        t.setPadding(0, dp(20), 0, dp(10))
        return t
    }

    private fun heroCard(k: String, v: String, sub: String): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = ContextCompat.getDrawable(this, R.drawable.bg_vcard)
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        val kt = TextView(this)
        kt.text = k.uppercase(Locale.getDefault())
        kt.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        kt.textSize = 10f
        card.addView(kt)
        val vt = TextView(this)
        vt.text = v
        vt.setTextColor(ContextCompat.getColor(this, R.color.primary_light))
        vt.textSize = 20f
        vt.setTypeface(null, android.graphics.Typeface.BOLD)
        vt.setPadding(0, dp(4), 0, 0)
        card.addView(vt)
        if (sub.isNotEmpty()) {
            val st = TextView(this)
            st.text = sub
            st.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            st.textSize = 10f
            st.setPadding(0, dp(2), 0, 0)
            card.addView(st)
        }
        return card
    }

    private fun addCardRow(cards: List<View>) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        row.layoutParams = lp
        cards.forEachIndexed { i, c ->
            val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) clp.marginStart = dp(10)
            c.layoutParams = clp
            row.addView(c)
        }
        container.addView(row)
    }

    private fun topRow(song: Song?, index: Int, count: Int, top: List<Pair<Long, Int>>): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = ContextCompat.getDrawable(this, R.drawable.bg_vcard)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        row.layoutParams = lp
        row.setPadding(dp(12), dp(10), dp(12), dp(10))

        val rank = TextView(this)
        rank.text = (index + 1).toString()
        rank.gravity = Gravity.CENTER
        rank.setTextColor(ContextCompat.getColor(this, R.color.white))
        rank.textSize = 13f
        rank.setTypeface(null, android.graphics.Typeface.BOLD)
        val rl = LinearLayout.LayoutParams(dp(32), dp(32))
        rl.marginEnd = dp(10)
        rank.layoutParams = rl
        rank.background = badge(index)
        row.addView(rank)

        val art = ImageView(this)
        val al = LinearLayout.LayoutParams(dp(42), dp(42))
        al.marginEnd = dp(10)
        art.layoutParams = al
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        art.background = ContextCompat.getDrawable(this, R.drawable.bg_album_art)
        if (song != null) {
            ArtLoader.load(song.albumId, song.path, art)
        } else {
            art.setImageResource(R.drawable.ic_music_note)
            art.setColorFilter(ContextCompat.getColor(this, R.color.ic_placeholder))
        }
        row.addView(art)

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        info.setPadding(0, 0, dp(8), 0)
        val title = TextView(this)
        title.text = song?.title ?: getString(R.string.rw_na)
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        title.textSize = 14f
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(title)
        val meta = mutableListOf<String>()
        if (song != null && song.artist.isNotBlank() && song.artist != song.title) {
            meta.add(song.artist)
        }
        meta.add(getString(R.string.rw_toques, count.toLocal()))
        val mt = TextView(this)
        mt.text = meta.joinToString(" · ")
        mt.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        mt.textSize = 11f
        info.addView(mt)
        row.addView(info)

        val play = TextView(this)
        play.text = getString(R.string.rw_play_top, index + 1)
        play.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_cyan)
        play.setTextColor(ContextCompat.getColor(this, R.color.on_primary))
        play.textSize = 12f
        play.setTypeface(null, android.graphics.Typeface.BOLD)
        play.gravity = Gravity.CENTER
        play.setPadding(dp(12), dp(7), dp(12), dp(7))
        val disabled = song == null
        play.isEnabled = !disabled
        play.alpha = if (disabled) 0.4f else 1f
        play.setOnClickListener {
            playTop(index, top)
        }
        row.addView(play)

        row.setOnClickListener { playTop(index, top) }
        return row
    }

    private fun playTop(index: Int, top: List<Pair<Long, Int>>) {
        val ctx = applicationContext
        ThreadPool.post {
            val all = Library.allSongs(ctx)
            val byId = all.associateBy { it.id }
            val list = top.mapNotNull { byId[it.first] }
            ThreadPool.onUi {
                if (list.isNotEmpty()) {
                    Playback.start(list, index.coerceAtMost(list.size - 1))
                    Toast.makeText(
                        this,
                        getString(R.string.rw_playing, top.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun badge(index: Int): GradientDrawable {
        val color = when (index) {
            0 -> ContextCompat.getColor(this, R.color.rw_gold)
            1 -> ContextCompat.getColor(this, R.color.rw_silver)
            2 -> ContextCompat.getColor(this, R.color.rw_bronze)
            else -> ContextCompat.getColor(this, R.color.surface_variant)
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), when (index) {
                0 -> ContextCompat.getColor(this@RewindActivity, R.color.white)
                else -> ContextCompat.getColor(this@RewindActivity, R.color.neon_border)
            })
        }
    }

    private fun chartCard(label: String, values: List<Int>, axis: List<String>, multiLine: Boolean): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = ContextCompat.getDrawable(this, R.drawable.bg_vcard)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        card.layoutParams = lp
        card.setPadding(dp(12), dp(10), dp(12), dp(10))

        val lt = TextView(this)
        lt.text = label
        lt.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        lt.textSize = 11f
        lt.setTypeface(null, android.graphics.Typeface.BOLD)
        card.addView(lt)

        val area = LinearLayout(this)
        area.orientation = LinearLayout.HORIZONTAL
        val al = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(84)
        )
        al.topMargin = dp(8)
        area.layoutParams = al
        area.gravity = Gravity.BOTTOM
        val max = values.maxOrNull() ?: 0
        values.forEach { v ->
            val bar = View(this)
            val h = if (max == 0) dp(3) else (v.toFloat() / max * 84f).toInt().coerceAtLeast(3).coerceAtMost(dp(84))
            val bl = LinearLayout.LayoutParams(0, h, 1f)
            bl.marginEnd = dp(2)
            bar.layoutParams = bl
            bar.background = if (max > 0 && v == max)
                ContextCompat.getDrawable(this, R.drawable.bg_bar_peak)
            else ContextCompat.getDrawable(this, R.drawable.bg_bar)
            area.addView(bar)
        }
        card.addView(area)

        val ax = LinearLayout(this)
        ax.orientation = LinearLayout.HORIZONTAL
        ax.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        ax.setPadding(0, dp(4), 0, 0)
        axis.forEachIndexed { i, txt ->
            val a = TextView(this)
            a.text = txt
            a.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            a.textSize = 9f
            a.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (axis.size <= 8) a.gravity = if (i == 0) Gravity.START else if (i == axis.size - 1) Gravity.END else Gravity.CENTER
            ax.addView(a)
        }
        card.addView(ax)
        return card
    }

    private fun factCard(k: String, v: String, sub: String): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = ContextCompat.getDrawable(this, R.drawable.bg_vcard)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        card.layoutParams = lp
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        val kt = TextView(this)
        kt.text = k.uppercase(Locale.getDefault())
        kt.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        kt.textSize = 10f
        card.addView(kt)
        val vt = TextView(this)
        vt.text = v
        vt.setTextColor(ContextCompat.getColor(this, R.color.primary_light))
        vt.textSize = 15f
        vt.setTypeface(null, android.graphics.Typeface.BOLD)
        vt.setPadding(0, dp(4), 0, 0)
        card.addView(vt)
        if (sub.isNotEmpty()) {
            val st = TextView(this)
            st.text = sub
            st.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            st.textSize = 10f
            st.setPadding(0, dp(2), 0, 0)
            card.addView(st)
        }
        return card
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}