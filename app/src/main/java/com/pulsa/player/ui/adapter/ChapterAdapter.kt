package com.pulsa.player.ui.adapter
import com.pulsa.player.util.TvFocus

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.podcast.Chapter
import com.pulsa.player.podcast.Chapters

/**
 * Lista de capítulos do episódio na tela de reprodução.
 *
 * Liga o carimbo de tempo do lado do rótulo; tocar pula direto para o capítulo
 * ([onClick] → `Playback.seekTo`). O capítulo em reprodução fica destacado — é o único
 * pedaço da lista em que a posição do player importa, então ela entra no [submit].
 */
class ChapterAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<ChapterAdapter.VH>() {

    private val items = mutableListOf<Chapter>()
    private var currentIndex = -1

    fun submit(chapters: List<Chapter>, positionMs: Long, durationMs: Long) {
        items.clear()
        items.addAll(chapters)
        currentIndex = when {
            items.isEmpty() -> -1
            else -> {
                var at = -1
                for (i in items.indices) {
                    val start = items[i].startMs
                    val end = items.getOrNull(i + 1)?.startMs ?: durationMs
                    if (positionMs >= start && (end == 0L || positionMs < end)) { at = i; break }
                }
                if (at == -1) items.lastIndex else at
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chapter, parent, false)
        TvFocus.apply(view)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position == currentIndex)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val time: TextView = itemView.findViewById(R.id.chapter_time)
        private val title: TextView = itemView.findViewById(R.id.chapter_title)

        fun bind(chapter: Chapter, current: Boolean) {
            time.text = Chapters.formatTime(chapter.startMs)
            title.text = chapter.title
            title.setTextColor(
                itemView.context.getColor(if (current) R.color.primary else R.color.text_primary)
            )
            time.setTextColor(
                itemView.context.getColor(if (current) R.color.primary else R.color.text_secondary)
            )
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(pos)
            }
        }
    }
}