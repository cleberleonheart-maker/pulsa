package com.pulsa.player.ui

/**
 * Seleção múltipla de itens de uma lista — músicas ou vídeos.
 *
 * Existe como objeto próprio, e não como um `Set<Long>` no adapter, por dois motivos:
 *
 * 1. O `SongListAdapter` é **compartilhado por quatro telas** (Músicas, Biblioteca por
 *    álbum/artista, Favoritas e Playlist). Se o estado morasse no adapter, selecionar
 *    numa tela apagaria a marcação das outras, e o "3 selecionadas" de uma apareceria na
 *    outra. Aqui o estado é por tela.
 * 2. O adapter precisa saber só duas coisas: se o item está marcado, e quem avisar quando
 *    isso muda. Nenhuma das duas é responsabilidade dele.
 *
 * O "tocando agora" continua separado, no `highlightId` do adapter: uma música pode estar
 * tocando e ao mesmo tempo estar marcada para apagar, e são informações diferentes.
 */
class ItemSelection {

    interface Listener {
        /**
         * [touched] é o item cujo estado mudou, quando mudou só um.
         *
         * É `null` quando a seleção mudou **de estado** — entrou, saiu, "marcar tudo",
         * ou a lista perdeu ids. Nesses casos quem escuta precisa repintar tudo, porque
         * o círculo de seleção e o menu de três pontinhos mudam em todas as linhas, não
         * só na que o dedo tocou.
         */
        fun onSelectionChanged(selection: ItemSelection, touched: Long?)
    }

    private val ids = LinkedHashSet<Long>()

    /**
     * Mais de um ouvinte, e não um só: a barra conta, mas quem **desenha** a marcação na
     * linha é o adapter. Com um listener só, marcar um item mudava o número da barra e
     * a lista continuava com o fundo e o círculo de sempre — ou seja, nada indicava o que
     * estava selecionado.
     */
    private val listeners = mutableListOf<Listener>()

    /** Seleção só existe com pelo menos um item: sem isso não há barra nem modo seleção. */
    val isActive: Boolean get() = ids.isNotEmpty()

    val count: Int get() = ids.size

    /** Cópia, porque o chamador não pode mexer no estado de dentro. */
    fun snapshot(): Set<Long> = LinkedHashSet(ids)

    fun isSelected(id: Long): Boolean = id in ids

    fun addListener(l: Listener) {
        if (l !in listeners) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Entra no modo seleção já com este item marcado — é o que faz o toque longo. */
    fun start(id: Long) {
        if (ids.add(id)) notifyChanged(id)
    }

    fun toggle(id: Long) {
        if (!ids.remove(id)) ids.add(id)
        notifyChanged(id)
    }

    /**
     * Marca tudo, ou desmarca se já estiver tudo marcado.
     *
     * O "ou desmarca" é o que faz o botão virar "Desmarcar tudo" sem precisar de dois
     * estados na tela: o rótulo já depende de `isAllSelected`.
     */
    fun selectAll(all: List<Long>) {
        if (ids.size == all.size && all.all { it in ids }) {
            clear()
        } else {
            ids.clear()
            ids.addAll(all)
            notifyChanged(null)
        }
    }

    fun isAllSelected(all: List<Long>): Boolean =
        all.isNotEmpty() && ids.size == all.size && all.all { it in ids }

    fun clear() {
        if (ids.isNotEmpty()) {
            ids.clear()
            notifyChanged(null)
        }
    }

    /**
     * Some da seleção o que não existe mais.
     *
     * Chamado depois de um `load()`: os ids são do MediaStore, e depois de apagar as
     * próprias listas mudam. Deixar id morto na seleção faria a barra continuar contando
     * músicas que não estão mais na tela — e a contagem é o que a pessoa lê para decidir
     * se vai confirmar a exclusão.
     */
    fun retainOnly(available: Set<Long>) {
        // Copia antes de remover: o predicado roda sobre o próprio conjunto, e filtrar
        // enquanto apaga pularia elemento.
        val gone = ids.filter { it !in available }
        if (gone.isEmpty()) return
        ids.removeAll(gone.toSet())
        notifyChanged(gone.singleOrNull())
    }

    private fun notifyChanged(touched: Long?) {
        // Cópia porque um ouvinte pode se soltar durante a volta (a barra é liberada no
        // fim da tela), e isso invalidaria a iteração.
        for (l in listeners.toList()) l.onSelectionChanged(this, touched)
    }
}
