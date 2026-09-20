package com.pulsa.player.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.VideoPlayerActivity
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.model.Video
import com.pulsa.player.ui.adapter.VideoListAdapter
import com.pulsa.player.core.Permissions
import com.pulsa.player.core.ThreadPool

class VideosTabFragment : Fragment() {

    private var list: RecyclerView? = null
    private var empty: View? = null
    private var emptyText: TextView? = null
    private var permissionBtn: View? = null
    private var headerSubtitle: TextView? = null
    private var adapter: VideoListAdapter? = null
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_videos, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.videos_list)
        empty = view.findViewById(R.id.videos_empty)
        emptyText = view.findViewById(R.id.videos_empty_text)
        permissionBtn = view.findViewById(R.id.videos_permission_btn)
        headerSubtitle = view.findViewById(R.id.videos_header_subtitle)
        val a = VideoListAdapter(
            onClick = { video, pos -> openPlayer(video, pos) },
            onMenu = { video, _ -> showMenu(video) }
        )
        adapter = a
        list?.apply {
            layoutManager = LinearLayoutManager(this@VideosTabFragment.context)
            adapter = a
        }
        permissionBtn?.setOnClickListener { Permissions.requestVideo(requireActivity()) }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    fun load() {
        if (view == null) return
        if (loading) return
        val ctx = requireContext()
        if (!Permissions.hasVideo(ctx)) {
            permissionBtn?.visibility = View.VISIBLE
            emptyText?.text = getString(R.string.empty_no_permission)
            showEmpty(true)
            loading = false
            return
        }
        loading = true
        ThreadPool.post {
            val videos = try {
                VideoLibrary.all(ctx).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            } catch (t: Throwable) {
                emptyList()
            }
            ThreadPool.onUi {
                loading = false
                if (isAdded) {
                    adapter?.videos = videos
                    headerSubtitle?.text = if (videos.isEmpty()) "" else getString(R.string.videos_count, videos.size)
                    if (videos.isEmpty()) {
                        permissionBtn?.visibility = View.GONE
                        emptyText?.text = getString(R.string.empty_videos)
                        showEmpty(true)
                    } else {
                        showEmpty(false)
                    }
                }
            }
        }
    }

    private fun showEmpty(on: Boolean) {
        empty?.visibility = if (on) View.VISIBLE else View.GONE
        list?.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun openPlayer(video: Video, pos: Int) {
        val ctx = context ?: return
        adapter?.highlightId = video.id
        val videos = adapter?.videos.orEmpty()
        if (videos.isEmpty()) {
            Toast.makeText(ctx, R.string.play_video_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val idx = videos.indexOfFirst { it.id == video.id }.let { if (it < 0) pos else it }
        VideoPlayerActivity.start(ctx, videos, idx)
    }

    private fun showMenu(video: Video) {
        val items = arrayOf(
            getString(R.string.video_play),
            getString(R.string.share_music)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(video.title)
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> openPlayer(video, -1)
                    1 -> share(video)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun share(video: Video) {
        val ctx = requireContext()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, VideoLibrary.contentUri(video.id))
            putExtra(Intent.EXTRA_TEXT, video.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.share_music)))
        }.onFailure {
            android.widget.Toast.makeText(ctx, R.string.share_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun title(): String = requireContext().getString(R.string.tab_videos)
}
