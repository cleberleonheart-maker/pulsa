package com.pulsa.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.VideoBookmarks
import com.pulsa.player.playback.Playback
import com.pulsa.player.podcast.Bookmark
import com.pulsa.player.podcast.Chapter
import com.pulsa.player.podcast.ChapterRow
import com.pulsa.player.podcast.Chapters
import com.pulsa.player.podcast.PodcastBookmarks
import com.pulsa.player.podcast.PodcastDb
import com.pulsa.player.ui.adapter.ChapterAdapter
import com.pulsa.player.util.TvFocus
import com.pulsa.player.util.UiMode

/**
 * Os capítulos e marcadores do episódio em reprodução, aberto pelo botão de listas.
 *
 * A própria tela já resolveu a descrição (e os capítulos) em background; aqui só recebe a
 * lista montada e a posição atual para destacar. Tocar numa linha pula direto para ela
 * ([Playback.seekTo]) — o mesmo gesto de "tocar para pular" da fila. Marcadores são lidos do
 * banco e podem ser criados/removidos aqui; capítulos do feed são só leitura.
 *
 * **Dois alvos de marcador:** o episódio do podcast ([PodcastDb]) e o **vídeo local**
 * ([VideoBookmarks]). Antes o vídeo ficava de fora — o `episodeId = 0` era o sinal de
 * "sem marcador" — e o filme de 2 h ficava sem o "voltar neste minuto" que o episódio de
 * podcast tem. A chave do vídeo é o caminho do arquivo.
 */
class ChapterSheetFragment : BottomSheetDialogFragment() {

    private var episodeId = 0L

    /** A chave do vídeo local (caminho do arquivo) quando o alvo é um marcador de vídeo. */
    private var videoKey = ""
    private var chapters: List<Chapter> = emptyList()
    private var bookmarks: List<Bookmark> = emptyList()
    private var rows: List<ChapterRow> = emptyList()
    private var positionMs = 0L
    private var durationMs = 0L
    private var adapter: ChapterAdapter? = null
    private var emptyView: TextView? = null
    private var titleView: TextView? = null
    private var addButton: View? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_chapters, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val starts = arguments?.getLongArray(ARG_STARTS) ?: LongArray(0)
        val titles = arguments?.getStringArrayList(ARG_TITLES) ?: ArrayList()
        chapters = starts.indices.map { Chapter(starts[it], titles.getOrElse(it) { "" }) }
        positionMs = arguments?.getLong(ARG_POSITION, 0L) ?: 0L
        durationMs = arguments?.getLong(ARG_DURATION, 0L) ?: 0L
        episodeId = arguments?.getLong(ARG_EPISODE_ID, 0L) ?: 0L
        videoKey = arguments?.getString(ARG_VIDEO_KEY).orEmpty()

        titleView = view.findViewById(R.id.chapter_title_header)
        emptyView = view.findViewById(R.id.chapter_empty)
        addButton = view.findViewById<View>(R.id.chapter_bookmark_add).apply {
            visibility = if (canBookmark()) View.VISIBLE else View.GONE
            setOnClickListener { promptBookmark() }
        }

        val list = view.findViewById<RecyclerView>(R.id.chapter_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        val a = ChapterAdapter(
            onClick = { pos ->
                rows.getOrNull(pos)?.let { Playback.seekTo(it.startMs) }
                dismiss()
            },
            onLongClick = { pos ->
                rows.getOrNull(pos)?.takeIf { it.isBookmark }?.let { confirmDelete(it.bookmarkId) }
            }
        )
        adapter = a
        list.adapter = a
        render()
        loadBookmarks()

        if (UiMode.isTv(requireContext())) {
            addButton?.let(TvFocus::apply)
            list.isFocusable = true
            list.isFocusableInTouchMode = true
            list.doOnLayout { TvFocus.focusFirst(list) }
        }
    }

    /** Há marcador onde? No episódio do podcast ou no vídeo local. */
    private fun canBookmark(): Boolean = episodeId > 0L || videoKey.isNotBlank()

