package com.pulsa.player.ui.adapter

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.model.Video
import com.pulsa.player.core.Helper
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.ui.ItemSelection
import java.util.LinkedHashMap

class VideoListAdapter(
    private val onClick: (Video, Int) -> Unit,
    private val onMenu: (Video, Int) -> Unit
) : RecyclerView.Adapter<VideoListAdapter.VH>() {

    var videos: List<Video> = emptyList()
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
     * avisado, marcar um vídeo mudaria o número no topo e a linha continuaria com o
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
            val pos = touched?.let { id -> videos.indexOfFirst { it.id == id } } ?: -1
            if (pos >= 0) notifyItemChanged(pos)
        }
    }

    /**
     * Seleção múltipla, ou null na tela que não tem. Por tela, não no adapter: o mesmo
     * adapter serve a lista de vídeos e a busca do PeerTube. Ver [ItemSelection].
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

    private val thumbCache =
        object : LinkedHashMap<Long, Bitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>): Boolean {
                return size > 200
            }
        }
    private val thumbLock = Any()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = videos.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(videos[position], position)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumb: ImageView = itemView.findViewById(R.id.video_thumb)
        private val title: TextView = itemView.findViewById(R.id.video_title)
        private val meta: TextView = itemView.findViewById(R.id.video_meta)
        private val menu: ImageView = itemView.findViewById(R.id.video_menu)
        private val check: ImageView = itemView.findViewById(R.id.video_check)
        private val color = itemView.context.getColor(R.color.surface_variant)

        fun bind(video: Video, position: Int) {
            title.text = video.title
            val size = Helper.formatBytes(video.sizeBytes)
            meta.text = "${Helper.formatDuration(video.durationMs)} • $size"

            val sel = selection
            val selecting = sel != null && sel.isActive
            val checked = sel != null && sel.isSelected(video.id)
            val playing = video.id == highlightId

            itemView.setBackgroundResource(
                when {
                    checked -> R.drawable.bg_song_checked
                    playing -> R.drawable.bg_song_selected
                    else -> R.drawable.bg_song_normal
                }
            )
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

            thumb.setImageDrawable(null)
            thumb.setBackgroundColor(color)
            val cached = synchronized(thumbLock) { thumbCache[video.id] }
            if (cached != null) {
                thumb.setImageBitmap(cached)
            } else {
                ThreadPool.post {
                    val bmp = loadThumb(itemView.context, video.id)
                    if (bmp != null) {
                        ThreadPool.onUi {
                            if (bindingAdapterPosition == position) {
                                thumb.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            }
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val s = selection
                if (s != null && s.isActive) {
                    s.toggle(video.id)
                } else {
                    highlightId = video.id
                    onClick(videos[pos], pos)
                }
            }
            itemView.setOnLongClickListener {
                selection?.start(video.id)
                // Consome o toque: sem isto o `setOnClickListener` dispara logo em
                // seguida e o item entra e sai da seleção no mesmo gesto.
                true
            }
            // O menu age em um item só, e em modo seleção a barra age em todos os marcados.
            menu.visibility = if (selecting) View.GONE else View.VISIBLE
            menu.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onMenu(videos[pos], pos)
            }
        }
    }

    private fun loadThumb(context: Context, videoId: Long): Bitmap? {
        synchronized(thumbLock) { thumbCache[videoId] }?.let { return it }
        val uri: Uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(uri, android.util.Size(200, 200), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    videoId,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null
                )
            }
        }.getOrNull()
        if (bmp != null) {
            synchronized(thumbLock) { thumbCache[videoId] = bmp }
        }
        return bmp
    }
}
