package com.pulsa.player.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.model.Song
import com.pulsa.player.core.Helper
import com.pulsa.player.ui.ItemSelection

class SongListAdapter(
    private val onPlay: (Song, Int) -> Unit,
    private val onMenu: (Song) -> Unit
) : RecyclerView.Adapter<SongListAdapter.VH>() {

    var songs: List<Song> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var highlightId: Long? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /**
     * Se a lista está em modo seleção, para distinguir "mudei um item" de "entrei ou saí
     * do modo". Dentro do modo só a linha tocada muda; sair dele muda todas, porque o
     * círculo e o menu de três pontinhos somem em todas de uma vez.
     */
    private var inSelectionMode = false

    /**
     * O adapter escuta a seleção, e não só consulta.
     *
     * Quem **desenha** a marcação é ele; a barra só conta. Se o adapter não fosse
     * avisado, marcar uma música mudaria o número no topo e a linha continuaria com o
     * fundo e o círculo de sempre — nada na tela diria o que está selecionado.
     */
    private val selectionListener = object : ItemSelection.Listener {
        override fun onSelectionChanged(selection: ItemSelection, touched: Long?) {
            val active = selection.isActive
            if (active != inSelectionMode) {
                inSelectionMode = active
                notifyDataSetChanged()
                return
            }
            val pos = touched?.let { id -> songs.indexOfFirst { it.id == id } } ?: -1
            if (pos >= 0) notifyItemChanged(pos)
        }
    }

    /**
     * Seleção múltipla, ou null na tela que não tem.
     *
     * Fica **fora** do adapter de propósito: o mesmo adapter serve a quatro telas, e a
     * seleção é por tela. Ver [ItemSelection].
     */
    var selection: ItemSelection? = null
        set(value) {
            if (field === value) return
            field?.removeListener(selectionListener)
            field = value
            value?.addListener(selectionListener)
            inSelectionMode = value?.isActive == true
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = songs.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(songs[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val art: ImageView = itemView.findViewById(R.id.song_art)
        private val title: TextView = itemView.findViewById(R.id.song_title)
        private val subtitle: TextView = itemView.findViewById(R.id.song_subtitle)
        private val menu: ImageView = itemView.findViewById(R.id.song_menu)
        private val check: ImageView = itemView.findViewById(R.id.song_check)

        fun bind(song: Song) {
            title.text = song.title
            subtitle.text = song.artist + " · " + Helper.formatDuration(song.durationMs)
            ArtLoader.load(song.albumId, song.path, art)

            val sel = selection
            val selecting = sel != null && sel.isActive
            val checked = sel != null && sel.isSelected(song.id)
            val playing = song.id == highlightId

            itemView.setBackgroundResource(
                when {
                    checked -> R.drawable.bg_song_checked
                    playing -> R.drawable.bg_song_selected
                    else -> R.drawable.bg_song_normal
                }
            )
            // A música tocando pode também estar marcada: aí o título fica cheio e o
            // círculo diz que está selecionada. Sem marcação, o alpha baixo é o de sempre.
            title.alpha = if (playing || checked) 1f else 0.75f

            check.visibility = if (selecting) View.VISIBLE else View.GONE
            if (checked) {
                check.setImageResource(R.drawable.ic_check)
                check.setBackgroundResource(R.drawable.bg_check_on)
            } else {
                check.setImageResource(R.drawable.ic_check_circle_off)
                // 0 e nao null: `setBackgroundResource` nao aceita null em Kotlin, e 0
                // limpa o fundo, que e o que o item desmarcado quer.
                check.setBackgroundResource(0)
            }

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val s = selection
                if (s != null && s.isActive) {
                    s.toggle(song.id)
                } else {
                    highlightId = song.id
                    onPlay(song, pos)
                }
            }
            itemView.setOnLongClickListener {
                selection?.start(song.id)
                // true = consumiu o toque, senão o sistema acha que é um clique e dispara
                // o `setOnClickListener` logo em seguida, marcando e desmarcando.
                true
            }
            // Em modo seleção o menu de três pontinhos não faz sentido: ele age em UM item,
            // e a barra já age em todos os marcados.
            menu.visibility = if (selecting) View.GONE else View.VISIBLE
            menu.setOnClickListener { onMenu(song) }
        }
    }
}
