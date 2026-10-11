package com.pulsa.player.util

import android.view.View
import com.pulsa.player.R

/**
 * Prepara uma linha de lista (ou container de controle) para navegacao por D-pad/remote.
 *
 * Em TV o toque nao existe: cada item precisa ser focavel e mostrar um destaque visivel
 * quando recebe o foco. Eh aplicado uma vez por item, em [onCreateViewHolder]; apos isso
 * o RecyclerView rola sozinho para o item focado, entao nao precisa de mais nada por lista.
 */
object TvFocus {

    fun apply(itemView: View) {
        if (!UiMode.isTv(itemView.context)) return
        itemView.isFocusable = true
        itemView.isClickable = true
        itemView.foreground = itemView.context.getDrawable(R.drawable.bg_tv_focus)
    }
}