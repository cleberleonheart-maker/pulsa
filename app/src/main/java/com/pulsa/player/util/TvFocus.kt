package com.pulsa.player.util

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import com.pulsa.player.R

/**
 * Prepara uma linha de lista (ou container de controle) para navegacao por D-pad/remote.
 *
 * Em TV o toque nao existe: cada item precisa ser focavel e mostrar um destaque visivel
 * quando recebe o foco. Eh aplicado uma vez por item, em [onCreateViewHolder]; apos isso
 * o RecyclerView rola sozinho para o item focado, entao nao precisa de mais nada por lista.
 *
 * Duas armadilhas do foco em TV que este util resolve:
 *
 * 1. **ScrollView**: o D-pad focado fora da area visivel nao rola sozinho — o foco some
 *    e o usuario acha que o controle travou. [nudgeIntoView] empurra o item focado para
 *    dentro da janela.
 * 2. **"Buraco de foco"**: quando nao ha foco em lugar nenhum (app abre sem nada tocando,
 *    ou o item focado some), o D-pad fica mudo. [focusFirst] da um alvo inicial sempre que
 *    a tela ganha foco e ninguem esta focado.
 */
object TvFocus {

    fun apply(itemView: View) {
        if (!UiMode.isTv(itemView.context)) return
        itemView.isFocusable = true
        itemView.isFocusableInTouchMode = true
        itemView.isClickable = true
        // `foreground` (e nao `background`) de proposito: os itens ja carregam o proprio
        // fundo (bg_vcard, bg_song_normal/selected...). Trocar o background apagaria o
        // estado "ligado" do card e o fundo de selecionado da lista.
        itemView.foreground = itemView.context.getDrawable(R.drawable.bg_tv_focus)
        itemView.setOnFocusChangeListener { _, focused ->
            nudgeIntoView(itemView, focused)
        }
    }

    /**
     * Garante que o item focado apareca dentro do container rolavel que o contem. Sem isso
     * o D-pad movido para um item abaixo da dobra (ou fora da tela na horizontal) deixa o
     * foco invisivel — nem o `ScrollView` nem o `HorizontalScrollView` do Android rolam por
     * conta propria para views focaveis fora da janela.
     */
    fun nudgeIntoView(view: View, focused: Boolean) {
        if (!focused || !UiMode.isTv(view.context)) return
        scrollVertical(view)
        scrollHorizontal(view)
    }

    private fun scrollVertical(view: View) {
        val scroll = findParent<ScrollView>(view) ?: return
        scroll.post {
            if (scroll.findFocus() !== view) return@post
            val rect = Rect()
            view.getDrawingRect(rect)
            scroll.offsetDescendantRectToMyCoords(view, rect)
            val margin = view.resources.displayMetrics.density * 24f
            val top = scroll.scrollY
            val bottom = top + scroll.height
            val target = when {
                rect.top - margin < top -> rect.top - margin
                rect.bottom + margin > bottom -> rect.bottom + margin - scroll.height
                else -> return@post
            }
            scroll.smoothScrollTo(0, target.toInt().coerceAtLeast(0))
        }
    }

    private fun scrollHorizontal(view: View) {
        val scroll = findParent<HorizontalScrollView>(view) ?: return
        scroll.post {
            if (scroll.findFocus() !== view) return@post
            val rect = Rect()
            view.getDrawingRect(rect)
            scroll.offsetDescendantRectToMyCoords(view, rect)
            val margin = view.resources.displayMetrics.density * 24f
            val left = scroll.scrollX
            val right = left + scroll.width
            val target = when {
                rect.left - margin < left -> rect.left - margin
                rect.right + margin > right -> rect.right + margin - scroll.width
                else -> return@post
            }
            scroll.smoothScrollTo(target.toInt().coerceAtLeast(0), 0)
        }
    }

    private inline fun <reified T : View> findParent(view: View): T? {
        var parent: ViewParent? = view.parent
        while (parent is View) {
            if (parent is T) return parent
            parent = parent.getParent()
        }
        return null
    }

    /**
     * Da foco ao primeiro descendente focavel quando nada esta focado. Usado na entrada de
     * uma tela nova em TV: sem isso o usuario precisa "acordar" o foco com uma seta antes
     * de qualquer coisa responder.
     */
    fun focusFirst(root: View?) {
        val v = root ?: return
        if (!UiMode.isTv(v.context)) return
        v.post {
            if (v.findFocus() != null) return@post
            val first = (v as? ViewGroup)?.let { firstFocusable(it) } ?: v.takeIf { it.isFocusable }
            first?.requestFocus()
        }
    }

    private fun firstFocusable(parent: ViewGroup): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE || child.isFocusable.not()) {
                if (child is ViewGroup) {
                    firstFocusable(child)?.let { return it }
                }
                continue
            }
            return child
        }
        return null
    }
}
