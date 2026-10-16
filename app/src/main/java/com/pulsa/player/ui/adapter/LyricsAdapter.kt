package com.pulsa.player.ui.adapter

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.sync.Karaoke
import com.pulsa.player.sync.Lyrics

/**
 * Letra sincronizada embutida na tela de reprodução: todas as linhas numa lista rolável,
 * com a linha atual em destaque e o karaokê **palavra-a-palavra** (item 126).
 *
 * Só a linha que mudou é redesenhada ([setCurrent]/[setCursor] guardam o índice anterior),
 * porque o destaque é atualizado a cada tick de progresso (500 ms) e um
 * `notifyDataSetChanged` a cada tick mataria a rolagem suave.
 */
class LyricsAdapter(private val textScale: Float = 1f) : RecyclerView.Adapter<LyricsAdapter.VH>() {

    private val lines = mutableListOf<Lyrics.Line>()
    private var current = -1
    private var cursor = 0f

    fun submit(list: List<Lyrics.Line>) {
        lines.clear()
        lines.addAll(list)
        current = -1
        cursor = 0f
        notifyDataSetChanged()
    }

    fun setCurrent(idx: Int) {
        if (idx == current) return
        val old = current
        current = idx
        cursor = 0f
        if (old in lines.indices) notifyItemChanged(old)
        if (idx in lines.indices) notifyItemChanged(idx)
    }

    /** Fração já cantada da linha atual (0..1) — move o karaokê palavra-a-palavra. */
    fun setCursor(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        if (f == cursor) return
        cursor = f
        if (current in lines.indices) notifyItemChanged(current)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric_line, parent, false)
        return VH(view as TextView)
    }

    override fun getItemCount(): Int = lines.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(
            lines[position].text,
            position == current,
            if (position == current) cursor else 0f,
            textScale
        )
    }

    class VH(private val tv: TextView) : RecyclerView.ViewHolder(tv) {
        fun bind(text: String, isCurrent: Boolean, fraction: Float, scale: Float) {
            val ctx = tv.context
            val shown = text.ifBlank { "\u266A" }
            val secondary = ContextCompat.getColor(ctx, R.color.text_secondary)
            val primary = ContextCompat.getColor(ctx, R.color.primary)

            if (isCurrent) {
                val end = Karaoke.highlightEnd(shown, fraction).coerceIn(0, shown.length)
                val sp = SpannableString(shown)
                if (end > 0) {
                    sp.setSpan(
                        ForegroundColorSpan(primary), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    sp.setSpan(
                        StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                tv.text = sp
                tv.setTextColor(secondary)
            } else {
                tv.text = shown
                tv.setTextColor(if (isCurrent) primary else secondary)
            }
            tv.alpha = if (isCurrent) 1f else 0.6f
            tv.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            tv.textSize = (if (isCurrent) 18f else 16f) * scale
        }
    }
}
