package com.pulsa.player.ui.adapter

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.sync.Lyrics

/**
 * Letra sincronizada embutida na tela de reprodução: todas as linhas numa lista rolável,
 * com a linha atual em destaque.
 *
 * Só a linha que mudou é redesenhada ([setCurrent] guarda o índice anterior), porque o
 * destaque é atualizado a cada tick de progresso (500 ms) e um `notifyDataSetChanged`
 * a cada tick mataria a rolagem suave.
 */
class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.VH>() {

    private val lines = mutableListOf<Lyrics.Line>()
    private var current = -1

    fun submit(list: List<Lyrics.Line>) {
        lines.clear()
        lines.addAll(list)
        current = -1
        notifyDataSetChanged()
    }

    fun setCurrent(idx: Int) {
        if (idx == current) return
        val old = current
        current = idx
        if (old in lines.indices) notifyItemChanged(old)
        if (idx in lines.indices) notifyItemChanged(idx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric_line, parent, false)
        return VH(view as TextView)
    }

    override fun getItemCount(): Int = lines.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(lines[position].text, position == current)
    }

    class VH(private val tv: TextView) : RecyclerView.ViewHolder(tv) {
        fun bind(text: String, isCurrent: Boolean) {
            val ctx = tv.context
            tv.text = text.ifBlank { "\u266A" }
            tv.alpha = if (isCurrent) 1f else 0.6f
            tv.setTextColor(
                ContextCompat.getColor(ctx, if (isCurrent) R.color.primary else R.color.text_secondary)
            )
            tv.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            tv.textSize = if (isCurrent) 18f else 16f
        }
    }
}
