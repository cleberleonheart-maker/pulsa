package com.pulsa.player.ui.adapter
import com.pulsa.player.util.TvFocus

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.core.Helper
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.model.Song
import com.pulsa.player.playback.QueueKey

/**
 * Lista da fila de reprodução na tela de reprodução.
 *
 * É separado do [SongListAdapter] de propósito: a fila tem a **posição** como identidade
 * (a mesma música pode aparecer duas vezes, e o arrasto reordena por índice, não por id),
 * e não tem seleção múltipla nem menu. O destaque do item atual vem por índice
 * ([Playback.index]), não por chave — é a única lista do app em que a posição importa.
 */
class QueueAdapter(
    private val onClick: (Int) -> Unit,
    private val onMove: (Int, Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    private val items = mutableListOf<Song>()
    private var currentIndex = -1

    fun submit(songs: List<Song>, current: Int) {
        items.clear()
        items.addAll(songs)
        currentIndex = current
        notifyDataSetChanged()
    }

    /** Redesenha o item atual depois de um pulo feito por fora (onSongChanged do serviço). */
    fun markCurrent(current: Int) {
        if (current == currentIndex) return
        val old = currentIndex
        currentIndex = current
        if (old in items.indices) notifyItemChanged(old)
        if (current in items.indices) notifyItemChanged(current)
    }

    /**
     * Move o item e avisa o motor. O [notifyItemMoved] é o que dá a animação do arrasto;
     * o motor é sincronizado em [onMove], que também corrige o índice atual.
     */
    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
        onMove(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        TvFocus.apply(view)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position == currentIndex)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val art: ImageView = itemView.findViewById(R.id.song_art)
        private val title: TextView = itemView.findViewById(R.id.song_title)
        private val subtitle: TextView = itemView.findViewById(R.id.song_subtitle)
        private val menu: ImageView = itemView.findViewById(R.id.song_menu)
        private val check: ImageView = itemView.findViewById(R.id.song_check)

        fun bind(song: Song, playing: Boolean) {
            title.text = song.title
            subtitle.text = song.artist + " · " + Helper.formatDuration(song.durationMs)
            ArtLoader.load(song.albumId, song.path, art)
            itemView.setBackgroundResource(
                if (playing) R.drawable.bg_song_selected else R.drawable.bg_song_normal
            )
            title.alpha = if (playing) 1f else 0.75f
            check.visibility = View.GONE
            menu.visibility = View.GONE
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(pos)
            }
        }
    }
}
