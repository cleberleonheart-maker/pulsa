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
import com.pulsa.player.playback.QueueKey

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
     * F2b — a linha que está tocando, por **chave** e não por id.
     *
     * Com vídeo na fila, `song.id` sozinho acende a linha errada: o áudio de id 42 e o
     * vídeo de id 42 são itens diferentes (o MediaStore numera as coleções por conta
     * própria), e era isso que a linha da música acendia enquanto o vídeo tocava. A chave
     * tipada ([QueueKey]) resolve, e [highlightId] continua existindo para quem só quer o
     * id — as listas de música nunca disinfectant isso.
     */
    var highlightKey: String? = null
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

    /**
     * Marca uma linha como "tocando agora" — **os dois campos, sempre juntos**.
     *
     * Isto é a correção do fundo preso na música anterior. [bind] só olha o [highlightId]
     * quando o [highlightKey] é nulo, e o `highlightKey` é preenchido uma vez, no
     * `load()` da tela. Tocar uma música atualizava só o id: a chave continuava com a faixa
     * antiga, `bind` preferia a chave, e o fundo em `bg_song_selected` ficava na música
     * anterior mesmo com a nova tocando. Por isso os dois são escritos juntos aqui, e não
     * em dois lugares diferentes.
     *
     * Rádio e stream não têm chave (`QueueKey.encode` devolve `null`), e aí o `bind` cai
     * no id — que é o comportamento de sempre para esses, e o mesmo que o `load()` faz.
     */
    private fun markPlaying(song: Song) {
        // Só um aviso quando nenhum dos dois mudou: os `set` já notificam, e eles são
        // escritos juntos para não redesenhar a lista duas vezes no mesmo toque.
        val key = QueueKey.encode(song)
        if (highlightId == song.id && highlightKey == key) return
        highlightId = song.id
        highlightKey = key
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
            // A chave manda quando há uma: só o id acenderia a linha do áudio de id 42
            // enquanto o vídeo de id 42 toca (F2b). Sem chave, cai no id — o comportamento
            // de sempre para quem não tem vídeo na fila.
            val key = highlightKey
            val playing = if (key != null) {
                QueueKey.sameType(QueueKey.encode(song), key)
            } else {
                song.id == highlightId
            }

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
                    markPlaying(song)
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
