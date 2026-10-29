package com.pulsa.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import com.pulsa.player.util.TvFocus
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.pulsa.player.R
import com.pulsa.player.playback.Playback
import com.pulsa.player.ui.adapter.QueueAdapter

/**
 * A fila de reprodução, aberta arrastando a capa para cima na tela de reprodução.
 *
 * Mostra a fila atual ([Playback.queue]) com o item que está tocando em destaque e permite
 * arrastar para reordenar ([Playback.moveQueueItem]) ou tocar para pular ([Playback.jumpTo]).
 */
class QueueSheetFragment : BottomSheetDialogFragment() {

    private var adapter: QueueAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_queue, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.queue_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        if (com.pulsa.player.util.UiMode.isTv(requireContext())) {
            list.isFocusable = true
            list.isFocusableInTouchMode = true
        }
        val a = QueueAdapter(
            onClick = { pos ->
                Playback.jumpTo(pos)
                dismiss()
            },
            onMove = { from, to ->
                Playback.moveQueueItem(from, to)
                // O motor pode ter trocado o índice atual (o arrasto cruzou por ele):
                // re-sincroniza o destaque com o que o serviço decidiu.
                adapter?.markCurrent(Playback.index)
            }
        )
        adapter = a
        list.adapter = a

        val current = Playback.index
        a.submit(Playback.queue, current)
        if (current in Playback.queue.indices) list.scrollToPosition(current)
        if (com.pulsa.player.util.UiMode.isTv(requireContext())) {
            // Dá foco ao primeiro item depois do layout: sem isso a lista abre focável,
            // mas o D-pad não entra nela.
            list.doOnLayout { TvFocus.focusFirst(list) }
        }

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                a.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }
        ItemTouchHelper(callback).attachToRecyclerView(list)
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.setBackgroundResource(R.drawable.bg_queue_sheet)
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    companion object {
        const val TAG = "queue_sheet"

        fun show(fm: FragmentManager) {
            QueueSheetFragment().show(fm, TAG)
        }
    }
}
