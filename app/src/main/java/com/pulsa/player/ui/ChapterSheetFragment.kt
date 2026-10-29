package com.pulsa.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.pulsa.player.R
import com.pulsa.player.playback.Playback
import com.pulsa.player.podcast.Chapter
import com.pulsa.player.ui.adapter.ChapterAdapter
import com.pulsa.player.util.TvFocus
import com.pulsa.player.util.UiMode

/**
 * Os capítulos do episódio em reprodução, aberto pelo botão de listas na tela de reprodução.
 *
 * A própria tela já resolveu a descrição (e os capítulos) em background; aqui só recebe a
 * lista montada e a posição atual para destacar. Tocar num capítulo pula direto para ele
 * ([Playback.seekTo]) — o mesmo gesto de "tocar para pular" da fila.
 */
class ChapterSheetFragment : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_chapters, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val starts = arguments?.getLongArray(ARG_STARTS) ?: LongArray(0)
        val titles = arguments?.getStringArrayList(ARG_TITLES) ?: ArrayList()
        val chapters = starts.indices.map { Chapter(starts[it], titles.getOrElse(it) { "" }) }
        val positionMs = arguments?.getLong(ARG_POSITION, 0L) ?: 0L
        val durationMs = arguments?.getLong(ARG_DURATION, 0L) ?: 0L

        val list = view.findViewById<RecyclerView>(R.id.chapter_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        val a = ChapterAdapter(onClick = { pos ->
            chapters.getOrNull(pos)?.let { Playback.seekTo(it.startMs) }
            dismiss()
        })
        a.submit(chapters, positionMs, durationMs)
        list.adapter = a
        if (UiMode.isTv(requireContext())) {
            list.isFocusable = true
            list.isFocusableInTouchMode = true
            list.doOnLayout { TvFocus.focusFirst(list) }
        }
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

        fun show(fm: FragmentManager, chapters: List<Chapter>, positionMs: Long, durationMs: Long) {
            val f = ChapterSheetFragment()
            f.arguments = Bundle().apply {
                putLongArray(ARG_STARTS, LongArray(chapters.size) { chapters[it].startMs })
                putStringArrayList(ARG_TITLES, ArrayList(chapters.map { it.title }))
                putLong(ARG_POSITION, positionMs)
                putLong(ARG_DURATION, durationMs)
            }
            f.show(fm, TAG)
        }
    }
}