    private fun loadBookmarks() {
        if (!canBookmark()) {
            render()
            return
        }
        val app = requireContext().applicationContext
        val episode = episodeId
        val video = videoKey
        ThreadPool.post {
            val found = runCatching {
                if (episode > 0L) {
                    PodcastDb.get(app).bookmarks(episode)
                } else {
                    VideoBookmarks.get(app).list(video)
                }
            }.getOrDefault(emptyList())
            ThreadPool.onUi {
                if (!isAdded) return@onUi
                bookmarks = found
                render()
            }
        }
    }

    private fun render() {
        rows = PodcastBookmarks.rows(chapters, bookmarks) {
            getString(R.string.bookmark_default_title, Chapters.formatTime(it))
        }
        titleView?.text = getString(
            if (bookmarks.isEmpty()) R.string.chapters else R.string.chapters_and_bookmarks
        )
        emptyView?.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        adapter?.submit(rows, positionMs, durationMs)
    }

    private fun promptBookmark() {
        if (!canBookmark()) return
        val at = Playback.position.coerceAtLeast(0L)
        val fallback = getString(R.string.bookmark_default_title, Chapters.formatTime(at))
        val input = EditText(requireContext()).apply {
            setText(fallback)
            setSelection(text.length)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bookmark_add)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = input.text.toString().trim().ifBlank { fallback }
                val app = requireContext().applicationContext
                val episode = episodeId
                val video = videoKey
                ThreadPool.post {
                    runCatching {
                        if (episode > 0L) {
                            PodcastDb.get(app).addBookmark(episode, at, title)
                        } else {
                            VideoBookmarks.get(app).add(video, at, title)
                        }
                    }
                    ThreadPool.onUi { if (isAdded) loadBookmarks() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(bookmarkId: Long) {
        if (bookmarks.none { it.id == bookmarkId }) return
        val label = rows.firstOrNull { it.bookmarkId == bookmarkId }?.title
            ?: getString(R.string.bookmark)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bookmark_remove)
            .setMessage(getString(R.string.bookmark_remove_message, label))
            .setPositiveButton(R.string.delete) { _, _ ->
                val app = requireContext().applicationContext
                val episode = episodeId
                val video = videoKey
                ThreadPool.post {
                    runCatching {
                        if (episode > 0L) {
                            PodcastDb.get(app).deleteBookmark(bookmarkId)
                        } else {
                            VideoBookmarks.get(app).delete(bookmarkId)
                        }
                    }
                    ThreadPool.onUi { if (isAdded) loadBookmarks() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.setBackgroundResource(R.drawable.bg_queue_sheet)
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    companion object {
        const val TAG = "chapter_sheet"
        private const val ARG_STARTS = "starts"
        private const val ARG_TITLES = "titles"
        private const val ARG_POSITION = "position"
        private const val ARG_DURATION = "duration"
        private const val ARG_EPISODE_ID = "episode_id"
        private const val ARG_VIDEO_KEY = "video_key"

        /**
         * Abre a folha de capítulos e marcadores.
         *
         * [episodeId] é o episódio do podcast; [videoKey] é o caminho do vídeo local. Quem
         * tiver os dois (não é o caso hoje) manda o episódio — o vídeo é o plano B.
         */
        fun show(
            fm: FragmentManager,
            chapters: List<Chapter>,
            positionMs: Long,
            durationMs: Long,
            episodeId: Long = 0L,
            videoKey: String = ""
        ) {
            val f = ChapterSheetFragment()
            f.arguments = Bundle().apply {
                putLongArray(ARG_STARTS, LongArray(chapters.size) { chapters[it].startMs })
                putStringArrayList(ARG_TITLES, ArrayList(chapters.map { it.title }))
                putLong(ARG_POSITION, positionMs)
                putLong(ARG_DURATION, durationMs)
                putLong(ARG_EPISODE_ID, episodeId)
                putString(ARG_VIDEO_KEY, videoKey)
            }
            f.show(fm, TAG)
        }
    }
}