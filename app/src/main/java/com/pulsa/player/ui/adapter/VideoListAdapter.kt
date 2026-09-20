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
        private val color = itemView.context.getColor(R.color.surface_variant)

        fun bind(video: Video, position: Int) {
            title.text = video.title
            val size = Helper.formatBytes(video.sizeBytes)
            meta.text = "${Helper.formatDuration(video.durationMs)} • $size"

            val selected = video.id == highlightId
            itemView.setBackgroundResource(
                if (selected) R.drawable.bg_song_selected else R.drawable.bg_song_normal
            )
            title.alpha = if (selected) 1f else 0.75f

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
                if (pos != RecyclerView.NO_POSITION) {
                    highlightId = video.id
                    onClick(videos[pos], pos)
                }
            }
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
