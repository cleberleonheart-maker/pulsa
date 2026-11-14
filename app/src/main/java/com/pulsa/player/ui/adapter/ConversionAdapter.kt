package com.pulsa.player.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.media.ConversionStore
import com.pulsa.player.util.TvFocus

class ConversionAdapter(
    private val onClick: (ConversionStore.Item) -> Unit,
    private val onMenu: (ConversionStore.Item) -> Unit
) : RecyclerView.Adapter<ConversionAdapter.VH>() {

    private var items: List<ConversionStore.Item> = emptyList()

    fun submit(newItems: List<ConversionStore.Item>) {
        val progressOnly = newItems.size == items.size && newItems.indices.all {
            newItems[it].id == items[it].id &&
                newItems[it].status == items[it].status &&
                newItems[it].error == items[it].error
        }
        items = newItems
        if (progressOnly) {
            notifyItemRangeChanged(0, newItems.size, PAYLOAD_PROGRESS)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_conversion, parent, false)
        TvFocus.apply(view)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PROGRESS)) {
            bindProgress(holder, items[position])
            hookClicks(holder)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.icon.setImageResource(
            if (item.output.isVideo) R.drawable.ic_videocam else R.drawable.ic_music_note
        )
        holder.play.visibility = if (item.isDone()) View.VISIBLE else View.INVISIBLE
        holder.itemView.alpha = if (item.isDone()) 1f else 0.88f
        bindProgress(holder, item)
        hookClicks(holder)
    }

    private fun bindProgress(holder: VH, item: ConversionStore.Item) {
        val context = holder.itemView.context
        val status = when (item.status) {
            ConversionStore.Status.QUEUED -> context.getString(R.string.converter_queued)
            ConversionStore.Status.RUNNING -> context.getString(R.string.converter_running)
            ConversionStore.Status.FINALIZING -> context.getString(R.string.converter_finalizing)
            ConversionStore.Status.DONE -> context.getString(R.string.converter_done)
            ConversionStore.Status.FAILED -> item.error ?: context.getString(R.string.converter_failed)
            ConversionStore.Status.CANCELLED -> context.getString(R.string.converter_cancelled)
        }
        val format = item.output.extension.uppercase()
        holder.meta.text = if (item.isActive()) {
            "$status • $format • ${item.progress}%"
        } else {
            "$status • $format"
        }
        if (item.status == ConversionStore.Status.RUNNING) {
            holder.progress.visibility = View.VISIBLE
            holder.progress.progress = item.progress.coerceIn(0, 100)
        } else {
            holder.progress.visibility = View.GONE
        }
    }

    private fun hookClicks(holder: VH) {
        holder.itemView.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnClickListener
            items.getOrNull(position)?.let(onClick)
        }
        holder.menu.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnClickListener
            items.getOrNull(position)?.let(onMenu)
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.conversion_icon)
        val play: ImageView = view.findViewById(R.id.conversion_play)
        val title: TextView = view.findViewById(R.id.conversion_title)
        val meta: TextView = view.findViewById(R.id.conversion_meta)
        val progress: ProgressBar = view.findViewById(R.id.conversion_progress)
        val menu: ImageView = view.findViewById(R.id.conversion_menu)
    }

    companion object {
        private const val PAYLOAD_PROGRESS = "progress"
    }
}
