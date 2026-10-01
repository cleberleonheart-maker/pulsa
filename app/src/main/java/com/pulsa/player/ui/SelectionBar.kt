package com.pulsa.player.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.pulsa.player.R

/**
 * A barra que aparece no topo da lista quando há seleção: quantas, marcar tudo e excluir.
 *
 * Existe como classe para não repetir a mesma trinta linhas em cada uma das cinco telas
 * com lista. A tela só precisa ter o `view_selection_bar` no layout e passar o que
 * acontece no botão de excluir.
 */
class SelectionBar(
    root: View,
    private val selection: ItemSelection,
    /** Recebe os ids marcados no momento do clique. */
    private val onAction: (Set<Long>) -> Unit,
    /** Ícone da ação. Apagar o arquivo e tirar da playlist não são a mesma coisa. */
    private val actionIcon: Int = R.drawable.ic_delete,
    private val actionLabel: Int = R.string.delete_selected,
    /** Segunda ação opcional (F2b: adicionar à fila), escondida quando não vem. */
    private val extraAction: (() -> Unit)? = null,
    private val extraIcon: Int = R.drawable.ic_queue_music,
    private val extraLabel: Int = R.string.add_to_queue
) : ItemSelection.Listener {

    private val bar: View = root.findViewById(R.id.selection_bar)
    private val count: TextView = root.findViewById(R.id.selection_count)
    private val all: TextView = root.findViewById(R.id.selection_all)
    private val extra: ImageView = root.findViewById(R.id.selection_extra)
    private val del: ImageView = root.findViewById(R.id.selection_delete)
    private val close: ImageView = root.findViewById(R.id.selection_close)

    /** Os ids da tela, para o "marcar tudo" e para limpar o que não existe mais. */
    private var available: List<Long> = emptyList()

    init {
        selection.addListener(this)
        close.setOnClickListener { selection.clear() }
        all.setOnClickListener { selection.selectAll(available) }
        del.setImageResource(actionIcon)
        del.contentDescription = bar.context.getString(actionLabel)
        del.setOnClickListener {
            val ids = selection.snapshot()
            if (ids.isNotEmpty()) onAction(ids)
        }
        // A segunda ação é opcional e some com a barra nas telas que não passam
        // `extraAction` — as quatro telas de música seguem com um botão só.
        extraAction?.let { action ->
            extra.setImageResource(extraIcon)
            extra.contentDescription = bar.context.getString(extraLabel)
            extra.visibility = View.VISIBLE
            extra.setOnClickListener {
                val ids = selection.snapshot()
                if (ids.isNotEmpty()) action()
            }
        }
        refresh()
    }

    /**
     * A lista mudou: guarda os ids disponíveis e descarta da seleção os que sumiram.
     *
     * Tem que vir **antes** do [refresh] das telas, senão a barra mostra a contagem antiga
     * por um quadro depois de um item ter sido apagado.
     */
    fun setAvailable(ids: List<Long>) {
        available = ids
        selection.retainOnly(ids.toHashSet())
    }

    override fun onSelectionChanged(selection: ItemSelection, touched: Long?) {
        refresh()
    }

    fun release() {
        selection.removeListener(this)
    }

    private fun refresh() {
        if (!selection.isActive) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        val n = selection.count
        count.text = if (n == 1) {
            bar.context.getString(R.string.selection_one)
        } else {
            bar.context.getString(R.string.selection_many, n)
        }
        all.setText(
            if (selection.isAllSelected(available)) R.string.selection_none else R.string.selection_all
        )
    }
}
